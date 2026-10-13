package com.folio.hermeslive.stt

/**
 * Word normalisation shared by the pass diff (R-STT-4) and the WER bench: lower case, no
 * punctuation but inner apostrophes, hyphens split, digits spelled out ("90" -> "ninety",
 * "8%" -> "eight percent", "£4,212" -> "four thousand two hundred and twelve pounds"). The
 * streaming Zipformer writes upper case without digits; Parakeet writes sentence case with
 * digits and punctuation, so without this the passes would "disagree" on every number.
 */
object TextNorm {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SPELLING = mapOf("ok" to "okay", "mr" to "mister", "mrs" to "missus", "dr" to "doctor")

    /** The raw words of [text] (split on white space). */
    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Normalised tokens of one raw word (may be empty, or several for a number). */
    fun tokens(word: String): List<String> {
        val w = word.lowercase()
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('–', '-')
            .replace('—', '-')
        val out = ArrayList<String>()
        for (part in w.split('-', '/')) {
            var p = part
            var suffix: String? = null
            if (p.startsWith("£")) suffix = "pounds"
            if (p.startsWith("$")) suffix = "dollars"
            if (p.contains('%')) p = p.replace("%", " percent ")
            for (piece in p.split(' ').filter { it.isNotEmpty() }) {
                val clean = piece.filter { it.isLetterOrDigit() || it == '\'' || it == '.' || it == ',' }
                    .trim('\'', '.', ',')
                if (clean.isEmpty()) continue
                val numeric = clean.replace(",", "")
                when {
                    numeric.all { it.isDigit() } && numeric.length <= 12 -> out += spell(numeric.toLong()).split(' ')
                    numeric.count { it == '.' } == 1 && numeric.replace(".", "").all { it.isDigit() } && numeric.length <= 12 -> {
                        val (a, b) = numeric.split('.')
                        if (a.isNotEmpty()) out += spell(a.toLong()).split(' ')
                        out += "point"
                        b.forEach { out += ONES[it - '0'] }
                    }
                    else -> {
                        val letters = clean.filter { it.isLetterOrDigit() || it == '\'' }
                        if (letters.isNotEmpty()) out += SPELLING[letters] ?: letters
                    }
                }
            }
            if (suffix != null) out += suffix
        }
        return out
    }

    /** Normalised tokens of [text]. */
    fun normalize(text: String): List<String> = words(text).flatMap(::tokens)

    /** One comparison key per raw word: its tokens joined (empty if it had none). */
    fun key(word: String): String = tokens(word).joinToString(" ")

    /** British reading: "one hundred and twelve", "four thousand two hundred and twelve". */
    fun spell(n: Long): String {
        if (n < 0) return "minus " + spell(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        if (n < 1000) {
            val rest = n % 100
            return ONES[(n / 100).toInt()] + " hundred" + if (rest != 0L) " and " + spell(rest) else ""
        }
        val scales = listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
        for ((size, name) in scales) {
            if (n >= size) {
                val rest = n % size
                val head = spell(n / size) + " " + name
                return when {
                    rest == 0L -> head
                    rest < 100 -> "$head and ${spell(rest)}"
                    else -> "$head ${spell(rest)}"
                }
            }
        }
        return n.toString()
    }
}

/** Word alignment and error rates over [TextNorm] tokens. Pure. */
object Align {
    enum class Op { MATCH, SUB, INS, DEL }

    /** Levenshtein alignment of [hyp] to [ref]: one op per step; INS consumes a hyp token, DEL a ref token. */
    fun ops(ref: List<String>, hyp: List<String>): List<Op> {
        val n = ref.size
        val m = hyp.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val out = ArrayList<Op>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] && d[i][j] == d[i - 1][j - 1] -> { out += Op.MATCH; i--; j-- }
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 -> { out += Op.SUB; i--; j-- }
                j > 0 && d[i][j] == d[i][j - 1] + 1 -> { out += Op.INS; j-- }
                else -> { out += Op.DEL; i-- }
            }
        }
        return out.reversed()
    }

    data class Errors(val sub: Int, val ins: Int, val del: Int, val refWords: Int) {
        val total: Int get() = sub + ins + del
        val wer: Double get() = if (refWords == 0) (if (total == 0) 0.0 else 1.0) else total.toDouble() / refWords
    }

    fun errors(reference: String, hypothesis: String): Errors {
        val r = TextNorm.normalize(reference)
        val ops = ops(r, TextNorm.normalize(hypothesis))
        return Errors(ops.count { it == Op.SUB }, ops.count { it == Op.INS }, ops.count { it == Op.DEL }, r.size)
    }

    fun wer(reference: String, hypothesis: String): Double = errors(reference, hypothesis).wer

    /**
     * For each raw hypothesis word: true if it is wrong against [reference] (any of its tokens is a
     * substitution or an insertion). Words with no tokens are never wrong. Deletions can't be put
     * on a hypothesis word, so they are not counted here (the bench reports them separately).
     */
    fun wrongWords(reference: String, hypothesisWords: List<String>): List<Boolean> {
        val perWord = hypothesisWords.map(TextNorm::tokens)
        val owner = perWord.flatMapIndexed { index, t -> List(t.size) { index } }
        val ops = ops(TextNorm.normalize(reference), perWord.flatten())
        val wrong = BooleanArray(hypothesisWords.size)
        var h = 0
        for (op in ops) {
            when (op) {
                Op.MATCH -> h++
                Op.SUB, Op.INS -> { wrong[owner[h]] = true; h++ }
                Op.DEL -> Unit
            }
        }
        return wrong.toList()
    }

    /**
     * Longest common subsequence of word keys (empty keys never match): the index pairs
     * (left, right) that agree, in order.
     */
    fun lcsPairs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i].isNotEmpty() && a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].isNotEmpty() && a[i] == b[j] -> { out += i to j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}
