package com.folio.hermes.cascade

import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermes.cascade.routing.Router
import com.folio.hermes.cascade.writer.WriterPrompts
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterException
import com.folio.hermes.openrouter.SentenceSplitter
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A user turn held in the [Outbox] while offline. */
data class HeldTurn(val text: String, val speechEndAt: Long)

/**
 * The cascade's conductor (`:hermes`, no Android): speech in (VAD + STT events from the app),
 * Jev decides ([TurnTaker], [Router]), tasks run on Hermes ([TaskBoard]), Bill speaks
 * ([SpeechQueue], [Speaker], stock lines from [PhraseBank] via [LineRotator] and the cache).
 *
 * Off state: nothing here runs unless the app constructs it and calls [start]; `:live` builds it
 * only when the voice engine is `cascade` and a key is saved.
 *
 * Call every input from one thread and run [scope] on it (Main in the app), with a
 * `SupervisorJob`. Task events come from the [TaskBoard]'s bus, so [events] carries both.
 *
 * Status is answered from the task's event log only: it never sends anything to a running task.
 * Telemetry carries event names, counts, durations and stock slot ids only, never words.
 */
class Cascade(
    private val scope: CoroutineScope,
    private val settings: () -> CascadeSettings,
    private val openRouter: OpenRouter,
    private val board: TaskBoard,
    private val bank: PhraseBank,
    private val rotator: LineRotator,
    voice: BillVoice,
    sink: AudioSink,
    private val decisions: DecisionLog? = null,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val onEndCall: (tasksRunning: Boolean) -> Unit = {},
) {
    private val bus: CascadeEventBus = board.bus
    private val queue = SpeechQueue()
    private val speaker = Speaker(scope, queue, voice, sink, ::onSpeaker, now, offline = { offline })
    private val outbox = Outbox<HeldTurn>()
    private val executing = Mutex()
    private val turnTaker = TurnTaker(
        scope = scope,
        settings = settings,
        context = ::jevRequest,
        decide = { req, timeoutMs ->
            try {
                openRouter.jev.decide(req.state, req.questions, timeoutMs)
            } finally {
                onJevSettled()
            }
        },
        onEvent = ::onTurn,
        now = now,
        onJevFired = ::onJevFired,
    )
    private val _ui = MutableStateFlow(CascadeUiState())
    private val recent = ArrayDeque<Pair<String, Long>>()
    private val resultUtterances = HashMap<String, Long>()
    /** Executed turns whose first audio has not played yet: record and speech-end time. */
    private val awaitingAudio = LinkedHashMap<String, Pair<DecisionRecord, Long>>()
    private val rememberedTurns = LinkedHashSet<String>()
    private val audioAt = HashMap<String, Long>()
    private val jobs = mutableListOf<Job>()
    private var ticker: Job? = null
    private var userSpeaking = false
    private var jevInFlight = 0
    private var writersRunning = 0
    private var offline = false
    private var started = false

    /** What the call screen shows. */
    val ui: StateFlow<CascadeUiState> = _ui.asStateFlow()

    /** One-shot moments for animations. */
    val events: SharedFlow<CascadeEvent> = bus.events

    /** True while Bill has something queued or playing (wait for this before closing the call). */
    val speaking: Boolean get() = !queue.isEmpty

    fun start() {
        if (started) return
        started = true
        speaker.start()
        jobs += scope.launch { board.all.collect { list -> _ui.update { it.copy(tasks = list.map(TaskSnapshot::toUi)) } } }
        jobs += scope.launch { board.results.collect(::onResult) }
        jobs += scope.launch { board.requests.collect(::onRequest) }
    }

    fun stop() {
        started = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        ticker?.cancel()
        turnTaker.reset()
        speaker.stop()
        flushAwaiting()
    }

    // ── inputs from the app ─────────────────────────────────────────────────────────────────

    /** VAD: the user started speaking. */
    fun speechStarted() {
        userSpeaking = true
        turnTaker.speechStarted()
        ticker?.cancel()
        jevInFlight = 0
        _ui.update { it.copy(turn = it.turn.copy(pauseProgress = 0f), jev = it.jev.copy(inFlight = false)) }
        gate()
    }

    /** VAD: the user stopped speaking. */
    fun speechEnded() {
        userSpeaking = false
        turnTaker.speechEnded()
        if (turnTaker.deciding) startTicker()
        gate()
    }

    fun partial(text: String) {
        turnTaker.partial(text)
        _ui.update { it.copy(turn = it.turn.copy(partial = turnTaker.heard)) }
    }

    fun final(text: String) {
        turnTaker.final(text)
        _ui.update { it.copy(turn = it.turn.copy(partial = turnTaker.heard)) }
    }

    /** VAD while Bill plays: the user has spoken for [continuousMs]. At 250 ms or more Bill stops. */
    fun userSpeech(continuousMs: Long) {
        val playing = queue.playing ?: return
        if (continuousMs < settings().bargeInMs) return
        val cut = speaker.cutIn()
        bus.emit(CascadeEvent.UserCutIn)
        report("cascade.cut_in", mapOf("slot" to playing.slot, "result" to (cut.interruptedResultTask != null)))
        _ui.update { it.copy(bill = it.bill.copy(speaking = false, slot = null)) }
        refreshPhase()
    }

    /** The player's output level, 0..1. */
    fun outputLevel(level: Float) {
        _ui.update { it.copy(bill = it.bill.copy(level = level.coerceIn(0f, 1f))) }
    }

    fun hermesConnection(up: Boolean) {
        _ui.update { it.copy(connection = it.connection.copy(hermesUp = up)) }
    }

    /** The phone's network came or went. Back online sends the outbox (each held turn once). */
    fun network(online: Boolean) {
        if (!online) {
            if (!offline) bus.emit(CascadeEvent.Offline)
            offline = true
            return
        }
        if (offline) bus.emit(CascadeEvent.Online)
        val wasOffline = offline
        offline = false
        if (outbox.size == 0) return
        scope.launch {
            if (wasOffline) say(AppLines.ONLINE)
            flushOutbox()
        }
    }

    fun renderProgress(progress: RenderProgress) {
        _ui.update { it.copy(render = progress) }
    }

    /** Play a slot the app picks itself (call start, approvals, warnings…). */
    fun say(slotId: String, kind: SpeechKind = SpeechKind.Notice): String? {
        val line = rotator.next(slotId) ?: return null
        val u = queue.open(kind)
        queue.add(u, line, slotId)
        queue.close(u)
        speaker.wake()
        return line
    }

    // ── turns ───────────────────────────────────────────────────────────────────────────────

    private fun jevRequest(heard: String, pending: List<String>, silenceMs: Long): JevRequest =
        CascadeBrain.request(
            TurnContext(heard, pending, silenceMs, recentExchanges(), board.briefs(), queue.playing != null),
            bank,
            settings().backchannel,
        )

    private fun onJevFired(early: Boolean) {
        jevInFlight++
        bus.emit(CascadeEvent.JevFired(early))
        _ui.update { it.copy(jev = it.jev.copy(inFlight = true)) }
        refreshPhase()
    }

    private fun onJevSettled() {
        jevInFlight = (jevInFlight - 1).coerceAtLeast(0)
        _ui.update { it.copy(jev = it.jev.copy(inFlight = jevInFlight > 0)) }
        refreshPhase()
    }

    private fun onTurn(event: TurnEvent) {
        ticker?.cancel()
        if (event is TurnEvent.Parked) {
            _ui.update { it.copy(turn = it.turn.copy(held = turnTaker.pending.joinToString(" "))) }
            log(DecisionRecord(turnId = event.turnId, atMs = wallClock(), outcome = "parked"))
            gate()
            return
        }
        val timing = when (event) {
            is TurnEvent.Done -> event.timing
            is TurnEvent.Holding -> event.timing
            is TurnEvent.Ignored -> event.timing
            is TurnEvent.NoAnswer -> event.timing
            is TurnEvent.Parked -> JevTiming(null, null, 0)
        }
        val answers = when (event) {
            is TurnEvent.Done -> event.answers
            is TurnEvent.Holding -> event.answers
            is TurnEvent.Ignored -> event.answers
            else -> null
        }
        _ui.update { s ->
            s.copy(
                turn = s.turn.copy(partial = turnTaker.heard, held = turnTaker.pending.joinToString(" "), pauseProgress = 0f),
                jev = s.jev.copy(
                    inFlight = false,
                    answers = answers?.mapValues { (_, a) -> JevPick.of(a) } ?: s.jev.answers,
                    latencyMs = timing.jevMs ?: s.jev.latencyMs,
                ),
            )
        }
        if (answers != null) bus.emit(CascadeEvent.JevAnswered(timing.jevMs ?: 0))
        val base = DecisionRecord(
            turnId = event.turnId,
            atMs = wallClock(),
            outcome = "",
            jevCalls = timing.calls,
            speechEndToJevMs = timing.speechEndToJevMs,
            jevMs = timing.jevMs,
            answers = answers?.mapValues { (_, a) -> LoggedAnswer(a.choice, a.confidence, a.probabilities) }.orEmpty(),
        )
        when (event) {
            is TurnEvent.Done -> {
                bus.emit(CascadeEvent.TurnCommitted(event.turnId))
                val decision = Router.decide(event.answers, board.briefs(), bank, settings())
                val text = (event.pending + event.heard).filter { it.isNotBlank() }.joinToString(" ")
                val record = base.copy(
                    outcome = "done",
                    state = event.request.state,
                    questions = event.request.questions.keys.toList(),
                    route = decision.route.wire,
                )
                flushAwaiting()
                scope.launch { executing.withLock { execute(event.turnId, text, decision, event.speechEndAt, record) } }
            }
            is TurnEvent.Holding -> {
                val slot = Router.backchannel(event.answers, bank, settings())
                val line = slot?.let { say(it, SpeechKind.Reply) }
                log(base.copy(outcome = "keep_listening", state = event.request.state, questions = event.request.questions.keys.toList(), slot = slot, line = line))
            }
            is TurnEvent.Ignored ->
                log(base.copy(outcome = "ignore", state = event.request.state, questions = event.request.questions.keys.toList()))
            is TurnEvent.Parked -> Unit
            is TurnEvent.NoAnswer -> {
                val text = (event.pending + event.heard).filter { it.isNotBlank() }.joinToString(" ")
                if (event.failure.kind == FailureKind.Offline) {
                    holdOffline(event.turnId, HeldTurn(text, event.speechEndAt))
                    log(base.copy(outcome = "held", failure = event.failure.kind.wire))
                } else {
                    val line = say(AppLines.SAY_AGAIN, SpeechKind.Reply)
                    log(base.copy(outcome = "no_answer", failure = event.failure.kind.wire, route = Route.AskRepeat.wire, slot = AppLines.SAY_AGAIN, line = line))
                }
            }
        }
        report(
            "cascade.turn",
            mapOf("outcome" to outcomeWire(event), "jev_calls" to timing.calls, "jev_ms" to timing.jevMs, "to_jev_ms" to timing.speechEndToJevMs),
        )
        gate()
    }

    /** Explicit names: class names do not survive R8 in release builds. */
    private fun outcomeWire(event: TurnEvent): String = when (event) {
        is TurnEvent.Done -> "done"
        is TurnEvent.Holding -> "keep_listening"
        is TurnEvent.Ignored -> "ignore"
        is TurnEvent.NoAnswer -> "no_answer"
        is TurnEvent.Parked -> "parked"
    }

    private fun holdOffline(turnId: String, turn: HeldTurn) {
        if (outbox.hold(turnId, turn)) {
            if (!offline) {
                offline = true
                bus.emit(CascadeEvent.Offline)
                say(AppLines.OFFLINE, SpeechKind.Reply)
            }
            _ui.update { it.copy(connection = it.connection.copy(outbox = outbox.size)) }
            report("cascade.outbox", mapOf("event" to "held", "size" to outbox.size))
        }
    }

    /** Send held turns again: Jev decides afresh, and steps that already happened are not repeated. */
    private suspend fun flushOutbox() {
        val sent = outbox.flush { entry ->
            val s = settings()
            val request = CascadeBrain.request(
                TurnContext(entry.payload.text, emptyList(), s.pause, recentExchanges(), board.briefs(), false),
                bank,
                s.backchannel,
            )
            val started = now()
            when (val result = openRouter.jev.decide(request.state, request.questions, s.jevTimeoutMs)) {
                is com.folio.hermes.openrouter.OpenRouterResult.Ok -> {
                    val decision = Router.decide(result.value, board.briefs(), bank, s)
                    val record = DecisionRecord(
                        turnId = entry.turnId, atMs = wallClock(), outcome = "resent", state = request.state,
                        questions = request.questions.keys.toList(),
                        answers = result.value.mapValues { (_, a) -> LoggedAnswer(a.choice, a.confidence, a.probabilities) },
                        route = decision.route.wire, jevCalls = 1, jevMs = now() - started,
                    )
                    executing.withLock { execute(entry.turnId, entry.payload.text, decision, entry.payload.speechEndAt, record, resend = true) }
                    true
                }
                is com.folio.hermes.openrouter.OpenRouterResult.Failed ->
                    if (result.kind == FailureKind.Offline) {
                        false
                    } else {
                        say(AppLines.SAY_AGAIN, SpeechKind.Reply)
                        true
                    }
            }
        }
        _ui.update { it.copy(connection = it.connection.copy(outbox = outbox.size)) }
        if (sent > 0) report("cascade.outbox", mapOf("event" to "sent", "count" to sent, "size" to outbox.size))
    }

    // ── actions ─────────────────────────────────────────────────────────────────────────────

    private class Spoken(var slot: String? = null, var line: String? = null, var writerRan: Boolean = false, var pieces: Int = 0)

    private suspend fun execute(
        turnId: String,
        text: String,
        decision: Decision,
        speechEndAt: Long,
        record: DecisionRecord,
        resend: Boolean = false,
    ) {
        val u = queue.open(SpeechKind.Reply, turnId = turnId)
        if (rememberedTurns.add(turnId)) {
            remember(text, u)
            while (rememberedTurns.size > 64) rememberedTurns.remove(rememberedTurns.first())
        }
        val spoken = Spoken()

        fun play(slot: String) {
            if (outbox.stageDone(turnId, "line:$slot")) return
            val line = rotator.next(slot) ?: return
            if (queue.add(u, line, slot)) {
                outbox.markStage(turnId, "line:$slot")
                if (spoken.slot == null) {
                    spoken.slot = slot
                    spoken.line = line
                }
                spoken.pieces++
                speaker.wake()
            }
        }

        suspend fun write(system: String, prompt: String, onFail: () -> Unit): Boolean {
            if (outbox.stageDone(turnId, "writer")) return true
            spoken.writerRan = true
            val result = writeInto(u, system, prompt)
            if (result.spoke) outbox.markStage(turnId, "writer")
            spoken.pieces += result.pieces
            if (result.failure != null && !result.spoke) {
                if (result.failure == FailureKind.Offline && outbox.hold(turnId, HeldTurn(text, speechEndAt))) {
                    if (!offline) {
                        offline = true
                        bus.emit(CascadeEvent.Offline)
                        play(AppLines.OFFLINE)
                    }
                    _ui.update { it.copy(connection = it.connection.copy(outbox = outbox.size)) }
                } else {
                    onFail()
                }
                return false
            }
            return true
        }

        val route = decision.route
        try {
            val atCap = route is Route.NewTask && board.isFull
            if (!atCap) decision.slot?.let(::play)
            val said = spoken.line
            when (route) {
                Route.Reply -> if (decision.writer) write(WriterPrompts.spoken(said), WriterPrompts.reply(text, recentExchanges(), board.briefs())) { play(AppLines.GAVE_UP) }
                Route.Clarify ->
                    if (decision.writer) write(WriterPrompts.spoken(said, 1), WriterPrompts.clarify(text, recentExchanges(), board.briefs())) { play(AppLines.SAY_AGAIN) }
                    else if (spoken.slot == null) play(AppLines.SAY_AGAIN)
                Route.AskRepeat -> play(AppLines.SAY_AGAIN)
                Route.Hold -> Unit // offline turns are held before they reach here
                Route.NewTask -> if (atCap) play(AppLines.LIMIT_FULL) else if (!outbox.stageDone(turnId, "task")) {
                    if (spoken.slot == null) play("ack.do")
                    spoken.writerRan = true
                    val raw = openRouter.writer.write(WriterPrompts.PLAN_SYSTEM, WriterPrompts.plan(text, recentExchanges())).valueOrNull()
                    val plan = WriterPrompts.parsePlan(raw, text)
                    when (board.start(plan.prompt, plan.label, voiceContext())) {
                        is StartResult.AtCap -> play(AppLines.LIMIT_FULL)
                        is StartResult.Started -> {
                            outbox.markStage(turnId, "task")
                            if (decision.writer && plan.say.isNotEmpty()) {
                                val splitter = SentenceSplitter(leadWords = 0)
                                (splitter.push(plan.say) + splitter.finish()).forEach { if (queue.add(u, it)) spoken.pieces++ }
                                speaker.wake()
                            }
                        }
                    }
                }
                is Route.UpdateTask -> when (board.update(route.taskId, text, route.mode, voiceContext())) {
                    UpdateResult.NotFound -> play(AppLines.WHICH_UPDATE)
                    is UpdateResult.AtCap -> play(AppLines.LIMIT_FULL)
                    is UpdateResult.Sent -> {
                        val label = board.snapshot(route.taskId)?.label.orEmpty()
                        if (decision.writer) write(WriterPrompts.spoken(said, 1), WriterPrompts.updated(label, route.mode)) {}
                        else if (spoken.slot == null) play(if (route.mode == UpdateMode.Queue) "update.queued" else "update.redirect")
                    }
                }
                is Route.Status -> {
                    // From the phone's event log only: never a prompt to a running task.
                    val snaps = board.snapshots()
                    if (decision.writer) {
                        write(WriterPrompts.spoken(said), WriterPrompts.status(snaps, route.taskId)) { play(statusSlot(snaps)) }
                    } else if (spoken.slot == null) {
                        play(statusSlot(snaps))
                    }
                }
                is Route.CancelTask -> when (board.cancel(route.taskId)) {
                    CancelResult.NotFound -> play(AppLines.WHICH_CANCEL)
                    CancelResult.AlreadyFinished -> if (spoken.slot != "cancel.too_late") play("cancel.too_late")
                    CancelResult.Cancelled -> {
                        val label = board.snapshot(route.taskId)?.label.orEmpty()
                        if (decision.writer) write(WriterPrompts.spoken(said, 1), WriterPrompts.cancelled(label)) {}
                        else if (spoken.slot == null) play("cancel.one")
                    }
                }
                is Route.ReadResult -> {
                    val snap = board.snapshot(route.taskId)
                    val result = snap?.result
                    if (snap == null || result == null) {
                        if (spoken.slot == null) play("status.working")
                    } else {
                        resultUtterances.remove(route.taskId)?.let(queue::drop)
                        board.markRead(route.taskId)
                        write(WriterPrompts.spoken(said), WriterPrompts.result(snap.label, result)) { play(AppLines.ON_SCREEN) }
                    }
                }
                Route.EndCall -> {
                    val running = board.runningCount > 0
                    if (spoken.slot == null) play(if (running) AppLines.BYE_TASKS else AppLines.BYE)
                }
            }
        } finally {
            queue.close(u)
            speaker.wake()
        }
        val done = record.copy(slot = spoken.slot, line = spoken.line, writerRan = spoken.writerRan)
        val audio = audioAt[turnId]
        when {
            audio != null -> log(done.copy(speechEndToAudioMs = audio - speechEndAt))
            spoken.pieces == 0 || queue.isDropped(u) -> log(done)
            else -> awaitingAudio[turnId] = done to speechEndAt // logged on first audio
        }
        report(
            "cascade.action",
            mapOf("route" to route.wire, "slot" to spoken.slot, "writer" to spoken.writerRan, "resend" to resend),
        )
        if (route == Route.EndCall) onEndCall(board.runningCount > 0)
    }

    private fun statusSlot(snaps: List<TaskSnapshot>): String = when {
        snaps.none { it.state == TaskState.Running } && snaps.isNotEmpty() && snaps.all { it.state == TaskState.Done } -> "status.all_done"
        snaps.none { it.state == TaskState.Running } -> "status.idle"
        snaps.any { it.waitingOnUser } -> "status.blocked"
        snaps.count { it.state == TaskState.Running } > 1 -> "status.several"
        else -> "status.working"
    }

    private data class Written(val spoke: Boolean, val pieces: Int, val failure: FailureKind?)

    /** Stream the writer into utterance [u], sentence by sentence; stops if the user cuts in. */
    private suspend fun writeInto(u: Long, system: String, prompt: String, lead: Pair<String, String>? = null): Written {
        writersRunning++
        _ui.update { it.copy(writer = WriterUi(streaming = true, sentences = emptyList())) }
        refreshPhase()
        var pieces = 0
        var failure: FailureKind? = null
        try {
            openRouter.writer.sentences(system, prompt).takeWhile { !queue.isDropped(u) }.collect { sentence ->
                if (pieces == 0 && lead != null) queue.add(u, lead.second, lead.first)
                if (queue.add(u, sentence)) {
                    pieces++
                    _ui.update { it.copy(writer = it.writer.copy(sentences = it.writer.sentences + sentence)) }
                    speaker.wake()
                }
            }
        } catch (e: OpenRouterException) {
            failure = e.failure.kind
        } finally {
            writersRunning--
            _ui.update { it.copy(writer = it.writer.copy(streaming = writersRunning > 0)) }
            refreshPhase()
        }
        return Written(pieces > 0, pieces, failure)
    }

    private fun onResult(result: TaskResult) {
        val u = queue.open(SpeechKind.Result, taskId = result.taskId)
        resultUtterances[result.taskId] = u
        bus.emit(CascadeEvent.ResultQueued(result.taskId))
        val slot = if (result.ok) AppLines.RESULT_BACK else AppLines.RESULT_FAILED
        val line = rotator.next(slot)
        if (!result.ok || result.text.isBlank()) {
            line?.let { queue.add(u, it, slot) }
            queue.close(u)
            speaker.wake()
            return
        }
        // The lead-in goes in with the first written sentence, so a result never starts (and holds
        // the floor) while its writer is still working.
        scope.launch {
            try {
                val written = writeInto(u, WriterPrompts.spoken(line), WriterPrompts.result(result.label, result.text), lead = line?.let { slot to it })
                if (!written.spoke) {
                    line?.let { queue.add(u, it, slot) }
                    rotator.next(AppLines.ON_SCREEN)?.let { queue.add(u, it, AppLines.ON_SCREEN) }
                }
            } finally {
                queue.close(u)
                speaker.wake()
            }
        }
    }

    private fun onRequest(request: TaskRequest) {
        when (request.update) {
            is TurnUpdate.ApprovalNeeded -> say(AppLines.NEEDS_OK)
            is TurnUpdate.ClarifyNeeded -> say(AppLines.QUESTION)
            else -> Unit
        }
    }

    private fun onSpeaker(event: SpeakerEvent) {
        when (event) {
            is SpeakerEvent.Started -> {
                val piece = event.piece
                _ui.update { it.copy(bill = it.bill.copy(speaking = true, slot = piece.slot)) }
                piece.slot?.let { bus.emit(CascadeEvent.LinePlayed(it)) }
                if (piece.index == 0 && queue.kindOf(piece.utteranceId) == SpeechKind.Result) {
                    queue.taskOf(piece.utteranceId)?.let { task ->
                        bus.emit(CascadeEvent.ResultSpoken(task))
                        board.markRead(task)
                        resultUtterances.remove(task)
                    }
                }
                queue.turnOf(piece.utteranceId)?.let { turn ->
                    if (turn !in audioAt) {
                        audioAt[turn] = event.atMs
                        while (audioAt.size > 64) audioAt.remove(audioAt.keys.first())
                        awaitingAudio.remove(turn)?.let { (rec, endAt) -> log(rec.copy(speechEndToAudioMs = event.atMs - endAt)) }
                    }
                }
            }
            is SpeakerEvent.Finished -> _ui.update { it.copy(bill = it.bill.copy(speaking = false)) }
            is SpeakerEvent.Skipped -> report("cascade.tts_skipped", mapOf("slot" to event.piece.slot))
            SpeakerEvent.Idle -> _ui.update { it.copy(bill = it.bill.copy(speaking = false, slot = null)) }
        }
        refreshPhase()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun gate() {
        queue.userSpeaking = userSpeaking
        queue.userHolding = turnTaker.holding
        speaker.wake()
        refreshPhase()
    }

    private fun startTicker() {
        ticker?.cancel()
        val pause = settings().pause
        val began = now()
        ticker = scope.launch {
            while (true) {
                val p = ((now() - began).toFloat() / pause).coerceIn(0f, 1f)
                _ui.update { it.copy(turn = it.turn.copy(pauseProgress = p)) }
                refreshPhase()
                if (p >= 1f) break
                delay(TICK_MS)
            }
        }
    }

    private fun refreshPhase() {
        _ui.update { s ->
            val phase = when {
                userSpeaking -> TurnPhase.UserSpeaking
                queue.playing != null -> TurnPhase.BillSpeaking
                turnTaker.deciding && (jevInFlight > 0 && s.turn.pauseProgress >= 1f) -> TurnPhase.Deciding
                turnTaker.deciding -> TurnPhase.Pausing
                writersRunning > 0 -> TurnPhase.Writing
                else -> TurnPhase.Idle
            }
            if (s.turn.phase == phase) s else s.copy(turn = s.turn.copy(phase = phase))
        }
    }

    private fun remember(text: String, utterance: Long) {
        recent.addLast(text to utterance)
        while (recent.size > settings().recentExchanges) recent.removeFirst()
    }

    private fun recentExchanges(): List<Exchange> = recent.map { (user, u) -> Exchange(user, queue.heard(u)) }

    private fun voiceContext(): String =
        recentExchanges().joinToString("\n") { "User: ${it.user}\nBill: ${it.bill}" }.takeLast(6_000)

    private fun flushAwaiting() {
        awaitingAudio.values.forEach { (rec, _) -> log(rec) }
        awaitingAudio.clear()
    }

    private fun log(record: DecisionRecord) {
        runCatching { decisions?.record(record) }
    }

    private fun report(name: String, attrs: Map<String, Any?>) {
        runCatching { telemetry.event(name, attrs, warn = false) }
    }

    private companion object {
        const val TICK_MS = 50L
    }
}
