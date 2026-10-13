package com.folio.hermes

import com.folio.hermes.live.DelegationBackend
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One step of a turn, as [HermesConversation.turn] streams it. */
sealed interface TurnUpdate {
    /** `prompt.submit` accepted the text. */
    data class Accepted(val sessionId: String, val status: SubmitStatus) : TurnUpdate

    /** The turn began streaming. */
    data object Started : TurnUpdate

    /** A streamed chunk: [delta] is new, [text] is the reply so far. */
    data class Text(val delta: String, val text: String) : TurnUpdate

    /** Commentary beside a tool call. */
    data class Interim(val text: String) : TurnUpdate

    /** A tool call began; [label] is a speakable name. */
    data class ToolStarted(val toolId: String, val name: String, val label: String) : TurnUpdate

    /** A tool call finished. */
    data class ToolCompleted(val toolId: String, val name: String, val summary: String?) : TurnUpdate

    /** The agent needs the user's approval; show [request] and answer it with [HermesGateway.answerApproval]. */
    data class ApprovalNeeded(val request: ApprovalRequest) : TurnUpdate

    /** The agent asks the user questions; answer with [HermesGateway.answerClarify]. */
    data class ClarifyNeeded(val request: ClarifyRequest) : TurnUpdate

    /** The server withdrew request [id]; clear its card. */
    data class RequestWithdrawn(val id: String, val reason: String) : TurnUpdate

    /** A session-level error while the turn ran (the turn continues). */
    data class Warning(val message: String) : TurnUpdate

    /** The turn ended. [text] is the final reply (may replace what was streamed). Terminal. */
    data class Completed(val text: String, val status: TurnStatus, val error: String?) : TurnUpdate

    /** The turn could not run or its stream was lost. Terminal. */
    data class Failed(val message: String, val cause: Throwable? = null) : TurnUpdate
}

/**
 * One Hermes session used for a series of turns: creates it on first use, keeps its stored id,
 * re-binds it after a reconnect (`session.resume`), recovers a reaped runtime id (4001) and waits
 * out a brief "session busy" (4009). Folio's text Ask and Hermes Live's voice each use their own.
 *
 * @param storedSessionId a stored id from an earlier run, to continue that conversation.
 * @param onStoredSessionChanged called when a new stored id is minted (persist it if wanted).
 */
