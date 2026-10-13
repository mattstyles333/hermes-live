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
 *   [TextNorm.key], via LCS) carries the fast pass's confidence and is uncertain below the
 *   threshold. A word the passes disagree on is uncertain with no confidence (the second pass
 *   gives none in Kotlin), and keeps the fast pass's reading of that gap as an alternative.
 */
object TranscriptMerge {
    fun fastOnly(fast: List<FastWord>, uncertainBelow: Double): Transcript {
        val words = fast.map { w -> Word(w.text, w.confidence, uncertain = w.confidence != null && w.confidence < uncertainBelow) }
        return Transcript(Casing.sentence(fast.map { it.text }), words, TranscriptPass.Fast)
    }

    fun merge(fast: List<FastWord>, accurate: String?, uncertainBelow: Double): Transcript {
        if (accurate == null) return fastOnly(fast, uncertainBelow)
        val acc = TextNorm.words(accurate)
        if (acc.isEmpty() && fast.isNotEmpty()) return fastOnly(fast, uncertainBelow)
        val pairs = Align.lcsPairs(acc.map(TextNorm::key), fast.map { TextNorm.key(it.text) })
        val matched = pairs.toMap()
        val words = ArrayList<Word>(acc.size)
        // Walk the gaps between matched pairs: an accurate word with no partner gets the fast
        // words of the same gap as its alternative.
        var prevAcc = -1
        var prevFast = -1
        val anchors = pairs + (acc.size to fast.size)
        for ((ai, fi) in anchors) {
            val gapFast = fast.subList(prevFast + 1, fi).map { it.text }.filter { TextNorm.key(it).isNotEmpty() }
            val gapAcc = (prevAcc + 1 until ai).filter { TextNorm.key(acc[it]).isNotEmpty() }
            for (k in prevAcc + 1 until ai) {
                val keyed = TextNorm.key(acc[k]).isNotEmpty()
                val alt = if (keyed && gapFast.isNotEmpty() && k == gapAcc.firstOrNull()) listOf(gapFast.joinToString(" ")) else emptyList()
                words += Word(acc[k], null, alt, uncertain = keyed)
            }
            if (ai < acc.size) {
                val f = fast[matched.getValue(ai)]
                words += Word(acc[ai], f.confidence, uncertain = f.confidence != null && f.confidence < uncertainBelow)
            }
            prevAcc = ai
            prevFast = fi
        }
        return Transcript(accurate.trim(), words, TranscriptPass.Accurate)
    }
}
