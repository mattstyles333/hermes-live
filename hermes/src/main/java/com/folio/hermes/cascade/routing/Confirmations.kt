package com.folio.hermes.cascade.routing

import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.SpeechKind
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.registry.TaskRegistry

/**
 * What Bill says once Hermes has the work (spec v2 §0, R-TASK-2, R-DUR-2):
 * - a new task's summary waits for its `Accepted`; the tasks one turn started are confirmed
 *   together, once every one of them is accepted or failed;
 * - an update's `update.*` line waits for that run's `Accepted`;
 * - a failed submit is always spoken (`error.gave_up`), once per task or update. With a task
 *   registry that retries (S-B), it is spoken when the registry gives up (`NotSent`, which the
 *   registry must set in its `taskUpdated` for the `Failed` update); with no registry, at the
 *   first failure.
 * Nothing is spoken after hang-up (it would play out of context at the next call).
 */
object Confirmations {
    const val QUEUED = "limit.queued"

    /** This task's summary is never spoken (offline dictation, or already told it is queued). */
    fun silence(tx: Tx, turnId: String, taskId: String) = tx.mark(turnId, "summary:$taskId")

    /** Speak [slot] when run [runId] of [taskId] is accepted (an update; spec v2 §0). */
    fun expectAck(tx: Tx, taskId: String, runId: Long, slot: String) = tx.mark(taskKey(taskId), "ack:$runId:$slot")

    /** A task update the table (and the registry) applied. */
    fun taskUpdated(tx: Tx, event: Event.TaskUpdated) {
        val t = tx.s.tasks[event.taskId] ?: return
        val run = t.runs[event.runId]
        when (event.update) {
            is TurnUpdate.Accepted -> {
                ack(tx, t.id, event.runId, accepted = true)
                if (run?.kind == RunKind.Start) t.turnId?.let { summaries(tx, it) }
            }
            is TurnUpdate.Failed -> {
                val wasAck = ack(tx, t.id, event.runId, accepted = false)
                if (!wasAck && t.send != SendState.Accepted && run?.kind == RunKind.Start) failedSubmit(tx, t)
                t.turnId?.let { summaries(tx, it) }
            }
            else -> Unit
        }
    }

    /** Settle a pending update line for [runId]: its line on Accepted, `error.gave_up` on failure. True if one was pending. */
    private fun ack(tx: Tx, taskId: String, runId: Long, accepted: Boolean): Boolean {
        val mark = tx.marks(taskKey(taskId)).firstOrNull { it.startsWith("ack:$runId:") } ?: return false
        tx.unmark(taskKey(taskId), mark)
        val slot = if (accepted) mark.removePrefix("ack:$runId:") else AppLines.GAVE_UP
        // After hang-up nothing is queued: it would play out of context at the next call (the card shows it).
        if (tx.s.started) tx.line(slot)?.let { tx.speak(SpeechKind.Reply, listOf(it), taskId = taskId) }
        return true
    }

    private fun failedSubmit(tx: Tx, t: TaskRecord) {
        if (tx.marked(taskKey(t.id), GAVE_UP)) return
        if (t.send != SendState.NotSent && tx.env.registry !== TaskRegistry.None) return
        tx.mark(taskKey(t.id), GAVE_UP)
        if (tx.s.started) tx.say(AppLines.GAVE_UP, SpeechKind.Reply)
    }

    /** Confirm the tasks turn [turnId] started, once all of them are accepted or failed. */
    fun summaries(tx: Tx, turnId: String) {
        val tasks = tx.s.tasks.tasks.filter { it.turnId == turnId }
        val unspoken = tasks.filter { !TaskQueue.isQueued(it) && !tx.marked(turnId, "summary:${it.id}") }
        if (unspoken.isEmpty() || unspoken.any { !settled(it) }) return
        val sentences = unspoken.filter { it.send == SendState.Accepted && it.state != TaskState.Failed }.mapNotNull { it.summary }.map { it to null }.toMutableList<Pair<String, String?>>()
        unspoken.forEach { silence(tx, turnId, it.id) }
        val queued = tasks.filter { TaskQueue.isQueued(it) && !tx.marked(turnId, "queued:${it.id}") }
        if (queued.isNotEmpty() && sentences.isNotEmpty()) {
            queued.forEach { tx.mark(turnId, "queued:${it.id}") }
            tx.line(QUEUED)?.let { sentences += it }
        }
        if (tx.s.started) tx.speak(SpeechKind.Notice, sentences, turnId = turnId)
    }

    private fun settled(t: TaskRecord): Boolean = t.send != SendState.Sending || t.state != TaskState.Running || t.lastFailed

    private fun taskKey(id: String) = "task:$id"

    private const val GAVE_UP = "gave_up"
}
