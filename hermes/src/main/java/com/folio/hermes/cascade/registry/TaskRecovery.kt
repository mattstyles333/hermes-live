package com.folio.hermes.cascade.registry

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.CascadeActor
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.ports.HermesPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What Hermes knows about a re-bound session's last turn (S-F implements [SessionRecovery] on the gateway). */
sealed interface SessionProbe {
    /** The session never received a prompt: sending again is safe. */
    data object NeverPrompted : SessionProbe

    /** A turn is still running: [updates] streams it to `Completed` or `Failed`. */
    data class Running(val updates: Flow<TurnUpdate>) : SessionProbe

    /** The last turn ended with [text]. */
    data class Ended(val text: String, val status: TurnStatus) : SessionProbe

    /** Hermes cannot say (session gone, unreachable). */
    data object Unknown : SessionProbe
}

/**
 * Reads a stored session's last turn after `session.resume` (R-DUR-3). Not a `HermesPort` method
 * (the port is frozen); `:live` implements it on the gateway: `session.resume` with messages, or
 * `running` plus the session's events until `message.complete`. Must not submit anything.
 */
fun interface SessionRecovery {
    suspend fun probe(taskId: String, storedSessionId: String): SessionProbe
}

/**
 * Re-binds and recovers tasks restored from the store (R-DUR-3). Run it once per actor, after
 * [CascadeActor.start]: it waits for the first started state, then for each running task bound
 * to a stored session it
 * 1. re-binds the session: `HermesPort.open(taskId, label, storedSessionId)` (`session.resume` on use);
 * 2. asks [SessionRecovery] how its last turn ended;
 * 3. posts the outcome as ordinary events on the task's stored run ids ([Event.TaskUpdated],
 *    then [Event.TaskRunEnded] per run), so the reducer applies it like a live update.
 *
 * | probe | task was `Accepted` | task was `Sending` |
 * |---|---|---|
 * | Ended | `Completed` | `Accepted`, then `Completed` |
 * | Running | the stream | `Accepted`, then the stream |
 * | NeverPrompted | failed ([LOST]) | failed submit ([NOT_RECEIVED]: resent once, R-DUR-4) |
 * | Unknown | failed ([LOST]) | `NotSent` ([UNCERTAIN]: never resent on its own) |
 *
 * Tasks with no stored session were already settled by restore (`NotSent` or `Failed`).
 */
class TaskRecovery(private val hermes: HermesPort, private val sessions: SessionRecovery) {
    /** Recover on [scope]; events go to [actor]. */
    fun launch(scope: CoroutineScope, actor: CascadeActor): Job = scope.launch {
        val state = actor.state.first { it.started }
        candidates(state).forEach { task -> launch { recover(task, actor::send) } }
    }

    /** One task: re-bind, probe, post. */
    suspend fun recover(task: TaskRecord, post: (Event) -> Unit) {
        val stored = task.storedSessionId ?: return
        val runs = task.runs.keys.sorted()
        val last = runs.lastOrNull() ?: return
        hermes.open(task.id, task.label, stored)
        val probe = try {
            sessions.probe(task.id, stored)
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            SessionProbe.Unknown
        }
        fun update(u: TurnUpdate) = post(Event.TaskUpdated(task.id, last, u))
        val accepted = task.send == SendState.Accepted || task.accepted
        when (probe) {
            SessionProbe.NeverPrompted -> update(TurnUpdate.Failed(if (accepted) LOST else NOT_RECEIVED))
            SessionProbe.Unknown -> update(TurnUpdate.Failed(if (accepted) LOST else UNCERTAIN))
            is SessionProbe.Ended -> {
                if (!accepted) update(TurnUpdate.Accepted(stored, SubmitStatus.Streaming))
                update(TurnUpdate.Started)
                update(TurnUpdate.Completed(probe.text, probe.status, null))
            }
            is SessionProbe.Running -> {
                if (!accepted) update(TurnUpdate.Accepted(stored, SubmitStatus.Streaming))
                try {
                    probe.updates.collect { update(it) }
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    update(TurnUpdate.Failed(LOST))
                }
            }
        }
        runs.forEach { post(Event.TaskRunEnded(task.id, it)) }
    }

    companion object {
        /** The session never got the prompt: a failed submit (resent once). */
        const val NOT_RECEIVED = "recovery:not_received"

        /** Nobody can tell whether the prompt arrived: `NotSent`, never resent on its own. */
        const val UNCERTAIN = "recovery:uncertain"

        /** An accepted task whose outcome cannot be read: it fails (retry with R-TASK-7). */
        const val LOST = "recovery:lost"

        /** Running tasks restored with a stored session and runs to recover. */
        fun candidates(state: State): List<TaskRecord> =
            state.tasks.tasks.filter { it.state == TaskState.Running && it.storedSessionId != null && it.runs.isNotEmpty() }
    }
}
