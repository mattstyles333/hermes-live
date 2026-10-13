package com.folio.hermeslive.lab

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/** Input level maths. 0 dBFS is a full-scale square wave; a full-scale sine is about -3.01. */
object Dbfs {
    const val FLOOR = -100.0

    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    fun fromRms(rms: Double): Double = if (rms <= 0.0) FLOOR else maxOf(FLOOR, 20.0 * log10(rms))

    fun of(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double = fromRms(rms(samples, from, to))

    fun pcm16ToFloat(pcm: ShortArray, count: Int = pcm.size): FloatArray = FloatArray(count) { pcm[it] / 32768f }

    /** dBFS of each [frame]-sample sub-frame, for onset detection at 10 ms resolution. */
    fun frames(samples: FloatArray, frame: Int): DoubleArray =
        DoubleArray(samples.size / frame) { of(samples, it * frame, (it + 1) * frame) }
}

/** Streaming-pass token confidence (R-STT-4). */
object TokenConfidence {
    /** sherpa-onnx reports ysProbs as log-probabilities; accept plain probabilities too. */
    fun probability(ysProb: Float): Float = when {
        ysProb.isNaN() -> 0f
        ysProb > 0f -> ysProb.coerceAtMost(1f)
        else -> exp(ysProb.toDouble()).toFloat()
    }

    data class WordConfidence(val word: String, val confidence: Float?)

    /**
     * Groups BPE tokens into words: a token starting with the sentencepiece mark (U+2581) or
     * a space starts a new word. A word's confidence is its least confident token; null when
     * the model gave no probabilities.
     */
    fun words(tokens: List<String>, ysProbs: FloatArray?): List<WordConfidence> {
        val out = ArrayList<WordConfidence>()
        val text = StringBuilder()
        var min: Float? = null
        fun flush() {
            if (text.isNotBlank()) out += WordConfidence(text.toString().trim(), min)
            text.clear()
            min = null
        }
        tokens.forEachIndexed { i, token ->
            val starts = token.startsWith("▁") || token.startsWith(" ")
            if (starts) flush()
            text.append(token.replace("▁", "").replace(" ", ""))
            val p = ysProbs?.getOrNull(i)?.let(::probability)
            if (p != null) min = min?.let { minOf(it, p) } ?: p
        }
        flush()
        return out
    }
}

/**
 * Collects streaming-pass word confidences, split by whether the second pass agreed on the
 * word, to suggest the R-STT-4 threshold. Numbers only.
 */
class ConfidenceStats {
    private val agree = ArrayList<Float>()
    private val disagree = ArrayList<Float>()

    val agreeCount: Int get() = agree.size
    val disagreeCount: Int get() = disagree.size

    fun add(confidence: Float, agreed: Boolean) {
        if (agreed) agree += confidence else disagree += confidence
    }

    fun clear() {
        agree.clear()
        disagree.clear()
    }

    fun percentile(agreed: Boolean, p: Double): Double? = percentile(if (agreed) agree else disagree, p)

    /**
     * The threshold t (from the observed values) that best separates the groups when "word is
     * uncertain" means confidence < t: maximises Youden's J = TPR - FPR. Null until both
     * groups have values.
     */
    fun suggestedThreshold(): Double? {
        if (agree.isEmpty() || disagree.isEmpty()) return null
        val candidates = (agree + disagree).map { it.toDouble() }.distinct().sorted() + 1.0001
        var best: Double? = null
        var bestJ = Double.NEGATIVE_INFINITY
        for (t in candidates) {
            val tpr = disagree.count { it < t }.toDouble() / disagree.size
            val fpr = agree.count { it < t }.toDouble() / agree.size
            val j = tpr - fpr
            if (j > bestJ) {
                bestJ = j
                best = t
            }
        }
        return best
    }

    companion object {
        fun percentile(values: List<Float>, p: Double): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val rank = (p / 100.0 * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
            val lo = rank.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
        }
    }
}

/** Splits a stream of chunks into fixed frames and records each frame's dBFS. */
class FrameMeter(private val frame: Int) {
    private val carry = FloatArray(frame)
    private var carried = 0
    private val values = ArrayList<Double>()

    val frames: Int get() = values.size

    fun accept(samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(frame - carried, samples.size - i)
            System.arraycopy(samples, i, carry, carried, n)
            carried += n
            i += n
            if (carried == frame) {
                values += Dbfs.of(carry)
                carried = 0
            }
        }
    }

    fun toArray(): DoubleArray = values.toDoubleArray()
}
