package com.folio.hermeslive.stt

import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.hooks.WavPcm
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug and staging only (`src/hooks`, never in release): feeds 16 kHz mono WAV files through
 * the **real** VAD and speech-to-text pipeline in place of the mic (S-F's emulator end-to-end
 * tests, through `BuildHooks.cascadeAudio`; S-C's corpus bench; the staging smoke test).
 *
 * - [pace] 1.0 is real time, 4.0 four times faster, 0 as fast as the pipeline goes. The
 *   pipeline is sample-driven (VAD and segments count samples), so the text does not depend on
 *   the pace; only wall-clock timings (pause timers, second-pass waits) do.
 * - Between clips, and when the queue is empty, it plays silence at the same pace
 *   ([Idle.SILENCE], like a quiet room) or returns no samples until the next clip ([Idle.WAIT]).
 * - [mix], if set, is called on every chunk before it is returned (e.g. to add Bill's echo).
 *
 * Nothing is recorded; the files are test input that never ships.
 */
class CorpusAudioSource(
    private val pace: Double = 1.0,
    private val idle: Idle = Idle.SILENCE,
) : AudioSource {
    enum class Idle { SILENCE, WAIT }

    private class Clip(val samples: ShortArray, val id: String)

    private val queue = LinkedBlockingDeque<Clip>()
    private var current: Clip? = null
    private var at = 0
    private var startNanos = 0L
    private val emitted = AtomicLong(0)
    private val emptyReads = AtomicLong(0)

    @Volatile var currentId: String? = null
        private set

    /** Clip id -> the sample index where it started (for latency measurements). */
    val clipStarts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    var mix: ((startSample: Long, chunk: ShortArray, count: Int) -> Unit)? = null

    override var onRouteChanged: (() -> Unit)? = null

    /** Samples handed to the pipeline so far. */
    val samplesOut: Long get() = emitted.get()

    /** Queue [file] (16 kHz mono 16-bit), then [silenceAfterMs] of silence. */
    fun enqueue(file: File, silenceAfterMs: Long = 1_500L) = enqueue(read(file), file.name, silenceAfterMs)

    fun enqueue(samples: ShortArray, id: String, silenceAfterMs: Long = 1_500L) {
        val silence = ShortArray((silenceAfterMs * AudioSource.SAMPLE_RATE / 1000).toInt())
        queue.put(Clip(samples + silence, id))
    }

    /**
     * Waits until everything queued has been read and the pipeline has asked for more (so it
     * has processed the last chunk). False on timeout.
     */
    fun awaitDrained(timeoutMs: Long): Boolean {
        val mark = emptyReads.get()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && current == null && emptyReads.get() > mark) return true
            Thread.sleep(2)
        }
        return false
    }

    override fun open() {
        startNanos = System.nanoTime()
    }

    override fun read(buffer: ShortArray): Int {
        var clip = current
        if (clip == null) {
            clip = if (idle == Idle.WAIT) queue.poll(50, TimeUnit.MILLISECONDS) else queue.poll()
            if (clip == null) {
                emptyReads.incrementAndGet()
                currentId = null
                if (idle == Idle.WAIT) {
                    // The clock restarts with the next clip: no catch-up burst after a wait.
                    startNanos = System.nanoTime() - nanosFor(emitted.get())
                    return 0
                }
                buffer.fill(0)
                return paced(buffer, buffer.size)
            }
            current = clip
            currentId = clip.id
            clipStarts[clip.id] = emitted.get()
            at = 0
        }
        val n = minOf(buffer.size, clip.samples.size - at)
        System.arraycopy(clip.samples, at, buffer, 0, n)
        at += n
        if (at >= clip.samples.size) current = null
        return paced(buffer, n)
    }

    override fun close() = Unit

    private fun paced(buffer: ShortArray, n: Int): Int {
        mix?.invoke(emitted.get(), buffer, n)
        val total = emitted.addAndGet(n.toLong())
        if (pace > 0) {
            val due = startNanos + nanosFor(total)
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        return n
    }

    /** `System.nanoTime()` at which sample [sample] is due (paced sources only). */
    fun nanosAt(sample: Long): Long = startNanos + nanosFor(sample)

    private fun nanosFor(samples: Long): Long =
        if (pace <= 0) 0 else (samples * 1_000_000_000.0 / AudioSource.SAMPLE_RATE / pace).toLong()

    companion object {
        /** 16 kHz mono 16-bit samples of [file]; throws for any other format. */
        fun read(file: File): ShortArray {
            val pcm = WavPcm.parse(file.readBytes())
            require(pcm.sampleRate == AudioSource.SAMPLE_RATE) { "${file.name}: ${pcm.sampleRate} Hz, need 16000" }
            return WavPcm.mono(pcm)
        }
    }
}
