package com.folio.hermeslive.meeting

/** What happened to a submit. Anything but [Sent] leaves the brief held on the phone, to try again. */
sealed interface MeetingSubmit {
    /** Hermes's task table has it (durably: the cascade sends it, and resends it after a restart). */
    data class Sent(val taskId: String, val queued: Boolean = false) : MeetingSubmit

    /** No network now: try again when it is back. */
    data object Offline : MeetingSubmit

    /**
     * Sending is not possible here and now, for a [reason] code: `bill_off` (Bill is not chosen
     * or has no usable OpenRouter key), `signed_out`, `no_reply` (the task table did not answer
     * in time; the same key is safe to retry). Not "offline": the screen says why.
     */
    data class NotAvailable(val reason: String) : MeetingSubmit
}

/**
 * Meeting mode's way into Hermes tasks: one agent task per meeting's notes, and one per "Bill,
 * start a task to …". [key] makes it idempotent: the same key never starts a second task (a
 * retry after a kill, or a double tap, returns the same task). The app's is
 * [CascadeMeetingTasks] (the shared task API on `CascadeCore.tasks`).
 */
fun interface MeetingTasks {
    suspend fun submit(brief: String, label: String, key: String): MeetingSubmit

    /**
     * Get ready to send, before any meeting data is read: the app's builds Bill's runtime here,
     * and building it wipes the store first when another account has signed in since
     * (`CascadeSignOut.ownedBy`). So a meeting recorded for one account is gone before it could
     * be read for another. Default: nothing.
     */
    suspend fun prepare() = Unit

    companion object {
        /** Nothing connected: every submit is held. */
        val None = MeetingTasks { _, _, _ -> MeetingSubmit.NotAvailable("not_connected") }
    }
}
