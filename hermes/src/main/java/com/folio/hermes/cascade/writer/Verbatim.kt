package com.folio.hermes.cascade.writer

/**
 * Names, numbers and dates stay verbatim (R-TASK-2). Names and dates are checked by the agent
 * against the transcript block; numbers are checked here: a number in the writer's text that the
 * user never said (as digits or as words, "forty" = 40) means the writer invented it.
 */
object Verbatim {
    private val digits = Regex("\\d[\\d,]*(?:\\.\\d+)?")

    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val scales = mapOf("hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L, "grand" to 1_000L, "k" to 1_000L)

    /** Numbers in [text] the user never said in [source]. */
    fun invented(source: String, text: String): List<String> {
        val said = numbers(source)
        return digits.findAll(text).map { it.value.trimEnd(',', '.') }.filter { normalise(it) !in said }.distinct().toList()
    }

    /** Every number in [text]: digit runs (also each part of "40-1187"), and spoken numbers as values. */
    fun numbers(text: String): Set<String> {
        val out = mutableSetOf<String>()
        digits.findAll(text).forEach { m ->
            val v = m.value.trimEnd(',', '.')
            out += normalise(v)
            v.split(',', '.').filter { it.isNotEmpty() }.forEach { out += normalise(it) }
        }
        // Spoken numbers: "forty" = 40, "two hundred and fifty" = 250, "a thousand" = 1000.
        var total = 0L
        var current = 0L
        var inNumber = false
        var pendingA = false
        fun flush() {
            if (inNumber) out += (total + current).toString()
            total = 0
            current = 0
            inNumber = false
        }
        for (word in text.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (word.isEmpty()) continue
            val unit = units[word]
            val scale = scales[word]
            val value = word.toLongOrNull()
            when {
                word == "and" && inNumber -> Unit
                word == "a" -> { flush(); pendingA = true; continue }
                value != null -> { flush(); current = value; inNumber = true }
                unit != null -> {
                    val joins = inNumber && ((current % 100 >= 20 && current % 10 == 0L && unit < 10) || (current % 100 == 0L && (current > 0 || total > 0)))
                    if (!joins) flush()
                    current += unit
                    inNumber = true
                }
                scale != null && (inNumber || pendingA) -> {
                    val base = if (current > 0) current else 1
                    if (scale >= 1_000) {
                        total += base * scale
                        current = 0
                    } else {
                        current = base * scale
                    }
                    inNumber = true
                }
                else -> flush()
            }
            pendingA = false
        }
        flush()
        return out
    }

    private fun normalise(n: String): String = n.replace(",", "").trimStart('0').ifEmpty { "0" }
}
