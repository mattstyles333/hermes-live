package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskState

/*
 * State → stored values (pure). What is stored is a function of the [State] alone, so a step's
 * writes follow from comparing the state before and after it.
 */
object Snapshots {
    /** Conversation ids are `c<wall ms when it began>`. */
    fun conversationId(wallMs: Long, avoid: String? = null): String {
        val id = "c$wallMs"
        return if (id == avoid) "c${wallMs + 1}" else id
    }

    fun startedWallMs(conversationId: String?): Long = conversationId?.removePrefix("c")?.toLongOrNull() ?: 0L

    /** Turns committed and still being acted on (R-DUR-1): stored as held so a restart carries them out. */
    fun pendingTurns(s: State): List<HeldDto> =
        (listOfNotNull(s.actions.active) + s.actions.waiting).map { HeldDto(it.turnId, it.text, it.speechEndAt, it.record.atMs, carried = true) }

    /** Turns whose task, or whose update run, was submitted and not yet `Accepted` (R-DUR-2). */
    fun awaiting(s: State): List<String> {
        val tasks = s.tasks.tasks.filter { it.send == SendState.Sending && it.state == TaskState.Running }.mapNotNull { it.turnId }
        val updates = s.tasks.tasks.flatMap { t -> t.runs.values.filter { !it.accepted }.mapNotNull { it.turnId } }
        return (tasks + updates).distinct()
    }

    fun outbox(s: State): OutboxDto {
        val held = s.outbox.held.map { HeldDto(it.turnId, it.turn.text, it.turn.speechEndAt, it.turn.wallMs, it.turn.carried) }
        val heldIds = held.map { it.turnId }.toSet()
        val pending = pendingTurns(s).filter { it.turnId !in heldIds && it.turnId !in s.outbox.sent }
        val awaiting = awaiting(s)
        return OutboxDto(
            held = held + pending,
            awaiting = awaiting,
            sent = s.outbox.sent.filter { it !in awaiting },
            stages = s.outbox.stages.mapValues { (_, v) -> v.sorted() },
        )
    }

    /** The current conversation (R-CONV-1): recent exchanges as heard, the summary and its links. */
    fun conversation(s: State, endedWallMs: Long? = null): ConversationDto? {
        val id = s.conversation.id ?: return null
        val started = startedWallMs(id)
        val linked = s.tasks.tasks.filter { it.startedAt >= started }
        return ConversationDto(
            id = id,
            startedWallMs = started,
            endedWallMs = endedWallMs,
            summary = s.conversation.summary,
            recent = s.recent.map { ExchangeDto(it.user, s.speech.heard(it.utteranceId)) },
            taskIds = linked.map { it.id },
            unheard = s.tasks.tasks.filter { it.unread }.map { it.id },
            heldTurnIds = s.outbox.held.map { it.turnId },
            sessionIds = linked.mapNotNull { it.storedSessionId },
        )
    }

    fun core(s: State): CoreDto = CoreDto(
        outbox = outbox(s),
        turns = s.turn.turns,
        counter = s.tasks.counter,
        runCounter = s.tasks.runCounter,
        conversation = conversation(s),
        memo = s.ceo.memoSessions.map { MemoDto(it.id, it.startedWallMs, it.endedWallMs, it.turnIds, it.speechMs) },
        offCall = s.offCall.results.map { OffCallDto(it.taskId, it.ok) },
        marks = s.marks.mapValues { (_, v) -> v.sorted() },
    )

    /** True if nothing [core] reads changed between [a] and [b] (a cheap check before building it). */
    fun sameCore(a: State, b: State): Boolean =
        a.outbox === b.outbox && a.actions === b.actions && a.tasks === b.tasks && a.conversation === b.conversation &&
            a.ceo === b.ceo && a.offCall === b.offCall && a.recent === b.recent && a.speech === b.speech && a.turn.turns == b.turn.turns &&
            a.marks === b.marks
}
