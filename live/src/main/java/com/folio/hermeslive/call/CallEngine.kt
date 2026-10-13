package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.HermesAuthException
import com.folio.hermes.live.BridgeEvent
import com.folio.hermes.live.BridgeStatus
import com.folio.hermes.live.DelegationBridge
import com.folio.hermes.live.DelegationPrompt
import com.folio.hermes.live.Speaker
import com.folio.hermes.live.StopReason
import com.folio.hermes.live.VoiceLiveApi
import com.folio.hermes.live.VoiceLiveException
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Where the Hermes session id to resume is kept between calls. */
interface SessionMemoryStore {
    fun load(): SessionMemory
    fun save(memory: SessionMemory)
}

/**
 * One Hermes Live call, end to end, without Android: WebRTC behind [LiveTransport], audio behind
 * [CallAudio], Hermes behind [HermesLink]. Owns the [DelegationBridge], the captions, the music
 * policy, routes, mute, the other-call pause, one automatic reconnect and the call summary.
 *
 * Lifecycle: [start] → Connecting → (session.started) Live → [end] / stop phrase / idle / cap →
 * Ending (goodbye, `session.close`, ≤15 s for `session.closed`) → Ended with a [CallSummary].
 * A dropped line while Live reconnects once with the transcript as history.
 *
 * Every method and callback runs on [scope]'s single thread (Main).
 */
