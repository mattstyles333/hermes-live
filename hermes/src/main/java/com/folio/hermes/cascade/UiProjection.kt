package com.folio.hermes.cascade

/**
 * [State] → [CascadeUiState]: what the call screen shows, recomputed after every event (pure).
 * The phase follows C1's rule: user speaking, then Bill speaking, then deciding (Jev still out
 * when the pause ring is full), pausing, writing, idle.
 */
object UiProjection {
    fun project(s: State): CascadeUiState {
        val turn = s.turn
        val phase = when {
            s.userSpeaking -> TurnPhase.UserSpeaking
            s.speech.current != null -> TurnPhase.BillSpeaking
            turn.deciding && turn.jevInFlight > 0 && s.pauseProgress >= 1f -> TurnPhase.Deciding
            turn.deciding -> TurnPhase.Pausing
            s.writers.isNotEmpty() -> TurnPhase.Writing
            else -> TurnPhase.Idle
        }
        val last = s.transcript.byTurn.entries.lastOrNull()
        val lastTranscript = last?.value
        // The words on screen are the last committed turn's (WordChosen indexes them) unless the user is mid-speech.
        val shownTurn = if (turn.heard.isNotEmpty() || last == null) null else last.key
        return CascadeUiState(
            turn = TurnUi(
                phase = phase,
                partial = turn.heard,
                pauseProgress = s.pauseProgress,
                held = turn.pending.joinToString(" "),
                heldPieces = turn.pending,
                words = if (turn.heard.isNotEmpty() || lastTranscript == null) s.transcript.words else lastTranscript.words,
                pass = if (turn.heard.isNotEmpty() || lastTranscript == null) TranscriptPass.Fast else lastTranscript.pass,
                offline = !s.connection.online,
                holdToTalk = s.transcript.holdToTalk,
                turnId = shownTurn,
            ),
            jev = JevUi(inFlight = turn.jevInFlight > 0, answers = s.jev.answers, latencyMs = s.jev.latencyMs),
            writer = s.writer,
            bill = s.bill,
            tasks = s.tasks.tasks.map { it.toUi() },
            connection = ConnectionUi(hermesUp = s.connection.hermesUp, outbox = s.outbox.size, online = s.connection.online, memo = s.connection.memo),
            render = s.render,
            ceo = CeoUi(
                heldTurns = s.outbox.held.map { HeldTurnUi(it.turnId, it.turn.text, it.turn.wallMs) },
                memoSessions = s.ceo.memoSessions.map { MemoSessionUi(it.id, it.startedWallMs, it.endedWallMs, it.turnIds.size) },
                unreadResults = s.tasks.tasks.filter { it.unread }.map { it.id },
                conversationId = s.conversation.id,
                briefing = s.ceo.briefing,
            ),
            resume = s.conversation.resume?.let {
                ResumeUi(it.conversationId, it.lastEndedWallMs, it.unheardResults, it.heldTurns, it.waitingTasks, it.defaultChoice)
            },
        )
    }
}

/** The call screen's view of a task (C1 fields plus the CEO ones). */
fun TaskRecord.toUi(): TaskUi = snapshot().toUi().let { if (queued && state == TaskState.Running && runs.isEmpty()) it.copy(status = TaskUiStatus.Queued) else it }.copy(
    brief = brief,
    summary = summary,
    send = send,
    unread = unread,
    offline = offline,
    result = result,
)
