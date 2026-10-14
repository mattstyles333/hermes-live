package com.folio.hermeslive.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Level maths. 0 dBFS is a full-scale square wave; a full-scale sine is about -3.01. Pure. */
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

    /** dBFS of little-endian 16-bit PCM bytes [from, to). */
    fun ofPcm16(bytes: ByteArray, from: Int, to: Int): Double {
        val n = (to - from) / 2
        if (n <= 0) return FLOOR
        var sum = 0.0
        var i = from
        while (i + 1 < to) {
            val s = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort() / 32768.0
            sum += s * s
            i += 2
        }
        return fromRms(sqrt(sum / n))
    }

    fun pcm16ToFloat(pcm: ShortArray, count: Int = pcm.size): FloatArray = FloatArray(count) { pcm[it] / 32768f }

    /** A level for the orb, 0..1: -60 dBFS and below is 0, 0 dBFS is 1. */
    fun toUnit(db: Double): Float = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The input's noise floor (dBFS), learnt over the first [learnMs] of a call and again after a
 * route change (a headset has a different floor from the speakerphone). The floor is a low
 * percentile of the frame levels, so speech in the learning window barely moves it. Until it is
 * learnt, [db] is [Dbfs.FLOOR] (no gating). Pure.
 */
class NoiseFloor(
    private val frameMs: Double = 32.0,
    private val learnMs: Long = 2_000L,
    private val percentile: Double = 0.2,
) {
    private val frames = ArrayList<Double>()
    private var learnt: Double? = null

    val db: Double get() = learnt ?: Dbfs.FLOOR
    val isLearnt: Boolean get() = learnt != null

    fun accept(levelDb: Double) {
        if (learnt != null) return
        frames += levelDb
        if (frames.size * frameMs >= learnMs) {
            val sorted = frames.sorted()
            learnt = sorted[((sorted.size - 1) * percentile).toInt()]
            frames.clear()
        }
    }

    /** Learn again (route change). */
    fun relearn() {
        learnt = null
        frames.clear()
    }
}