class CallEngine(
    private val scope: CoroutineScope,
    private val settings: () -> LiveSettings,
    private val links: HermesLinkFactory,
    private val transports: LiveTransportFactory,
    private val audio: CallAudio,
    private val effects: CallEffects,
    private val memory: SessionMemoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : CallAudio.Listener {
    private val _state = MutableStateFlow(CallUiState())

    /** Everything the UI, notification, tile and provider show. */
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var epoch = 0
    private var link: HermesLink? = null
    private var transport: LiveTransport? = null
    private var bridge: DelegationBridge? = null
    private var sender: OrderedSender? = null
    private val captions = CaptionLog()
    private var policy = GapMusicPolicy()
    private val vad = EnergyVad()
    private val remoteGate = LevelGate()
    private var callSettings = LiveSettings()

    private var devices: List<RouteOption> = emptyList()
    private var route = RouteChoice(null, manual = false)
    private var lastUserTextAtMs = Long.MIN_VALUE / 2
    private var lastVoiceTextAtMs = Long.MIN_VALUE / 2
    private var delegationActive = false
    private var delegationCutOff = false
    private var reconnectsLeft = 0
    private var userEnding = false
    private var everLive = false
    private var liveAtMs: Long? = null
    private var usageDone = 0.0
    private var usageNow = 0.0
    private var pendingPrompt: String? = null
    private var autoMuted = false
    private var otherCall = false
    private var utterance = StringBuilder()
    private var utteranceJob: Job? = null
    private var tickJob: Job? = null
    private var startedTimeout: Job? = null
    private var micLevel = 0f
    private var remoteLevel = 0f
    private var lastRemoteLevel = 0.0

    // ── commands ────────────────────────────────────────────────────────────────────────────

    /** Start a call. While one is running, a [CallRequest.prompt] is submitted to it instead. */
    fun start(request: CallRequest = CallRequest()) {
        val current = _state.value
        if (current.inCall) {
            request.prompt?.let(::submitText)
            if (request.muted && !current.muted) setMuted(true)
            return
        }
        callSettings = settings()
        if (request.newConversation) memory.save(SessionMemory.Empty)
        resetCall()
        policy = GapMusicPolicy(callSettings.gapSettings())
        reconnectsLeft = 1
        pendingPrompt = request.prompt?.trim()?.ifEmpty { null }
        _state.value = CallUiState(phase = CallPhase.Connecting, muted = request.muted)
        audio.begin(this)
        devices = audio.devices()
        route = RouteChoice(RouteSelector.auto(devices, callSettings.fallbackRoute), manual = false)
        applyRoute()
        audio.setFocus(FocusMode.Hold)
        link = links.create(memory.load().resumable(clock()))
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                evaluate()
            }
        }
        connect()
    }

    /** End the call (End button, notification, tile, headset). A second press skips the goodbye. */
    fun end() {
        val phase = _state.value.phase
        if (!_state.value.inCall) return
        userEnding = true
        val b = bridge
        if (b == null || phase == CallPhase.Connecting || phase == CallPhase.Reconnecting) {
            if (b != null && b.status.value != BridgeStatus.Connecting) b.close() else finish("close_requested")
            return
        }
        _state.update { it.copy(phase = CallPhase.Ending, notice = "Ending…") }
        b.close(StopReason.UserRequest)
    }

    fun toggleMute() = setMuted(!_state.value.muted)

    fun setMuted(muted: Boolean, quiet: Boolean = false) {
        if (!_state.value.inCall || _state.value.muted == muted) return
        if (!muted) autoMuted = false
        _state.update { it.copy(muted = muted) }
        transport?.setMicEnabled(!muted)
        if (bridge?.status?.value.let { it != null && it != BridgeStatus.Connecting && it != BridgeStatus.Closed }) {
            bridge?.setMuted(muted)
        }
        if (!quiet) {
            effect(if (muted) Earcon.Muted else Earcon.Unmuted)
        }
        evaluate()
    }

    /** The user picked a route in the call screen. */
    fun selectRoute(option: RouteOption) {
        if (!_state.value.inCall) return
        route = RouteChoice(devices.firstOrNull { it.id == option.id } ?: option, manual = true)
        applyRoute()
    }

    /** A typed or shared request during the call. */
    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val b = bridge
        if (b == null || b.status.value == BridgeStatus.Connecting) {
            pendingPrompt = clean
            return
        }
        captions.appendUserText(clean)
        _state.update { it.copy(captions = captions.captions) }
        b.delegateText(clean)
    }

    /** The user tapped an approval answer. The only way (besides opted-in speech) an approval is answered. */
    fun answerApproval(id: String, choice: ApprovalChoice) {
        val card = _state.value.approvals.firstOrNull { it.id == id } ?: return
        if (choice !in card.choices && choice != ApprovalChoice.Deny) return
        _state.update { s -> s.copy(approvals = s.approvals.filterNot { it.id == id }) }
        val l = link ?: return
        scope.launch {
            try {
                l.answerApproval(card.request, choice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
            }
        }
    }

    /** The user answered the clarify card ([answers] by question id; null skips one). */
    fun answerClarify(id: String, answers: Map<String, String?>) {
        val card = _state.value.clarify?.takeIf { it.id == id } ?: return
        _state.update { it.copy(clarify = null) }
        val l = link ?: return
        scope.launch {
            try {
                l.answerClarify(card.request, answers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Could not send your answer to Hermes.") }
            }
        }
    }

    /** Clear the summary or error after the user saw it. */
    fun dismissSummary() {
        if (_state.value.inCall) return
        _state.value = CallUiState()
    }

    // ── CallAudio.Listener ─────────────────────────────────────────────────────────────────

    override fun onDevicesChanged(devices: List<RouteOption>) {
        if (!_state.value.inCall) return
        val choice = RouteSelector.onDevicesChanged(this.devices, devices, route, callSettings.fallbackRoute)
        this.devices = devices
        val moved = choice.route?.id != route.route?.id
        route = choice
        if (moved) applyRoute() else _state.update { it.copy(routes = devices, route = route.route) }
        evaluate()
    }

    override fun onBecomingNoisy() {
        if (!_state.value.inCall) return
        route = RouteSelector.onNoisy(devices, callSettings.fallbackRoute)
        applyRoute()
    }

    override fun onOtherCall(active: Boolean) {
        if (!_state.value.inCall || active == otherCall) return
        otherCall = active
        if (active) {
            if (!_state.value.muted) {
                setMuted(true, quiet = true)
                autoMuted = true
            }
            transport?.setSpeakerMuted(true)
            _state.update { it.copy(onOtherCall = true, notice = "Paused for your other call.") }
        } else {
            audio.reassert()
            applyRoute()
            transport?.setSpeakerMuted(false)
            if (autoMuted) setMuted(false, quiet = true)
            autoMuted = false
            _state.update { it.copy(onOtherCall = false, notice = "Back from your other call.") }
        }
        evaluate()
    }

    // ── connection ─────────────────────────────────────────────────────────────────────────

    private fun connect() {
        val attempt = ++epoch
        val l = link ?: return
        val s = callSettings
        scope.launch {
            try {
                val validity = (if (s.callCapMinutes > 0) s.callCapMinutes else 60) * 60_000L + 5 * 60_000L
                l.prepare(validity)
                if (attempt != epoch) return@launch
                val t = transports.create(eventsFor(attempt))
                transport = t
                val snd = OrderedSender(scope, t::send, ::commentaryLead)
                sender = snd
                val b = DelegationBridge(scope, l.backend, snd::send, s.bridgeSettings()) { event ->
                    if (attempt == epoch) onBridgeEvent(event)
                }
                b.delegationInterceptor = ::intercept
                bridge = b
                if (_state.value.muted) t.setMicEnabled(false)
                val history = VoiceLiveApi.history(captions.history())
                withTimeout(START_TIMEOUT_MS) { t.start { offer -> l.createVoiceSession(offer, history) } }
                if (attempt != epoch) return@launch
                startedTimeout = scope.launch {
                    delay(STARTED_TIMEOUT_MS)
                    if (attempt == epoch && b.status.value == BridgeStatus.Connecting) fail("Hermes Live did not start. Try again.")
                }
            } catch (e: CancellationException) {
                if (attempt == epoch && _state.value.inCall && e is kotlinx.coroutines.TimeoutCancellationException) {
                    fail("Hermes Live took too long to connect.")
                } else if (e !is kotlinx.coroutines.TimeoutCancellationException) {
                    throw e
                }
            } catch (e: Exception) {
                if (attempt == epoch) fail(describe(e))
            }
        }
    }

    private fun eventsFor(attempt: Int) = object : TransportEvents {
        override fun onMessage(text: String) {
            if (attempt == epoch) bridge?.onDataChannelMessage(text)
        }

        override fun onLost(reason: String) {
            if (attempt == epoch) bridge?.onTransportLost() ?: finish("connection_lost")
        }

        override fun onRemoteLevel(level: Double) {
            if (attempt != epoch) return
            lastRemoteLevel = level
            remoteGate.offer(level, clock())
            remoteLevel = (level * 4).toFloat().coerceIn(0f, 1f)
        }

        override fun onMicLevel(levelDb: Double, durationMs: Long) {
            if (attempt != epoch) return
            vad.offer(levelDb, durationMs)
            micLevel = ((levelDb + 60) / 50).toFloat().coerceIn(0f, 1f)
        }
    }

    private fun onBridgeEvent(event: BridgeEvent) {
        when (event) {
            is BridgeEvent.Started -> onStarted()
            is BridgeEvent.Transcript -> {
                captions.append(event.fragment)
                val now = clock()
                if (event.fragment.speaker == Speaker.User) {
                    lastUserTextAtMs = now
                    onUserWords(event.fragment.text)
                } else {
                    lastVoiceTextAtMs = now
                    utterance = StringBuilder()
                }
                _state.update { it.copy(captions = captions.captions) }
                evaluate()
            }
            is BridgeEvent.DelegationStarted -> {
                delegationActive = true
                touchMemory()
                _state.update { it.copy(tool = null) }
                evaluate()
            }
            is BridgeEvent.ToolStarted -> _state.update { it.copy(tool = event.label) }
            is BridgeEvent.DelegationSettled -> settled(null)
            is BridgeEvent.DelegationFailed -> settled(
                if (event.message.contains("sign in", ignoreCase = true)) "Sign in to Hermes again." else "Could not reach Hermes for that request.",
            )
            is BridgeEvent.ApprovalNeeded -> {
                if (_state.value.approvals.none { it.id == event.request.id }) {
                    _state.update { it.copy(approvals = it.approvals + ApprovalCard(event.request)) }
                    effects.haptic(strong = true)
                }
            }
            is BridgeEvent.ClarifyNeeded -> {
                _state.update { it.copy(clarify = ClarifyCard(event.request)) }
                effects.haptic(strong = true)
            }
            is BridgeEvent.RequestWithdrawn -> _state.update { s ->
                s.copy(approvals = s.approvals.filterNot { it.id == event.id }, clarify = s.clarify?.takeUnless { it.id == event.id })
            }
            is BridgeEvent.Stopping -> _state.update {
                it.copy(
                    phase = CallPhase.Ending,
                    notice = when (event.reason) {
                        StopReason.SpokenStop -> "Saying goodbye…"
                        StopReason.IdleTimeout -> "Nobody has spoken for a while. Hanging up."
                        StopReason.CallCap -> "Call time limit reached. Hanging up."
                        StopReason.UserRequest -> "Ending…"
                    },
                )
            }
            BridgeEvent.CapWarning -> {
                effect(Earcon.Warning)
                _state.update { it.copy(warning = "This call ends in 1 minute (call time limit).") }
            }
            BridgeEvent.IdleWarning -> {
                effect(Earcon.Warning)
                _state.update { it.copy(warning = "It has gone quiet. Hanging up in 30 seconds unless you speak.") }
            }
            BridgeEvent.IdleWarningCleared -> _state.update { it.copy(warning = null) }
            is BridgeEvent.LiveError -> _state.update { it.copy(notice = event.message) }
            is BridgeEvent.UsageUpdated -> usageNow = event.seconds
            is BridgeEvent.Closed -> onClosed(event.reason, event.usageSeconds)
        }
    }

    private fun onStarted() {
        startedTimeout?.cancel()
        val reconnected = everLive
        everLive = true
        if (liveAtMs == null) liveAtMs = clock()
        _state.update {
            it.copy(
                phase = CallPhase.Live,
                startedAtMs = liveAtMs,
                notice = if (reconnected) "Reconnected." else null,
                error = null,
            )
        }
        val b = bridge ?: return
        if (_state.value.muted) b.setMuted(true)
        if (!reconnected) {
            effect(Earcon.Connected)
        } else {
            effect(Earcon.Unmuted)
            b.instruct(
                "The line dropped for a moment and is back now. Tell the user in a few words that you are back." +
                    if (delegationCutOff) " The request you passed to Hermes was cut off; ask whether to try it again." else "",
            )
            delegationCutOff = false
        }
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            captions.appendUserText(prompt)
            _state.update { it.copy(captions = captions.captions) }
            b.delegateText(prompt)
        }
        applyRoute()
    }

    private fun onClosed(reason: String, usage: Double?) {
        usageDone += usage ?: usageNow
        usageNow = 0.0
        val wasLive = _state.value.phase == CallPhase.Live
        if (reason == "connection_lost" && !userEnding && wasLive && reconnectsLeft > 0) {
            reconnect()
        } else {
            finish(reason)
        }
    }

    private fun reconnect() {
        reconnectsLeft--
        delegationCutOff = delegationActive
        delegationActive = false
        dropTransport()
        effect(Earcon.Warning)
        _state.update {
            // Approval and clarify cards stay: the Hermes turn and the gateway outlive the WebRTC drop.
            it.copy(phase = CallPhase.Reconnecting, notice = "The line dropped. Reconnecting…", tool = null)
        }
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (_state.value.phase == CallPhase.Reconnecting) connect()
        }
    }

    private fun settled(problem: String?) {
        delegationActive = false
        touchMemory()
        _state.update { it.copy(tool = null, notice = problem ?: it.notice) }
        evaluate()
    }

    // ── spoken answers ─────────────────────────────────────────────────────────────────────

    /** A delegation while a card is open: answer the clarify, or an approval when spoken approvals are on. */
    private fun intercept(prompt: DelegationPrompt): Boolean {
        val said = prompt.prompt
        _state.value.clarify?.let { card ->
            val question = card.request.questions.singleOrNull()
            val answer = question?.let { SpokenReplies.clarify(said, it) }
            if (question != null && answer != null) {
                answerClarify(card.id, mapOf(question.qid to answer))
                bridge?.noteProgress("The user answered Hermes's question: $answer. Hermes is carrying on. Not done yet.")
                return true
            }
        }
        val approval = _state.value.approvals.firstOrNull() ?: return false
        val choice = SpokenReplies.approval(said, approval.choices) ?: return false
        if (callSettings.spokenApprovals) {
            answerApproval(approval.id, choice)
            bridge?.noteProgress("The user said ${if (choice == ApprovalChoice.Deny) "deny" else "approve"}. Hermes is carrying on. Not done yet.")
        } else {
            bridge?.noteProgress("Approvals need a tap on the screen. Ask the user to tap Approve or Deny. Not done yet.")
        }
        return true
    }

    /** Spoken approvals (setting, off by default): judge the whole utterance after 1.5 s of quiet. */
    private fun onUserWords(text: String) {
        if (!callSettings.spokenApprovals) return
        utterance.append(text)
        utteranceJob?.cancel()
        utteranceJob = scope.launch {
            delay(UTTERANCE_SETTLE_MS)
            val said = utterance.toString()
            utterance = StringBuilder()
            val card = _state.value.approvals.firstOrNull() ?: return@launch
            SpokenReplies.approval(said, card.choices)?.let { answerApproval(card.id, it) }
        }
    }

    // ── audio ──────────────────────────────────────────────────────────────────────────────

    private fun commentaryLead(): Long {
        val lead = policy.beforeCommentary(clock())
        if (lead > 0) audio.setFocus(FocusMode.Hold)
        return lead
    }

    /** Music policy, orb activity and level; runs on every signal and every [TICK_MS]. */
    private fun evaluate() {
        val s = _state.value
        if (!s.inCall) return
        val now = clock()
        remoteGate.offer(lastRemoteLevel, now)
        val userSpeaking = !s.muted && !otherCall && (vad.speaking || now - lastUserTextAtMs < TEXT_SPEECH_MS)
        val remoteSpeaking = remoteGate.on || now - lastVoiceTextAtMs < TEXT_SPEECH_MS
        bridge?.setRemoteSpeaking(remoteSpeaking)
        val headset = route.route?.kind?.isHeadset == true
        val decision = policy.update(GapSignals(userSpeaking, remoteSpeaking, delegationActive, headset, otherCall), now)
        if (decision.changed) {
            audio.setFocus(decision.mode)
            // Music is about to come back where the mic can hear it: make it the VAD's new floor
            // so it does not read as the user talking and pause itself again.
            if (decision.mode != FocusMode.Hold && !headset) vad.rebaseline(VAD_SETTLE_MS)
        }
        val activity = when {
            remoteSpeaking -> CallActivity.HermesSpeaking
            userSpeaking -> CallActivity.UserSpeaking
            delegationActive -> CallActivity.Thinking
            else -> CallActivity.Listening
        }
        val level = when (activity) {
            CallActivity.HermesSpeaking -> remoteLevel
            CallActivity.UserSpeaking -> micLevel
            else -> 0f
        }
        val music = policy.musicAllowed && !otherCall && audio.isMusicActive()
        if (s.activity != activity || s.musicInGap != music || s.level != level) {
            _state.update { it.copy(activity = activity, musicInGap = music, level = level) }
        }
    }

    private fun applyRoute() {
        val r = route.route
        if (r != null) audio.select(r)
        effects.proximity(_state.value.inCall && r?.kind == RouteKind.Earpiece)
        _state.update { it.copy(route = r, routes = devices) }
    }

    private fun effect(kind: Earcon) {
        if (callSettings.earcons) effects.earcon(kind)
        if (callSettings.haptics) effects.haptic(strong = kind == Earcon.Warning)
    }

    // ── teardown ───────────────────────────────────────────────────────────────────────────

    private fun fail(message: String) {
        finish("failed", error = message)
    }

    private fun finish(reason: String, error: String? = null) {
        if (!_state.value.inCall) return
        epoch++
        val endedAt = clock()
        tickJob?.cancel()
        utteranceJob?.cancel()
        startedTimeout?.cancel()
        dropTransport()
        touchMemory()
        link?.close()
        link = null
        effects.proximity(false)
        audio.end()
        if (everLive || error == null) effect(Earcon.Ended)
        val summary = if (everLive) {
            CallSummary(
                durationMs = liveAtMs?.let { endedAt - it } ?: 0L,
                voiceSeconds = usageDone.takeIf { it > 0 },
                reason = endReason(reason),
            )
        } else {
            null
        }
        _state.value = CallUiState(
            phase = if (error != null && !everLive) CallPhase.Failed else CallPhase.Ended,
            summary = summary,
            error = error,
            captions = captions.captions,
        )
    }

    private fun dropTransport() {
        sender?.clear()
        sender = null
        transport?.close()
        transport = null
        bridge = null
        remoteGate.reset()
    }

    private fun touchMemory() {
        val id = link?.storedSessionId ?: return
        memory.save(SessionMemory(id, clock()))
    }

    private fun resetCall() {
        captions.clear()
        devices = emptyList()
        route = RouteChoice(null, false)
        lastUserTextAtMs = Long.MIN_VALUE / 2
        lastVoiceTextAtMs = Long.MIN_VALUE / 2
        delegationActive = false
        delegationCutOff = false
        userEnding = false
        everLive = false
        liveAtMs = null
        usageDone = 0.0
        usageNow = 0.0
        autoMuted = false
        otherCall = false
        utterance = StringBuilder()
        micLevel = 0f
        remoteLevel = 0f
        lastRemoteLevel = 0.0
    }

    companion object {
        const val TICK_MS = 150L
        const val TEXT_SPEECH_MS = 700L
        const val UTTERANCE_SETTLE_MS = 1_500L
        const val START_TIMEOUT_MS = 60_000L
        const val STARTED_TIMEOUT_MS = 20_000L
        const val RECONNECT_DELAY_MS = 600L
        const val VAD_SETTLE_MS = 1_200L

        /** Plain English for `session.closed` reasons and our own. */
        fun endReason(reason: String): String = when (reason.trim()) {
            "", "closed", "close_requested", "client_closed", "client_close" -> "Call ended."
            "connection_lost" -> "The connection dropped."
            "failed" -> "The call failed."
            else -> "Hermes Live ended the call (${reason.trim().replace('_', ' ')})."
        }

        /** Plain English for a failed start. */
        fun describe(e: Throwable): String = when (e) {
            is HermesAuthException.SignInRequired -> "Sign in to Hermes again."
            is HermesAuthException.InvalidCredentials -> "Hermes no longer accepts the saved password. Sign in again."
            is HermesAuthException -> e.message ?: "Could not sign in to Hermes."
            is VoiceLiveException -> when (e.kind) {
                VoiceLiveException.Kind.NotConfigured -> "Voice is not set up on this Hermes server (no OpenAI key)."
                VoiceLiveException.Kind.VendorRejected -> "OpenAI refused the voice session. Try again in a moment."
                VoiceLiveException.Kind.Unauthorized -> "Sign in to Hermes again."
                VoiceLiveException.Kind.EmptyOffer, VoiceLiveException.Kind.Failed -> "Could not start the voice session."
            }
            else -> {
                val text = e.message.orEmpty()
                when {
                    text.contains("CLEARTEXT", ignoreCase = true) || text.contains("cleartext", ignoreCase = true) ->
                        "Android refused plain http:// to that address. Use the *.ts.net name or https://."
                    text.contains("Unable to resolve host", ignoreCase = true) -> "Could not find the Hermes server. Check the network."
                    text.contains("microphone", ignoreCase = true) -> "The microphone is not available."
                    else -> "Could not connect to Hermes Live."
                }
            }
        }
    }
}
