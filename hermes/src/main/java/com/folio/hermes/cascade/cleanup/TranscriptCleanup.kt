package com.folio.hermes.cascade.cleanup

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.routing.CeoRouter
import com.folio.hermes.cascade.writer.Repair
import com.folio.hermes.openrouter.CleanupInput
import com.folio.hermes.openrouter.OpenRouterResult

/**
 * Transcript cleanup for committed turns (hub decision 2026-10-14 from the cleanup bench; spec v2
 * R-STT-5/6, R-TASK-2). With `CascadeSettings.cleanup` on and online, once a committed turn's
 * accurate pass is in ([start], from `CeoHooks.accurate`) its text goes to the cleanup model
 * ([Effect.Cleanup]: heard, the fast pass as `other`, the unsure words, the vocabulary and the task
 * labels) and the turn waits behind the [GATE]. The stock line has already played at commit
 * (fastline); only the brief, the submit and the caption wait, at most `cleanupWaitMs`.
 *
 * When it is back ([cleaned]): a failure, a bad reply or a timeout keeps the heard text;
 * [CleanupCheck] refuses implausible swaps (the heard text stays); otherwise the cleaned text
 * becomes the turn's text and transcript (so the brief, its repair check, the submit and the
 * caption all use it). If a name or number changed, Jev is asked again with the cleaned words
 * before the turn goes on ([jevAnswered]), the way the outbox resend asks it. Its decision is
 * taken when the route is the same (another task target is fine) or no line has played yet; a
 * different route after a played line keeps the first decision (the user already heard it).
 *
 * Telemetry `cascade.cleanup`: outcome, reason codes, timings and counts only, never words.
 */
object TranscriptCleanup {
    /** The gate a committed turn waits behind until its cleanup is back. */
    const val GATE = "cleanup"

    /** Action data: a hook (the key-doubt answer) chose the route, so a re-ask never replaces it. */
    const val LOCKED = "cleanup.locked"

    private const val OTHER = "cleanup.other"
    private const val JEV = "cleanup.jev"

    /** Ask for [turnId]'s cleanup if it applies; [fast] is the fast pass's text (the `other` recogniser). */
    fun start(tx: Tx, turnId: String, fast: String) {
        val s = tx.s
        if (!s.settings.cleanup || !s.connection.online || s.connection.memo) return
        val a = s.actions.waiting.firstOrNull { it.turnId == turnId } ?: return
        if (a.resend || a.decision.route == Route.EndCall || a.decision.route == Route.AskRepeat || GATE in a.gates) return
        val heard = a.text.trim()
        val count = CleanupCheck.tokens(heard).size
        if (count == 0 || count > CleanupCheck.MAX_WORDS) return
        val unsure = a.transcript?.words.orEmpty().filter { it.uncertain }.map { it.text }.distinctBy { it.lowercase() }
        val input = CleanupInput(heard, fast.trim().ifEmpty { heard }, unsure, vocabulary(tx))
        update(tx, turnId) { it.copy(gates = it.gates + GATE, data = it.data + (OTHER to input.other)) }
        tx.effects += Effect.Cleanup(turnId, input)
    }

    /** The cleanup for [Event.Cleaned.turnId] is back: check it, apply it, maybe re-ask Jev, then open the gate. */
    fun cleaned(tx: Tx, event: Event.Cleaned) {
        val a = tx.s.actions.waiting.firstOrNull { it.turnId == event.turnId && GATE in it.gates } ?: return
        val attrs = mutableMapOf<String, Any?>("ms" to event.ms, "words" to CleanupCheck.tokens(a.text).size)
        val result = event.result
        if (result !is OpenRouterResult.Ok) {
            report(tx, attrs + mapOf("outcome" to "failed", "kind" to (result as OpenRouterResult.Failed).kind.wire), warn = false)
            open(tx, event.turnId)
            return
        }
        val heard = heardWords(a)
        when (val v = CleanupCheck.check(heard, result.value, a.data[OTHER] ?: a.text, vocabulary(tx))) {
            CleanupCheck.Verdict.Same -> {
                report(tx, attrs + ("outcome" to "same"))
                open(tx, event.turnId)
            }
            is CleanupCheck.Verdict.Rejected -> {
                report(tx, attrs + mapOf("outcome" to "rejected", "reason" to v.reason))
                open(tx, event.turnId)
            }
            is CleanupCheck.Verdict.Accepted -> {
                val t = Transcript(v.text, v.words, a.transcript?.pass ?: TranscriptPass.Accurate, a.transcript?.offline ?: false)
                update(tx, event.turnId) { it.copy(text = v.text, transcript = t) }
                caption(tx, event.turnId, t)
                val reask = v.key && tx.s.connection.online && tx.env.handlers.hooks.useJev(tx.s)
                report(tx, attrs + mapOf("outcome" to "changed", "changes" to v.changes.size, "key" to v.key, "reask" to reask))
                if (reask) reask(tx, event.turnId, v.text) else open(tx, event.turnId)
            }
        }
    }

