package com.folio.hermes.cascade.cleanup

import com.folio.hermes.cascade.Word

/**
 * The cheap post-check on a transcript cleanup (the cleanup slice, 2026-10-14). The cleanup model
 * is told to change only unsure words, words the two passes disagree on, and sound-alikes of
 * vocabulary names; this check holds it to that. It aligns the heard words with the cleaned ones
 * and accepts each changed span only if:
 * - its new words come from the other recogniser's text (`other`), or
 * - it completes a clipped word ("sto" → "stock"), or
 * - the two sound alike: letter edit distance at most 40%, or the same consonant skeleton
 *   ("castle" ~ "Kestrel", "safe hello" ~ "Safilo"), or the same number ("four" = "4");
 * - a deletion drops no name or number, and an insertion adds none that neither pass heard.
 *
 * One refused span refuses the whole cleanup (the heard text stands). A span that changes a key
 * token (a vocabulary name, a capitalised name, a number) is reported as [Verdict.Accepted.key],
 * so Jev is asked again with the cleaned words.
 */
object CleanupCheck {
    sealed interface Verdict {
        /** Nothing changed beyond case and punctuation. */
        data object Same : Verdict

        /** [text] stands; [words] are its tokens (unchanged ones keep the heard word's marks); [key]: a name or number changed. */
        data class Accepted(val text: String, val words: List<Word>, val changes: List<Pair<String, String>>, val key: Boolean) : Verdict

        /** The heard text stands. [reason] is a stable code; [change] the refused span (never logged off the phone). */
        data class Rejected(val reason: String, val change: Pair<String, String>) : Verdict
    }

    /** A cleanup longer than this many words is not asked for (the reply would not fit, and long dictations are verbatim, R-CTX-3). */
    const val MAX_WORDS = 300

    fun check(heard: List<Word>, cleaned: String, other: String, vocabulary: List<String>): Verdict {
        val new = tokens(cleaned)
        if (new.isEmpty()) return Verdict.Rejected("empty", heard.joinToString(" ") { it.text } to "")
        val old = heard.map { it.text }
        val ops = align(old.map(::norm), new.map(::norm))
        val vocab = vocabulary.flatMap { v -> listOf(squash(v)) + tokens(v).map(::norm) }.filter { it.isNotEmpty() }.toSet()
        val otherWords = tokens(other).map(::norm).filter { it.isNotEmpty() }.toSet()
        val words = mutableListOf<Word>()
        val changes = mutableListOf<Pair<String, String>>()
        var key = false
        var i = 0
        var j = 0
        var k = 0
        while (k < ops.size) {
            if (ops[k] == Op.Match) {
                words += heard[i].copy(text = new[j])
                i++
                j++
                k++
                continue
            }
            val i0 = i
            val j0 = j
            while (k < ops.size && ops[k] != Op.Match) {
                when (ops[k]) {
                    Op.Sub -> { i++; j++ }
                    Op.Del -> i++
                    Op.Ins -> j++
                    Op.Match -> Unit
                }
                k++
            }
            val from = old.subList(i0, i)
            val to = new.subList(j0, j)
            val change = from.joinToString(" ") to to.joinToString(" ")
            if (sameNumber(from, to)) {
                words += to.map { Word(it) }
                continue
            }
            val reason = refusal(from, to, j0 == 0, otherWords, vocab)
            if (reason != null) return Verdict.Rejected(reason, change)
            // The old side's capitals say nothing (title-cased accurate text): only its names and numbers count.
            val keyChange = isKey(from, false, vocab, capitals = false) || isKey(to, j0 == 0, vocab)
            if (keyChange) key = true
            changes += change
            // A changed name or number stays visible as a doubt: "As heard" keeps the heard word, and the caption lets the user correct it.
            words += to.map { Word(it, alternatives = if (from.isEmpty()) emptyList() else listOf(change.first), uncertain = keyChange && from.isNotEmpty()) }
        }
        if (changes.isEmpty()) return Verdict.Same
        return Verdict.Accepted(new.joinToString(" "), words, changes, key)
    }

    /** Why [from] → [to] is refused, or null if it is allowed. [atStart]: [to] begins the sentence (its capital is not a name). */
    private fun refusal(from: List<String>, to: List<String>, atStart: Boolean, other: Set<String>, vocab: Set<String>): String? {
        if (to.isEmpty()) return if (isKey(from, false, vocab, capitals = false)) "dropped_key" else null
        val fromOther = to.all { norm(it) in other }
        if (from.isEmpty()) return if (fromOther || !isKey(to, atStart, vocab)) null else "invented"
        if (fromOther) return null
        val a = letters(from)
        val b = letters(to)
        if (a.length >= 2 && b.startsWith(a)) return null
        if (soundAlike(a, b)) return null
        return "not_sound_alike"
    }

