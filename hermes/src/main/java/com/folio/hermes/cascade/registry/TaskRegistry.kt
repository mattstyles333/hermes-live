package com.folio.hermes.cascade.registry

import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.Tx

/**
 * The persisted task registry (R-DUR-2/3; CEO §1; S-B owns this package): task id, Hermes stored
 * session id, label, brief, status, timestamps and result, so tasks survive the app and belong to
 * the user, not the conversation.
 *
 * - [taskForTurn]: the task a turn already created (a submit is idempotent per turn id; R-DUR-2).
 * - [entry] / [record]: the stored form of a task and back (S-B chooses the encoding).
 * - On start, unfinished tasks are re-bound with `session.resume` (`Effect.SubmitTask` and
 *   `HermesPort.open` take the stored session id) and their final state recovered (R-DUR-3).
 * - [taskUpdated]: every task update (count submit attempts, `NotSent` after two failures; R-DUR-4).
 * - [retry]: "Not sent: tap to retry" and retrying a failed task with the same brief (R-DUR-4, R-TASK-7).
 *
 * Interface only in S0; [None] keeps C1's behaviour (no registry).
 */
interface TaskRegistry {
    fun taskForTurn(state: State, turnId: String): String?

    fun entry(task: TaskRecord): String

    fun record(entry: String): TaskRecord?

    fun taskUpdated(tx: Tx, event: Event.TaskUpdated) = Unit

    fun retry(tx: Tx, taskId: String) = Unit

    /**
     * Recovery found that Hermes never got run [Event.RunNotReceived.runId] (R-DUR-2): send it
     * again, once. Default: the run just ends (no registry, nothing is recovered).
     */
    fun notReceived(tx: Tx, event: Event.RunNotReceived) {
        tx.tasks(tx.s.tasks.forget(event.taskId, event.runId))
    }

    /** The stored state was read back for the first time (a call start or the off-call restore): settle what crash recovery left. */
    fun restored(tx: Tx) = Unit

    companion object {
        val None: TaskRegistry = object : TaskRegistry {
            override fun taskForTurn(state: State, turnId: String): String? =
                state.tasks.tasks.firstOrNull { it.turnId == turnId }?.id

            override fun entry(task: TaskRecord): String = ""

            override fun record(entry: String): TaskRecord? = null
        }
    }
}
