package com.folio.hermes.openrouter

import com.folio.hermes.await
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer

/** What `/audio/speech` should return. */
sealed interface SpeechFormat {
    /** `response_format`. */
    val wire: String

    /** Bytes per whole frame; chunks are cut on frame boundaries. */
    val frameBytes: Int

    /**
     * Raw little-endian PCM. Probed 2026-10-13: OpenRouter's Eleven v4 Turbo answers
     * `audio/pcm;rate=24000;channels=1`, 16-bit; the response header wins over this.
     */
    data class Pcm(val sampleRateHz: Int = DEFAULT_RATE, val channels: Int = 1, val bitsPerSample: Int = 16) : SpeechFormat {
        override val wire: String get() = "pcm"
        override val frameBytes: Int get() = channels * bitsPerSample / 8

        /** Playback length of [bytes] bytes. */
        fun durationMs(bytes: Long): Long = if (frameBytes == 0 || sampleRateHz == 0) 0 else bytes / frameBytes * 1_000 / sampleRateHz

        /** Bytes for [ms] of audio, whole frames. */
        fun bytesFor(ms: Long): Long = ms * sampleRateHz / 1_000 * frameBytes
    }

    /** MP3 (probed: 44.1 kHz mono, 128 kb/s). The player decodes it; no frame alignment here. */
    data object Mp3 : SpeechFormat {
        override val wire: String get() = "mp3"
        override val frameBytes: Int get() = 1
    }

    companion object {
        const val DEFAULT_RATE = 24_000

        /**
         * The format the server says it sent: `audio/pcm;rate=24000;channels=1` sets rate and
         * channels; anything missing comes from [requested].
         */
        fun fromContentType(contentType: String?, requested: SpeechFormat): SpeechFormat {
            if (requested !is Pcm || contentType == null) return requested
            val params = contentType.split(';').drop(1).associate { part ->
                val (k, v) = (part.split('=', limit = 2) + "").let { it[0].trim().lowercase() to it[1].trim() }
                k to v
            }
            return requested.copy(
                sampleRateHz = params["rate"]?.toIntOrNull()?.takeIf { it > 0 } ?: requested.sampleRateHz,
                channels = params["channels"]?.toIntOrNull()?.takeIf { it in 1..8 } ?: requested.channels,
            )
        }
    }
}

/** How [SpeechClient.speak] hands the body over. */
enum class SpeechDelivery {
    /** Emit bytes as they arrive (for a server that streams audio). */
    Streamed,

    /** Read the whole clip, then emit it once (what OpenRouter does today). */
    WholeClip,
}

/** One piece of a speech response. */
sealed interface SpeechAudio {
    /** Headers arrived. [format] is what the server says it sent (rate from `Content-Type`). */
    data class Started(val format: SpeechFormat, val generationId: String?) : SpeechAudio

    /** Audio bytes, whole frames only. */
    class Chunk(val bytes: ByteArray) : SpeechAudio

    /** The body ended. */
    data class Finished(val totalBytes: Long, val durationMs: Long?) : SpeechAudio
}

/**
 * Text to speech on OpenRouter (`POST /audio/speech {model, input, voice, response_format}`),
 * Eleven v4 Turbo with voice `bill` by default. The response is raw audio; whether it streams is
 * the server's business, so both deliveries are handled: [SpeechDelivery.Streamed] emits as bytes
 * arrive, [SpeechDelivery.WholeClip] emits the clip once it is complete. PCM chunks are cut on
 * frame boundaries (an odd byte waits for its pair). A failed request retries
 * [OpenRouterConfig.speechRetries] times after [OpenRouterConfig.retryBackoffMs], only before any audio.
 */
