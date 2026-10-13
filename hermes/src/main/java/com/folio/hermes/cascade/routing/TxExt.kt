package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.Timer
import com.folio.hermes.cascade.Tx

/*
 * Tx helpers for the routing slice (S-A), as extensions so the hub's `Tx` stays untouched.
 *
 * Marks: small facts the routing slice must remember across events ("this turn's summary was
 * spoken", "this run's update line waits for Accepted"). They live in the outbox's stage map,
 * keyed by turn id (or `task:<id>`), so they are persisted with the outbox and a resend never
 * repeats them. A hub-owned scratch field would be cleaner; see the slice report.
 */

/** Remember [value] under [key] (a turn id or `task:<id>`). */
internal fun Tx.mark(key: String, value: String) {
    s = s.copy(outbox = s.outbox.markStage(key, value))
}

internal fun Tx.marked(key: String, value: String): Boolean = s.outbox.stageDone(key, value)

/** Every mark under [key]. */
internal fun Tx.marks(key: String): Set<String> = s.outbox.stages[key].orEmpty()

/** Forget mark [value] under [key]. */
internal fun Tx.unmark(key: String, value: String) {
    val left = s.outbox.stages[key].orEmpty() - value
    s = s.copy(outbox = s.outbox.copy(stages = if (left.isEmpty()) s.outbox.stages - key else s.outbox.stages + (key to left)))
}

/** Queue [action] behind the running one (the reducer starts it after this event). */
internal fun Tx.enqueueAction(action: ActionState) {
    s = s.copy(actions = s.actions.copy(waiting = s.actions.waiting + action))
}

/**
 * Speak [sentences] (text, stock slot or null) as one utterance of [kind]. Returns false if
 * nothing was added (an empty list).
 */
internal fun Tx.speak(kind: SpeechKind, sentences: List<Pair<String, String?>>, turnId: String? = null, taskId: String? = null): Boolean {
    if (sentences.isEmpty()) return false
    val u = open(kind, taskId = taskId, turnId = turnId)
    var added = false
    sentences.forEach { (text, slot) -> added = add(u, text, slot) || added }
    close(u)
    return added
}

/** One sentence of stock slot [slot] (advancing its rotation), or null for an unknown slot. */
internal fun Tx.line(slot: String): Pair<String, String?>? = nextLine(slot)?.let { it to slot }

/** Wait for a slice timer: the reducer resumes the active action with `ActionResult.TimerFired`. */
internal fun Tx.waitTimer(name: String, delayMs: Long): Next {
    val seq = nextSeq()
    effects += Effect.StartTimer(Timer(name, seq), delayMs)
    updateAction { it.copy(waitingOn = timerKey(name, seq)) }
    return Next.Wait(name)
}

/** The reducer's correlation key for a slice timer the active action waits on. */
internal fun timerKey(name: String, seq: Long): String = "timer:$name:$seq"

/** Fire the timer the active action waits on now (a same-key start replaces the running one). */
internal fun Tx.fireWaitingTimer(name: String): Boolean {
    val key = s.actions.active?.waitingOn ?: return false
    val prefix = "timer:$name:"
    if (!key.startsWith(prefix)) return false
    val seq = key.removePrefix(prefix).toLongOrNull() ?: return false
    effects += Effect.StartTimer(Timer(name, seq), 0)
    return true
}
