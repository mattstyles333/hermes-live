package com.folio.hermes.live

/** Text shaping for what the live voice says: chunks within the vendor's append cap, speakable text. */
object Commentary {
    /** Vendor cap is 500 tokens per append; ~4 chars/token with headroom. */
    const val APPEND_CHAR_LIMIT = 1_400

    private val WHITESPACE = Regex("(?U)\\s+")
    private val SENTENCE_SPLIT = Regex("(?U)(?<=[.!?])\\s+")

    /** Port of the desktop `chunkForCommentary`: split on sentence boundaries into ≤[limit] chunks. */
    fun chunk(text: String, limit: Int = APPEND_CHAR_LIMIT): List<String> {
        val clean = text.replace(WHITESPACE, " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= limit) return listOf(clean)
        val chunks = ArrayList<String>()
        var current = ""
        for (sentence in clean.split(SENTENCE_SPLIT)) {
            if (sentence.length > limit) {
                if (current.isNotEmpty()) {
                    chunks.add(current)
                    current = ""
                }
                var index = 0
                while (index < sentence.length) {
                    chunks.add(sentence.substring(index, minOf(sentence.length, index + limit)))
                    index += limit
                }
                continue
            }
            val candidate = if (current.isNotEmpty()) "$current $sentence" else sentence
            if (candidate.length > limit) {
                chunks.add(current)
                current = sentence
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private val FENCE = Regex("```[\\s\\S]*?(```|$)")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val QUOTE = Regex("(?m)^\\s*>\\s?")
    private val BULLET = Regex("(?m)^\\s*(?:[-*+•]|\\d+[.)])\\s+")
    private val TABLE_RULE = Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val PIPES = Regex("\\s*\\|\\s*")

    /**
     * Make Markdown speakable: code blocks become "(code omitted)", links keep their text, bare
     * URLs become "a link", headings/bullets/emphasis/table pipes go. The desktop's
     * `sanitizeTextForSpeech` was not in the copied source; this is this client's own version.
     */
    fun speakable(text: String): String = text
        .replace(FENCE, " (code omitted) ")
        .replace(IMAGE, "$1")
        .replace(LINK, "$1")
        .replace(URL, "a link")
        .replace(INLINE_CODE, "$1")
        .replace(TABLE_RULE, "")
        .replace(HEADING, "")
        .replace(QUOTE, "")
        .replace(BULLET, "")
        .replace(EMPHASIS, "$2")
        .replace(PIPES, ", ")
        .replace(WHITESPACE, " ")
        .trim()
}

/**
 * Tracks how much of a streaming reply has been handed to the voice. While Hermes streams, only
 * complete sentences are released; [settle] releases the rest. The cursor is on the raw text so
 * an unclosed code fence or link in the stream cannot shift it; each released slice is made
 * speakable on its own.
 */
class SpeechCursor {
    private var raw = ""
    private var cursor = 0

    /** True once anything was released. */
    val spokeAnything: Boolean get() = cursor > 0

    /** Feed the reply so far; returns the speakable text of newly completed sentences, or null. */
    fun advance(textSoFar: String): String? {
        raw = textSoFar
        val boundary = lastSentenceEnd(textSoFar)
        if (boundary <= cursor) return null
        val slice = textSoFar.substring(cursor, boundary)
        cursor = boundary
        return Commentary.speakable(slice).ifEmpty { null }
    }

    /**
     * The turn ended with [finalText] (which may replace the stream). Returns the speakable
     * remainder, or null when nothing is left.
     */
    fun settle(finalText: String): String? {
        val text = finalText.ifEmpty { raw }
        val remainder = when {
            cursor == 0 -> text
            text.startsWith(raw.substring(0, minOf(cursor, raw.length))) -> text.substring(minOf(cursor, text.length))
            else -> text.substring(minOf(cursor, text.length)) // replaced text: best effort, as the desktop does
        }
        cursor = text.length
        raw = text
        return Commentary.speakable(remainder).ifEmpty { null }
    }

    companion object {
        /**
         * Index just past the last `.`, `!` or `?` followed by whitespace and outside an unclosed
         * ``` fence (a half-streamed code block is never cut); 0 when none.
         */
        internal fun lastSentenceEnd(text: String): Int {
            val fences = ArrayList<Int>()
            var at = text.indexOf("```")
            while (at >= 0) {
                fences.add(at)
                at = text.indexOf("```", at + 3)
            }
            var i = text.length - 2
            while (i >= 0) {
                if (text[i] in ".!?" && text[i + 1].isWhitespace() && fences.count { it < i } % 2 == 0) return i + 1
                i--
            }
            return 0
        }
    }
}
