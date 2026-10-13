package com.folio.hermeslive.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one path every data-channel write takes, in order. A `session.commentary.append` asks
 * [leadFor] first; when music was playing it waits that long (focus taken, music pausing) before
 * it goes, and everything sent after it (thinking, mute, close) queues behind it, so nothing
 * overtakes it and it never overtakes a close. Single-threaded ([scope] on Main).
 */
class OrderedSender(
    private val scope: CoroutineScope,
    private val transportSend: (String) -> Boolean,
    private val leadFor: () -> Long,
) {
    private val queue = ArrayDeque<String>()
    private var waiting: Job? = null

    /** Number of messages held back. */
    val pending: Int get() = queue.size

    /** Send or queue [message]. Returns false only when a direct send failed. */
    fun send(message: String): Boolean {
        if (waiting?.isActive == true) {
            queue.addLast(message)
            return true
        }
        if (isCommentary(message)) {
            val lead = leadFor()
            if (lead > 0) {
                queue.addLast(message)
                waiting = scope.launch {
                    delay(lead)
                    flush()
                }
                return true
            }
        }
        return transportSend(message)
    }

    /** Drop anything queued (the transport is gone). */
    fun clear() {
        waiting?.cancel()
        waiting = null
        queue.clear()
    }

    private fun flush() {
        waiting = null
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            // A later commentary after the lead does not wait again: focus is already held.
            transportSend(next)
        }
    }

    companion object {
        fun isCommentary(message: String): Boolean =
            message.contains("\"type\":\"session.commentary.append\"")
    }
}
