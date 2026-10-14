package com.folio.hermes.cascade.writer

import com.folio.hermes.cascade.Exchange

/**
 * R-CTX-2: the rolling conversation summary (≤ `conversationSummaryChars`) and the summary of long
 * held text, written by the writer off the critical path (no turn ever waits for them). Until
 * one exists, Jev's budget cuts the text by head and tail instead.
 */
object Summaries {
    /** Held text above this many characters gets a writer summary (v1 §9, item 4). */
    const val HELD_ABOVE = 800
    const val HELD_CHARS = 300

    /** v1 §9 item 6: refresh every [every] committed turns (from the sixth on). */
    fun conversationDue(turns: Int, every: Int = 6): Boolean = turns > 0 && turns % every == 0

    fun heldDue(heldChars: Int): Boolean = heldChars > HELD_ABOVE

    const val SYSTEM =
        "You keep short running notes for a voice assistant that routes a user's requests to agents. " +
            "Plain text, no markdown. Keep names, numbers and dates exactly as said."

    fun conversation(previous: String, recent: List<Exchange>, tasks: List<String>, maxChars: Int): String = buildString {
        append("Update the notes on this conversation in at most ").append(maxChars).append(" characters: what the user is working on, ")
        append("what they asked for, decisions made. Drop small talk.\n")
        if (previous.isNotBlank()) append("Notes so far: ").append(previous).append('\n')
        if (tasks.isNotEmpty()) append("Tasks: ").append(tasks.joinToString("; ")).append('\n')
        recent.forEach { append("User: ").append(it.user).append("\nBill: ").append(it.bill).append('\n') }
    }

    fun held(text: String, maxChars: Int = HELD_CHARS): String =
        "The user is still dictating. Summarise what they have said so far in at most $maxChars characters, " +
            "keeping every name, number and date:\n\"$text\""

    /** The writer's answer, trimmed to [maxChars] at a word boundary. */
    fun clamp(text: String, maxChars: Int): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length <= maxChars) return t
        val cut = t.lastIndexOf(' ', maxChars - 1).takeIf { it > maxChars / 2 } ?: (maxChars - 1)
        return t.substring(0, cut).trimEnd() + "…"
    }
}
