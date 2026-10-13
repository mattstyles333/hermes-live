package com.folio.hermeslive.lab

/**
 * Word-level comparison of the streaming and second-pass texts. Words are compared without
 * case or punctuation (Zipformer writes upper case without punctuation; Parakeet writes
 * sentence case with punctuation), so only real differences are highlighted.
 */
object WordDiff {
    data class Word(val text: String, val differs: Boolean)

    data class Result(val left: List<Word>, val right: List<Word>) {
        val leftDiffering: Int get() = left.count { it.differs }
        val rightDiffering: Int get() = right.count { it.differs }
    }

    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun normalize(word: String): String = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    fun diff(left: String, right: String): Result {
        val a = words(left)
        val b = words(right)
        val keep = lcs(a.map(::normalize), b.map(::normalize))
        return Result(
            left = a.mapIndexed { i, w -> Word(w, i !in keep.first && normalize(w).isNotEmpty()) },
            right = b.mapIndexed { j, w -> Word(w, j !in keep.second && normalize(w).isNotEmpty()) },
        )
    }

    /** Word error rate of [hypothesis] against [reference] (substitutions + deletions + insertions). */
    fun wer(reference: String, hypothesis: String): Double {
        val r = words(reference).map(::normalize).filter { it.isNotEmpty() }
        val h = words(hypothesis).map(::normalize).filter { it.isNotEmpty() }
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) {
                val sub = prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[h.size].toDouble() / r.size
    }

    /** Indices of a longest common subsequence in [a] and [b]. Empty-normalised words never match. */
    private fun lcs(a: List<String>, b: List<String>): Pair<Set<Int>, Set<Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i].isNotEmpty() && a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val left = HashSet<Int>()
        val right = HashSet<Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].isNotEmpty() && a[i] == b[j] -> {
                    left += i
                    right += j
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return left to right
    }
}
