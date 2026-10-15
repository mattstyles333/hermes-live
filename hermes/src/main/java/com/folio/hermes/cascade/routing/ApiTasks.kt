package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.CancelResult
import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.OffCallState
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskCommand
import com.folio.hermes.cascade.TaskReply
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateMode

/**
 * The shared task API's commands inside `reduce` ([Event.TaskApi]; grok-voice slice). They run
 * the same task operations as Bill's routes and card taps ([TaskQueue], [CeoTasks]), so the
 * durable seams persist them in the same step, before the reply ([Effect.TaskApiReply]) and
 * before any submit leaves. A command's key is its turn id (`api:<key>`): a repeated start finds
 * its task ([com.folio.hermes.cascade.registry.TaskRegistry.taskForTurn]), a repeated update is
 * not sent twice ([CeoTasks.update]).
 */
object ApiTasks {
    const val TURN_PREFIX = "api:"

    fun apply(tx: Tx, event: Event.TaskApi) {
        // Before the stored state is read back, the first save would overwrite it.
        val reply = if (!tx.s.restored) TaskReply.NotReady else run(tx, event.command)
        tx.effects += Effect.TaskApiReply(event.requestId, reply)
        tx.report("cascade.task_api", mapOf("command" to wire(event.command), "reply" to wire(reply)))
    }

    private fun run(tx: Tx, command: TaskCommand): TaskReply = when (command) {
        is TaskCommand.Start -> start(tx, command)
        is TaskCommand.Update -> update(tx, command)
        is TaskCommand.Cancel -> cancel(tx, command.taskId)
        is TaskCommand.Heard -> heard(tx, command.taskId)
        is TaskCommand.Retry -> retry(tx, command.taskId)
    }

    private fun start(tx: Tx, c: TaskCommand.Start): TaskReply {
        val turnId = TURN_PREFIX + c.key
        tx.env.registry.taskForTurn(tx.s, turnId)?.let { id ->
            val t = tx.s.tasks[id]!!
            return TaskReply.Started(id, t.label, TaskQueue.isQueued(t), existing = true)
        }
        val label = c.label.trim().ifEmpty { "Task" }.take(LABEL_CHARS)
        val placed = TaskQueue.add(tx, listOf(PlannedTask(c.brief, label, c.summary)), turnId)
        val id = (placed.started + placed.queued).first()
        return TaskReply.Started(id, label, queued = id in placed.queued)
    }

    private fun update(tx: Tx, c: TaskCommand.Update): TaskReply {
        // No action to hang a busy-mode read on: a redirect interrupts, then sends (as the card's "Tell it…").
        val kind = if (c.mode == UpdateMode.Queue) RunKind.Queue else RunKind.InterruptFirst
        return when (val r = CeoTasks.update(tx, c.taskId, c.text, kind, TURN_PREFIX + c.key)) {
            is CeoTasks.Updated.Sent -> TaskReply.Updated(c.taskId, kind.how)
            CeoTasks.Updated.Amended -> TaskReply.Updated(c.taskId, "amended")
            CeoTasks.Updated.AlreadySent -> TaskReply.Updated(c.taskId, "already_sent")
            is CeoTasks.Updated.Retried -> TaskReply.Updated(r.taskId, "retried")
            CeoTasks.Updated.AtCap -> TaskReply.AtCap
            CeoTasks.Updated.NotFound -> TaskReply.NotFound(c.taskId)
        }
    }

    private fun cancel(tx: Tx, taskId: String): TaskReply {
        if (taskId == CascadeTasks.ALL || taskId == CeoRouter.ALL_TASKS) {
            // Queued first ([CancelAll]): a slot freed by a running task must never launch one about to be dropped.
            val targets = CeoRouter.candidates(CascadeBrain.CANCEL_TASK, tx.s.tasks.tasks).sortedByDescending { TaskQueue.isQueued(it) }
            val stopped = targets.filter { CeoTasks.cancel(tx, it.id) == CancelResult.Cancelled }.map { it.id }
            return TaskReply.Cancelled(stopped)
        }
        return when (CeoTasks.cancel(tx, taskId)) {
            CancelResult.Cancelled -> TaskReply.Cancelled(listOf(taskId))
            CancelResult.AlreadyFinished -> TaskReply.TooLate(taskId)
            CancelResult.NotFound -> TaskReply.NotFound(taskId)
        }
    }

    /**
     * A card's Retry through the registry, exactly as Bill's tap (`Event.TaskTapped`, which the actor
     * drops with no Bill call open): a `NotSent` task is resent as itself, a `Failed` one starts again
     * as a new task with its brief. Off-call the registry speaks nothing, so the reply carries the cap.
     */
    private fun retry(tx: Tx, taskId: String): TaskReply {
        val t = tx.s.tasks[taskId] ?: return TaskReply.NotFound(taskId)
        val notSent = t.send == SendState.NotSent
        // The registry's own order: a running task is left alone, a finished one has nothing to retry.
        if (t.state == TaskState.Running) return TaskReply.Started(t.id, t.label, TaskQueue.isQueued(t), existing = true)
        if (!notSent && t.state != TaskState.Failed) return TaskReply.TooLate(taskId)
        val known = tx.s.tasks.tasks.map { it.id }.toSet()
        tx.env.registry.retry(tx, taskId)
        val fresh = tx.s.tasks.tasks.firstOrNull { it.id !in known }
        return when {
            fresh != null -> TaskReply.Updated(fresh.id, "retried")
            notSent && tx.s.tasks[taskId]?.send != SendState.NotSent -> TaskReply.Updated(taskId, "resent")
            else -> TaskReply.AtCap
        }
    }

    /** The front end told the user: read, and gone from the backlog a later Bill call would speak. */
    private fun heard(tx: Tx, taskId: String): TaskReply {
        val s = tx.s
        if (s.tasks[taskId] == null) return TaskReply.NotFound(taskId)
        s.resultUtterances[taskId]?.let(tx::drop)
        tx.s = tx.s.copy(
            tasks = tx.s.tasks.markRead(taskId),
            resultUtterances = tx.s.resultUtterances - taskId,
            offCall = OffCallState(s.offCall.results.filter { it.taskId != taskId }, s.offCall.requests.filter { it.taskId != taskId }),
        )
        return TaskReply.Noted
    }

    /** Telemetry names only (never briefs, labels or results). */
    private fun wire(c: TaskCommand): String = when (c) {
        is TaskCommand.Start -> "start"
        is TaskCommand.Update -> if (c.mode == UpdateMode.Queue) "update_queue" else "update_redirect"
        is TaskCommand.Cancel -> if (c.taskId == CascadeTasks.ALL) "cancel_all" else "cancel"
        is TaskCommand.Heard -> "heard"
        is TaskCommand.Retry -> "retry"
    }

    private fun wire(r: TaskReply): String = when (r) {
        is TaskReply.Started -> if (r.existing) "existing" else if (r.queued) "queued" else "started"
        is TaskReply.Updated -> r.how
        is TaskReply.Cancelled -> "cancelled"
        is TaskReply.TooLate -> "too_late"
        is TaskReply.NotFound -> "not_found"
        TaskReply.AtCap -> "at_cap"
        TaskReply.Noted -> "noted"
        TaskReply.NotReady -> "not_ready"
        TaskReply.NoReply -> "no_reply"
    }

    private const val LABEL_CHARS = 40
}
