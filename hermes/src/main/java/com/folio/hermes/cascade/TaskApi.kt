package com.folio.hermes.cascade

import com.folio.hermes.TurnUpdate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/*
 * The shared task API (grok-voice slice, 2026-10-14): a front end that is not Jev (the Grok
 * voice engine, a meeting mode) drives Hermes tasks through the same actor as Bill, so
 * durability (persist before submit, session-first idempotency, crash recovery), the registry,
 * the task tray and the notifications are unchanged. Every command is an [Event.TaskApi]; its
 * reply comes back as [Effect.TaskApiReply] after the step's durable writes, so a `Started`
 * reply means the task is stored. Reads ([CascadeTasks.status], [CascadeTasks.readResult]) come
 * from the phone's task table and event log only: they never touch Hermes (R-TASK-3).
 */

/** What a front end asks of the task table. [key] makes a command idempotent (Grok's `call_id`). */
sealed interface TaskCommand {
    val key: String

    /** A new task: [brief] goes to Hermes as written, [label] (2–4 words) names its card, [summary] is its one-line confirmation. */
    data class Start(val brief: String, val label: String, val summary: String?, override val key: String) : TaskCommand

    /** Pass [text] to task [taskId]: queued after its current step, or a redirect (interrupt, then the text). */
    data class Update(val taskId: String, val text: String, val mode: UpdateMode, override val key: String) : TaskCommand

    /** Stop task [taskId], or every running and queued task with [CascadeTasks.ALL]. */
    data class Cancel(val taskId: String, override val key: String) : TaskCommand

    /** The front end told the user task [taskId]'s result or request: mark it read and drop it from the off-call backlog. */
    data class Heard(val taskId: String, override val key: String) : TaskCommand
}

/** A command's outcome. */
sealed interface TaskReply {
    /** [queued]: over the cap, it waits on the phone for a free slot. [existing]: the key had already started it. */
    data class Started(val taskId: String, val label: String, val queued: Boolean, val existing: Boolean = false) : TaskReply

    /** [how]: `queue`, `interrupt_first`, `amended` (not started yet), `retried` (a failed task, as new task [taskId]) or `already_sent` (same key). */
    data class Updated(val taskId: String, val how: String) : TaskReply

    /** The tasks stopped (empty: nothing was running). */
    data class Cancelled(val taskIds: List<String>) : TaskReply

    /** The task had already finished. */
    data class TooLate(val taskId: String) : TaskReply
    data class NotFound(val taskId: String) : TaskReply

    /** Every slot is busy and the task cannot take a follow-up now. */
    data object AtCap : TaskReply
    data object Noted : TaskReply

    /** The stored state is not read back yet (the API restores first, so only a race with a dying actor sees this). */
    data object NotReady : TaskReply

    /** No reply in time (the actor stopped, or the process is dying). Retrying with the same key is safe. */
    data object NoReply : TaskReply
}

/** A task outcome for the front end, as it happens (with no Bill call open). */
sealed interface TaskNotice {
    val taskId: String
    val label: String

    /** The task finished ([ok]) or failed; [text] is the whole result (empty on failure). */
    data class Finished(override val taskId: String, override val label: String, val text: String, val ok: Boolean) : TaskNotice

    /** The task waits for the user: [kind] `approval` or `clarify`. Approvals are tapped on screen, never spoken. */
    data class NeedsYou(override val taskId: String, override val label: String, val kind: String) : TaskNotice

    /** A pending approval or question was withdrawn. */
    data class Withdrawn(override val taskId: String, override val label: String) : TaskNotice
}

/** One task's status, from the phone's event log only. */
data class TaskView(
    val id: String,
    val label: String,
    /** `queued`, `running`, `waiting_on_user`, `not_sent`, `done`, `failed` or `cancelled`. */
    val status: String,
    val lastEvent: String,
    val elapsedS: Long,
    val steps: Int,
    val currentTool: String?,
    /** The agent's last interim note (≤ 200 characters). */
    val lastNote: String?,
    val hasResult: Boolean,
    val unread: Boolean,
)

/**
 * The task API on one [CascadeActor] (`CascadeCore.tasks`). [restore] reads the stored state
 * back first (idempotent; nothing during a Bill call); [timeoutMs] bounds every reply.
 */
