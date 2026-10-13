package com.folio.hermes.cascade.registry

import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.StartResult
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.UpdateResult
import com.folio.hermes.cascade.store.StoreCodec

/**
 * The persisted task registry (R-DUR-2..4, R-TASK-7, R-CONV-3 logic). The tasks themselves live
 * in `State.tasks` and are stored by [com.folio.hermes.cascade.store.DurablePersistence]; this
 * class holds the rules.
 *
 * - **Dedupe (R-DUR-2):** [taskForTurn] finds a task already started by a turn, across restarts,
 *   so a resent turn never starts a second task.
 * - **Failed submits (R-DUR-4):** a submit that fails before `Accepted` is sent again once,
 *   automatically (the failure result it queued is withdrawn). After `submitAttempts` failures
 *   (default 2) the task is `NotSent`: [CascadeEvent.TaskNotSent], the card offers "tap to retry".
 *   Runs before S-A's `hooks.taskUpdated`, which can read `send == NotSent` to say `error.gave_up`.
 * - **Recovery markers:** [TaskRecovery] posts a failed update with [TaskRecovery.NOT_RECEIVED]
 *   (counts as a failed submit, so it is resent) or [TaskRecovery.UNCERTAIN] (`NotSent` at once:
 *   it may have reached Hermes, so it is never resent on its own).
 * - **Retry ([retry]):** a `NotSent` task is submitted again as the same task; a `Failed` task
 *   starts a new task with the same brief (R-TASK-7). Over the cap, `limit.full`.
 * - **Off-call (R-CONV-3):** with no call open, a finished task is recorded unread (the task table
 *   does that) and an [Effect.Notify] goes to the app: `task_done`, `task_failed` or `task_needs_you`.
 */
object DurableTaskRegistry : TaskRegistry {
    const val NOTIFY_DONE = "task_done"
    const val NOTIFY_FAILED = "task_failed"
    const val NOTIFY_NEEDS_YOU = "task_needs_you"

    override fun taskForTurn(state: State, turnId: String): String? = state.tasks.tasks.firstOrNull { it.turnId == turnId }?.id

    override fun entry(task: TaskRecord): String = StoreCodec.encodeTask(task)

    override fun record(entry: String): TaskRecord? = StoreCodec.decodeTask(entry)

    override fun taskUpdated(tx: Tx, event: Event.TaskUpdated) {
        val t = tx.s.tasks[event.taskId] ?: return
        val update = event.update
        val submitFailed = !t.accepted && t.send == SendState.Sending && !t.cancelled &&
            (update is TurnUpdate.Failed || (update is TurnUpdate.Completed && update.status == TurnStatus.Error))
        if (submitFailed) {
            failedSubmit(tx, t, (update as? TurnUpdate.Failed)?.message)
            return
        }
        if (!tx.s.started) notifyOffCall(tx, t, update)
    }

    override fun retry(tx: Tx, taskId: String) {
        val t = tx.s.tasks[taskId] ?: return
        when {
            t.state == TaskState.Running -> Unit
            t.send == SendState.NotSent -> resend(tx, t)
            t.state == TaskState.Failed -> {
                val brief = t.brief ?: t.label
                when (tx.tasks(tx.s.tasks.start(brief, t.label, tx.voiceContext(), tx.s.settings.taskCap, tx.s.wallMs))) {
                    is StartResult.AtCap -> tx.say(AppLines.LIMIT_FULL)
                    is StartResult.Started -> tx.report("cascade.task", mapOf("event" to "retry", "running" to tx.s.tasks.runningCount, "how" to "new_task"))
                }
            }
        }
    }

    /**
     * Hermes never got run [Event.RunNotReceived.runId] (R-DUR-2): send it again, once. A start
     * run gets a fresh session (the stored one was never prompted), created and stored before the
     * prompt; an update goes to the task's session again with the same turn id.
     */
    override fun notReceived(tx: Tx, event: Event.RunNotReceived) {
        val t = tx.s.tasks[event.taskId] ?: return
        val run = t.runs[event.runId] ?: return
        tx.tasks(tx.s.tasks.forget(t.id, event.runId))
        if (t.cancelled || t.state != TaskState.Running) return
        val how = if (run.kind == RunKind.Start) {
            val fresh = tx.s.tasks[t.id] ?: return
            put(tx, fresh.copy(storedSessionId = null, send = SendState.Sending, accepted = false))
            tx.tasks(tx.s.tasks.launch(t.id, run.text ?: t.brief ?: t.label, tx.voiceContext(), RunKind.Start, tx.s.wallMs, run.turnId ?: t.turnId))
            "resend_start"
        } else {
            val text = run.text ?: return
            tx.tasks(tx.s.tasks.launch(t.id, text, tx.voiceContext(), run.kind, tx.s.wallMs, run.turnId))
            "resend_update"
        }
        tx.report("cascade.task", mapOf("event" to "recovered", "running" to tx.s.tasks.runningCount, "how" to how))
    }

