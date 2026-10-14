package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.OutboxEntry
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.Tx
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One offline dictation session to route: its held turns in order and when it was dictated. */
data class MemoBatchSession(val turnIds: List<String>, val startWallMs: Long, val endWallMs: Long)

/**
 * R-OFF-4: held dictation sent on reconnect. S-B's `ConversationPolicy.online` calls [begin]
 * (and returns its answer) when the network comes back with held turns:
 * 1. the writer segments each dictation session into instructions ([MemoSegmentHandler]; the
 *    instructions must be the user's own words in order, else each held turn is one instruction);
 * 2. the instructions replace the held turns in the outbox, in order, and the outbox flush sends
 *    each through Jev against the current tasks;
 * 3. their briefs carry "Dictated offline hh:mm–hh:mm" ([offlineNote]) and say nothing each;
 * 4. after the last one, Bill says one summary line.
 *
 * [zone] formats hh:mm (the reducer never reads the clock or the zone itself).
 */
class MemoRouting(val zone: ZoneId = ZoneId.systemDefault()) {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

    /**
     * Start routing the held dictation, if any. Returns true when this takes over sending the
     * outbox (S-B's `online` returns it). Safe to call again mid-batch: nothing is segmented twice.
     */
    fun begin(tx: Tx): Boolean {
        val s = tx.s
        if (s.outbox.size == 0) return false
        if (s.flush != null || segmenting(s)) return true
        val sessions = sessions(tx)
        if (sessions.isEmpty()) {
            tx.startFlush()
            return true
        }
        val id = "memo:" + sessions.first().turnIds.first()
        val action = ActionState(
            turnId = id,
            text = "",
            decision = Decision(Route.NewTask, null, writer = false),
            speechEndAt = s.nowMs,
            record = DecisionRecord(turnId = id, atMs = s.wallMs, outcome = "memo", route = Route.NewTask.wire),
            data = mapOf(KIND to SEGMENT, MemoSegmentHandler.PLAN to MemoSegmentHandler.encode(MemoSegmentHandler.plan(tx, sessions))),
        )
        // Not a user turn: keep it out of `recent`.
        tx.s = tx.s.copy(log = tx.s.log.copy(remembered = (tx.s.log.remembered + id).takeLast(64)))
        tx.enqueueAction(action)
        return true
    }

    /** The held turns not segmented yet, as dictation sessions (S-B's, else grouped by `memoGapMs`). */
    fun sessions(tx: Tx): List<MemoBatchSession> {
        val s = tx.s
        // Carried turns (hang-up, restart) are not offline dictation: the plain flush sends them.
        val raw = s.outbox.held.filter { !isMemo(tx, it.turnId) && !it.turn.carried }
        if (raw.isEmpty()) return emptyList()
        val byId = raw.associateBy { it.turnId }
        val fromPolicy = s.ceo.memoSessions.mapNotNull { m ->
            val ids = m.turnIds.filter { it in byId }
            if (ids.isEmpty()) null else MemoBatchSession(ids, m.startedWallMs, m.endedWallMs)
        }
        val covered = fromPolicy.flatMap { it.turnIds }.toSet()
        val rest = raw.filter { it.turnId !in covered }
        val grouped = mutableListOf<MemoBatchSession>()
        for (e in rest) {
            val last = grouped.lastOrNull()
            if (last != null && e.turn.wallMs - last.endWallMs < s.settings.memoGapMs) {
                grouped[grouped.size - 1] = last.copy(turnIds = last.turnIds + e.turnId, endWallMs = e.turn.wallMs)
            } else {
                grouped += MemoBatchSession(listOf(e.turnId), e.turn.wallMs, e.turn.wallMs)
            }
        }
        // Keep the outbox's order.
        val order = raw.map { it.turnId }
        return (fromPolicy + grouped).sortedBy { order.indexOf(it.turnIds.first()) }
    }

