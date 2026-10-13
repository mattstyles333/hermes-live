package com.folio.hermes.live

import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.HermesRpcException
import com.folio.hermes.TurnUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where delegations go. [com.folio.hermes.HermesConversation] implements it. */
interface DelegationBackend {
    /**
     * Run one Hermes turn for a voice delegation and stream it. [interruptFirst]: a previous
     * delegation is still running; stop it before submitting.
     */
    fun delegate(prompt: String, voiceContext: String, interruptFirst: Boolean): Flow<TurnUpdate>
}

/** Timings for [DelegationBridge]. Zero disables a limit. */
data class BridgeSettings(
    /** Quiet after the last user fragment before the utterance is judged for a stop phrase. */
    val utteranceSettleMs: Long = 1_500L,
    /** A delegation with no sign of life from Hermes for this long is dropped. */
    val submitGraceMs: Long = 15_000L,
    /** Hang up after this long with no user speech and no Hermes work pending (cost guard). */
    val idleHangUpMs: Long = 3 * 60_000L,
    /** Hard cap on call length. */
    val callCapMs: Long = 30 * 60_000L,
    /** Spoken warning this long before the cap. */
    val capWarningMs: Long = 60_000L,
    /** After `session.close`, wait this long for `session.closed`, then tear down anyway. */
    val closeTimeoutMs: Long = 15_000L,
    /** Stop-phrase list. */
    val stopPhrases: StopPhrases.Config = StopPhrases.Config.Default,
    /**
     * When above zero, a call the bridge ends itself (stop phrase, idle, cap) first sends
     * `session.instructions.append` "Say a brief goodbye.", waits until the voice has spoken and
     * gone quiet (at most this long), then sends `session.close`. Zero closes at once.
     */
    val goodbyeMaxMs: Long = 0L,
    /** Quiet after the goodbye's last output (transcript or [DelegationBridge.setRemoteSpeaking]) before closing. */
    val goodbyeQuietMs: Long = 700L,
    /** When above zero (and below [idleHangUpMs]), a spoken and visual warning this long before the idle hang-up. */
    val idleWarningMs: Long = 0L,
)

/** What the bridge is doing. */
enum class BridgeStatus { Connecting, Listening, Thinking, Closing, Closed }

/** Why the bridge ended the call. */
enum class StopReason { SpokenStop, IdleTimeout, CallCap, UserRequest }

/** Things the app shows or acts on. */
sealed interface BridgeEvent {
    data class Started(val sessionId: String?) : BridgeEvent
    data class Transcript(val fragment: LiveTranscriptFragment) : BridgeEvent
    data class DelegationStarted(val delegationId: String?, val prompt: String) : BridgeEvent
    /** Hermes finished; [spoke] is false when there was nothing to say. */
    data class DelegationSettled(val delegationId: String?, val spoke: Boolean) : BridgeEvent
    /**
     * [message] may echo the server (show it, don't log it). [kind] is safe to log: "turn_failed"
     * for a failed Hermes turn, else the exception's simple class name; [code] is the gateway's
     * JSON-RPC code when there was one.
     */
    data class DelegationFailed(val delegationId: String?, val message: String, val kind: String = "turn_failed", val code: Int? = null) : BridgeEvent
    /** Hermes started a tool; [label] is speakable ("web search"). */
    data class ToolStarted(val delegationId: String?, val label: String) : BridgeEvent
    /** Show an Approve / Deny card. Never answered by the bridge. */
    data class ApprovalNeeded(val request: ApprovalRequest) : BridgeEvent
    data class ClarifyNeeded(val request: ClarifyRequest) : BridgeEvent
    data class RequestWithdrawn(val id: String) : BridgeEvent
    /** The call is about to end itself; the bridge already sent `session.close`. */
    data class Stopping(val reason: StopReason) : BridgeEvent
    data object CapWarning : BridgeEvent
    /** The call will hang up for silence in [BridgeSettings.idleWarningMs] unless the user speaks. */
    data object IdleWarning : BridgeEvent
    /** User speech after [IdleWarning] kept the call open. */
    data object IdleWarningCleared : BridgeEvent
    /** [message] is the vendor's and may echo content: log [code] only. */
    data class LiveError(val message: String, val code: String? = null) : BridgeEvent
    data class UsageUpdated(val seconds: Double) : BridgeEvent
    /** Tear down WebRTC now. */
    data class Closed(val reason: String, val usageSeconds: Double?) : BridgeEvent
}

