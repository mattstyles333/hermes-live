package com.folio.hermeslive.stt

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.Word
import kotlin.math.exp

/** One streaming-pass word with its least confident token's probability (null if the engine gave none). */
data class FastWord(val text: String, val confidence: Double?)

/** Streaming-pass token confidence (R-STT-4), from sherpa-onnx's per-token `ysProbs`. Pure. */
object TokenConfidence {
    /** sherpa-onnx reports ysProbs as log-probabilities; accept plain probabilities too. */
    fun probability(ysProb: Float): Double = when {
        ysProb.isNaN() -> 0.0
        ysProb > 0f -> ysProb.toDouble().coerceAtMost(1.0)
        else -> exp(ysProb.toDouble())
    }

    /**
     * Groups BPE tokens into words: a token starting with the sentencepiece mark (U+2581) or a
     * space starts a new word. A word's confidence is its least confident token.
     */
    fun words(tokens: List<String>, ysProbs: FloatArray?): List<FastWord> {
        val out = ArrayList<FastWord>()
        val text = StringBuilder()
        var min: Double? = null
        fun flush() {
            if (text.isNotBlank()) out += FastWord(text.toString().trim(), min)
            text.clear()
            min = null
        }
        tokens.forEachIndexed { i, token ->
            if (token.startsWith("▁") || token.startsWith(" ")) flush()
            text.append(token.replace("▁", "").replace(" ", ""))
            val p = ysProbs?.getOrNull(i)?.let(::probability)
            if (p != null) min = min?.let { minOf(it, p) } ?: p
        }
        flush()
        return out
    }
}

/** Display casing for an all-capitals model (Zipformer): "GET ME THIS" -> "get me this", "I" kept. */
object Casing {
    fun fromUpper(word: String): String {
        val lower = word.lowercase()
        return if (lower == "i" || lower.startsWith("i'")) "I" + lower.drop(1) else lower
    }

