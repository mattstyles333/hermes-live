package com.folio.hermes.cascade.writer

import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState

/** Writer prompts for CEO mode's spoken lines (results, status). Every line: plain speech, short. */
object CeoPrompts {
    /**
     * R-TASK-5: a result as a briefing, a headline plus any decision needed, in at most
     * [sentences] sentences. The full text stays on the card; "read it all" reads everything.
     */
    fun result(label: String, text: String, sentences: Int): String =
        "The task \"$label\" finished. Its result is below. Brief the user in at most $sentences sentences: " +
            "the headline first, then any decision they need to make. Do not read details, lists or figures that are not the headline. " +
            "If there is more, end with \"Full answer's on screen.\" within those $sentences sentences.\n\nResult:\n${text.take(6_000)}"

    /**
     * R-TASK-3: status from the phone's event log only (never a prompt to a running task).
     * [focus] limits it to one task; queued tasks have not started.
     */
    fun status(tasks: List<TaskRecord>, focus: String?, nowMs: Long, queued: Set<String>): String = buildString {
        append("Say how the user's tasks are getting on, from this log only. Do not invent progress. At most 3 sentences.\n")
        tasks.filter { focus == null || it.id == focus }.forEach { t ->
            append("- ").append(t.label).append(": ")
            when {
                t.id in queued -> append("queued, not started (waiting for a free slot)")
                else -> {
                    append(t.state.wire)
                    if (t.state == TaskState.Running && t.waiting) append(", waiting on the user's approval or answer (on screen)")
                    append(", ").append(t.steps).append(" steps")
                    t.currentTool?.let { append(", now using ").append(it) }
                    append(", running ").append(((nowMs - t.startedAt) / 1000).coerceAtLeast(0)).append(" s")
                    when (val e = t.events.lastOrNull()) {
                        is TaskEvent.Interim -> append(", last note: \"").append(e.text.take(200)).append('"')
                        else -> Unit
                    }
                    val tail = t.progress.takeLast(300).trim()
                    if (tail.isNotEmpty()) append(", latest text: \"").append(tail).append('"')
                }
            }
            append('\n')
        }
    }
}