    /** True if [a] and [b] (letters only, numbers spelled) could be one sound misheard. */
    fun soundAlike(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (distance(a, b) <= 0.4 * maxOf(a.length, b.length)) return true
        val x = skeleton(a)
        val y = skeleton(b)
        return x == y || (minOf(x.length, y.length) >= 3 && distance(x, y) <= 1)
    }

    /** A rough consonant skeleton: common spellings of one sound folded, vowels (after the first letter), h, w, y dropped, doubles collapsed. */
    fun skeleton(s: String): String {
        var t = s.lowercase()
        for ((from, to) in FOLDS) t = t.replace(from, to)
        val out = StringBuilder()
        t.forEachIndexed { i, c ->
            val keep = i == 0 || c !in "aeiouyhw"
            if (keep && (out.isEmpty() || out.last() != c)) out.append(c)
        }
        return out.toString()
    }

    private val FOLDS = listOf(
        Regex("^kn") to "n", Regex("ph") to "f", Regex("ck") to "k", Regex("c(?=[eiy])") to "s", Regex("c") to "k",
        Regex("q") to "k", Regex("x") to "ks", Regex("z") to "s", Regex("dg") to "j", Regex("gh") to "", Regex("wh") to "w",
        Regex("v") to "f", Regex("(?<=.)th") to "t",
    )

    private fun isKey(span: List<String>, atStart: Boolean, vocab: Set<String>, capitals: Boolean = true): Boolean {
        if (span.isEmpty()) return false
        if (squash(span.joinToString(" ")) in vocab) return true
        return span.withIndex().any { (n, w) ->
            val t = norm(w)
            t in vocab || t.any(Char::isDigit) || t in NUMBERS ||
                (capitals && w.firstOrNull()?.isUpperCase() == true && !(atStart && n == 0) && t != "i")
        }
    }

    /** Both spans are the same number, written differently ("four" / "4", "a hundred" / "100"). */
    private fun sameNumber(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val x = number(a) ?: return false
        return x == number(b)
    }

    /** The value of a span of digits or number words; null if it is not only a number. */
    fun number(span: List<String>): Long? {
        val t = span.map(::norm).filter { it.isNotEmpty() && it != "and" }
        if (t.isEmpty()) return null
        if (t.size == 1 && t[0].all(Char::isDigit)) return t[0].toLongOrNull()
        var total = 0L
        var part = 0L
        var any = false
        for ((n, w) in t.withIndex()) {
            when {
                w == "a" && n == 0 && t.size > 1 -> part = 1
                w in UNITS -> {
                    val u = UNITS.getValue(w)
                    // "twenty one" is 21, but "four zero" is two digits, not a sum.
                    if (any && (if (u < 10) part % 10 != 0L else part % 100 != 0L)) return null
                    part += u
                    any = true
                }
                w == "hundred" -> { part = (if (part == 0L) 1 else part) * 100; any = true }
                w == "thousand" -> { total += (if (part == 0L) 1 else part) * 1000; part = 0; any = true }
                else -> return null
            }
        }
        return if (any) total + part else null
    }

    /** Spans as letters only, numbers spelled out (so "4" sounds like "for"). */
    private fun letters(span: List<String>): String = span.joinToString("") { w -> spell(norm(w)) }

    private fun spell(t: String): String = if (t.isNotEmpty() && t.all(Char::isDigit)) t.map { DIGITS[it - '0'] }.joinToString("") else t.filter(Char::isLetter)

    private enum class Op { Match, Sub, Del, Ins }

    /** Word-level edit alignment (Levenshtein backtrace). */
    private fun align(a: List<String>, b: List<String>): List<Op> {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        val ops = ArrayDeque<Op>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] && d[i][j] == d[i - 1][j - 1] -> { ops.addFirst(Op.Match); i--; j-- }
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 -> { ops.addFirst(Op.Sub); i--; j-- }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { ops.addFirst(Op.Del); i-- }
                else -> { ops.addFirst(Op.Ins); j-- }
            }
        }
        return ops.toList()
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    fun tokens(s: String): List<String> = s.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Lower case, letters, digits and apostrophes only ("Brand." = "brand", "1,000" = "1000"). */
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() || it == '\'' }.trim('\'')

    private fun squash(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private val WHITESPACE = Regex("\\s+")
    private val DIGITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val UNITS: Map<String, Long> = (
        DIGITS.withIndex().associate { (i, w) -> w to i.toLong() } +
            listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
                .withIndex().associate { (i, w) -> w to (10L + i) } +
            listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety").withIndex().associate { (i, w) -> w to (20L + 10 * i) }
        )
    private val NUMBERS: Set<String> = UNITS.keys + setOf("hundred", "thousand", "million", "half", "dozen")
}
