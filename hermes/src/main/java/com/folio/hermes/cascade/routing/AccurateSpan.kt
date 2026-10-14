package com.folio.hermes.cascade.routing

/**
 * Whether the accurate pass's text is the same stretch of speech as a committed turn's text
 * (R-STT-2, R-CTX-3), so it may replace it. The pass covers the audio held since the last commit
 * or drop. That is not always the turn's whole text: an ignored pause drops the held audio while
 * the words held before it (`pending`) stay in the turn, and typed text has no audio at all.
 * Replacing the text then would lose words Hermes must get, so the fast text stays.
 *
 * The check is cheap and conservative (a false "no" keeps the fast text, as before):
 * - the two have about as many words (accurate / fast within 0.75..1.34);
 * - at each end, over the first and last [WINDOW] words, at least half align, and the fast words
 *   left unaligned before the first aligned pair (after the last) are no more than the accurate
 *   words there: a misheard word is a substitution, missing audio is a deletion.
 */
object AccurateSpan {
    const val WINDOW = 12

    fun same(fast: String, accurate: String): Boolean {
        val f = tokens(fast)
        val a = tokens(accurate)
        if (f.isEmpty() || a.isEmpty()) return false
        val ratio = a.size.toDouble() / f.size
        if (ratio < 0.75 || ratio > 1.34) return false
        return endAligned(f.take(WINDOW), a.take(WINDOW)) && endAligned(f.takeLast(WINDOW).reversed(), a.takeLast(WINDOW).reversed())
    }

    /** Lower-case letters and digits per word; punctuation-only words dropped. */
    fun tokens(text: String): List<String> =
        text.split(Regex("\\s+")).map { w -> w.lowercase().filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

    /** [f] and [a] start at the same point: half of the shorter aligns, and [f] skips no more before the first pair than [a]. */
    private fun endAligned(f: List<String>, a: List<String>): Boolean {
        val pairs = lcs(f, a)
        if (pairs.isEmpty() || pairs.size * 2 < minOf(f.size, a.size)) return false
        val (fi, ai) = pairs.first()
        return fi <= ai
    }

    /** The longest common subsequence as (index in [f], index in [a]) pairs, in order. Small inputs only. */
    private fun lcs(f: List<String>, a: List<String>): List<Pair<Int, Int>> {
        val n = f.size
        val m = a.size
        val len = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            len[i][j] = if (f[i] == a[j]) len[i + 1][j + 1] + 1 else maxOf(len[i + 1][j], len[i][j + 1])
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                f[i] == a[j] -> {
                    out += i to j
                    i++
                    j++
                }
                len[i + 1][j] >= len[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}
