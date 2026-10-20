package com.folio.hermeslive.meeting

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The hold-and-send shape meeting mode ([MeetingOutbox]) and offline memos (`MemoSender`) share:
 * work the phone keeps until Hermes takes it as a task.
 *
 * - **One at a time:** [exclusive] runs its block under one lock, after [MeetingTasks.prepare]
 *   (which may wipe the store for another account, so nothing is read before it).
 * - **Compiled at send time:** [send]'s `compile` hook builds the brief (a meeting's notes; a
 *   memo's transcript), or says why it can't yet ([Compiled.Hold]).
 * - **Idempotent:** the submit key is the caller's, so a retry after a kill or a lost reply never
 *   starts a second task.
 * - **Held, not lost:** anything but [Result.Sent] is [Result.Held] with a reason; the caller keeps
 *   it and tries again.
 */
class HeldTasks(private val tasks: () -> MeetingTasks) {
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
        return when (val r = runCatching { tasks().submit(brief, label, key) }.getOrElse { MeetingSubmit.NotAvailable("failed") }) {
            is MeetingSubmit.Sent -> Result.Sent(r.taskId, r.queued)
            MeetingSubmit.Offline -> Result.Held("offline")
            is MeetingSubmit.NotAvailable -> Result.Held(r.reason)
        }
    }
}
