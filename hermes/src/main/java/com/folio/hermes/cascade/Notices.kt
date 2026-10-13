package com.folio.hermes.cascade

import com.folio.hermes.cascade.registry.DurableTaskRegistry
import com.folio.hermes.cascade.writer.Speakable

/**
 * What a result notification shows for one task (R-CONV-3; spec v2 §14 item 12). `:live` maps
 * `Effect.Notify(kind, taskId)` to its notification with [notifyInfo]. The notification is
 * private (`VISIBILITY_PRIVATE`), so the headline may carry the result's first sentence; it
 * never goes to telemetry.
 */
data class NotifyInfo(
    val taskId: String,
    /** The task's 2–4 word label. */
    val label: String,
    /** One line: the result's first sentence (≤ [Notices.HEADLINE_CHARS]), or what the task needs. */
    val headline: String,
    /** The notify kind, when known (`task_done`, `task_failed`, `task_needs_you`). */
    val kind: String? = null,
)

object Notices {
    const val HEADLINE_CHARS = 120

    /** The first sentence of [result], cleaned for reading, cut to [HEADLINE_CHARS]. */
    fun headline(result: String): String {
        val first = Speakable.sentences(result).firstOrNull() ?: return ""
        return if (first.length <= HEADLINE_CHARS) first else first.take(HEADLINE_CHARS - 1).trimEnd() + "…"
    }
}

/**
 * The label and headline for task [taskId] in [state] (null: no such task). [kind] picks the
 * wording when the app has it from `Effect.Notify`; otherwise it follows the task's state.
 */
fun notifyInfo(state: State, taskId: String, kind: String? = null): NotifyInfo? {
    val t = state.tasks[taskId] ?: return null
    val headline = when {
        kind == DurableTaskRegistry.NOTIFY_NEEDS_YOU || (kind == null && t.waiting) -> "Needs you: open it to answer."
        t.send == SendState.NotSent -> "Not sent. Tap to retry."
        kind == DurableTaskRegistry.NOTIFY_FAILED || t.state == TaskState.Failed -> "Didn't finish. Tap to retry."
        !t.result.isNullOrBlank() -> Notices.headline(t.result).ifEmpty { "Done." }
        t.state == TaskState.Cancelled -> "Cancelled."
        t.state == TaskState.Done -> "Done."
        else -> "Still working."
    }
    return NotifyInfo(t.id, t.label, headline, kind)
}
