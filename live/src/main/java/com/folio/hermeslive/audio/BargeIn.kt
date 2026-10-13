package com.folio.hermeslive.audio

import kotlin.math.ceil

/**
 * What Bill is playing, read by the barge-in gate on the capture thread. [AudioTrackOut]
 * implements it; S-F's capturing `AudioOut` for the emulator end-to-end must too.
 */
interface PlaybackMonitor {
    /** True while audio is audibly playing (the head has not reached the end, and no stop). */
    val playing: Boolean

    /** Level (dBFS) of the audio at the playback head now, i.e. what the speaker emits. */
    fun levelDb(): Double

    /** Milliseconds of the current (or last) clip played so far. */
    fun playedMs(): Long

    object None : PlaybackMonitor {
        override val playing: Boolean get() = false
        override fun levelDb(): Double = Dbfs.FLOOR
        override fun playedMs(): Long = 0L
    }
}

/** Knobs for [BargeIn] and [EchoEstimator]. Defaults are the S-C choices (spec v2 R-SPK-4/6). */
data class BargeInConfig(
    /** Confirmed user speech this long cuts Bill off (`CascadeSettings.bargeInMs`). */
    val bargeInMs: Long = 250L,
    val frameMs: Double = 32.0,
    /** The echo gain is learnt over Bill's first this-many ms of playback in a call. */
    val learnMs: Long = 2_000L,
    /** Gain used until it is learnt (mic dB minus playback dB). */
    val defaultGainDb: Double = -6.0,
    val minGainDb: Double = -45.0,
    val maxGainDb: Double = 10.0,
    /** Percentile of the learning frames' mic-minus-playback ratios: high, so echo peaks are covered. */
    val gainPercentile: Double = 0.9,
    /** The mic must beat the echo estimate by this much to count as the user. */
    val marginDb: Double = 6.0,
    /** ...and the noise floor by this much. */
    val floorMarginDb: Double = 6.0,
    /** Playback level is the loudest over this window (the echo path's delay and reverb). */
    val echoWindowMs: Long = 160L,
    /** Share of the last [bargeInMs] frames that must beat the estimate. */
    val quorum: Double = 0.6,
    /** After playback stops, the room still rings for this long: keep gating. */
    val hangoverMs: Long = 250L,
    /** Playback quieter than this is not used for learning. */
    val minPlaybackDb: Double = -60.0,
) {
    val windowFrames: Int get() = maxOf(1, ceil(bargeInMs / frameMs).toInt())
    val echoFrames: Int get() = maxOf(1, ceil(echoWindowMs / frameMs).toInt())
    val hangoverFrames: Int get() = ceil(hangoverMs / frameMs).toInt()
    val learnFrames: Int get() = maxOf(1, ceil(learnMs / frameMs).toInt())
}

/**
 * The echo estimate (R-SPK-6): playback level × a gain learnt during Bill's first [BargeInConfig.learnMs]
 * of playback. In dB: `echoDb = playbackDb + gainDb`. Relearn after a route change. Pure.
 */
class EchoEstimator(private val config: BargeInConfig = BargeInConfig()) {
    private val ratios = ArrayList<Double>()
    private var learnt: Double? = null

    val isLearnt: Boolean get() = learnt != null
    val gainDb: Double get() = learnt ?: config.defaultGainDb

    /** One frame while Bill plays: [playbackDb] (windowed) and what the mic heard. */
    fun observe(playbackDb: Double, micDb: Double) {
        if (learnt != null || playbackDb < config.minPlaybackDb) return
        ratios += micDb - playbackDb
        if (ratios.size >= config.learnFrames) {
            val sorted = ratios.sorted()
            learnt = sorted[((sorted.size - 1) * config.gainPercentile).toInt()].coerceIn(config.minGainDb, config.maxGainDb)
            ratios.clear()
        }
    }

    fun estimateDb(playbackDb: Double): Double = playbackDb + gainDb

    fun reset() {
        learnt = null
        ratios.clear()
    }
}

/**
 * The cut-in gate (R-SPK-4, R-SPK-6). While Bill plays (and for a short hangover after), what
 * the mic hears is not the user's until a cut-in is confirmed: the VAD says speech **and** in
 * the last `bargeInMs` at least [BargeInConfig.quorum] of the frames are louder than the echo
 * estimate (and the noise floor) by the margins. Then [frame] returns [Decision.CutIn] once,
 * and stays [Decision.Open] until playback has ended. Not playing: always [Decision.Open].
 * Pure: one call per capture frame, on the capture thread.
 */
class BargeIn(
    val config: BargeInConfig = BargeInConfig(),
    val echo: EchoEstimator = EchoEstimator(config),
) {
    sealed interface Decision {
        /** Speech is the user's. */
        data object Open : Decision

        /** Bill is playing and no cut-in is confirmed: drop what is heard. */
        data object Hold : Decision

        /**
         * Confirmed: the user has spoken over Bill for [continuousMs]. Accept audio from
         * [framesBack] frames ago (the start of the confirming window), this frame included.
         */
        data class CutIn(val continuousMs: Long, val framesBack: Int) : Decision
    }

    private val playbackDb = ArrayDeque<Double>()
    private val above = ArrayDeque<Boolean>()
    private var quietFrames = Int.MAX_VALUE
    private var cut = false
    private var vadFrames = 0

    /** Frames of playback seen since the last [reset] (for tests and reports). */
    var playingFrames: Int = 0
        private set

    fun frame(micDb: Double, vadSpeech: Boolean, playing: Boolean, playingDb: Double, floorDb: Double): Decision {
        quietFrames = if (playing) 0 else if (quietFrames == Int.MAX_VALUE) quietFrames else quietFrames + 1
        val gating = playing || quietFrames <= config.hangoverFrames
        if (!gating) {
            clearRun()
            cut = false
            return Decision.Open
        }
        playbackDb.addLast(if (playing) playingDb else Dbfs.FLOOR)
        while (playbackDb.size > config.echoFrames) playbackDb.removeFirst()
        val refDb = playbackDb.max()
        if (playing) {
            playingFrames++
            echo.observe(refDb, micDb)
        }
        if (cut) return Decision.Open
        val isAbove = micDb > echo.estimateDb(refDb) + config.marginDb && micDb > floorDb + config.floorMarginDb
        above.addLast(isAbove)
        while (above.size > config.windowFrames) above.removeFirst()
        vadFrames = if (vadSpeech) vadFrames + 1 else 0
        val share = above.count { it }.toDouble() / config.windowFrames
        if (vadSpeech && above.size == config.windowFrames && share >= config.quorum) {
            cut = true
            val run = maxOf(config.bargeInMs, (vadFrames * config.frameMs).toLong())
            clearRun()
            return Decision.CutIn(run, config.windowFrames)
        }
        return Decision.Hold
    }

    /** A new route: the echo path changed. */
    fun routeChanged() {
        echo.reset()
        clearRun()
    }

    private fun clearRun() {
        above.clear()
        vadFrames = 0
        if (quietFrames > config.hangoverFrames) playbackDb.clear()
    }
}
