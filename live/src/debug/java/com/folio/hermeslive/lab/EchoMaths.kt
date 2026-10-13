package com.folio.hermeslive.lab

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

/** Minimal RIFF/WAVE reader for TTS output synthesised into memory. */
object WavPcm {
    data class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun parse(bytes: ByteArray): Pcm {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val declared = buf.getInt(pos + 4).toLong() and 0xffffffffL
            val body = pos + 8
            if (id == "fmt ") {
                require(buf.getShort(body).toInt() == 1) { "not PCM" }
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                require(rate > 0 && bits == 16) { "need 16-bit PCM after fmt" }
                // Streaming writers leave the size 0 or 0xFFFFFFFF: take the rest of the bytes.
                val available = bytes.size - body
                val size = if (declared == 0L || declared > available) available else declared.toInt()
                val samples = ShortArray(size / 2)
                buf.position(body)
                buf.asShortBuffer().get(samples)
                return Pcm(rate, channels, samples)
            }
            if (declared > bytes.size) break
            pos = body + declared.toInt() + (declared.toInt() and 1)
        }
        error("no data chunk")
    }

    /** Mono 16-bit samples: channels averaged. */
    fun mono(pcm: Pcm): ShortArray {
        if (pcm.channels <= 1) return pcm.samples
        val n = pcm.samples.size / pcm.channels
        return ShortArray(n) { i ->
            var sum = 0
            for (c in 0 until pcm.channels) sum += pcm.samples[i * pcm.channels + c]
            (sum / pcm.channels).toShort()
        }
    }
}
