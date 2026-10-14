package com.folio.hermes.realtime

import com.folio.hermes.cascade.TaskView

/** One exchange of a realtime call: what the user said and what the voice answered (in memory only). */
data class SpokenExchange(val user: String, val reply: String)

/**
 * The short note a fresh realtime session gets when it could not resume the conversation (the
 * ondemand slice: xAI keeps a resumable conversation for 30 minutes of inactivity; GPT-Live
 * cannot resume). The conversation summary, the last few exchanges, and every open or unread
 * task with its id, so the model can carry on and still use the task tools. Bounded; never logged.
 */
object ContextNote {
    const val MAX_CHARS = 2_000
    const val MAX_EXCHANGES = 4
    private const val LINE_CHARS = 240

    fun build(summary: String?, recent: List<SpokenExchange>, tasks: List<TaskView>): String? {
        val parts = mutableListOf<String>()
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "Summary so far: ${it.take(600)}" }
        val last = recent.filter { it.user.isNotBlank() || it.reply.isNotBlank() }.takeLast(MAX_EXCHANGES)
        if (last.isNotEmpty()) {
            parts += "Last exchanges:\n" + last.joinToString("\n") { e ->
                listOfNotNull(
                    e.user.trim().takeIf { it.isNotEmpty() }?.let { "User: ${it.take(LINE_CHARS)}" },
                    e.reply.trim().takeIf { it.isNotEmpty() }?.let { "You: ${it.take(LINE_CHARS)}" },
                ).joinToString("\n")
            }
        }
        val shown = tasks.filter { it.status in OPEN || it.unread }
        if (shown.isNotEmpty()) {
            parts += "Tasks: " + shown.joinToString("; ") { t ->
                "${t.id} \"${t.label}\" ${t.status}" + if (t.unread) " (result not yet told to the user)" else ""
            } + "."
        }
        if (parts.isEmpty()) return null
        val body = "The voice session was reconnected and you lost the earlier conversation. Context:\n" + parts.joinToString("\n") +
            "\nDon't mention the reconnection. Carry on from here."
        return if (body.length <= MAX_CHARS) body else body.take(MAX_CHARS - 1) + "…"
    }

    private val OPEN = setOf("queued", "running", "waiting_on_user", "not_sent")
}
