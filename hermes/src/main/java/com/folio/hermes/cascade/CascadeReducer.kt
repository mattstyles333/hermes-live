package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.ActionResult
import com.folio.hermes.cascade.routing.Next
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult

/**
 * The cascade's one state transition (R-ARC-1): `reduce(state, event) -> (state', effects)`.
 * Pure: no clocks (the actor stamps [State.nowMs], [State.wallMs] and [State.settings]), no I/O,
 * no shared mutable state. Effects carry correlation ids and results come back as events; a stale
 * result is dropped here (R-ARC-2).
 *
 * The core is C1's conductor (`Cascade`): turn-taking ([TurnRules]), tasks ([TaskTable]), Bill's
 * speech ([SpeechQueueState] and the speaker loop, as [Effect.FetchClip]/[Effect.Play]), the
 * outbox ([OutboxState]) and the decision log. Every feature decision is delegated to the
 * [CascadeEnv] seams: routes and turn hooks ([com.folio.hermes.cascade.routing.HandlerRegistry]),
 * the Jev budget, persistence, the task registry and conversations. Phase 1 slices change those,
 * not this file.
 */
class CascadeReducer(val env: CascadeEnv) {
    fun reduce(state: State, event: Event): Pair<State, List<Effect>> {
        val tx = Tx(state, env)
        tx.on(event)
        if (tx.s.started) {
            tx.advance()
            tx.pump()
        }
        val durable = env.persistence.save(state, tx.s)
        return tx.s to (durable + tx.effects)
    }

    // ── events ──────────────────────────────────────────────────────────────────────────────

    private fun Tx.on(event: Event) {
        // Off state: nothing runs until the call starts (R-Q-1). Tasks belong to the user, not the
        // call: their events still update the task table (results wait in `offCall` for the next start).
        if (!s.started && !event.offCall) return
        when (event) {
            is Event.Start -> start(event)
            Event.Stop -> stop()
            Event.SpeechStarted -> {
                s = s.copy(userSpeaking = true)
                turn(TurnRules.speechStarted(s.turn))
                stopTicker()
                s = s.copy(pauseProgress = 0f)
                gate()
            }
            Event.SpeechEnded -> speechEnded(s.nowMs)
            is Event.SpeechEndedAt -> speechEnded(event.atMs)
            is Event.Partial -> turn(TurnRules.partial(s.turn, event.text))
            is Event.Final -> {
                turn(TurnRules.final(s.turn, event.text))
                if (event.words.isNotEmpty()) s = s.copy(transcript = s.transcript.copy(words = s.transcript.words + event.words))
            }
            is Event.AccurateFinal -> {
                env.handlers.hooks.accurate(this, event)
                // Fast text when the pass gave nothing: the turn goes on either way.
                openGate(event.turnId, ACCURATE_GATE)
            }
            is Event.UserSpeech -> cutIn(event.continuousMs)
            is Event.HoldToTalk -> {
                s = s.copy(transcript = s.transcript.copy(holdToTalk = event.pressed))
                env.handlers.hooks.holdToTalk(this, event.pressed)
                if (!event.pressed) released()
            }
            is Event.OutputLevel -> s = s.copy(bill = s.bill.copy(level = event.level.coerceIn(0f, 1f)))
            is Event.Network -> network(event.online)
            is Event.HermesConnection -> s = s.copy(connection = s.connection.copy(hermesUp = event.up))
            is Event.Say -> say(event.slotId, event.kind)
            is Event.RenderProgressed -> s = s.copy(render = event.progress)
            is Event.TaskTapped -> when (event.tap) {
                TaskTap.Retry -> env.registry.retry(this, event.taskId)
                TaskTap.Play -> env.conversation.play(this, event.taskId)
                else -> env.handlers.hooks.tap(this, event)
            }
            is Event.WordChosen -> env.handlers.hooks.wordChosen(this, event)
            is Event.ResumeChosen -> chooseResume(event.choice)
            is Event.TimerFired -> timer(event.timer)
            is Event.JevAnswered ->
                if (s.flush?.jevSeq == event.seq) flushAnswered(event.result) else turn(TurnRules.answered(s.turn, event.seq, event.result, event.atMs, ask))
            is Event.WriterSentence -> sentence(event.seq, event.text)
            is Event.WriterFinished -> s.writers[event.seq]?.let { writerEnded(it, event.failure) }
            is Event.WriterWrote ->
                if (s.actions.active?.waitingOn == "plan:${event.seq}") {
                    resume("plan:${event.seq}", ActionResult.Wrote(event.result))
                } else {
                    // Off the critical path (R-CTX-2): a whole answer no action waits for.
                    env.handlers.hooks.backgroundWrote(this, event.seq, event.result)
                }
            is Event.BusyModeRead -> resume("busy:${event.seq}", ActionResult.Busy(event.mode))
            is Event.ClipReady -> clipReady(event.piece, event.outcome)
            is Event.PlaybackStarted -> playbackStarted(event.piece, event.atMs)
            is Event.PlaybackFinished -> playbackFinished(event.piece, event.playedMs, event.durationMs)
            is Event.TaskUpdated -> {
                val known = s.tasks[event.taskId]?.runs?.containsKey(event.runId) == true
                tasks(s.tasks.update(event.taskId, event.runId, event.update, s.wallMs))
                if (known) {
                    env.registry.taskUpdated(this, event)
                    env.handlers.hooks.taskUpdated(this, event)
                }
            }
            is Event.TaskRunEnded -> {
                val known = s.tasks[event.taskId]?.runs?.containsKey(event.runId) == true
                tasks(s.tasks.runEnded(event.taskId, event.runId, s.wallMs))
                if (known) env.handlers.hooks.taskRunEnded(this, event)
            }
            is Event.TaskInterrupted -> {
                tasks(s.tasks.interrupted(event.taskId, event.ok))
                resume("interrupt:${event.taskId}", ActionResult.Interrupted(event.taskId, event.ok))
            }
            is Event.TaskSessionBound -> s = s.copy(tasks = s.tasks.bound(event.taskId, event.storedSessionId))
            is Event.TaskSessionOpened -> tasks(s.tasks.sessionOpened(event.taskId, event.runId, event.storedSessionId))
            is Event.RunNotReceived -> env.registry.notReceived(this, event)
            is Event.Restore -> restore(event.restored, call = false)
            // Only once the store is read back: before that, the first save would overwrite it.
            is Event.OpenConversation -> if (s.restored) env.conversation.open(this, event.loaded)
        }
    }

