package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.MemoSession
import com.folio.hermes.cascade.OutboxEntry
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.Tx
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One dictation session waiting to be sent (R-OFF-3/4), in the order it was spoken: its held
 * turns, oldest first. [sessionId] is null for a held turn that is not part of a memo session
 * (e.g. a turn carried over a restart).
 */
data class MemoBatch(val sessionId: String?, val startedWallMs: Long, val endedWallMs: Long, val turns: List<OutboxEntry>) {
    val turnIds: List<String> get() = turns.map { it.turnId }

    /** The whole dictation, verbatim, one turn per line (Hermes always gets all of it; R-CTX-3). */
    val text: String get() = turns.joinToString("\n") { it.turn.text }

    /** "dictated offline 14:05–14:32" for the brief (R-OFF-4), in [zone]. */
    fun note(zone: ZoneId = ZoneId.systemDefault()): String = "dictated offline ${hhmm(startedWallMs, zone)}–${hhmm(endedWallMs, zone)}"

    private fun hhmm(wallMs: Long, zone: ZoneId): String = HHMM.format(Instant.ofEpochMilli(wallMs).atZone(zone))

    private companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * S-A's reconnect routing plugs in here (R-OFF-4): given the batches in order, carry them out
 * (writer segments, Jev routes, briefs with [MemoBatch.note], one summary line) and mark each
 * one with [MemoQueue.markSent] once its work is durable. Return true to take over the send;
 * false leaves C1's flush (each held turn through Jev, in order).
 */
fun interface ReconnectRouter {
    fun route(tx: Tx, batches: List<MemoBatch>): Boolean
}

/**
 * Memo mode's storage and ordering (R-OFF-3, R-OFF-4). Held turns are grouped into dictation
 * sessions: a turn joins the newest session when the gap since its last turn is under
 * `memoGapMs` and the session stays within `memoCapMs` (wall span and speech). Sessions are text
 * only and never expire: they leave only when sent ([markSent]).
 */
object MemoQueue {
    /** Speech estimate per word when no timing is known (150 words a minute). */
    const val MS_PER_WORD = 400L

    fun speechMs(turn: HeldTurn): Long = turn.text.split(' ').count { it.isNotBlank() }.coerceAtLeast(1) * MS_PER_WORD

    /** [sessions] with held turn [turnId] added: to the newest session if it fits, else a new one. */
    fun group(sessions: List<MemoSession>, turnId: String, turn: HeldTurn, settings: CascadeSettings): List<MemoSession> {
        if (sessions.any { turnId in it.turnIds }) return sessions
        val speech = speechMs(turn)
        val last = sessions.lastOrNull()
        val fits = last != null &&
            turn.wallMs - last.endedWallMs < settings.memoGapMs &&
            turn.wallMs - last.startedWallMs <= settings.memoCapMs &&
            last.speechMs + speech <= settings.memoCapMs
        return if (fits) {
            sessions.dropLast(1) + last!!.copy(endedWallMs = maxOf(last.endedWallMs, turn.wallMs), turnIds = last.turnIds + turnId, speechMs = last.speechMs + speech)
        } else {
            sessions + MemoSession("m-$turnId", turn.wallMs, turn.wallMs, listOf(turnId), speech)
        }
    }

    /** Every held turn, as batches in spoken order: memo sessions plus held turns outside any session. */
    fun batches(s: State): List<MemoBatch> {
        val held = s.outbox.held
        val byId = held.associateBy { it.turnId }
        val inSessions = s.ceo.memoSessions.flatMap { it.turnIds }.toSet()
        val sessions = s.ceo.memoSessions.mapNotNull { m ->
            val turns = m.turnIds.mapNotNull(byId::get)
            if (turns.isEmpty()) null else MemoBatch(m.id, m.startedWallMs, m.endedWallMs, turns)
        }
        val loose = held.filter { it.turnId !in inSessions }.map { MemoBatch(null, it.turn.wallMs, it.turn.wallMs, listOf(it)) }
        val order = held.withIndex().associate { (i, e) -> e.turnId to i }
        return (sessions + loose).sortedWith(compareBy({ it.startedWallMs }, { order[it.turns.first().turnId] ?: 0 }))
    }

    /** [batch] went out: its turns leave the outbox (never sent again) and its session ends. */
    fun markSent(tx: Tx, batch: MemoBatch) {
        batch.turnIds.forEach { markTurnSent(tx, it) }
        batch.sessionId?.let { id -> tx.s = tx.s.copy(ceo = tx.s.ceo.copy(memoSessions = tx.s.ceo.memoSessions.filter { it.id != id })) }
    }

    /** One held turn went out. */
    fun markTurnSent(tx: Tx, turnId: String) {
        if (tx.s.outbox.held.none { it.turnId == turnId }) return
        tx.s = tx.s.copy(outbox = tx.s.outbox.markSent(turnId))
        tx.emit(CascadeEvent.HeldSent(turnId))
        tx.s = prune(tx.s)
    }

    /** Sessions keep only turns still held; empty ones go. */
    fun prune(s: State): State {
        val held = s.outbox.held.map { it.turnId }.toSet()
        val kept = s.ceo.memoSessions.map { it.copy(turnIds = it.turnIds.filter { t -> t in held }) }.filter { it.turnIds.isNotEmpty() }
        return if (kept == s.ceo.memoSessions) s else s.copy(ceo = s.ceo.copy(memoSessions = kept))
    }
}
