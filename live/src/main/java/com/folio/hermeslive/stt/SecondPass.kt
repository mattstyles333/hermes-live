package com.folio.hermeslive.stt

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
 *
 * **Cloud mode** ([cloudModel] not null, a [cloud] given): each segment's audio also goes, as an
 * in-memory WAV, to the cloud model as soon as the segment ends, and its text is reported at once
 * ([onCloud]: the caption, and Jev when it differs materially). The on-device decode still runs:
 * [accurate] returns when it would without the cloud (the on-device text is in), taking each
 * segment's cloud text if it is in by then, else the on-device text; if every cloud text is in
 * first it returns sooner. So a turn never waits longer because of the cloud.
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
    /** The cloud pass; null: on device only. */
    private val cloud: CloudStt? = null,
    /** The cloud model now (`CascadeSettings.cloudSttModel`), read as each segment ends; null: on device. */
    private val cloudModel: () -> String? = { null },
    private val cloudTimeoutMs: () -> Long = { 4_000L },
    /** A segment's cloud text arrived: (its fast text, as its `Final` read; the cloud text). Any thread. */
    private val onCloud: (fast: String, text: String) -> Unit = { _, _ -> },
    private val cloudScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Held(val fast: List<FastWord>, val audioMs: Long) {
        @Volatile var pcm: FloatArray? = null
        val text = CompletableDeferred<String?>()
        @Volatile var decodeMs: Long = 0

        /** The cloud text (null: failed); absent when the segment was not sent. */
        var cloud: CompletableDeferred<String?>? = null
        var upload: Job? = null
        @Volatile var cloudMs: Long = 0
    }

    /**
     * Timings of the last [accurate] call (for the bench and diagnostics; numbers only).
     * [cloudSent]: segments sent to the cloud; [cloudUsed]: of those, whose cloud text was used.
     */
    data class Timing(val segments: Int, val audioMs: Long, val decodeMs: Long, val waitMs: Long, val cloudSent: Int = 0, val cloudUsed: Int = 0, val cloudMs: Long = 0)

    private val held = ArrayList<Held>()

    @Volatile var lastTiming: Timing? = null
        private set

    /** A finished segment (capture thread). Blank fast text is not held: the turn never heard it. */
    fun submit(pcm: FloatArray, fast: List<FastWord>) {
        if (fast.isEmpty()) return
        val h = Held(fast, pcm.size * 1000L / AudioRate.HZ).also { it.pcm = pcm }
        synchronized(held) { held += h }
        upload(h, pcm)
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

    /** Cloud mode: send [pcm] now, as a WAV in memory; its text completes [Held.cloud]. */
    private fun upload(h: Held, pcm: FloatArray) {
        val c = cloud ?: return
        val model = cloudModel()?.takeIf { it.isNotBlank() } ?: return
        val result = CompletableDeferred<String?>()
        h.cloud = result
        val fastText = Casing.sentence(h.fast.map { it.text })
        val terms = runCatching { vocabulary() }.getOrDefault(emptyList())
        val timeout = cloudTimeoutMs()
        h.upload = cloudScope.launch {
            val t0 = System.nanoTime()
            val text = try {
                c.transcribe(Wav.encode(pcm), model, terms, timeout)?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                result.complete(null)
                throw e
            } catch (e: Exception) {
                null
            }
            h.cloudMs = (System.nanoTime() - t0) / 1_000_000
            result.complete(text)
            if (text != null) runCatching { onCloud(fastText, text) }
        }.also { job -> job.invokeOnCompletion { result.complete(null) } }
    }

    /**
     * One segment's accurate text: the cloud's if it is in by the time the on-device text is (or
     * comes first), else the on-device text. Without a cloud: the on-device text, as before.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun textOf(h: Held): Pair<String?, Boolean> {
        val c = h.cloud ?: return h.text.await() to false
        val cloudFirst = select {
            c.onAwait { it }
            h.text.onAwait { null }
        }
        if (cloudFirst != null) return cloudFirst to true
        val device = h.text.await()
        val late = if (c.isCompleted) c.getCompleted() else null
        return if (late != null) late to true else device to false
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
        val chosen = segments.map { textOf(it) }
        val texts = chosen.map { it.first }
        val waitMs = (System.nanoTime() - t0) / 1_000_000
        segments.forEach { it.upload?.cancel() }
        val sent = segments.count { it.cloud != null }
        lastTiming = Timing(
            segments.size, segments.sumOf { it.audioMs }, segments.sumOf { it.decodeMs }, waitMs,
            cloudSent = sent, cloudUsed = chosen.count { it.second }, cloudMs = segments.maxOfOrNull { it.cloudMs } ?: 0,
        )
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
        gone.forEach {
            it.pcm = null
            it.upload?.cancel()
        }
    }

    /** Segments held (tests). */
    val heldCount: Int get() = synchronized(held) { held.size }

    /** Stop the worker, waiting for a running decode (so the engine can be released safely). */
    fun shutdown(timeoutMs: Long = 30_000): Boolean {
        drop()
        cloudScope.cancel()
        executor.shutdown()
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

object AudioRate {
    const val HZ = 16_000
}
