package com.folio.hermeslive.tasks

import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskReply
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What happened to a submit. Anything but [Sent] leaves the brief held on the phone, to try again. */
sealed interface HeldSubmit {
    /** Hermes's task table has it (durably: the cascade sends it, and resends it after a restart). */
    data class Sent(val taskId: String, val queued: Boolean = false) : HeldSubmit

    /** No network now: try again when it is back. */
    data object Offline : HeldSubmit

    /**
     * Sending is not possible here and now, for a [reason] code: `bill_off` (Bill is not chosen
     * or has no usable OpenRouter key), `signed_out`, `no_reply` (the task table did not answer
     * in time; the same key is safe to retry). Not "offline": the screen says why.
     */
    data class NotAvailable(val reason: String) : HeldSubmit
}

/**
 * The phone's way into Hermes tasks for held work: meeting mode (one task per meeting's notes,
 * one per "Bill, start a task to …") and offline memos (one per memo). [key] makes it idempotent: the same key never starts a second task (a
 * retry after a kill, or a double tap, returns the same task). The app's is
 * [CascadeTaskSubmitter] (the shared task API on `CascadeCore.tasks`).
 */
fun interface TaskSubmitter {
    suspend fun submit(brief: String, label: String, key: String): HeldSubmit

    /**
     * Get ready to send, before any held data is read: the app's builds Bill's runtime here,
     * and building it wipes the store first when another account has signed in since
     * (`CascadeSignOut.ownedBy`). So a meeting or memo recorded for one account is gone before it
     * could be read for another. Default: nothing.
     */
    suspend fun prepare() = Unit

    companion object {
        /** Nothing connected: every submit is held. */
        val None = TaskSubmitter { _, _, _ -> HeldSubmit.NotAvailable("not_connected") }
    }
}

/**
 * The hold-and-send shape meeting mode (`MeetingOutbox`) and offline memos (`MemoSender`) share:
 * work the phone keeps until Hermes takes it as a task.
 *
 * - **One at a time:** [exclusive] runs its block under one lock, after [TaskSubmitter.prepare]
 *   (which may wipe the store for another account, so nothing is read before it).
 * - **Compiled at send time:** [send]'s `compile` hook builds the brief (a meeting's notes; a
 *   memo's transcript), or says why it can't yet ([Compiled.Hold]).
 * - **Idempotent:** the submit key is the caller's, so a retry after a kill or a lost reply never
 *   starts a second task.
 * - **Held, not lost:** anything but [Result.Sent] is [Result.Held] with a reason; the caller keeps
 *   it and tries again.
 */
class HeldTasks(private val tasks: () -> TaskSubmitter) {
    private val lock = Mutex()

    /** What the compile hook made. */
    sealed interface Compiled {
        data class Brief(val text: String) : Compiled

        /** Not yet: [reason] says why (`offline`, `server_old`, …). */
        data class Hold(val reason: String) : Compiled
    }

    sealed interface Result {
        data class Sent(val taskId: String, val queued: Boolean) : Result

        /** [reason]: `offline`, `signed_out`, `no_reply`, `failed`, or the compile hook's. */
        data class Held(val reason: String) : Result
    }

    /** Run [block] alone, after the task side got ready (see the class comment). */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock {
        runCatching { tasks().prepare() }
        block()
    }

    /** Compile, then submit with [key]. Call inside [exclusive]. */
    suspend fun send(key: String, label: String, compile: suspend () -> Compiled): Result {
        val brief = when (val c = compile()) {
            is Compiled.Hold -> return Result.Held(c.reason)
            is Compiled.Brief -> c.text
        }
        return when (val r = runCatching { tasks().submit(brief, label, key) }.getOrElse { HeldSubmit.NotAvailable("failed") }) {
            is HeldSubmit.Sent -> Result.Sent(r.taskId, r.queued)
            HeldSubmit.Offline -> Result.Held("offline")
            is HeldSubmit.NotAvailable -> Result.Held(r.reason)
        }
    }
}

/**
 * [TaskSubmitter] on the shared task API (`CascadeCore.tasks`, grok-voice slice `11cc01c`):
 * `startTask(brief, label, key)`. `Started` means the task is durably in Bill's task table: the
 * cascade sends it (now, or when the network is back) and resends it after a restart, and its
 * result arrives like any task (the card, the result notification, Hear it). The same key returns
 * the same task, so a retry never double-sends.
 *
 * [tasks] is null when Bill's runtime can't be built: not chosen in Settings › Voice, no usable
 * OpenRouter key, or signed out. [afterStart] keeps the gateway open while the task runs (the
 * off-call service, started while meeting mode is still in the foreground).
 */
class CascadeTaskSubmitter(
    private val tasks: suspend () -> CascadeTasks?,
    private val signedIn: () -> Boolean,
    private val afterStart: () -> Unit = {},
    /** The held reason when the runtime can't be built (meetings: `bill_off`; offline memos: `no_runtime`). */
    private val noRuntime: String = "bill_off",
) : TaskSubmitter {
    /** Build Bill's runtime now (its account check may wipe the store) before the outbox reads a meeting. */
    override suspend fun prepare() {
        if (signedIn()) tasks()
    }

    override suspend fun submit(brief: String, label: String, key: String): HeldSubmit {
        if (!signedIn()) return HeldSubmit.NotAvailable("signed_out")
        val api = tasks() ?: return HeldSubmit.NotAvailable(noRuntime)
        return when (val r = api.startTask(brief, label, summary = null, key = key)) {
            is TaskReply.Started -> HeldSubmit.Sent(r.taskId, r.queued).also { runCatching(afterStart) }
            TaskReply.NoReply, TaskReply.NotReady -> HeldSubmit.NotAvailable("no_reply")
            else -> HeldSubmit.NotAvailable("refused")
        }
    }
}
