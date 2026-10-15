package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechClient
import com.folio.hermes.openrouter.SpeechFormat

/**
 * Text → a whole PCM clip in Bill's voice: from the [TtsCache] when it is there (no request),
 * otherwise one request to the [source] (OpenRouter TTS, or the user's Hermes server), then cached.
 * When the source fails, the [fallback] (OpenRouter while a key is saved) speaks that line: from
 * its own cache entry, else one request, cached under its own identity, so the source's key never
 * holds another voice. Both are read on every call so a Settings change applies at once.
 */
class BillVoice(
    private val cache: TtsCache,
    private val source: () -> SpeechSource,
    private val format: SpeechFormat.Pcm = SpeechFormat.Pcm(),
    private val fallback: () -> SpeechSource? = { null },
) {
    /** OpenRouter's voice (today's): [voice] and [model] read per call. Cache keys as before the Hermes voice slice. */
    constructor(
        speech: SpeechClient,
        cache: TtsCache,
        voice: () -> String,
        model: () -> String,
        format: SpeechFormat.Pcm = SpeechFormat.Pcm(),
    ) : this(cache, OpenRouterSpeechSource(speech, voice, model, format).let { s -> { s } }, format, { null })

    fun key(text: String): String = key(source().identity(), text)

    private fun key(id: VoiceIdentity?, text: String): String =
        TtsCache.key(id?.model ?: PENDING, id?.voice ?: PENDING, format, text)

    /** True if [text] is cached (no read, no request). False while the source's identity is unknown. */
    fun isCached(text: String): Boolean = source().identity()?.let { cache.contains(key(it, text)) } ?: false

    /** The clip if it is cached (no request). */
    fun cached(text: String): AudioClip? = source().identity()?.let { cache.get(key(it, text)) }

    /** The clip, from the cache or one TTS request (the fallback's when the source fails). */
    suspend fun clip(text: String): OpenRouterResult<AudioClip> {
        val first = from(source(), text)
        if (first is OpenRouterResult.Ok) return first
        val other = fallback() ?: return first
        return from(other, text)
    }

    private suspend fun from(src: SpeechSource, text: String): OpenRouterResult<AudioClip> {
        val id = src.identity() ?: run {
            src.prepare()
            src.identity()
        }
        if (id != null) cache.get(key(id, text))?.let { return OpenRouterResult.Ok(it) }
        return when (val r = src.synth(text)) {
            is OpenRouterResult.Ok -> {
                cache.put(key(r.value.identity, text), r.value.clip)
                OpenRouterResult.Ok(r.value.clip)
            }
            is OpenRouterResult.Failed -> r
        }
    }

    private companion object {
        const val PENDING = "pending"
    }
}