    fun sentence(words: List<String>): String {
        val text = words.joinToString(" ")
        return text.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Merges the two passes of one committed turn into a [Transcript] (R-STT-2, R-STT-4).
 *
 * - No accurate text: the fast words, `pass: fast`; uncertain where confidence < [uncertainBelow].
 * - Otherwise the accurate words, `pass: accurate`. A word both passes agree on (by
 *   [TextNorm.key], via LCS) carries the fast pass's confidence and is uncertain below
 *   `agreedBelow`. A word the passes disagree on has no confidence (the second pass gives none
 *   in Kotlin); it is uncertain only if it is a **key token** ([KeyTokens]: a name or capitalised
 *   word, a vocabulary word, a number or date, a quantity word), and then keeps the fast pass's
 *   reading of that gap as its alternative.
 *
 * Calibration (S-C corpus bench, 2026-10-13): on agreed words the streaming `ysProbs` only add
 * false flags (best F1 at a threshold of 0), so `agreedBelow` defaults to 0 in
 * [PipelineConfig]. Every disagreement flagged ~18% of words at 12% precision (Zipformer is
 * the weak pass, so most disagreements are its mistakes), hence the key-token rule (spec v2 §14,
 * S-C spec problem 1). For the fast-only fallback 0.45 is best.
 */
object TranscriptMerge {
    fun fastOnly(fast: List<FastWord>, uncertainBelow: Double): Transcript {
        val words = fast.map { w -> Word(w.text, w.confidence, uncertain = w.confidence != null && w.confidence < uncertainBelow) }
        return Transcript(Casing.sentence(fast.map { it.text }), words, TranscriptPass.Fast)
    }

    fun merge(
        fast: List<FastWord>,
        accurate: String?,
        uncertainBelow: Double,
        agreedBelow: Double = uncertainBelow,
        vocabulary: Collection<String> = emptyList(),
    ): Transcript {
        if (accurate == null) return fastOnly(fast, uncertainBelow)
        val acc = TextNorm.words(accurate)
        if (acc.isEmpty() && fast.isNotEmpty()) return fastOnly(fast, uncertainBelow)
        val pairs = Align.lcsPairs(acc.map(TextNorm::key), fast.map { TextNorm.key(it.text) })
        val matched = pairs.toMap()
        val words = ArrayList<Word>(acc.size)
        val vocab = KeyTokens.vocabulary(vocabulary)
        // Walk the gaps between matched pairs: an accurate word with no partner gets the fast
        // words of the same gap as its alternative.
        var prevAcc = -1
        var prevFast = -1
        val anchors = pairs + (acc.size to fast.size)
        for ((ai, fi) in anchors) {
            val gapFast = fast.subList(prevFast + 1, fi).map { it.text }.filter { TextNorm.key(it).isNotEmpty() }
            val gapAcc = (prevAcc + 1 until ai).filter { TextNorm.key(acc[it]).isNotEmpty() }
            for (k in prevAcc + 1 until ai) {
                val flagged = TextNorm.key(acc[k]).isNotEmpty() && KeyTokens.isKey(acc, k, vocab)
                val alt = if (flagged && gapFast.isNotEmpty() && k == gapAcc.firstOrNull { KeyTokens.isKey(acc, it, vocab) }) listOf(gapFast.joinToString(" ")) else emptyList()
                words += Word(acc[k], null, alt, uncertain = flagged)
            }
            if (ai < acc.size) {
                val f = fast[matched.getValue(ai)]
                words += Word(acc[ai], f.confidence, uncertain = f.confidence != null && f.confidence < agreedBelow)
            }
            prevAcc = ai
            prevFast = fi
        }
        return Transcript(accurate.trim(), words, TranscriptPass.Accurate)
    }
}

/**
 * The words worth a doubt when the two passes disagree (R-STT-4, spec v2 §14): the ones a
 * mishearing would make an agent act wrongly on. Pure, English-only, deliberately small lists.
 *
 * - a **name**: capitalised anywhere but the start of a sentence, an acronym (`VAT`), or mixed
 *   case (`McAllister`); "I" and its contractions are not names;
 * - a **vocabulary** word (Settings › Voice, task labels and learned corrections; any word of a phrase);
 * - a **number or date**: any digit, `£ $ € %`, number and ordinal words, months, weekdays,
 *   today / tomorrow / yesterday / tonight;
 * - a **quantity** word: units, currencies, counts and spans (`pounds`, `percent`, `boxes`, `weeks`…).
 */
object KeyTokens {
    private val NUMBERS = setOf(
        "zero", "nought", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion",
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
        "twentieth", "thirtieth", "half", "quarter", "dozen", "double", "triple", "single", "couple", "few", "several", "none",
    )
    private val DATES = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "today", "tomorrow", "yesterday", "tonight", "weekend", "christmas", "easter",
    )
    private val QUANTITIES = setOf(
        "percent", "per", "cent", "pound", "pounds", "quid", "pence", "p", "euro", "euros", "dollar", "dollars", "k", "grand",
        "unit", "units", "pair", "pairs", "box", "boxes", "case", "cases", "pack", "packs", "piece", "pieces", "item", "items",
        "each", "lot", "lots", "kilo", "kilos", "kg", "gram", "grams", "litre", "litres", "metre", "metres", "mm", "cm",
        "minute", "minutes", "hour", "hours", "day", "days", "week", "weeks", "month", "months", "year", "years",
        "more", "less", "fewer", "extra", "all", "every",
    )
    private val SYMBOLS = setOf('£', '$', '€', '%')
    private val SENTENCE_END = setOf('.', '?', '!')

    /** The vocabulary as lower-case single words. */
    fun vocabulary(phrases: Collection<String>): Set<String> =
        phrases.flatMap { p -> p.split(Regex("[\\s-]+")) }.map(::core).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** True if word [i] of [words] (display text, punctuation attached) is a key token. */
    fun isKey(words: List<String>, i: Int, vocabulary: Set<String> = emptySet()): Boolean {
        val raw = words[i]
        if (raw.any { it.isDigit() || it in SYMBOLS }) return true
        val word = core(raw)
        if (word.isEmpty()) return false
        val lower = word.lowercase()
        if (lower in vocabulary || lower in NUMBERS || lower in DATES || lower in QUANTITIES) return true
        if (lower == "i" || lower.startsWith("i'")) return false
        if (word.length >= 2 && word.all { !it.isLetter() || it.isUpperCase() }) return true // VAT, UK
        if (word.drop(1).any { it.isUpperCase() }) return true // McAllister, iPhone
        val sentenceStart = i == 0 || words[i - 1].trimEnd().lastOrNull() in SENTENCE_END
        return word.first().isUpperCase() && !sentenceStart
    }

    private fun core(w: String): String = w.trim { !it.isLetterOrDigit() && it != '\'' }
}