    private fun Tx.start(event: Event.Start) {
        if (s.started) return
        restore(event.restored, call = true)
        // What arrived while no call was open is handled now (C1: buffered until the next start),
        // unless the Resume card is up: then after Play or Continue (R-CONV-2), never over it.
        if (s.conversation.resume == null) releaseOffCall(speakResults = true)
        env.conversation.briefing(this)
    }

    /**
     * The user stopped speaking, really at [endAt] (R-RT-1; the pause counts from there). After a
     * hold-to-talk release the turn commits at once instead (R-STT-8).
     */
    private fun Tx.speechEnded(endAt: Long) {
        s = s.copy(userSpeaking = false)
        val withoutJev = !env.handlers.hooks.useJev(s)
        if (s.transcript.released) {
            s = s.copy(transcript = s.transcript.copy(released = false))
            turn(TurnRules.released(s.turn, s.nowMs, s.settings, ask, withoutJev))
        } else {
            turn(TurnRules.speechEnded(s.turn, s.nowMs, s.settings, withoutJev = withoutJev, endAt = endAt))
            if (s.turn.deciding) startTicker()
        }
        gate()
    }

    /**
     * Hold-to-talk released (R-STT-8): mid-speech, the `SpeechEnded` that follows commits at once;
     * with the words already in (no speech open), they commit now.
     */
    private fun Tx.released() {
        if (s.userSpeaking) {
            s = s.copy(transcript = s.transcript.copy(released = true))
        } else if (s.turn.heard.isNotEmpty()) {
            stopTicker()
            turn(TurnRules.released(s.turn, s.nowMs, s.settings, ask, !env.handlers.hooks.useJev(s)))
            gate()
        }
    }

    /**
     * Take the stored state ([restored], read by the actor) and, the first time, let the seams
     * settle it: the registry starts again what never left, the hooks fill free slots. [call]:
     * a call starts now; otherwise this is the off-call [Event.Restore] (tasks run, nothing is said).
     */
    private fun Tx.restore(restored: State?, call: Boolean) {
        if (s.started || (!call && s.restored)) return
        val stamp = s
        val first = !s.restored
        val base = restored ?: s
        s = base.copy(nowMs = stamp.nowMs, wallMs = stamp.wallMs, settings = stamp.settings, started = call, restored = true)
        if (first) {
            env.registry.restored(this)
            env.handlers.hooks.restored(this)
        }
    }

