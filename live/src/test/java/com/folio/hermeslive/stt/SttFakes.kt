package com.folio.hermeslive.stt

import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.PlaybackMonitor
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/*
 * Fakes for the speech pipeline. Test audio is made of 32 ms frames of a constant value (the
 * value marks where a frame came from); 0 is silence. The fake VAD calls a frame speech when its
 * level is above -40 dBFS, after [FakeVad.minSpeech] such frames, and ends after
 * [FakeVad.minSilence] quiet ones, like Silero's min speech/silence durations.
 */

const val F = SttPipeline.FRAME

/** A frame of constant [amplitude] (0.1 is -20 dBFS; 0 is silence). */
fun tone(amplitude: Float = 0.1f): FloatArray = FloatArray(F) { amplitude }

fun silence(): FloatArray = FloatArray(F)

fun frames(n: Int, amplitude: Float = 0.1f): List<FloatArray> = List(n) { tone(amplitude) }

class FakeVad(val minSpeech: Int = 3, val minSilence: Int = 3) : VoiceDetector {
    private var on = false
    private var loud = 0
    private var quiet = 0
    var frames = 0

    /** Overrides the level rule (a VAD fooled by noise, or deaf). */
    var forced: Boolean? = null

    override fun accept(frame: FloatArray): Boolean {
        frames++
        val speech = forced ?: (Dbfs.of(frame) > -40.0)
        if (speech) {
            loud++
            quiet = 0
            if (!on && loud >= minSpeech) on = true
        } else {
            quiet++
            loud = 0
            if (on && quiet >= minSilence) on = false
        }
        return on
    }

    override fun reset() {
        on = false
        loud = 0
        quiet = 0
    }

    override fun release() = Unit
}

/**
 * One word per 4 non-silent frames ("w1", "w2", ...); confidence from [confidence] by word
 * index. Records every frame and the hotwords each stream was opened with.
 */
class FakeStreaming(private val confidence: (Int) -> Double? = { 0.9 }) : StreamingEngine {
    override val model: SttModel? = null
    val sessions = ArrayList<FakeSession>()

    override fun open(hotwords: String): StreamingSession = FakeSession(hotwords, confidence).also { sessions += it }

    override fun release() = Unit
}

class FakeSession(val hotwords: String, private val confidence: (Int) -> Double?) : StreamingSession {
    val frames = ArrayList<FloatArray>()
    var finished = false
    var released = false

    override fun accept(samples: FloatArray) {
        frames += samples
    }

    override fun words(): List<FastWord> {
        val loud = frames.count { it[0] != 0f }
        return (1..loud / 4).map { FastWord("w$it", confidence(it)) }
    }

    override fun finish() {
        finished = true
    }

    override fun release() {
        released = true
    }
}

/** Decodes to [text] (or throws when null). */
class FakeOffline(var text: (FloatArray) -> String? = { "accurate" }) : OfflineEngine {
    override val model: SttModel? = null
    val decoded = ArrayList<Pair<Int, String>>()

    override fun decode(samples: FloatArray, hotwords: String): String {
        decoded += samples.size to hotwords
        return text(samples) ?: error("decode failed")
    }

    override fun release() = Unit
}

class FakePlayback(var isPlaying: Boolean = false, var db: Double = -20.0, var played: Long = 0) : PlaybackMonitor {
    override val playing: Boolean get() = isPlaying
    override fun levelDb(): Double = if (isPlaying) db else Dbfs.FLOOR
    override fun playedMs(): Long = played
}

/** Runs tasks on the calling thread (deterministic second pass). */
class DirectExecutor : AbstractExecutorService() {
    private var shut = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() {
        shut = true
    }
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shut = true }
    override fun isShutdown(): Boolean = shut
    override fun isTerminated(): Boolean = shut
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

/** A source that plays [frames] then ends (-1). Counts opens. */
class FramesSource(private val frames: List<FloatArray>) : AudioSource {
    var opens = 0

    /** Counted down when the capture thread closes the source: its last step before it stops. */
    val closed = java.util.concurrent.CountDownLatch(1)
    private var i = 0
    override var onRouteChanged: (() -> Unit)? = null
    override fun open() {
        opens++
    }
    override fun read(buffer: ShortArray): Int {
        if (i >= frames.size) return -1
        val f = frames[i++]
        for (k in buffer.indices) buffer[k] = (f[k] * 32768f).toInt().toShort()
        return buffer.size
    }
    override fun close() = closed.countDown()
}
