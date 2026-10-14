package com.folio.hermes.cascade.registry

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.CascadeActor
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.RunKind
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

/**
 * What Hermes knows about a re-bound session's last turn ([com.folio.hermes.cascade.ports.GatewaySessionRecovery]
 * on the gateway). [prompts] is the user text the session holds (stored messages plus a live or
 * queued one), oldest first; null when Hermes cannot say.
 */
sealed interface SessionProbe {
    val prompts: List<String>? get() = null

    /** The session never received a prompt: sending again is safe. */
    data object NeverPrompted : SessionProbe {
        override val prompts: List<String> get() = emptyList()
    }

    /** A turn is still running: [updates] streams it to `Completed` or `Failed`. */
    data class Running(val updates: Flow<TurnUpdate>, override val prompts: List<String>? = null) : SessionProbe

    /** The last turn ended with [text]. */
    data class Ended(val text: String, val status: TurnStatus, override val prompts: List<String>? = null) : SessionProbe

    /** Hermes cannot say (session gone, unreachable). */
    data object Unknown : SessionProbe
}

/**
 * Reads a stored session's last turn after `session.resume` (R-DUR-3). Not a `HermesPort` method
 * (the port is frozen); [com.folio.hermes.cascade.ports.GatewaySessionRecovery] implements it on
 * the gateway. Must not submit anything.
 */
fun interface SessionRecovery {
    suspend fun probe(taskId: String, storedSessionId: String): SessionProbe
}

/**
 * Re-binds and recovers tasks restored from the store (R-DUR-3). Run it once per actor (the
 * cascade factory does): it waits for the first restored state (a call start or the off-call
 * [CascadeActor.restore]), then for each running task bound to a stored session it
 * 1. re-binds the session: `HermesPort.open(taskId, label, storedSessionId)` (`session.resume` on use);
 * 2. asks [SessionRecovery] how its last turn ended and which prompts it holds;
 * 3. posts the outcome as ordinary events on the task's stored run ids.
 *
 * Exact idempotency (R-DUR-2): the stored session id is durable before any prompt leaves, so
 * - a run Hermes never got is sent again, once ([Event.RunNotReceived]): a start run whose session
 *   was **never prompted**, or an update run whose text is not among [SessionProbe.prompts];
 * - a run Hermes did get is followed, never sent again.
 *
 * | probe | task was `Accepted` | task was `Sending` |
 * |---|---|---|
 * | Ended | `Completed` | `Accepted`, then `Completed` |
 * | Running | the stream | `Accepted`, then the stream |
 * | NeverPrompted | failed ([LOST]) | sent again ([Event.RunNotReceived]) |
 * | Unknown | failed ([LOST]) | `NotSent` ([UNCERTAIN]: never resent on its own) |
 *
 * Tasks with no stored session were settled by restore: never prompted (session-first), so the
 * registry starts them again; or, from a port that cannot create ahead, `NotSent`.
 */
class TaskRecovery(private val hermes: HermesPort, private val sessions: SessionRecovery) {
    /** Recover on [scope]; events go to [actor]. */
    fun launch(scope: CoroutineScope, actor: CascadeActor): Job = scope.launch {
        val state = actor.state.first { it.restored }
        candidates(state).forEach { task -> launch { recover(task, actor::send) } }
    }

    /** One task: re-bind, probe, post. */
    suspend fun recover(task: TaskRecord, post: (Event) -> Unit) {
        val stored = task.storedSessionId ?: return
        if (task.runs.isEmpty()) return
        hermes.open(task.id, task.label, stored)
        val probe = try {
            sessions.probe(task.id, stored)
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            SessionProbe.Unknown
        }
        val accepted = task.send == SendState.Accepted || task.accepted
        val runs = task.runs.toSortedMap()
        val prompts = probe.prompts
        // Updates Hermes never got go again; updates it got are accepted now.
        val unsent = runs.filter { (_, r) -> r.kind != RunKind.Start && !r.accepted && r.text != null && prompts != null && !received(prompts, r.text) }.keys
        unsent.forEach { post(Event.RunNotReceived(task.id, it)) }
        val rest = runs.filterKeys { it !in unsent }
        if (rest.isEmpty()) return
        rest.filter { (_, r) -> r.kind != RunKind.Start && !r.accepted }.keys
            .forEach { post(Event.TaskUpdated(task.id, it, TurnUpdate.Accepted(stored, SubmitStatus.Queued))) }
        val last = rest.keys.max()
        fun update(u: TurnUpdate) = post(Event.TaskUpdated(task.id, last, u))
        when (probe) {
            SessionProbe.NeverPrompted -> if (accepted) update(TurnUpdate.Failed(LOST)) else {
                rest.keys.forEach { post(Event.RunNotReceived(task.id, it)) }
                return
            }
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
        rest.keys.forEach { post(Event.TaskRunEnded(task.id, it)) }
    }

    companion object {
        /** The session never got the prompt: a failed submit (resent once). Kept for stored markers; recovery now posts [Event.RunNotReceived]. */
        const val NOT_RECEIVED = "recovery:not_received"

        /** Nobody can tell whether the prompt arrived: `NotSent`, never resent on its own. */
        const val UNCERTAIN = "recovery:uncertain"

        /** An accepted task whose outcome cannot be read: it fails (retry with R-TASK-7). */
        const val LOST = "recovery:lost"

        /** Running tasks restored with a stored session and runs to recover (a task queued on the phone has no runs: never resumed). */
        fun candidates(state: State): List<TaskRecord> =
            state.tasks.tasks.filter { it.state == TaskState.Running && it.storedSessionId != null && it.runs.isNotEmpty() }

        /** True if Hermes holds [text] among [prompts] (whitespace-insensitive). */
        fun received(prompts: List<String>, text: String): Boolean {
            val want = norm(text)
            return prompts.any { norm(it) == want }
        }

        private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")
    }
}