    private fun Tx.chooseResume(choice: ResumeChoice) {
        env.conversation.resume(this, choice)
        releaseOffCall(speakResults = choice != ResumeChoice.New)
    }

    /** The user talked with the Resume card up: that is its default choice (R-CONV-2), so nothing waits behind it. */
    private fun Tx.resumeByDefault() {
        val card = s.conversation.resume ?: return
        chooseResume(card.defaultChoice)
        report("cascade.conversation", mapOf("event" to "resume_default", "choice" to card.defaultChoice.name.lowercase()))
    }

    /** Results and requests held while no call was open (or while the Resume card showed). New: results stay unread on their cards. */
    private fun Tx.releaseOffCall(speakResults: Boolean) {
        val waiting = s.offCall
        if (waiting.results.isEmpty() && waiting.requests.isEmpty()) return
        s = s.copy(offCall = OffCallState())
        if (speakResults) waiting.results.filter { it.taskId !in s.resultUtterances }.forEach { env.handlers.result.arrived(this, it) }
        waiting.requests.forEach { env.handlers.hooks.taskRequest(this, it) }
    }

    /**
     * The call ends: held words are forgotten, Bill stops (queued replies and notices are dropped,
     * as on a cut-in; queued results stay for the next call), the call's in-flight work (timers,
     * Jev, writer, speech) is cancelled, and pending records are logged. Tasks keep running.
     */
    private fun Tx.stop() {
        if (!s.started) return
        s = s.copy(started = false)
        turn(TurnRules.reset(s.turn))
        stopTicker()
        effects += Effect.StopPlayback
        s.writers.keys.forEach { effects += Effect.CancelWriter(it) }
        // A result whose writer was cut off still closes (with its lead-in), so it can play later.
        s.writers.values.filter { it.owner is WriterOwner.Result }.forEach { env.handlers.result.written(this, it, it.pieces > 0) }
        val (cut, _) = s.speech.cutIn()
        val current = cut.current
        holdUnfinished()
        s = s.copy(
            writers = emptyMap(),
            writer = s.writer.copy(streaming = false),
            actions = ActionQueue(),
            flush = null,
            playback = null,
            speech = (if (current != null) cut.played(current, 0, 0) else cut).copy(userSpeaking = false, userHolding = false),
            userSpeaking = false,
            bill = s.bill.copy(speaking = false, slot = null, text = null),
        )
        flushAwaiting()
    }

    /**
     * Hang-up mid-action (R-DUR-5): a committed turn whose work may not have reached Hermes
     * (new task, update, cancel) is held in the outbox and carried out at the next reconnect;
     * steps already done (`task`, `update:<id>`, lines) are not repeated. A conversational turn
     * (reply, status, read a result, clarify, goodbye) is dropped: its answer would be out of
     * context at the next call.
     */
    private fun Tx.holdUnfinished() {
        val unfinished = listOfNotNull(s.actions.active) + s.actions.waiting
        for (a in unfinished) {
            if (a.resend || a.turnId in s.outbox.sent || !a.decision.route.isWork) continue
            if (s.outbox.stageDone(a.turnId, NEW_TASK_STAGE) || (a.decision.route as? Route.UpdateTask)?.let { updateSent(a.turnId, it.taskId) } == true) continue
            val (box, held) = s.outbox.hold(a.turnId, HeldTurn(a.text, a.speechEndAt, s.wallMs, a.transcript, carried = true))
            if (!held) continue
            s = s.copy(outbox = box)
            report("cascade.outbox", mapOf("event" to "held_at_stop", "size" to s.outbox.size))
        }
    }

    private val Route.isWork: Boolean get() = this is Route.NewTask || this is Route.UpdateTask || this is Route.CancelTask

    // ── turn-taking ─────────────────────────────────────────────────────────────────────────

    private val Tx.ask: JevAsk get() = { heard, pending, silenceMs -> jevRequest(heard, pending, silenceMs) }

    /** Apply a turn-taking step: its state, effects, fired calls, then its turn events. */
    private fun Tx.turn(step: TurnStep) {
        s = s.copy(turn = step.state)
        effects += step.effects
        step.fired.forEach { emit(CascadeEvent.JevFired(it)) }
        step.events.forEach { onTurn(it) }
    }

