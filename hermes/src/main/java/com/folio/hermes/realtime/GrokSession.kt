package com.folio.hermes.realtime

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskNotice
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where Grok's voice plays: streamed PCM at the session rate, stopped at once on a barge-in. */
interface RealtimePlayback {
    fun write(pcm: ByteArray)

    /** Stop now and drop everything queued (the user cut in). */
    fun stop()

    /** True when nothing is queued or playing (audio keeps playing after `response.done`). */
    val idle: StateFlow<Boolean>

    /** Milliseconds heard since the current stream began (after it was last idle or stopped). */
    fun playedMs(): Long
}

/** A tool call on the call screen's strip (in place of Jev's answers). [ok] null while it runs. */
data class ToolChip(val callId: String, val name: String, val taskId: String? = null, val ok: Boolean? = null)

/** What the Grok call screen shows. */
data class GrokUi(
    val link: LinkState = LinkState.Idle,
    val userSpeaking: Boolean = false,
    /** Grok's audio is queued or playing. */
    val speaking: Boolean = false,
    /** The user's words as transcribed (cumulative within a turn). */
    val caption: String = "",
    /** The tail of Grok's spoken words. */
    val reply: String = "",
    /** The last few tool calls, oldest first. */
    val chips: List<ToolChip> = emptyList(),
    /** The last server error code (no message), for the screen. */
    val error: String? = null,
)

/**
 * One Grok Voice call driving Hermes tasks (the grok-voice slice): the realtime socket, the tools
 * on the shared task API, and the turn rules around them, all on one loop (no locks).
 *
 * - **Tools** (verified flow): calls are gathered per response (`function_call_arguments.done`,
 *   `output_item.done` or `response.done`'s output, deduped by `call_id`); once the response is
 *   done each runs once, every output is sent, then **one** `response.create`, only after the
 *   phone's playback has drained, the user is not speaking and no response is in flight.
 *   `end_call` sends no `response.create`: the goodbye plays out, then [onEndCall].
 * - **Results** ([CascadeTasks.notices]) wait in a queue, like Bill's: they are injected as one
 *   [GrokConfig.noticeRole] text item plus `response.create` only when Grok is idle (no response,
 *   no tools, nothing playing, the user silent). Once that response is done they are marked
 *   heard, so a later Bill call never replays them. Long results: an excerpt; the card has it all.
 * - **Barge-in:** `speech_started` stops playback at once, drops the rest of that response's
 *   audio, and truncates the model's copy of its audio item to what was heard.
 * - **Reconnect:** the socket reconnects itself (resuming the conversation when it can); the
 *   loop resets what was in flight, and a conversation that could not resume gets the task list.
 */
