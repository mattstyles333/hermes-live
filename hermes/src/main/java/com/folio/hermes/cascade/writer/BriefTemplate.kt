package com.folio.hermes.cascade.writer

/**
 * The transcript block every brief ends with (R-STT-6, v1 CEO §7). [transcript] is the repaired
 * text (or the verbatim text when nothing was repaired); [asHeard] is the raw text with
 * `[word|alt]` marks; Hermes therefore always gets the full verbatim dictation (R-CTX-3).
 */
data class TranscriptBlock(
    val transcript: String,
    val asHeard: String,
    /** Jev's clarity answer and confidence, e.g. `minor_doubt (0.72)`. */
    val clarity: String,
    val notes: String,
    /** "Dictated offline 14:32–14:41." for memo turns (R-OFF-4). */
    val offline: String? = null,
)

/**
 * Assembles the text a task's `prompt.submit` carries: the v1 §CEO-2 template (the writer's
 * Goal/Constraints/Done-when lines plus the fixed report-back line), then the transcript block
 * and the ask-before-external-actions line.
 */
object BriefTemplate {
    const val REPORT_BACK = "Report back: one-line headline first, then any decision needed from me, then details."
    const val ASK_FIRST =
        "If an uncertain name, number or date would change an external action (email, order, payment), ask me first (clarify) instead of guessing."

    /** The goal of a brief the writer could not write: the user's own words are below. */
    const val FALLBACK_GOAL = "Goal: Do what the user asked in the transcript below."

    fun build(jobLines: String?, block: TranscriptBlock, part: Int = 1, parts: Int = 1, others: List<String> = emptyList()): String = buildString {
        val lines = jobLines?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("Report back", ignoreCase = true) }.orEmpty()
        if (lines.isEmpty()) append(FALLBACK_GOAL).append('\n') else lines.forEach { append(it).append('\n') }
        if (parts > 1) {
            append("Scope: job ").append(part).append(" of ").append(parts).append(" from one dictation")
            if (others.isNotEmpty()) append("; the others (").append(others.joinToString("; ")).append(") are separate tasks, so leave them")
            append(".\n")
        }
        append(REPORT_BACK).append("\n\n")
        append(block(block))
    }

    fun block(b: TranscriptBlock): String = buildString {
        append("Transcript: ").append(b.transcript).append('\n')
        append("As heard: ").append(b.asHeard).append('\n')
        append("Clarity: ").append(b.clarity).append('\n')
        append("Notes: ").append(b.notes.ifBlank { "none" }).append('\n')
        b.offline?.let { append(it).append('\n') }
        append(ASK_FIRST)
    }
}
