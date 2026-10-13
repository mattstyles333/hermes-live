package com.folio.hermes.openrouter

/**
 * Cuts streamed writer text into speakable pieces, one TTS request each.
 *
 * - **Lead.** Clips come back whole, so the first audio waits for the whole first piece. The
 *   writer opens every reply with a 1–[leadWords] word lead ("On it.", "Sure.", "Right,"); the
 *   first piece is cut after that lead, at a sentence end or a comma, so it is its own short
 *   request. A first sentence longer than [leadWords] words is left whole.
 * - **Sentences.** After the lead, a piece ends at `.`, `!`, `?` or `…` followed by whitespace,
 *   or at a newline. A dot between digits ("2.5") or after a known abbreviation ("Mr.") is not an end.
 * - [finish] returns what is left (a last sentence with no closing punctuation).
 */
class SentenceSplitter(private val leadWords: Int = 4) {
    private val buffer = StringBuilder()
    private var first = true

    /** Add streamed [delta]; returns the pieces it completed, in order. */
    fun push(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (piece.isNotEmpty()) {
                out += piece
                first = false
            }
        }
        return out
    }

    /** The stream ended: the rest, if any. */
    fun finish(): List<String> {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        first = true
        return if (rest.isEmpty()) emptyList() else listOf(rest)
    }

    /** Index just past the end of the next complete piece, or null if none is complete yet. */
    private fun nextCut(): Int? {
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n') return i + 1
            val terminal = c == '.' || c == '!' || c == '?' || c == '…'
            val leadComma = first && (c == ',' || c == ';' || c == ':' || c == '—')
            if (terminal || leadComma) {
                // Need the next character to know the piece really ended.
                if (i + 1 >= buffer.length) return null
                var end = i + 1
                while (end < buffer.length && buffer[end] in CLOSERS) end++
                if (end >= buffer.length) return null
                if (buffer[end].isWhitespace() && !(terminal && notAnEnd(i))) {
                    if (leadComma && words(i) > leadWords) {
                        i++
                        continue
                    }
                    return end
                }
            }
            i++
        }
        return null
    }

    /** "2.5", "e.g.", "Mr." do not end a sentence. */
    private fun notAnEnd(dot: Int): Boolean {
        if (buffer[dot] != '.') return false
        val prev = dot - 1
        if (prev >= 0 && buffer[prev].isDigit() && dot + 1 < buffer.length && buffer[dot + 1].isDigit()) return true
        var start = prev
        while (start >= 0 && (buffer[start].isLetter() || buffer[start] == '.')) start--
        val word = buffer.substring(start + 1, dot).lowercase()
        return word in ABBREVIATIONS
    }

    private fun words(end: Int): Int = buffer.substring(0, end).trim().split(SPACES).count { it.isNotEmpty() }

    private companion object {
        val CLOSERS = setOf('"', '\'', ')', '”', '’')
        val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "vs", "e.g", "i.e", "approx")
        val SPACES = Regex("\\s+")
    }
}