/**
 * The WebRTC-free half of a Hermes Live call: data-channel events in, data-channel JSON out.
 *
 * A `session.delegation.created` becomes a Hermes turn built from the transcript
 * ([LiveTranscript.delegationPrompt]) and submitted through [backend]. While Hermes streams,
 * completed sentences go to the voice as `session.commentary.append`; tool changes as quiet
 * `session.thinking.append` ("Hermes is working: … Not done yet."). Results for a superseded
 * delegation are dropped; a new delegation while one runs interrupts it first. A failed submit
 * says "Sorry, I could not reach Hermes for that request." Approvals surface as
 * [BridgeEvent.ApprovalNeeded] and are never answered here.
 *
 * The call ends on a spoken stop phrase (judged on the user's utterance after 1.5 s quiet, and on
 * the delegation prompt), after [BridgeSettings.idleHangUpMs] idle, or at the call cap. `:live`
 * wires [onDataChannelMessage] to the `oai-events` channel and [send] to its `send`.
 *
 * All methods must be called from [scope]'s thread (single-threaded dispatcher, e.g. Main).
 */
class DelegationBridge(
    private val scope: CoroutineScope,
    private val backend: DelegationBackend,
    private val send: (String) -> Boolean,
    private val settings: BridgeSettings = BridgeSettings(),
    private val listener: (BridgeEvent) -> Unit = {},
) {
    private val events = LiveClientEvents()
    private val transcript = LiveTranscript()
    private val _status = MutableStateFlow(BridgeStatus.Connecting)
    private var utterance = StringBuilder()
    private var utteranceTimer: Job? = null
    private var idleTimer: Job? = null
    private var capTimer: Job? = null
    private var closeTimer: Job? = null
    private var graceTimer: Job? = null
    private var turnJob: Job? = null
    private var localIds = 0

    /** The delegation being answered; results for any other id are dropped. */
    var activeDelegation: String? = null
        private set
    private var activeWireId: String? = null
    private var finished = false
    private var remoteSpeaking = false
    private var outputPulse = false
    private var idleWarned = false

    /**
     * Optional hook for a delegation before it becomes a Hermes turn. Return true to consume it
     * (no turn is started and the running one keeps going). Hermes Live uses it to answer an open
     * clarify question with what the user just said.
     */
    var delegationInterceptor: ((DelegationPrompt) -> Boolean)? = null

    /** Whether the voice is audible now (remote audio level), for the goodbye's quiet check. */
    fun setRemoteSpeaking(speaking: Boolean) {
        remoteSpeaking = speaking
        if (speaking) outputPulse = true
    }

    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    /** Feed one raw `oai-events` message. */
    fun onDataChannelMessage(raw: String) {
        LiveServerEvent.parse(raw)?.let(::onServerEvent)
    }

    /** Feed one parsed event. */
    fun onServerEvent(event: LiveServerEvent) {
        if (finished) return
        when (event) {
            is LiveServerEvent.Started -> {
                if (_status.value == BridgeStatus.Connecting) _status.value = BridgeStatus.Listening
                listener(BridgeEvent.Started(event.sessionId))
                armCap()
                armIdle()
            }
            is LiveServerEvent.Transcript -> onTranscript(event.fragment)
            is LiveServerEvent.DelegationCreated -> onDelegation(event.delegationId)
            is LiveServerEvent.UsageUpdated -> listener(BridgeEvent.UsageUpdated(event.seconds))
            is LiveServerEvent.Error -> if (!event.ignorable) listener(BridgeEvent.LiveError(event.message, event.code))
            is LiveServerEvent.Closed -> finish(event.reason, event.usageSeconds)
            is LiveServerEvent.Other -> Unit
        }
    }

    /** A typed or intent-supplied request (Hermes Live's PROMPT extra): run it as a delegation with no vendor id. */
    fun delegateText(text: String) {
        if (finished || text.isBlank()) return
        // No vendor delegation exists for a typed request: tell the voice it's in hand.
        events.instructions(
            "The user typed this request and it has gone to Hermes: \"${text.trim().take(300)}\". " +
                "Say briefly that you are checking, then wait for the answer. Do not answer it yourself.",
        )?.let(send)
        startDelegation(localId = "text-${++localIds}", wireId = null, prompt = DelegationPrompt(text.trim(), "User: ${text.trim()}"))
    }

    /** Mute or unmute the user's microphone on the vendor side (the app also disables the track). */
    fun setMuted(muted: Boolean) {
        send(events.mute(muted))
    }

    /** Steer the voice for the rest of the session (`session.instructions.append`). */
    fun instruct(text: String) {
        if (finished) return
        events.instructions(text)?.let(send)
    }

    /** Quiet progress for the voice about the running delegation (`session.thinking.append`). */
    fun noteProgress(text: String) {
        if (finished) return
        think(activeWireId, text)
    }

    /** No turn boundary in full duplex: tell the voice to answer now. */
    fun nudge() {
        events.instructions("The user has finished speaking. Respond now to what they said.")?.let(send)
    }

    /**
     * End the call gracefully: `session.close`, then [BridgeEvent.Closed] on `session.closed` or
     * after the timeout. A bridge-initiated end says goodbye first when [BridgeSettings.goodbyeMaxMs]
     * is set. A user request during that goodbye closes at once.
     */
    fun close(reason: StopReason = StopReason.UserRequest) {
        if (finished) return
        if (_status.value == BridgeStatus.Closing) {
            // Already closing. A user request skips a goodbye still in progress.
            if (reason == StopReason.UserRequest && goodbyeJob?.isActive == true) {
                goodbyeJob?.cancel()
                sendClose()
            }
            return
        }
        if (reason != StopReason.UserRequest) listener(BridgeEvent.Stopping(reason))
        _status.value = BridgeStatus.Closing
        cancelTurn()
        utteranceTimer?.cancel()
        idleTimer?.cancel()
        if (reason != StopReason.UserRequest && settings.goodbyeMaxMs > 0) {
            val goodbye = events.instructions("Say a brief goodbye.")
            if (goodbye != null && send(goodbye)) {
                goodbyeJob = scope.launch {
                    awaitGoodbye()
                    sendClose()
                }
                return
            }
        }
        sendClose()
    }

    private var goodbyeJob: Job? = null

    private fun sendClose() {
        if (finished || closeTimer?.isActive == true) return
        if (!send(events.close())) {
            finish("close_requested", null)
            return
        }
        closeTimer = scope.launch {
            delay(settings.closeTimeoutMs)
            finish("close_requested", null)
        }
    }

    /** Wait until the goodbye was heard and has been quiet for [BridgeSettings.goodbyeQuietMs], at most [BridgeSettings.goodbyeMaxMs]. */
    private suspend fun awaitGoodbye() {
        val tick = 100L
        var elapsed = 0L
        var quiet = 0L
        var heard = false
        outputPulse = false
        while (elapsed < settings.goodbyeMaxMs) {
            delay(tick)
            elapsed += tick
            val active = outputPulse || remoteSpeaking
            outputPulse = false
            if (active) {
                heard = true
                quiet = 0
            } else {
                quiet += tick
            }
            if (heard && quiet >= settings.goodbyeQuietMs) return
        }
    }

    /** The data channel or peer connection dropped without `session.closed`. */
    fun onTransportLost() = finish("connection_lost", null)

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private fun onTranscript(fragment: LiveTranscriptFragment) {
        transcript.append(fragment)
        listener(BridgeEvent.Transcript(fragment))
        if (fragment.speaker != Speaker.User) {
            outputPulse = true
            return
        }
        if (_status.value == BridgeStatus.Closing) return
        armIdle()
        utterance.append(fragment.text)
        utteranceTimer?.cancel()
        utteranceTimer = scope.launch {
            delay(settings.utteranceSettleMs)
            val said = utterance.toString()
            utterance = StringBuilder()
            if (StopPhrases.isStopCommand(said, settings.stopPhrases)) close(StopReason.SpokenStop)
        }
    }

    private fun onDelegation(delegationId: String) {
        if (_status.value == BridgeStatus.Closing) return
        val prompt = LiveTranscript.delegationPrompt(transcript.contextWindow())
        if (prompt.prompt.isNotEmpty() && StopPhrases.isStopCommand(prompt.prompt, settings.stopPhrases)) {
            close(StopReason.SpokenStop)
            return
        }
        if (delegationInterceptor?.invoke(prompt) == true) return
        startDelegation(localId = delegationId, wireId = delegationId, prompt = prompt)
    }

    private fun startDelegation(localId: String, wireId: String?, prompt: DelegationPrompt) {
        val interruptFirst = activeDelegation != null
        cancelTurn()
        activeDelegation = localId
        activeWireId = wireId
        _status.value = BridgeStatus.Thinking
        idleTimer?.cancel()
        listener(BridgeEvent.DelegationStarted(wireId, prompt.prompt))
        val cursor = SpeechCursor()
        var lastTool: String? = null
        graceTimer = scope.launch {
            delay(settings.submitGraceMs)
            if (activeDelegation == localId) settle(localId, spoke = cursor.spokeAnything)
        }
        turnJob = scope.launch {
            try {
                backend.delegate(prompt.prompt, prompt.context, interruptFirst).collect { update ->
                    if (activeDelegation != localId) return@collect
                    graceTimer?.cancel()
                    when (update) {
                        is TurnUpdate.Text -> cursor.advance(update.text)?.let { speak(wireId, it) }
                        is TurnUpdate.ToolStarted -> if (update.label != lastTool) {
                            lastTool = update.label
                            listener(BridgeEvent.ToolStarted(wireId, update.label))
                            think(wireId, "Hermes is working: ${update.label}. Not done yet.")
                        }
                        is TurnUpdate.ApprovalNeeded -> {
                            listener(BridgeEvent.ApprovalNeeded(update.request))
                            think(wireId, "Hermes needs the user to approve a step on screen. Not done yet.")
                        }
                        is TurnUpdate.ClarifyNeeded -> {
                            listener(BridgeEvent.ClarifyNeeded(update.request))
                            think(wireId, "Hermes asked the user a question on screen. Not done yet.")
                        }
                        is TurnUpdate.RequestWithdrawn -> listener(BridgeEvent.RequestWithdrawn(update.id))
                        is TurnUpdate.Completed -> {
                            cursor.settle(update.text)?.let { speak(wireId, it) }
                            settle(localId, cursor.spokeAnything)
                        }
                        is TurnUpdate.Failed -> fail(localId, update.message)
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeDelegation == localId) {
                    fail(localId, e.message ?: "Could not reach Hermes.", e.javaClass.simpleName, (e as? HermesRpcException)?.code)
                }
            }
        }
    }

    private fun settle(localId: String, spoke: Boolean) {
        if (activeDelegation != localId) return
        if (!spoke) think(activeWireId, "Hermes finished that request without a spoken result.")
        listener(BridgeEvent.DelegationSettled(activeWireId, spoke))
        clearDelegation()
    }

    private fun fail(localId: String, message: String, kind: String = "turn_failed", code: Int? = null) {
        if (activeDelegation != localId) return
        speak(activeWireId, "Sorry, I could not reach Hermes for that request.")
        listener(BridgeEvent.DelegationFailed(activeWireId, message, kind, code))
        clearDelegation()
    }

    private fun clearDelegation() {
        graceTimer?.cancel()
        activeDelegation = null
        activeWireId = null
        if (_status.value == BridgeStatus.Thinking) _status.value = BridgeStatus.Listening
        armIdle()
    }

    private fun cancelTurn() {
        turnJob?.cancel()
        graceTimer?.cancel()
        turnJob = null
    }

    /**
     * Have the voice say [text]. Commentary and thinking need a vendor delegation id; GPT-Live
     * ignores them without one (typed requests, idle and cap warnings). Those go as session
     * instructions, the one append that takes `delegation_id: null`.
     */
    private fun speak(delegationId: String?, text: String) {
        if (delegationId != null) {
            events.commentary(delegationId, text).forEach { send(it) }
        } else {
            Commentary.chunk(text).forEach { chunk ->
                events.instructions("Say this to the user now, in your own words, then stop: $chunk")?.let(send)
            }
        }
    }

    /** Quiet progress for a vendor delegation. Without one there is nothing to attach it to. */
    private fun think(delegationId: String?, text: String) {
        if (delegationId != null) events.thinking(delegationId, text)?.let(send)
    }

    private fun armIdle() {
        idleTimer?.cancel()
        if (idleWarned) {
            idleWarned = false
            listener(BridgeEvent.IdleWarningCleared)
        }
        if (settings.idleHangUpMs <= 0 || finished) return
        idleTimer = scope.launch {
            if (settings.idleWarningMs in 1 until settings.idleHangUpMs) {
                delay(settings.idleHangUpMs - settings.idleWarningMs)
                if (activeDelegation != null) return@launch
                idleWarned = true
                listener(BridgeEvent.IdleWarning)
                val seconds = (settings.idleWarningMs + 999) / 1000
                speak(null, "It has gone quiet. I will hang up in $seconds seconds unless you need anything.")
                delay(settings.idleWarningMs)
            } else {
                delay(settings.idleHangUpMs)
            }
            if (activeDelegation == null) close(StopReason.IdleTimeout)
        }
    }

    private fun armCap() {
        capTimer?.cancel()
        if (settings.callCapMs <= 0) return
        capTimer = scope.launch {
            val warnAt = settings.callCapMs - settings.capWarningMs
            if (settings.capWarningMs in 1 until settings.callCapMs) {
                delay(warnAt)
                listener(BridgeEvent.CapWarning)
                speak(null, "This call will end in one minute because of the call time limit.")
                delay(settings.capWarningMs)
            } else {
                delay(settings.callCapMs)
            }
            close(StopReason.CallCap)
        }
    }

    private fun finish(reason: String, usageSeconds: Double?) {
        if (finished) return
        finished = true
        listOf(utteranceTimer, idleTimer, capTimer, closeTimer, graceTimer, turnJob, goodbyeJob).forEach { it?.cancel() }
        activeDelegation = null
        activeWireId = null
        _status.value = BridgeStatus.Closed
        listener(BridgeEvent.Closed(reason, usageSeconds))
    }
}