class CascadeTasks internal constructor(
    private val actor: CascadeActor,
    private val restore: () -> Unit,
    private val timeoutMs: Long = REPLY_TIMEOUT_MS,
) {
    /** Results, approvals and withdrawals as they arrive with no Bill call open (hot: read [status] for what came before). */
    val notices: SharedFlow<TaskNotice> get() = actor.notices

    suspend fun startTask(brief: String, label: String, summary: String? = null, key: String = newKey()): TaskReply =
        send(TaskCommand.Start(brief, label, summary, key))

    suspend fun updateTask(taskId: String, text: String, mode: UpdateMode = UpdateMode.Queue, key: String = newKey()): TaskReply =
        send(TaskCommand.Update(taskId, text, mode, key))

    /** [taskId] or [ALL]. */
    suspend fun cancelTask(taskId: String, key: String = newKey()): TaskReply = send(TaskCommand.Cancel(taskId, key))

    /** The front end told the user about [taskId] (its result or request): read, and never replayed by Bill later. */
    suspend fun heard(taskId: String): TaskReply = send(TaskCommand.Heard(taskId, newKey()))

    /** Every task (or just [taskId]), oldest first, from the event log only. */
    fun status(taskId: String? = null): List<TaskView> = views(actor.state.value, taskId)

    /**
     * What the user has not been told yet: finished tasks with an unread result (or an unread
     * failure), and running tasks waiting on them. For a front end that attaches after they arrived.
     */
    suspend fun unheard(): List<TaskNotice> {
        ready()
        return actor.state.value.tasks.tasks.mapNotNull { t ->
            when {
                t.state == TaskState.Running && t.waiting -> TaskNotice.NeedsYou(t.id, t.label, if (t.events.lastOrNull().let { it is TaskEvent.Waiting && it.what == "clarify" }) "clarify" else "approval")
                t.unread && t.state == TaskState.Done -> TaskNotice.Finished(t.id, t.label, t.result.orEmpty(), ok = true)
                else -> null
            }
        }
    }

    /**
     * Read the stored state back (no call, nothing spoken) and wait for it, at most [timeoutMs].
     * True once the task table is the stored one. In a fresh process [status] is empty until this
     * (or any command) has run.
     */
    suspend fun ready(timeoutMs: Long = READY_TIMEOUT_MS): Boolean {
        restore()
        return withTimeoutOrNull(timeoutMs) { actor.state.first { it.restored } } != null
    }

    /** Task [taskId]'s whole result, or null (no such task, or no result yet). Marks it heard. */
    suspend fun readResult(taskId: String): String? {
        val result = actor.state.value.tasks[taskId]?.result ?: return null
        heard(taskId)
        return result
    }

    private suspend fun send(command: TaskCommand): TaskReply {
        restore()
        val reply = actor.command(command)
        return try {
            withTimeout(timeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            actor.forget(reply)
            TaskReply.NoReply
        }
    }

    companion object {
        /** [cancelTask]'s "every task". */
        const val ALL = "all"
        const val REPLY_TIMEOUT_MS = 10_000L
        const val READY_TIMEOUT_MS = 3_000L

        fun newKey(): String = UUID.randomUUID().toString()

        fun views(state: State, taskId: String? = null): List<TaskView> =
            state.tasks.tasks.filter { taskId == null || it.id == taskId }.map { it.view(state.wallMs) }

        private fun TaskRecord.view(nowMs: Long): TaskView {
            val queuedHere = state == TaskState.Running && runs.isEmpty() && (queued || events.isEmpty())
            val status = when {
                queuedHere -> "queued"
                send == SendState.NotSent -> "not_sent"
                state == TaskState.Running && waiting -> "waiting_on_user"
                else -> state.wire
            }
            return TaskView(
                id = id,
                label = label,
                status = status,
                lastEvent = if (queuedHere) "queued" else snapshot().lastEvent,
                elapsedS = (((finishedAt ?: nowMs) - startedAt) / 1000).coerceAtLeast(0),
                steps = steps,
                currentTool = currentTool,
                lastNote = events.filterIsInstance<TaskEvent.Interim>().lastOrNull()?.text?.take(200),
                hasResult = !result.isNullOrBlank(),
                unread = unread,
            )
        }

        /** The notices for what one step added to the off-call backlog (results and requests). */
        internal fun added(before: OffCallState, after: OffCallState): List<TaskNotice> {
            val results = after.results.minusEach(before.results).map { TaskNotice.Finished(it.taskId, it.label, it.text, it.ok) }
            val requests = after.requests.minusEach(before.requests).mapNotNull { r ->
                when (r.update) {
                    is TurnUpdate.ApprovalNeeded -> TaskNotice.NeedsYou(r.taskId, r.label, "approval")
                    is TurnUpdate.ClarifyNeeded -> TaskNotice.NeedsYou(r.taskId, r.label, "clarify")
                    is TurnUpdate.RequestWithdrawn -> TaskNotice.Withdrawn(r.taskId, r.label)
                    else -> null
                }
            }
            return results + requests
        }

        /** The entries of this list not matched one-for-one in [other]. */
        private fun <T> List<T>.minusEach(other: List<T>): List<T> {
            if (size >= other.size && subList(0, other.size) == other) return drop(other.size)
            val left = other.toMutableList()
            return filter { !left.remove(it) }
        }
    }
}