class SpeechClient internal constructor(private val router: OpenRouter) {
    /** Speak [text]. Throws [OpenRouterException] on failure; cancelling collection cancels the call. */
    fun speak(
        text: String,
        format: SpeechFormat = SpeechFormat.Pcm(),
        delivery: SpeechDelivery = SpeechDelivery.WholeClip,
        voice: String = router.config.voice,
        model: String = router.config.ttsModel,
    ): Flow<SpeechAudio> = flow {
        val key = router.currentKey() ?: throw OpenRouterException(router.noKey(LABEL))
        val request = router.postJson("/audio/speech", key, body(model, text, voice, format), "audio/*")
        var attemptsLeft = router.config.speechRetries
        while (true) {
            var emitted = false
            val started = System.nanoTime()
            var firstMs: Long? = null
            var total = 0L
            try {
                val call = router.http.newCall(request)
                val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                var actual: SpeechFormat = format
                try {
                    call.await().use { response ->
                        if (!response.isSuccessful) throw OpenRouterException(OpenRouter.statusFailure(response.code))
                        actual = SpeechFormat.fromContentType(response.header("Content-Type"), format)
                        val head = SpeechAudio.Started(actual, response.header("X-Generation-Id"))
                        // Started goes out with the first audio, so a body that fails before any audio can still retry.
                        suspend fun audio(bytes: ByteArray) {
                            if (!emitted) emit(head)
                            emitted = true
                            emit(SpeechAudio.Chunk(bytes))
                        }
                        val source = response.body.source()
                        val aligner = FrameAligner(actual.frameBytes)
                        val whole = Buffer()
                        val read = Buffer()
                        while (true) {
                            val n = source.read(read, READ_SIZE)
                            if (n == -1L) break
                            if (firstMs == null) firstMs = (System.nanoTime() - started) / 1_000_000
                            total += n
                            if (total > MAX_CLIP_BYTES) throw OpenRouterException(OpenRouterResult.Failed(FailureKind.BadBody, response.code))
                            if (delivery == SpeechDelivery.Streamed) {
                                aligner.push(read.readByteArray())?.let { audio(it) }
                            } else {
                                whole.writeAll(read)
                            }
                        }
                        if (delivery == SpeechDelivery.WholeClip) aligner.push(whole.readByteArray())?.let { audio(it) }
                        if (total == 0L) throw OpenRouterException(OpenRouterResult.Failed(FailureKind.BadBody, response.code))
                    }
                } finally {
                    handle.dispose()
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                router.report(LABEL, OpenRouterResult.Ok(Unit), ms, mapOf("first_byte_ms" to firstMs, "bytes" to total))
                emit(SpeechAudio.Finished(total, (actual as? SpeechFormat.Pcm)?.durationMs(total)))
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val failure = OpenRouter.ioFailure(e)
                router.report(LABEL, failure, (System.nanoTime() - started) / 1_000_000)
                if (emitted || attemptsLeft <= 0 || !failure.retryable) throw OpenRouterException(failure)
                attemptsLeft--
                delay(router.config.retryBackoffMs)
            }
        }
    }.flowOnInOrder(router.io)

    internal companion object {
        const val LABEL = "tts"
        const val READ_SIZE = 8_192L

        /** 20 MB: about 7 minutes of 24 kHz PCM, far beyond one sentence. */
        const val MAX_CLIP_BYTES = 20L * 1024 * 1024

        fun body(model: String, text: String, voice: String, format: SpeechFormat): JsonObject = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", format.wire)
        }
    }
}

/** Passes on whole frames only; a partial frame waits for the rest. */
internal class FrameAligner(private val frameBytes: Int) {
    private var carry = ByteArray(0)

    /** Whole frames from [carry] + [bytes], or null if there is not one yet. */
    fun push(bytes: ByteArray): ByteArray? {
        val all = if (carry.isEmpty()) bytes else carry + bytes
        if (frameBytes <= 1) {
            carry = ByteArray(0)
            return all.takeIf { it.isNotEmpty() }
        }
        val whole = all.size - all.size % frameBytes
        carry = all.copyOfRange(whole, all.size)
        return if (whole == 0) null else all.copyOfRange(0, whole)
    }
}
