package com.folio.hermes.cascade.writer

import com.folio.hermes.openrouter.SentenceSplitter

/**
 * A task's result as plain speech, for "read it all" (R-TASK-5): markdown, links and list marks
 * are removed, and the text is cut into sentences for TTS. Nothing is summarised or dropped.
 */
object Speakable {
    fun clean(text: String): String = text.lines().joinToString("\n") { line ->
        line.trim()
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^([-*+•]|\\d+[.)])\\s+"), "")
            .replace(Regex("^>\\s*"), "")
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_`~]{1,3}"), "")
            .replace(Regex("\\|"), ", ")
            .trim()
    }.replace(Regex("\n{2,}"), "\n").trim()

    /** [text] cleaned and split into sentences; each line ends a sentence. */
    fun sentences(text: String): List<String> = clean(text).lines().filter { it.isNotBlank() }.flatMap { line ->
        val splitter = SentenceSplitter(leadWords = 0)
        val withStop = if (line.last() in ".!?:;") line else "$line."
        splitter.push(withStop) + splitter.finish()
    }.map { it.trim() }.filter { it.isNotEmpty() }
}