    /** A Jev answer: true if it was a cleanup re-ask's (then the turn goes on with the new decision or the old one). */
    fun jevAnswered(tx: Tx, event: Event.JevAnswered): Boolean {
        // Only a waiting turn's own re-ask: a late one (the call stopped) falls through, and no pause call has its seq.
        val seq = event.seq.toString()
        val a = tx.s.actions.waiting.firstOrNull { it.data[JEV] == seq } ?: return false
        val result = event.result
        if (result is OpenRouterResult.Ok) {
            val fresh = CeoRouter.withWords(tx.s, result.value, a.text, tx.env.handlers.hooks.decide(tx.s, result.value, tx.env.bank))
            val played = a.slot != null || a.utteranceId != 0L
            val sameRoute = fresh.route.wire == a.decision.route.wire
            val take = LOCKED !in a.data && (!played || sameRoute)
            if (take) {
                val decision = if (played) fresh.copy(slot = a.decision.slot) else fresh
                update(tx, a.turnId) { it.copy(decision = decision, record = it.record.copy(route = decision.route.wire)) }
            }
            report(tx, mapOf("outcome" to "reasked", "taken" to take, "route_changed" to !sameRoute))
        } else {
            report(tx, mapOf("outcome" to "reask_failed", "kind" to (result as OpenRouterResult.Failed).kind.wire))
        }
        update(tx, a.turnId) { it.copy(data = it.data - JEV) }
        open(tx, a.turnId)
        return true
    }

    private fun reask(tx: Tx, turnId: String, text: String) {
        val seq = tx.s.turn.jevSeq + 1
        val request = tx.jevRequest(text, emptyList(), tx.s.settings.pause, speaking = false)
        tx.s = tx.s.copy(turn = tx.s.turn.copy(jevSeq = seq))
        update(tx, turnId) { it.copy(data = it.data + (JEV to seq.toString())) }
        tx.effects += Effect.CallJev(seq, request, tx.s.settings.jevTimeoutMs, early = false)
    }

    /** The heard words, one per token of the action's text, keeping the pass's marks when they line up. */
    private fun heardWords(a: ActionState): List<Word> {
        val t = a.transcript
        if (t != null && t.text.trim() == a.text.trim()) return Repair.aligned(t)
        return CleanupCheck.tokens(a.text).map { Word(it) }
    }

    /** The user's vocabulary plus the labels of tasks still worth naming (running or unheard), as the hotwords. */
    private fun vocabulary(tx: Tx): List<String> {
        val labels = tx.s.tasks.tasks.filter { it.state == TaskState.Running || it.unread }.map { it.label }
        return (tx.s.settings.vocabulary + labels).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    }

    /** The caption shows the committed turn's transcript: replace it in place. */
    private fun caption(tx: Tx, turnId: String, t: Transcript) {
        val byTurn = tx.s.transcript.byTurn
        if (turnId !in byTurn) return
        tx.s = tx.s.copy(transcript = tx.s.transcript.copy(byTurn = byTurn.mapValues { (k, v) -> if (k == turnId) t else v }))
    }

    private fun update(tx: Tx, turnId: String, f: (ActionState) -> ActionState) {
        tx.s = tx.s.copy(actions = tx.s.actions.copy(waiting = tx.s.actions.waiting.map { if (it.turnId == turnId) f(it) else it }))
    }

    private fun open(tx: Tx, turnId: String) {
        update(tx, turnId) { it.copy(data = it.data - OTHER) }
        tx.openGate(turnId, GATE)
    }

    private fun report(tx: Tx, attrs: Map<String, Any?>, warn: Boolean = false) = tx.report("cascade.cleanup", attrs.filterValues { it != null }, warn)
}
