package com.folio.hermeslive.call

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small energy voice-activity detector for the local mic, with hysteresis.
 *
 * Speech starts after [onsetMs] of frames at least [marginDb] above the adaptive noise floor (and
 * above [absoluteFloorDb]); it ends after [hangMs] below. The floor follows quiet frames quickly
 * and loud ones slowly, so steady background (a fan, ducked music on the speaker) raises it.
 * Single-threaded; feed it from one thread.
 */
class EnergyVad(
    private val marginDb: Double = 12.0,
    private val absoluteFloorDb: Double = -52.0,
    private val onsetMs: Long = 120L,
    private val hangMs: Long = 600L,
) {
    private var floorDb = -70.0
    private var aboveMs = 0L
    private var belowMs = 0L
    private var settleLeftMs = 0L

    /**
     * The sound around the mic is about to change on purpose (music coming back in a gap on the
     * speaker): for [settleMs] no onset fires and the floor follows the level quickly, so steady
     * music is the new floor. Speech above it still counts afterwards.
     */
    fun rebaseline(settleMs: Long) {
        settleLeftMs = settleMs
        speaking = false
        aboveMs = 0
        belowMs = 0
    }

    /** Whether the user is speaking, after the latest frame. */
    var speaking: Boolean = false
        private set

    /** Feed one frame's level ([levelDb], dBFS) lasting [durationMs]. Returns [speaking]. */
    fun offer(levelDb: Double, durationMs: Long): Boolean {
        if (settleLeftMs > 0) {
            settleLeftMs -= durationMs
            floorDb = if (levelDb > floorDb) floorDb + (levelDb - floorDb) * 0.5 else floorDb + (levelDb - floorDb) * 0.2
            return speaking
        }
        val loud = levelDb >= max(floorDb + marginDb, absoluteFloorDb)
        // Floor: drop fast to quiet frames, creep up slowly under sustained sound.
        floorDb = if (levelDb < floorDb) floorDb + (levelDb - floorDb) * 0.5 else floorDb + (levelDb - floorDb) * 0.002 * durationMs / 10.0
        if (loud) {
            aboveMs += durationMs
            belowMs = 0
            if (!speaking && aboveMs >= onsetMs) speaking = true
        } else {
            belowMs += durationMs
            aboveMs = 0
            if (speaking && belowMs >= hangMs) speaking = false
        }
        return speaking
    }

    companion object {
        /** RMS level of 16-bit little-endian PCM in dBFS (-120 for silence). */
        fun rmsDbfs(pcm16le: ByteArray, length: Int = pcm16le.size): Double {
            val samples = length / 2
            if (samples == 0) return -120.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((pcm16le[i + 1].toInt() shl 8) or (pcm16le[i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val rms = sqrt(sum / samples) / 32768.0
            return if (rms <= 1e-6) -120.0 else 20 * log10(rms)
        }
    }
}

/**
 * On/off with hysteresis for a 0..1 level (Hermes's remote audio): on at [onLevel], off after
 * [hangMs] below [offLevel].
 */
class LevelGate(
    private val onLevel: Double = 0.02,
    private val offLevel: Double = 0.01,
    private val hangMs: Long = 450L,
) {
    private var quietSinceMs: Long? = null

    var on: Boolean = false
        private set

    fun offer(level: Double, nowMs: Long): Boolean {
        if (level >= onLevel) {
            on = true
            quietSinceMs = null
        } else if (on && level < offLevel) {
            val since = quietSinceMs ?: nowMs.also { quietSinceMs = it }
            if (nowMs - since >= hangMs) {
                on = false
                quietSinceMs = null
            }
        } else if (level >= offLevel) {
            quietSinceMs = null
        }
        return on
    }

    fun reset() {
        on = false
        quietSinceMs = null
    }
}