    /**
     * Replace the held turns of the segmented sessions with their instructions (in order) and
     * start sending. [segments]: per session, its instructions.
     */
    fun apply(tx: Tx, sessions: List<MemoBatchSession>, segments: List<List<String>>) {
        val s = tx.s
        val replaced = sessions.flatMap { it.turnIds }.toSet()
        val byId = s.outbox.held.associateBy { it.turnId }
        val fresh = mutableListOf<OutboxEntry>()
        sessions.forEachIndexed { i, session ->
            val last = byId[session.turnIds.last()]?.turn
            segments[i].forEachIndexed { k, text ->
                val id = "m${session.turnIds.first()}.${k + 1}"
                fresh += OutboxEntry(id, HeldTurn(text, last?.speechEndAt ?: s.nowMs, session.endWallMs, Transcript(text, offline = true)))
                tx.mark(id, "$OFFLINE${session.startWallMs}:${session.endWallMs}")
                tx.mark(BATCH, "$PENDING$id")
            }
        }
        val kept = s.outbox.held.filter { it.turnId !in replaced }
        val sent = (tx.s.outbox.sent.filter { it !in replaced } + replaced).takeLast(200)
        tx.s = tx.s.copy(outbox = tx.s.outbox.copy(held = kept + fresh, sent = sent))
        tx.report("cascade.outbox", mapOf("event" to "memo_segmented", "sessions" to sessions.size, "count" to fresh.size))
    }

    /** Send the outbox through Jev, one held turn at a time (the reducer's flush continues it). */
    fun startFlush(tx: Tx) = tx.startFlush()

    /** "Dictated offline 14:32–14:41." for an instruction from offline dictation, else null. */
    fun offlineNote(tx: Tx, turnId: String): String? {
        val mark = tx.marks(turnId).firstOrNull { it.startsWith(OFFLINE) } ?: return null
        val (from, to) = mark.removePrefix(OFFLINE).split(':').mapNotNull { it.toLongOrNull() }.let { it.getOrNull(0) to it.getOrNull(1) }
        if (from == null || to == null) return null
        return "Dictated offline ${hhmm.format(Instant.ofEpochMilli(from))}–${hhmm.format(Instant.ofEpochMilli(to))}."
    }

    /**
     * An offline instruction was carried out as [route] ([count] tasks or updates). After the
     * last one of the batch, Bill says one summary line. Idempotent per turn.
     */
    fun record(tx: Tx, turnId: String, route: String, count: Int) {
        if (!tx.marked(BATCH, "$PENDING$turnId")) return
        tx.unmark(BATCH, "$PENDING$turnId")
        tx.mark(BATCH, "$DONE$turnId:$route:$count")
        if (tx.marks(BATCH).any { it.startsWith(PENDING) }) return
        // done:<turn id>:<route>:<count>; the route and count are the last two fields.
        val done = tx.marks(BATCH).filter { it.startsWith(DONE) }.map { it.split(':') }.map { it[it.size - 2] to (it.last().toIntOrNull() ?: 0) }
        tx.marks(BATCH).toList().forEach { tx.unmark(BATCH, it) }
        val sentences = summary(done).map { it to null }
        val a = tx.s.actions.active
        if (a != null && a.turnId == turnId) {
            sentences.forEach { (text, _) -> if (tx.add(a.utteranceId, text)) tx.updateAction { it.copy(pieces = it.pieces + 1) } }
        } else {
            tx.speak(SpeechKind.Notice, sentences)
        }
    }

    /** The one line said after an offline batch. */
    fun summary(done: List<Pair<String, Int>>): List<String> {
        val tasks = done.filter { it.first == "new_task" }.sumOf { it.second }
        val updates = done.filter { it.first == "update_task" }.sumOf { it.second }
        val stopped = done.count { it.first == "cancel_task" }
        val parts = listOfNotNull(
            tasks.takeIf { it > 0 }?.let { "started $it ${if (it == 1) "task" else "tasks"}" },
            updates.takeIf { it > 0 }?.let { "passed on $it ${if (it == 1) "update" else "updates"}" },
            stopped.takeIf { it > 0 }?.let { "stopped $it" },
        )
        val line = if (parts.isEmpty()) "That's everything from your offline notes." else
            "From your offline notes, I've " + parts.dropLast(1).joinToString(", ") + (if (parts.size > 1) " and " else "") + parts.last() + "."
        return listOf("Back online.", line)
    }

    private fun segmenting(s: State): Boolean =
        (listOfNotNull(s.actions.active) + s.actions.waiting).any { it.data[KIND] == SEGMENT }

    companion object {
        const val KIND = "kind"
        const val SEGMENT = "memo_segment"
        private const val OFFLINE = "offline:"
        private const val BATCH = "memo:batch"
        private const val PENDING = "pending:"
        private const val DONE = "done:"

        /** True if [turnId] is an instruction from offline dictation. */
        fun isMemo(tx: Tx, turnId: String): Boolean = tx.marks(turnId).any { it.startsWith(OFFLINE) }
    }
}
