package com.folio.hermeslive.stt

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The accurate pass (R-STT-2, R-STT-3, R-STT-4). Each speech segment's PCM is decoded by the
 * offline model **as soon as the segment ends** (on one background thread), so by the time a
 * turn commits most of its audio is already done and [accurate] waits only for the last
 * segment. The PCM is dropped right after its decode: audio lives in memory only until both
 * passes are done, never on disk.
 *
 * [accurate] takes every segment held since the previous [accurate] (or [drop]) call: "the
 * audio of the committed turn". A turn the routing ignores must call [drop], or its audio joins
 * the next turn.
 */
class SecondPass(
    private val engine: OfflineEngine?,
    private val hotwords: () -> String,
    private val uncertainBelow: () -> Double,
    /** Threshold for words both passes agree on (see [TranscriptMerge]). */
    private val agreedBelow: () -> Double = { 0.0 },
    /** The user's words (vocabulary, task labels, corrections): key tokens when the passes disagree ([KeyTokens]). */
    private val vocabulary: () -> List<String> = { emptyList() },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "stt-second-pass").apply { isDaemon = true } },
) {
    private class Held(val fast: List<FastWord>, val audioMs: Long) {
        @Volatile var pcm: FloatArray? = null
        val text = CompletableDeferred<String?>()
        @Volatile var decodeMs: Long = 0
    }

    /** Timings of the last [accurate] call (for the bench and diagnostics; numbers only). */
    data class Timing(val segments: Int, val audioMs: Long, val decodeMs: Long, val waitMs: Long)

    private val held = ArrayList<Held>()

    @Volatile var lastTiming: Timing? = null
        private set

    /** A finished segment (capture thread). Blank fast text is not held: the turn never heard it. */
    fun submit(pcm: FloatArray, fast: List<FastWord>) {
        if (fast.isEmpty()) return
        val h = Held(fast, pcm.size * 1000L / AudioRate.HZ).also { it.pcm = pcm }
        synchronized(held) { held += h }
        val e = engine
        if (e == null) {
            h.pcm = null
            h.text.complete(null)
            return
        }
        runCatching {
            executor.execute {
                val audio = h.pcm
                if (audio == null) {
                    h.text.complete(null)
                    return@execute
                }
                val t0 = System.nanoTime()
                val text = runCatching { e.decode(audio, hotwords()) }.getOrNull()
                h.decodeMs = (System.nanoTime() - t0) / 1_000_000
                h.pcm = null
                h.text.complete(text)
            }
        }.onFailure {
            h.pcm = null
            h.text.complete(null)
        }
    }

    /**
     * The transcript of everything held since the previous call: accurate if every segment's
     * second pass succeeded, else the fast words (`pass: fast`). Null if nothing is held.
     * Suspends until the decodes finish; the caller bounds the wait (`accuratePassWaitMs`).
     */
    suspend fun accurate(): Transcript? {
        val segments = synchronized(held) { held.toList().also { held.clear() } }
        if (segments.isEmpty()) return null
        val t0 = System.nanoTime()
        val texts = segments.map { it.text.await() }
        val waitMs = (System.nanoTime() - t0) / 1_000_000
        lastTiming = Timing(segments.size, segments.sumOf { it.audioMs }, segments.sumOf { it.decodeMs }, waitMs)
        val threshold = uncertainBelow()
        if (texts.any { it == null }) return TranscriptMerge.fastOnly(segments.flatMap { it.fast }, threshold)
        val agreed = agreedBelow()
        val vocab = vocabulary()
        val parts = segments.zip(texts).map { (s, t) -> TranscriptMerge.merge(s.fast, t, threshold, agreed, vocab) }
        return Transcript(
            text = parts.joinToString(" ") { it.text }.trim(),
            words = parts.flatMap { it.words },
            pass = TranscriptPass.Accurate,
        )
    }

    /** Forget held audio and text (an ignored turn, the call ending). */
    fun drop() {
        val gone = synchronized(held) { held.toList().also { held.clear() } }
        gone.forEach { it.pcm = null }
    }

    /** Segments held (tests). */
    val heldCount: Int get() = synchronized(held) { held.size }

    /** Stop the worker, waiting for a running decode (so the engine can be released safely). */
    fun shutdown(timeoutMs: Long = 30_000): Boolean {
        drop()
        executor.shutdown()
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

object AudioRate {
    const val HZ = 16_000
}