class GrokSession(
    private val realtime: GrokRealtime,
    private val tasks: CascadeTasks,
    private val playback: RealtimePlayback,
    private val scope: CoroutineScope,
    private val config: () -> GrokConfig,
    private val onEndCall: () -> Unit,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The longest wait for playback to drain before a tool round's `response.create` or the hang-up. */
    private val drainCapMs: Long = 8_000L,
) {
    private sealed interface Input {
        data class Server(val event: ServerEvent) : Input
        data class Notice(val notice: TaskNotice) : Input
        data class Idle(val idle: Boolean) : Input
        data class Link(val state: LinkState) : Input
        data class ToolsDone(val outputs: List<Pair<FunctionCall, ToolOutcome>>, val cancelled: Boolean) : Input
        data class Grace(val turn: Int) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val runner = GrokToolRunner(tasks)
    private val _ui = MutableStateFlow(GrokUi())
    private val jobs = mutableListOf<Job>()

    val ui: StateFlow<GrokUi> = _ui.asStateFlow()

    // Loop state (touched only on the loop).
    private var responding = false
    private var userSpeaking = false
    @Volatile private var muted = false
    private var toolsRunning = false
    private var createPending = false
    private var ending = false
    private var dropAudio = false
    private var audioItem: String? = null
    private var audioItemMs = 0L
    private val calls = linkedMapOf<String, FunctionCall>()
    private val seenCalls = hashSetOf<String>()
    private val queue = mutableListOf<TaskNotice>()
    private val queuedAt = hashMapOf<String, Long>()

    /** The user's turn ended and the server's answer to it has not finished (or the grace ran out). */
    private var turnPending = false
    private var turnSeq = 0

    /** Notices sent, waiting for their `response.created`; then bound to that response's id. */
    private var injecting: List<TaskNotice>? = null
    private var injectedResponse: String? = null
    private var injected: List<TaskNotice> = emptyList()

    /** We asked for a response and its `response.created` has not come. */
    private var ownCreatePending = false

    /** Tool outputs the socket did not take (it was reconnecting): resent if the conversation resumed. */
    private val unsent = mutableListOf<Pair<FunctionCall, ToolOutcome>>()
    private var connectedOnce = false
    private var drainWait: Job? = null

    /** Connect and run. Results already waiting (from before the call) are queued first. */
    fun start(): Boolean {
        jobs += scope.launch { for (input in inputs) step(input) }
        // After process death the store is read back first (bounded), so nothing waiting is missed.
        jobs += scope.launch { tasks.unheard().forEach { inputs.send(Input.Notice(it)) } }
        jobs += scope.launch { realtime.events.collect { inputs.send(Input.Server(it)) } }
        jobs += scope.launch { tasks.notices.collect { inputs.send(Input.Notice(it)) } }
        jobs += scope.launch { playback.idle.collect { inputs.send(Input.Idle(it)) } }
        jobs += scope.launch { realtime.link.collect { inputs.send(Input.Link(it)) } }
        return realtime.connect()
    }

    /** Microphone audio (PCM s16le mono at the session rate); dropped while muted. */
    fun audio(pcm: ByteArray) {
        if (!muted) realtime.appendAudio(pcm)
    }

    fun mute(on: Boolean) {
        muted = on
    }

    /** The network is back: reconnect (resuming). */
    fun networkBack() {
        val link = realtime.link.value
        // A socket often fails before the phone notices it is offline: after the retries it is `unreachable`.
        if (link is LinkState.Offline || link == LinkState.Failed(GrokRealtime.UNREACHABLE)) realtime.reconnect()
    }

    /** Hang-up: the socket closes, playback stops. Tasks keep running. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        drainWait?.cancel()
        playback.stop()
        realtime.close()
        inputs.close()
    }

    private suspend fun step(input: Input) {
        when (input) {
            is Input.Server -> server(input.event)
            is Input.Notice -> notice(input.notice)
            is Input.Idle -> _ui.value = _ui.value.copy(speaking = !input.idle)
            is Input.Link -> link(input.state)
            is Input.ToolsDone -> toolsDone(input.outputs, input.cancelled)
            // No answer to the user's turn came: a VAD blip must not hold results back for ever.
            is Input.Grace -> if (input.turn == turnSeq && !responding) turnPending = false
        }
        pump()
    }

    private fun server(e: ServerEvent) {
        when (e) {
            is ServerEvent.SpeechStarted -> bargeIn()
            is ServerEvent.SpeechStopped -> {
                userSpeaking = false
                // The server now commits the turn and answers it: nothing of ours goes in before that answer is done.
                turnPending = true
                val seq = ++turnSeq
                scope.launch {
                    kotlinx.coroutines.delay(TURN_GRACE_MS)
                    inputs.send(Input.Grace(seq))
                }
                _ui.value = _ui.value.copy(userSpeaking = false)
            }
            is ServerEvent.UserTranscript -> _ui.value = _ui.value.copy(caption = e.text.takeLast(CAPTION_CHARS))
            is ServerEvent.ResponseCreated -> {
                responding = true
                ownCreatePending = false
                dropAudio = false
                injecting?.let { batch ->
                    injected = batch
                    injectedResponse = e.responseId
                    injecting = null
                }
                _ui.value = _ui.value.copy(reply = "")
            }
            is ServerEvent.AudioDelta -> if (!dropAudio) {
                if (e.itemId != audioItem) {
                    audioItem = e.itemId
                    audioItemMs = 0L
                }
                audioItemMs += e.pcm.size * 1_000L / (config().sampleRate * 2)
                playback.write(e.pcm)
            }
            is ServerEvent.AssistantText -> if (!e.done) _ui.value = _ui.value.copy(reply = (_ui.value.reply + e.text).takeLast(CAPTION_CHARS))
            is ServerEvent.Call -> gather(e.call)
            is ServerEvent.ResponseDone -> responseDone(e)
            // Answered in order, after everything the server sent before it (the keepalive, and a fence in tests).
            is ServerEvent.Ping -> realtime.send(RealtimeProtocol.pong(e.timestamp))
            is ServerEvent.Error -> {
                _ui.value = _ui.value.copy(error = e.code ?: e.type)
                // A refused `response.create` never gets its `response.created`: do not wait for it, and keep its notices.
                if (ownCreatePending) {
                    ownCreatePending = false
                    responding = false
                    injecting?.let { queue.addAll(0, it) }
                    injecting = null
                }
            }
            else -> Unit
        }
    }

    /** The user started speaking: Grok stops at once; the server cancels the response under `server_vad`. */
    private fun bargeIn() {
        userSpeaking = true
        // The server answers this turn itself, with any tool outputs already in its context.
        createPending = false
        val speaking = !playback.idle.value
        if (speaking || responding) {
            playback.stop()
            dropAudio = responding
            val item = audioItem
            if (speaking && item != null) realtime.send(RealtimeProtocol.truncate(item, minOf(playback.playedMs(), audioItemMs)))
            report("grok.barge_in", mapOf("speaking" to speaking, "responding" to responding))
        }
        audioItem = null
        _ui.value = _ui.value.copy(userSpeaking = true, caption = "")
    }

    private fun gather(call: FunctionCall) {
        if (call.callId in seenCalls) return
        seenCalls += call.callId
        calls[call.callId] = call
        _ui.value = _ui.value.copy(chips = (_ui.value.chips + ToolChip(call.callId, call.name)).takeLast(MAX_CHIPS))
    }

    private fun responseDone(e: ServerEvent.ResponseDone) {
        responding = false
        turnPending = false
        e.calls.forEach(::gather)
        report("grok.response", mapOf("status" to e.status, "calls" to calls.size))
        if (injected.isNotEmpty() && e.responseId == injectedResponse) {
            val batch = injected
            injected = emptyList()
            injectedResponse = null
            // Said: read, never replayed. Cut off by the user: it stays unread on its card.
            if (e.status == "completed") scope.launch { batch.map { it.taskId }.distinct().forEach { runCatching { tasks.heard(it) } } }
        }
        if (calls.isEmpty()) return
        val batch = calls.values.toList()
        calls.clear()
        toolsRunning = true
        val cancelled = e.status == "cancelled"
        scope.launch {
            val outputs = batch.map { call ->
                val started = clock()
                val outcome = runCatching { runner.run(call) }.getOrElse { ToolOutcome("""{"error":"failed"}""", ok = false) }
                report("grok.tool", mapOf("name" to call.name, "ok" to outcome.ok, "ms" to clock() - started))
                call to outcome
            }
            inputs.send(Input.ToolsDone(outputs, cancelled))
        }
    }

    private fun toolsDone(outputs: List<Pair<FunctionCall, ToolOutcome>>, cancelled: Boolean) {
        toolsRunning = false
        outputs.forEach { (call, outcome) -> if (!realtime.send(RealtimeProtocol.functionOutput(call.callId, outcome.output))) unsent += call to outcome }
        _ui.value = _ui.value.copy(
            chips = _ui.value.chips.map { chip ->
                outputs.firstOrNull { it.first.callId == chip.callId }?.let { (_, o) -> chip.copy(ok = o.ok, taskId = taskIdOf(o.output)) } ?: chip
            },
        )
        if (outputs.any { it.second.endCall }) {
            ending = true
            drainThen { onEndCall() }
            return
        }
        // Cut off by the user: the server answers their new turn, with these outputs in its context.
        if (!cancelled) createPending = true
    }

    private fun notice(n: TaskNotice) {
        if (n is TaskNotice.Withdrawn) {
            queue.removeAll { it.taskId == n.taskId && it is TaskNotice.NeedsYou }
            return
        }
        if (queue.any { it == n }) return
        queue += n
        queuedAt[n.taskId] = clock()
    }

    private fun link(state: LinkState) {
        _ui.value = _ui.value.copy(link = state)
        if (state !is LinkState.Open) {
            // Whatever was in flight on the old socket is gone.
            if (responding) playback.stop()
            responding = false
            return
        }
        val first = !connectedOnce
        connectedOnce = true
        if (unsent.isNotEmpty()) {
            // Resumed: the calls are in the replayed history, so their outputs still belong. Otherwise they are gone.
            if (state.resumed) {
                unsent.forEach { (call, outcome) -> realtime.send(RealtimeProtocol.functionOutput(call.callId, outcome.output)) }
                createPending = true
            }
            unsent.clear()
        }
        turnPending = false
        injecting?.let { queue.addAll(0, it) }
        injecting = null
        ownCreatePending = false
        if (!first && !state.resumed) {
            // A new conversation: Grok has lost the task ids. Tell it, without a response.
            val views = tasks.status().filter { it.status == "running" || it.status == "queued" || it.status == "waiting_on_user" }
            if (views.isNotEmpty()) realtime.send(RealtimeProtocol.message(config().noticeRole, "Tasks still open: " + views.joinToString("; ") { "${it.id} \"${it.label}\" ${it.status}" } + "."))
        }
    }

    /** The idle rule: one thing at a time, never over the user or over Grok's own audio. */
    private fun pump() {
        if (ending || realtime.link.value !is LinkState.Open) return
        val quiet = playback.idle.value && !userSpeaking && !responding && !turnPending
        if (createPending) {
            if (!quiet) return
            createPending = false
            if (realtime.send(RealtimeProtocol.responseCreate())) {
                responding = true
                ownCreatePending = true
            }
            report("grok.response_create", mapOf("after" to "tools"))
            return
        }
        if (queue.isEmpty() || toolsRunning || !quiet) return
        val batch = queue.toList()
        queue.clear()
        val text = batch.joinToString("\n") { noteFor(it) }
        if (realtime.send(RealtimeProtocol.message(config().noticeRole, text)) && realtime.send(RealtimeProtocol.responseCreate())) {
            responding = true
            ownCreatePending = true
            injecting = batch
            batch.forEach { n ->
                report("grok.notice", mapOf("kind" to kindOf(n), "waited_ms" to (queuedAt.remove(n.taskId)?.let { clock() - it } ?: 0L)))
            }
        } else {
            queue.addAll(0, batch)
        }
    }

    /** Wait (bounded) for the playback to drain, then [then]. */
    private fun drainThen(then: () -> Unit) {
        drainWait?.cancel()
        drainWait = scope.launch {
            withTimeoutOrNull(drainCapMs) { playback.idle.first { it } }
            then()
        }
    }

    private fun taskIdOf(output: String): String? = Regex("\"task_id\":\"(t\\d+)\"").find(output)?.groupValues?.get(1)

    private fun report(name: String, attrs: Map<String, Any?>) {
        runCatching { telemetry.event(name, attrs, false) }
    }

    companion object {
        const val CAPTION_CHARS = 280

        /** How long after the user stops speaking the server has to start its answer before results may go in. */
        const val TURN_GRACE_MS = 3_000L
        const val MAX_CHIPS = 6

        /** The excerpt of a result Grok gets (the card has the whole text). */
        const val EXCERPT_CHARS = 600

        private fun kindOf(n: TaskNotice): String = when (n) {
            is TaskNotice.Finished -> if (n.ok) "finished" else "failed"
            is TaskNotice.NeedsYou -> n.kind
            is TaskNotice.Withdrawn -> "withdrawn"
        }

        /** The system note for one notice: the headline material and what the user needs to do. */
        fun noteFor(n: TaskNotice): String = when (n) {
            is TaskNotice.Finished -> if (n.ok && n.text.isNotBlank()) {
                val excerpt = n.text.trim().take(EXCERPT_CHARS)
                val more = if (n.text.length > EXCERPT_CHARS) " (excerpt; the full ${n.text.length}-character result is on the screen)" else ""
                "Task ${n.taskId} \"${n.label}\" finished. Result$more: $excerpt\n" +
                    "Tell the user the headline in a sentence or two, and any decision they need to make. Don't read details or lists; the full answer is on the screen."
            } else if (n.ok) {
                "Task ${n.taskId} \"${n.label}\" finished with nothing to report. Say so in a few words."
            } else {
                "Task ${n.taskId} \"${n.label}\" didn't finish. Tell the user in a few words; they can retry it from its card."
            }
            is TaskNotice.NeedsYou -> if (n.kind == "clarify") {
                "Task ${n.taskId} \"${n.label}\" has a question for the user. It is on the screen: ask them to answer it there."
            } else {
                "Task ${n.taskId} \"${n.label}\" needs the user's approval. It is on the screen: ask them to tap Approve or Deny. Never approve anything yourself."
            }
            is TaskNotice.Withdrawn -> "Task ${n.taskId} \"${n.label}\" no longer needs the user."
        }
    }
}
