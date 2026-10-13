package com.folio.hermes.cascade.registry

import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord

/**
 * The persisted task registry (R-DUR-2/3; CEO §1; S-B owns this package): task id, Hermes stored
 * session id, label, brief, status, timestamps and result, so tasks survive the app and belong to
 * the user, not the conversation.
 *
 * - [taskForTurn]: the task a turn already created (a submit is idempotent per turn id; R-DUR-2).
 * - [entry] / [record]: the stored form of a task and back (S-B chooses the encoding).
 * - On start, unfinished tasks are re-bound with `session.resume` (`Effect.SubmitTask` and
 *   `HermesPort.open` take the stored session id) and their final state recovered (R-DUR-3).
 *
 * Interface only in S0; [None] keeps C1's behaviour (no registry).
 */
interface TaskRegistry {
    fun taskForTurn(state: State, turnId: String): String?

    fun entry(task: TaskRecord): String

    fun record(entry: String): TaskRecord?

    companion object {
        val None: TaskRegistry = object : TaskRegistry {
            override fun taskForTurn(state: State, turnId: String): String? =
                state.tasks.tasks.firstOrNull { it.turnId == turnId }?.id

            override fun entry(task: TaskRecord): String = ""

            override fun record(entry: String): TaskRecord? = null
        }
    }
}