    /** Crash recovery left tasks whose prompt never left (session-first): start them again, as the same task. */
    override fun restored(tx: Tx) {
        tx.s.tasks.tasks.filter { it.relaunch }.forEach { t ->
            val text = t.runs.values.firstOrNull { it.kind == RunKind.Start }?.text ?: t.brief ?: t.label
            put(tx, t.copy(relaunch = false, runs = emptyMap(), active = 0, pendingUpdates = 0, storedSessionId = null, send = SendState.Sending, accepted = false))
            tx.tasks(tx.s.tasks.launch(t.id, text, tx.voiceContext(), RunKind.Start, tx.s.wallMs, t.turnId))
            // Updates that were waiting for that session wait again, in order.
            t.runs.toSortedMap().values.filter { it.kind != RunKind.Start && it.text != null }
                .forEach { r -> tx.tasks(tx.s.tasks.launch(t.id, r.text!!, tx.voiceContext(), r.kind, tx.s.wallMs, r.turnId)) }
            tx.report("cascade.task", mapOf("event" to "recovered", "running" to tx.s.tasks.runningCount, "how" to "relaunch"))
        }
    }

    /** "Not sent: tap to retry": the same task, submitted again (attempts start over). */
    private fun resend(tx: Tx, t: TaskRecord) {
        if (tx.tasks(tx.s.tasks.reopen(t.id, tx.s.settings.taskCap)) is UpdateResult.AtCap) {
            tx.say(AppLines.LIMIT_FULL)
            return
        }
        val reopened = tx.s.tasks[t.id] ?: return
        put(tx, reopened.copy(send = SendState.Sending, accepted = false, submitAttempts = 0, lastFailed = false))
        launch(tx, t.id)
        tx.report("cascade.task", mapOf("event" to "retry", "running" to tx.s.tasks.runningCount, "how" to "resend"))
    }

    private fun failedSubmit(tx: Tx, t: TaskRecord, message: String?) {
        val attempts = t.submitAttempts + 1
        if (message == TaskRecovery.UNCERTAIN || attempts >= tx.s.settings.submitAttempts.coerceAtLeast(1)) {
            put(tx, t.copy(send = SendState.NotSent, submitAttempts = attempts))
            tx.emit(CascadeEvent.TaskNotSent(t.id))
            tx.report("cascade.task", mapOf("event" to "not_sent", "running" to tx.s.tasks.runningCount, "attempts" to attempts), warn = true)
            if (!tx.s.started) tx.effects += Effect.Notify(NOTIFY_FAILED, t.id)
            return
        }
        // Withdraw the failure the task table queued: this is not the end of the task.
        tx.s.resultUtterances[t.id]?.let { u ->
            tx.drop(u)
            tx.s = tx.s.copy(resultUtterances = tx.s.resultUtterances - t.id)
        }
        tx.s = tx.s.copy(offCall = tx.s.offCall.copy(results = tx.s.offCall.results.filter { it.taskId != t.id }))
        put(tx, t.copy(submitAttempts = attempts, lastFailed = false))
        launch(tx, t.id)
        tx.report("cascade.task", mapOf("event" to "resubmit", "running" to tx.s.tasks.runningCount, "attempts" to attempts))
    }

    private fun launch(tx: Tx, id: String) {
        val t = tx.s.tasks[id] ?: return
        tx.tasks(tx.s.tasks.launch(id, t.brief ?: t.label, tx.voiceContext(), RunKind.Start, tx.s.wallMs))
    }

    private fun notifyOffCall(tx: Tx, t: TaskRecord, update: TurnUpdate) {
        val kind = when (update) {
            is TurnUpdate.ApprovalNeeded, is TurnUpdate.ClarifyNeeded -> NOTIFY_NEEDS_YOU
            is TurnUpdate.Completed -> when {
                t.cancelled || update.status == TurnStatus.Interrupted -> null
                update.status == TurnStatus.Error -> if (t.active <= 1) NOTIFY_FAILED else null
                else -> NOTIFY_DONE
            }
            is TurnUpdate.Failed -> if (!t.cancelled && t.active <= 1) NOTIFY_FAILED else null
            else -> null
        } ?: return
        tx.effects += Effect.Notify(kind, t.id)
    }

    private fun put(tx: Tx, record: TaskRecord) {
        tx.s = tx.s.copy(tasks = tx.s.tasks.with(record))
    }
}

/** [record] in place of the task with its id. */
fun TaskTable.with(record: TaskRecord): TaskTable = copy(tasks = tasks.map { if (it.id == record.id) record else it })
