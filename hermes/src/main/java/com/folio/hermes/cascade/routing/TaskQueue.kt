package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskStep
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.Tx

/** One brief ready to become a task: the full `prompt.submit` text, its label and spoken summary. */
data class PlannedTask(val brief: String, val label: String, val summary: String?)

/** What [TaskQueue.add] did: task ids started now, and those queued until a slot frees. */
data class Placed(val started: List<String>, val queued: List<String>)

/**
 * Voice tasks beyond the cap wait on the phone (R-TASK-1, R-TASK-2: "split into ≤ 4, the rest
 * queued"). A queued task is a running [TaskRecord] marked [TaskRecord.queued], with no runs: the
 * card shows it as Queued, its `last_event` reads "queued", and nothing reaches Hermes until
 * [pump] launches it when a slot frees. The hooks pump after every run ends and when the stored
 * state is read back (a queued task survives restarts and is never resumed).
 */
object TaskQueue {
    fun isQueued(t: TaskRecord): Boolean = t.state == TaskState.Running && t.runs.isEmpty() && (t.queued || t.events.isEmpty())

    /** Tasks holding a Hermes slot. */
    fun busy(table: TaskTable): Int = table.tasks.count { it.state == TaskState.Running && !isQueued(it) }

    fun queued(table: TaskTable): List<TaskRecord> = table.tasks.filter(::isQueued)

    /**
     * Add [planned] tasks for turn [turnId]: up to `maxBriefsPerTurn` start now while slots are
     * free, the rest are queued. [offline] marks briefs dictated offline (memo mode).
     */
    fun add(tx: Tx, planned: List<PlannedTask>, turnId: String?, offline: Boolean = false): Placed {
        val started = mutableListOf<String>()
        val queued = mutableListOf<String>()
        for (p in planned) {
            val s = tx.s
            val id = "t${s.tasks.counter + 1}"
            val record = TaskRecord(id, p.label, s.wallMs, brief = p.brief, summary = p.summary, turnId = turnId, offline = offline, queued = true)
            tx.s = s.copy(tasks = s.tasks.copy(tasks = s.tasks.tasks + record, counter = s.tasks.counter + 1))
            val room = busy(tx.s.tasks) < tx.s.settings.taskCap && started.size < tx.s.settings.maxBriefsPerTurn.coerceAtLeast(1)
            if (room && queued.isEmpty()) {
                launch(tx, id)
                started += id
            } else {
                queued += id
                tx.report("cascade.task", mapOf("event" to "queued", "running" to busy(tx.s.tasks)))
            }
        }
        return Placed(started, queued)
    }

    /** Start queued tasks, oldest first, while slots are free. Returns the ids started. */
    fun pump(tx: Tx): List<String> {
        val out = mutableListOf<String>()
        while (busy(tx.s.tasks) < tx.s.settings.taskCap) {
            val next = queued(tx.s.tasks).firstOrNull() ?: break
            launch(tx, next.id)
            out += next.id
        }
        return out
    }

    /** Amend a queued task's brief before it starts (an update to a task that has not started). */
    fun amend(tx: Tx, id: String, text: String): Boolean {
        val t = tx.s.tasks[id]?.takeIf(::isQueued) ?: return false
        val brief = (t.brief.orEmpty() + "\n\nUpdate from the user before you started: " + text).trim()
        tx.s = tx.s.copy(tasks = tx.s.tasks.copy(tasks = tx.s.tasks.tasks.map { if (it.id == id) it.copy(brief = brief) else it }))
        return true
    }

    /** Drop a queued task (cancel before it started): nothing was sent, so nothing to interrupt. */
    fun drop(tx: Tx, id: String): Boolean {
        val t = tx.s.tasks[id]?.takeIf(::isQueued) ?: return false
        val now = tx.s.wallMs
        val record = t.copy(state = TaskState.Cancelled, cancelled = true, finishedAt = now, queued = false).log(TaskEvent.Cancelled(now))
        tx.s = tx.s.copy(tasks = tx.s.tasks.copy(tasks = tx.s.tasks.tasks.map { if (it.id == id) record else it }))
        tx.emit(CascadeEvent.TaskCancelled(id))
        return true
    }

    private fun launch(tx: Tx, id: String) {
        val s = tx.s
        val t = s.tasks[id] ?: return
        val table = s.tasks.copy(tasks = s.tasks.tasks.map { if (it.id == id) it.copy(startedAt = s.wallMs, lastEventAt = s.wallMs, queued = false) else it })
        val step = table.launch(id, t.brief.orEmpty(), tx.voiceContext(), RunKind.Start, s.wallMs, t.turnId)
        tx.tasks(
            TaskStep(
                step.table,
                Unit,
                effects = step.effects,
                events = listOf(CascadeEvent.TaskStarted(id)),
                reports = listOf("start" to mapOf("running" to busy(step.table))),
            ),
        )
    }
}
