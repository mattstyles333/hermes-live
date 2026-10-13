package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterException
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechAudio
import com.folio.hermes.openrouter.SpeechClient
import com.folio.hermes.openrouter.SpeechDelivery
import com.folio.hermes.openrouter.SpeechFormat
import java.io.ByteArrayOutputStream

/**
 * Text → a whole PCM clip in Bill's voice: from the [TtsCache] when it is there (no request),
 * otherwise one OpenRouter TTS request (whole clip; that is what OpenRouter returns), then cached.
 * [voice] and [model] are read on every call so a Settings change applies at once.
 */
class BillVoice(
    private val speech: SpeechClient,
    private val cache: TtsCache,
    private val voice: () -> String,
    private val model: () -> String,
    private val format: SpeechFormat.Pcm = SpeechFormat.Pcm(),
) {
    fun key(text: String): String = TtsCache.key(model(), voice(), format, text)

    /** True if [text] is cached (no read, no request). */
    fun isCached(text: String): Boolean = cache.contains(key(text))

    /** The clip if it is cached (no request). */
    fun cached(text: String): AudioClip? = cache.get(key(text))

    /** The clip, from the cache or one TTS request. */
    suspend fun clip(text: String): OpenRouterResult<AudioClip> {
        val key = key(text)
        cache.get(key)?.let { return OpenRouterResult.Ok(it) }
        return try {
            var actual: SpeechFormat = format
            val out = ByteArrayOutputStream()
            speech.speak(text, format, SpeechDelivery.WholeClip, voice(), model()).collect { part ->
                when (part) {
                    is SpeechAudio.Started -> actual = part.format
                    is SpeechAudio.Chunk -> out.write(part.bytes)
                    is SpeechAudio.Finished -> Unit
                }
            }
            val clip = AudioClip(out.toByteArray(), actual as? SpeechFormat.Pcm ?: format)
            cache.put(key, clip)
            OpenRouterResult.Ok(clip)
        } catch (e: OpenRouterException) {
            e.failure
        }
    }
}