    private fun Tx.onTurn(event: TurnEvent) {
        stopTicker()
        if (event is TurnEvent.Parked) {
            log(DecisionRecord(turnId = event.turnId, atMs = s.wallMs, outcome = "parked"))
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
        s = s.copy(
            pauseProgress = 0f,
            jev = JevStatus(answers?.mapValues { (_, a) -> JevPick.of(a) } ?: s.jev.answers, timing.jevMs ?: s.jev.latencyMs),
        )
        if (answers != null) emit(CascadeEvent.JevAnswered(timing.jevMs ?: 0))
        val base = DecisionRecord(
            turnId = event.turnId,
            atMs = s.wallMs,
            outcome = "",
            jevCalls = timing.calls,
            speechEndToJevMs = timing.speechEndToJevMs,
            jevMs = timing.jevMs,
            answers = answers?.logged().orEmpty(),
        )
        val hooks = env.handlers.hooks
        when (event) {
            is TurnEvent.Done -> {
                resumeByDefault()
                emit(CascadeEvent.TurnCommitted(event.turnId))
                val decision = hooks.decide(s, event.answers, env.bank)
                val text = (event.pending + event.heard).filter { it.isNotBlank() }.joinToString(" ")
                val record = base.copy(
                    outcome = "done",
                    state = event.request.state,
                    questions = event.request.questions.keys.toList(),
                    route = decision.route.wire,
                )
                flushAwaiting()
                val action = hooks.committed(this, ActionState(event.turnId, text, decision, event.speechEndAt, record), event)
                enqueue(accuratePass(event.turnId, action))
            }
            is TurnEvent.Holding -> {
                val slot = hooks.backchannel(s, event.answers, env.bank)
                val line = slot?.let { say(it, SpeechKind.Reply) }
                log(base.copy(outcome = "keep_listening", state = event.request.state, questions = event.request.questions.keys.toList(), slot = slot, line = line))
            }
            is TurnEvent.Ignored -> {
                if (s.settings.accuratePass) effects += Effect.DropSpeech(event.turnId)
                log(base.copy(outcome = "ignore", state = event.request.state, questions = event.request.questions.keys.toList()))
            }
            is TurnEvent.NoAnswer -> {
                if (s.settings.accuratePass) effects += Effect.DropSpeech(event.turnId)
                hooks.noAnswer(this, event, base)
            }
            is TurnEvent.Parked -> Unit
        }
        report("cascade.turn", mapOf("outcome" to outcomeWire(event), "jev_calls" to timing.calls, "jev_ms" to timing.jevMs, "to_jev_ms" to timing.speechEndToJevMs))
        gate()
    }

    /**
     * R-STT-2: with [CascadeSettings.accuratePass] on, committed turn [turnId] waits for its
     * accurate pass (the actor bounds the wait). A turn absorbed into another action (a key-doubt
     * answer) drops its audio instead: that action already has its transcript.
     */
    private fun Tx.accuratePass(turnId: String, action: ActionState): ActionState {
        if (!s.settings.accuratePass) return action
        if (action.turnId != turnId) {
            effects += Effect.DropSpeech(turnId)
            return action
        }
        effects += Effect.AccuratePass(turnId)
        return action.copy(gates = action.gates + ACCURATE_GATE)
    }

    /** Explicit names: class names do not survive R8 in release builds. */
    private fun outcomeWire(event: TurnEvent): String = when (event) {
        is TurnEvent.Done -> "done"
        is TurnEvent.Holding -> "keep_listening"
        is TurnEvent.Ignored -> "ignore"
        is TurnEvent.NoAnswer -> "no_answer"
        is TurnEvent.Parked -> "parked"
    }

    private fun Tx.timer(timer: Timer) {
        when (timer.name) {
            TurnRules.EARLY, TurnRules.COMMIT, TurnRules.RECHECK -> turn(TurnRules.timer(s.turn, timer, s.nowMs, ask))
            TICK -> if (s.tickSeq == timer.seq) {
                val p = ((s.nowMs - s.tickBegan).toFloat() / s.settings.pause).coerceIn(0f, 1f)
                s = s.copy(pauseProgress = p)
                if (p >= 1f) s = s.copy(tickSeq = null) else effects += Effect.StartTimer(timer, TICK_MS)
            }
            else -> {
                val key = "timer:${timer.name}:${timer.seq}"
                if (s.actions.active?.waitingOn == key) resume(key, ActionResult.TimerFired(timer)) else env.handlers.hooks.timer(this, timer)
            }
        }
    }

    /** The pause ring fills over `pause_ms` (C1's ticker, now a timer). */
    private fun Tx.startTicker() {
        stopTicker()
        val seq = nextSeq()
        // From the real end of speech (`SpeechEndedAt`), like the pause itself.
        s = s.copy(tickSeq = seq, tickBegan = s.turn.pause?.endAt ?: s.nowMs, pauseProgress = 0f)
        effects += Effect.StartTimer(Timer(TICK, seq), TICK_MS)
    }

    private fun Tx.stopTicker() {
        val seq = s.tickSeq ?: return
        effects += Effect.CancelTimer(Timer(TICK, seq))
        s = s.copy(tickSeq = null)
    }

    /** Who may speak: the user's speech, and words held but not acted on, keep results back. */
    private fun Tx.gate() {
        s = s.copy(speech = s.speech.copy(userSpeaking = s.userSpeaking, userHolding = s.turn.holding))
    }

    // ── actions (C1 `execute`, one at a time, in order) ─────────────────────────────────────

    private fun Tx.enqueue(action: ActionState) {
        s = s.copy(actions = s.actions.copy(waiting = s.actions.waiting + action))
        advance()
    }

    /** Start the next queued action if none is running and its gates are open (also after every event). */
    private fun Tx.advance() {
        while (s.actions.active == null) {
            val next = s.actions.waiting.firstOrNull() ?: return
            if (next.gates.isNotEmpty()) return
            s = s.copy(actions = ActionQueue(active = null, waiting = s.actions.waiting.drop(1)))
            begin(next)
        }
    }

    private fun Tx.begin(a: ActionState) {
        val u = open(SpeechKind.Reply, turnId = a.turnId)
        s = s.copy(actions = s.actions.copy(active = a.copy(utteranceId = u)))
        if (a.turnId !in s.log.remembered) {
            s = s.copy(
                log = s.log.copy(remembered = (s.log.remembered + a.turnId).takeLast(KEEP_TURNS)),
                recent = (s.recent + RecentTurn(a.text, u)).takeLast(s.settings.recentExchanges.coerceAtLeast(0)),
            )
        }
        val handler = env.handlers.handler(a.decision.route)
        if (handler.playsSlotFirst(this)) a.decision.slot?.let(::play)
        if (handler.start(this) == Next.Done) finish()
    }

    /** A result the active action waits for ([key]); anything else is stale and dropped. */
    private fun Tx.resume(key: String, result: ActionResult) {
        val a = s.actions.active ?: return
        if (a.waitingOn != key) return
        updateAction { it.copy(waitingOn = null) }
        if (env.handlers.handler(a.decision.route).resume(this, result) == Next.Done) finish()
    }

    private fun Tx.finish() {
        val a = action
        close(a.utteranceId)
        val done = a.record.copy(slot = a.slot, line = a.line, writerRan = a.writerRan)
        val audio = s.log.audioAt.lastOrNull { it.first == a.turnId }?.second
        when {
            audio != null -> log(done.copy(speechEndToAudioMs = audio - a.speechEndAt))
            a.pieces == 0 || s.speech.isDropped(a.utteranceId) -> log(done)
            // Logged on first audio.
            else -> s = s.copy(log = s.log.copy(awaitingAudio = s.log.awaitingAudio.filter { it.first.turnId != a.turnId } + (done to a.speechEndAt)))
        }
        report("cascade.action", mapOf("route" to a.decision.route.wire, "slot" to a.slot, "writer" to a.writerRan, "resend" to a.resend))
        if (a.decision.route == Route.EndCall) effects += Effect.EndCall(s.tasks.runningCount > 0)
        s = s.copy(actions = s.actions.copy(active = null))
        if (a.resend && s.flush?.turnId == a.turnId) flushSent(a.turnId)
        advance()
    }

    // ── the writer ──────────────────────────────────────────────────────────────────────────

    private fun Tx.sentence(seq: Long, text: String) {
        val job = s.writers[seq] ?: return
        if (s.speech.isDropped(job.utteranceId)) {
            // The user cut in: stop reading (C1's takeWhile); what was said stands, no failure.
            effects += Effect.CancelWriter(seq)
            writerEnded(job, null)
            return
        }
        var j = job
        if (j.pieces == 0 && j.lead != null) add(j.utteranceId, j.lead!!.second, j.lead!!.first)
        if (add(j.utteranceId, text)) {
            j = j.copy(pieces = j.pieces + 1)
            s = s.copy(writer = s.writer.copy(sentences = s.writer.sentences + text))
        }
        s = s.copy(writers = s.writers + (seq to j))
    }

    private fun Tx.writerEnded(job: WriterJob, failure: FailureKind?) {
        s = s.copy(writers = s.writers - job.seq)
        s = s.copy(writer = s.writer.copy(streaming = s.writers.isNotEmpty()))
        val spoke = job.pieces > 0
        when (val owner = job.owner) {
            is WriterOwner.Result -> env.handlers.result.written(this, job, spoke)
            is WriterOwner.Action -> {
                val a = s.actions.active ?: return
                if (a.turnId != owner.turnId || a.waitingOn != "writer:${job.seq}") return
                if (spoke) s = s.copy(outbox = s.outbox.markStage(a.turnId, "writer"))
                updateAction { it.copy(pieces = it.pieces + job.pieces) }
                if (failure != null && !spoke) {
                    val (box, held) = if (failure == FailureKind.Offline) s.outbox.hold(a.turnId, HeldTurn(a.text, a.speechEndAt, s.wallMs)) else s.outbox to false
                    if (held) {
                        s = s.copy(outbox = box)
                        if (s.connection.online) {
                            s = s.copy(connection = s.connection.copy(online = false))
                            emit(CascadeEvent.Offline)
                            play(AppLines.OFFLINE)
                        }
                        env.conversation.held(this, a.turnId, HeldTurn(a.text, a.speechEndAt, s.wallMs))
                    } else {
                        job.failSlot?.let(::play)
                    }
                }
                resume("writer:${job.seq}", ActionResult.Written(spoke, failure))
            }
        }
    }

    // ── Bill's voice (C1 `Speaker`) ─────────────────────────────────────────────────────────

    /** Start the next piece if Bill is free (C1's speaker loop, woken after every event). */
    private fun Tx.pump() {
        if (s.playback != null) return
        val (q, piece) = s.speech.next()
        if (piece == null) {
            if (s.bill.speaking || s.bill.slot != null || s.bill.text != null) s = s.copy(bill = s.bill.copy(speaking = false, slot = null, text = null))
            return
        }
        s = s.copy(speech = q, playback = Playback(piece))
        effects += Effect.FetchClip(piece, offline = !s.connection.online)
    }

    private fun Tx.clipReady(piece: Piece, outcome: ClipOutcome) {
        val pb = s.playback
        if (pb == null || pb.piece != piece || pb.playing) return
        if (outcome != ClipOutcome.Ok) {
            s = s.copy(speech = s.speech.played(piece, 0, 1), playback = null)
            report("cascade.tts_skipped", mapOf("slot" to piece.slot))
            return
        }
        s.speech.upcoming()?.let { effects += Effect.FetchClip(it, offline = !s.connection.online, prefetch = true) }
        if (s.speech.isDropped(piece.utteranceId)) {
            s = s.copy(speech = s.speech.played(piece, 0, 1), playback = null)
            return
        }
        s = s.copy(playback = pb.copy(playing = true))
        effects += Effect.Play(piece)
    }

    private fun Tx.playbackStarted(piece: Piece, atMs: Long) {
        if (s.playback?.piece != piece) return
        s = s.copy(bill = s.bill.copy(speaking = true, slot = piece.slot, text = piece.text))
        piece.slot?.let { emit(CascadeEvent.LinePlayed(it)) }
        val u = piece.utteranceId
        if (piece.index == 0 && s.speech.kindOf(u) == SpeechKind.Result) {
            s.speech.taskOf(u)?.let { task ->
                emit(CascadeEvent.ResultSpoken(task))
                s = s.copy(tasks = s.tasks.markRead(task), resultUtterances = s.resultUtterances - task)
            }
        }
        s.speech.turnOf(u)?.let { turn ->
            if (s.log.audioAt.none { it.first == turn }) {
                val awaiting = s.log.awaitingAudio.firstOrNull { it.first.turnId == turn }
                s = s.copy(
                    log = s.log.copy(
                        audioAt = (s.log.audioAt + (turn to atMs)).takeLast(KEEP_TURNS),
                        awaitingAudio = s.log.awaitingAudio.filter { it.first.turnId != turn },
                    ),
                )
                awaiting?.let { (rec, endAt) -> log(rec.copy(speechEndToAudioMs = atMs - endAt)) }
            }
        }
    }

    private fun Tx.playbackFinished(piece: Piece, playedMs: Long, durationMs: Long) {
        if (s.playback?.piece != piece) return
        s = s.copy(speech = s.speech.played(piece, playedMs, durationMs), playback = null, bill = s.bill.copy(speaking = false))
    }

    /** The user spoke over Bill for [continuousMs]: at `bargeInMs` or more he stops (R-SPK-4). */
    private fun Tx.cutIn(continuousMs: Long) {
        val playing = s.speech.current ?: return
        if (continuousMs < s.settings.bargeInMs) return
        val (q, cut) = s.speech.cutIn()
        s = s.copy(speech = q)
        effects += Effect.StopPlayback
        val dropped = (q.queue + q.done).filter { it.dropped }.map { it.id }.toSet()
        effects += Effect.DropClips(dropped, keep = cut.playing)
        emit(CascadeEvent.UserCutIn)
        report("cascade.cut_in", mapOf("slot" to playing.slot, "result" to (cut.interruptedResultTask != null)))
        s = s.copy(bill = s.bill.copy(speaking = false, slot = null))
    }

    // ── network and the outbox ──────────────────────────────────────────────────────────────

    private fun Tx.network(online: Boolean) {
        if (!online) {
            val was = s.connection.online
            if (was) emit(CascadeEvent.Offline)
            s = s.copy(connection = s.connection.copy(online = false))
            if (was) env.conversation.offline(this)
            return
        }
        val wasOffline = !s.connection.online
        if (wasOffline) emit(CascadeEvent.Online)
        s = s.copy(connection = s.connection.copy(online = true))
        if (s.outbox.size == 0) {
            // Nothing held: memo mode ends here (with held turns, the conversation seam ends it).
            if (s.connection.memo) {
                s = s.copy(connection = s.connection.copy(memo = false))
                emit(CascadeEvent.Memo(false))
            }
            return
        }
        if (env.conversation.online(this)) return
        if (s.flush != null) return
        if (wasOffline) say(AppLines.ONLINE)
        startFlush()
    }

    private fun Tx.flushAnswered(result: OpenRouterResult<Map<String, JevAnswer>>) {
        val f = s.flush ?: return
        val entry = s.outbox.first()?.takeIf { it.turnId == f.turnId }
        val request = f.request
        if (entry == null || request == null) {
            endFlush()
            return
        }
        s = s.copy(flush = f.copy(jevSeq = null))
        when (result) {
            is OpenRouterResult.Ok -> {
                val decision = env.handlers.hooks.decide(s, result.value, env.bank)
                val record = DecisionRecord(
                    turnId = entry.turnId, atMs = s.wallMs, outcome = "resent", state = request.state,
                    questions = request.questions.keys.toList(), answers = result.value.logged(),
                    route = decision.route.wire, jevCalls = 1, jevMs = s.nowMs - f.startedAt,
                )
                enqueue(ActionState(entry.turnId, entry.turn.text, decision, entry.turn.speechEndAt, record, resend = true))
            }
            is OpenRouterResult.Failed -> {
                // Never drop a held turn on a failed resend (R-DUR-4): it stays held and goes
                // with the next reconnect or call. Offline needs no word; anything else is said once.
                if (result.kind != FailureKind.Offline) {
                    report("cascade.outbox", mapOf("event" to "resend_failed", "kind" to result.kind.wire, "size" to s.outbox.size), warn = true)
                    if (s.started) say(AppLines.GAVE_UP, SpeechKind.Reply)
                }
                endFlush()
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun Tx.flushAwaiting() {
        s.log.awaitingAudio.forEach { (rec, _) -> log(rec) }
        s = s.copy(log = s.log.copy(awaitingAudio = emptyList()))
    }

    private fun Map<String, JevAnswer>.logged(): Map<String, LoggedAnswer> = mapValues { (_, a) -> LoggedAnswer(a.choice, a.confidence, a.probabilities) }

    companion object {
        /** The outbox stage that says a turn's tasks exist (the new-task handlers mark it). */
        const val NEW_TASK_STAGE = "task"
        const val TICK = "pause.tick"

        /** The gate a committed turn waits behind until its accurate pass is in (R-STT-2). */
        const val ACCURATE_GATE = "accurate"
        const val TICK_MS = 50L
        private const val KEEP_TURNS = 64
    }
}
