package com.folio.hermeslive.lab

/** Measurements for the echo and cut-in test (R-SPK-4, R-SPK-5). Pure. */
object EchoMaths {
    fun ratio(part: Int, total: Int): Double = if (total <= 0) 0.0 else part.toDouble() / total

    /** Words heard during playback, and how many of them are words of the played passage. */
    fun echoWords(heard: List<String>, passage: String): Pair<Int, Int> {
        val passageWords = WordDiff.words(passage).map(WordDiff::normalize).filter { it.isNotEmpty() }.toSet()
        val normalized = heard.map(WordDiff::normalize).filter { it.isNotEmpty() }
        return normalized.size to normalized.count { it in passageWords }
    }

    fun median(values: DoubleArray, from: Int, to: Int): Double? {
        if (to <= from) return null
        val slice = values.copyOfRange(maxOf(0, from), minOf(values.size, to)).sorted()
        if (slice.isEmpty()) return null
        val mid = slice.size / 2
        return if (slice.size % 2 == 1) slice[mid] else (slice[mid - 1] + slice[mid]) / 2
    }

    /**
     * Estimates when the user started talking over playback: walking back from the frame where
     * the VAD fired, the first frame of the loud run (frames above [baselineDb] + [marginDb],
     * allowing gaps of up to [maxGap] frames), never before [earliest]. Null when no frame
     * between [earliest] and [fireFrame] is loud.
     */
    fun onsetFrame(
        frameDb: DoubleArray,
        fireFrame: Int,
        earliest: Int,
        baselineDb: Double,
        marginDb: Double = 10.0,
        maxGap: Int = 3,
    ): Int? {
        val threshold = baselineDb + marginDb
        val last = minOf(fireFrame, frameDb.size - 1)
        var i = last
        while (i >= earliest && frameDb[i] <= threshold) i--
        if (i < earliest) return null
        var onset = i
        var gap = 0
        var j = i - 1
        while (j >= earliest) {
            if (frameDb[j] > threshold) {
                onset = j
                gap = 0
            } else if (++gap > maxGap) {
                break
            }
            j--
        }
        return onset
    }

    /** Milliseconds from the start of [onsetFrame] to the sample where the VAD fired. */
    fun latencyMs(onsetFrame: Int, frameSize: Int, fireSample: Long, sampleRate: Int): Double =
        (fireSample - onsetFrame.toLong() * frameSize) * 1000.0 / sampleRate
}
