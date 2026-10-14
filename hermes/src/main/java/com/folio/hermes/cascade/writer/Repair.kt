package com.folio.hermes.cascade.writer

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.Word

/** The repair the app accepted: [text] is what the agent gets as "Transcript", [notes] say why. */
data class Repaired(val text: String, val accepted: Boolean, val changes: List<Pair<String, String>>, val rejected: List<Pair<String, String>>)

/**
 * R-STT-6: the writer may change only words marked uncertain or words matching the user's
 * vocabulary. [check] aligns the writer's `repaired` text with what was heard and accepts it only
 * if every changed span is allowed; otherwise the verbatim text stands. Case and punctuation
 * changes are free.
 */
object Repair {
    /** The transcript as heard, uncertain words marked `[word|alt|alt]` (v1 CEO §7, "As heard"). */
    fun marked(t: Transcript): String {
        val words = aligned(t)
        if (words.none { it.uncertain }) return t.text
        return words.joinToString(" ") { w -> if (w.uncertain) "[" + (listOf(w.text) + w.alternatives).joinToString("|") + "]" else w.text }
    }

    /** The transcript's words, one per token of its text (rebuilt from the text when they do not line up). */
    fun aligned(t: Transcript): List<Word> {
        val tokens = t.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.words.size == tokens.size) return t.words
        val doubtful = t.words.filter { it.uncertain }.associateBy { norm(it.text) }
        return tokens.map { tok -> doubtful[norm(tok)]?.copy(text = tok) ?: Word(tok) }
    }

    fun check(heard: Transcript, repaired: String?, vocabulary: List<String>): Repaired {
        val original = aligned(heard)
        if (repaired.isNullOrBlank()) return Repaired(heard.text, false, emptyList(), emptyList())
        val fixed = repaired.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val vocab = vocabulary.map { norm(it) }.filter { it.isNotEmpty() }.toSet() + vocabulary.map { squash(it) }.filter { it.isNotEmpty() }
        val changes = mutableListOf<Pair<String, String>>()
        val rejected = mutableListOf<Pair<String, String>>()
        var i = 0
        var j = 0
        while (i < original.size || j < fixed.size) {
            if (i < original.size && j < fixed.size && norm(original[i].text) == norm(fixed[j])) {
                i++
                j++
                continue
            }
            val (ni, nj) = resync(original, fixed, i, j)
            val old = original.subList(i, ni)
            val new = fixed.subList(j, nj)
            val change = old.joinToString(" ") { it.text } to new.joinToString(" ")
            val allowed = (old.isNotEmpty() && old.all { it.uncertain }) ||
                (new.isNotEmpty() && (squash(change.second) in vocab || norm(change.second) in vocab)) ||
                (old.isNotEmpty() && (squash(change.first) in vocab || norm(change.first) in vocab) && new.isNotEmpty())
            if (allowed) changes += change else rejected += change
            i = ni
            j = nj
        }
        return if (rejected.isEmpty()) Repaired(repaired.trim(), changes.isNotEmpty(), changes, rejected)
        else Repaired(heard.text, false, emptyList(), rejected)
    }

    /** The next point where both sequences agree on two tokens (or one at the end), within a short window. */
    private fun resync(a: List<Word>, b: List<String>, i: Int, j: Int): Pair<Int, Int> {
        for (span in 1..WINDOW * 2) {
            for (di in 0..span) {
                val dj = span - di
                val x = i + di
                val y = j + dj
                if (x > a.size || y > b.size) continue
                if (x == a.size && y == b.size) return x to y
                if (x < a.size && y < b.size && norm(a[x].text) == norm(b[y]) &&
                    (x + 1 >= a.size || y + 1 >= b.size || norm(a[x + 1].text) == norm(b[y + 1]))
                ) return x to y
            }
        }
        return a.size to b.size
    }

    private const val WINDOW = 6

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim().replace(Regex("\\s+"), " ")

    private fun squash(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