class HermesConversation(
    private val gateway: HermesGateway,
    private val config: HermesConfig,
    private val options: SessionOptions = SessionOptions(),
    storedSessionId: String? = null,
    private val onStoredSessionChanged: (String) -> Unit = {},
) : DelegationBackend {
    private val mutex = Mutex()

    @Volatile private var runtimeId: String? = null
    @Volatile private var boundGeneration: Long = -1

    @Volatile var storedSessionId: String? = storedSessionId
        private set

    /** True while this session runs a turn (see [HermesGateway.isRunning]). */
    val isBusy: Boolean get() = runtimeId?.let(gateway::isRunning) == true

    /** The live runtime id for this socket, creating or resuming the session as needed. */
    suspend fun ensureSession(): String = mutex.withLock {
        val ready = gateway.awaitReady()
        runtimeId?.let { if (boundGeneration == ready.generation) return@withLock it }
        storedSessionId?.let { stored ->
            try {
                val resumed = gateway.resumeSession(stored, options.profile)
                bind(resumed.sessionId, resumed.storedSessionId, ready.generation)
                return@withLock resumed.sessionId
            } catch (e: HermesRpcException) {
                // Never prompted (no stored row yet) or deleted: start a new session below.
            }
        }
        val created = gateway.createSession(options)
        bind(created.sessionId, created.storedSessionId, ready.generation)
        created.sessionId
    }

    /** Stop the running turn, if any. */
    suspend fun interrupt(): Boolean {
        val sid = runtimeId ?: return false
        return runCatching { gateway.interrupt(sid) }.isSuccess
    }

    override fun delegate(prompt: String, voiceContext: String, interruptFirst: Boolean): Flow<TurnUpdate> =
        turn(prompt, PromptSurface.VoiceLive, voiceContext, interruptFirst)

    /**
     * Submit [text] and stream the turn until [TurnUpdate.Completed] or [TurnUpdate.Failed].
     * With [interruptFirst], a turn the session is still running is stopped and its completion
     * awaited first, so the reply is for this text (an idle session is not interrupted). Without
     * it the server's busy mode applies (queued / redirected / steered; all handled).
     *
     * A socket drop mid-turn re-binds the session on the next connection (`session.resume`) and
     * keeps reading; if the turn finished during the gap the turn fails. It never hangs: an error
     * before the turn starts, a failed re-bind, or [HermesConfig.turnInactivityTimeoutMs] of
     * silence fails it.
     */
    fun turn(
        text: String,
        surface: PromptSurface? = null,
        voiceContext: String? = null,
        interruptFirst: Boolean = false,
    ): Flow<TurnUpdate> = channelFlow {
        val inbox = Channel<Any>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { gateway.events.collect { inbox.send(it) } }
        var watcher: kotlinx.coroutines.Job? = null
        try {
            var sid = ensureSession()
            if (interruptFirst && gateway.isRunning(sid)) settleInterrupt(sid, inbox)
            fun watch() = launch {
                val generation = (gateway.connection.value as? GatewayConnection.Ready)?.generation
                gateway.connection.first { !(it is GatewayConnection.Ready && it.generation == generation) }
                inbox.send(Dropped)
            }
            watcher = watch()
            val (usedSid, result) = submit(sid, text, surface, voiceContext)
            sid = usedSid
            send(TurnUpdate.Accepted(sid, result.status))
            if (result.status == SubmitStatus.VoiceStopped) {
                send(TurnUpdate.Completed("", TurnStatus.Interrupted, null))
                return@channelFlow
            }
            var skipCompletes = if (result.status == SubmitStatus.Queued) 1 else 0
            var started = result.status == SubmitStatus.Redirected || result.status == SubmitStatus.Steered
            val streamed = StringBuilder()
            while (true) {
                val item = withTimeoutOrNull(config.turnInactivityTimeoutMs) { inbox.receive() }
                    ?: run {
                        send(TurnUpdate.Failed("Hermes stopped answering."))
                        return@channelFlow
                    }
                if (item === Dropped) {
                    val rebound = rebind()
                    if (rebound == null || !gateway.isRunning(rebound)) {
                        send(TurnUpdate.Failed("Lost the connection to Hermes."))
                        return@channelFlow
                    }
                    sid = rebound
                    watcher = watch()
                    continue
                }
                val event = item as GatewayEvent
                if (event.sessionId != sid) continue
                when (event) {
                    is GatewayEvent.MessageStart -> if (skipCompletes == 0 && !started) {
                        started = true
                        streamed.setLength(0)
                        send(TurnUpdate.Started)
                    }
                    is GatewayEvent.MessageDelta -> if (started && skipCompletes == 0) {
                        streamed.append(event.text)
                        send(TurnUpdate.Text(event.text, streamed.toString()))
                    }
                    is GatewayEvent.MessageInterim -> if (started) send(TurnUpdate.Interim(event.text))
                    is GatewayEvent.ToolStart -> if (started) {
                        send(TurnUpdate.ToolStarted(event.toolId, event.name, toolLabel(event.name)))
                    }
                    is GatewayEvent.ToolComplete -> if (started) {
                        send(TurnUpdate.ToolCompleted(event.toolId, event.name, event.summary))
                    }
                    is GatewayEvent.ApprovalRequested -> send(TurnUpdate.ApprovalNeeded(event.request))
                    is GatewayEvent.ClarifyRequested -> send(TurnUpdate.ClarifyNeeded(event.request))
                    is GatewayEvent.RequestCancelled -> send(TurnUpdate.RequestWithdrawn(event.id, event.reason))
                    is GatewayEvent.Error -> if (started) {
                        send(TurnUpdate.Warning(event.message))
                    } else {
                        send(TurnUpdate.Failed(event.message.ifEmpty { "Hermes could not run that." }))
                        return@channelFlow
                    }
                    is GatewayEvent.MessageComplete -> when {
                        skipCompletes > 0 -> skipCompletes--
                        !started -> Unit // an older turn finishing (interrupt race); ours has not begun
                        else -> {
                            val final = event.text.ifEmpty { streamed.toString() }
                            send(TurnUpdate.Completed(final, event.status, event.error))
                            return@channelFlow
                        }
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            send(TurnUpdate.Failed(e.message ?: "Could not reach Hermes.", e))
        } finally {
            collector.cancel()
            watcher?.cancel()
            inbox.close()
        }
    }

    /** Interrupt, then wait (bounded) for that session's `message.complete` so the next submit starts fresh. */
    private suspend fun settleInterrupt(sid: String, inbox: Channel<Any>) {
        runCatching { gateway.interrupt(sid) }
        withTimeoutOrNull(config.interruptSettleMs) {
            while (true) {
                val event = inbox.receive()
                if (event is GatewayEvent.MessageComplete && event.sessionId == sid) break
            }
        }
    }

    /** After a drop: wait for the next connection, resume the session there; the new runtime id or null. */
    private suspend fun rebind(): String? = try {
        withTimeoutOrNull(config.connectTimeoutMs) {
            gateway.connection.first { it is GatewayConnection.Ready }
            runtimeId = null
            ensureSession()
        }
    } catch (e: IOException) {
        null
    }

    /** Submit with 4001 recovery (resume the stored session, retry once) and 4009 busy retries. */
    private suspend fun submit(
        sid: String,
        text: String,
        surface: PromptSurface?,
        voiceContext: String?,
    ): Pair<String, PromptSubmitResult> = try {
        sid to submitBusyAware(sid, text, surface, voiceContext)
    } catch (e: HermesRpcException) {
        if (!e.isSessionNotFound) throw e
        runtimeId = null
        val fresh = ensureSession()
        fresh to submitBusyAware(fresh, text, surface, voiceContext)
    }

    private suspend fun submitBusyAware(sid: String, text: String, surface: PromptSurface?, voiceContext: String?): PromptSubmitResult {
        val deadline = System.nanoTime() + config.busyRetryWindowMs * 1_000_000
        while (true) {
            try {
                return gateway.submitPrompt(sid, text, surface, voiceContext)
            } catch (e: HermesRpcException) {
                if (!e.isSessionBusy || System.nanoTime() > deadline) throw e
                delay(config.busyRetryIntervalMs)
            }
        }
    }

    private fun bind(runtime: String, stored: String, generation: Long) {
        runtimeId = runtime
        boundGeneration = generation
        if (stored != storedSessionId) {
            storedSessionId = stored
            onStoredSessionChanged(stored)
        }
    }

    private object Dropped

    companion object {
        /** `web_search` → "web search": a tool name the voice can say. */
        fun toolLabel(name: String): String = name.replace('_', ' ').replace('.', ' ').trim().ifEmpty { "a tool" }
    }
}
