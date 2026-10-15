package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechClient
import com.folio.hermes.openrouter.SpeechFormat

/**
 * Text → a whole PCM clip in Bill's voice: from the [TtsCache] when it is there (no request),
 * otherwise one request to the [source] (OpenRouter TTS, or the user's Hermes server), then cached
 * under the identity that actually spoke it. The source (and its voice and model) is read on every
 * call so a Settings change applies at once.
 */
class BillVoice(
    private val cache: TtsCache,
    private val source: () -> SpeechSource,
    private val format: SpeechFormat.Pcm = SpeechFormat.Pcm(),
) {
    /** OpenRouter's voice (today's): [voice] and [model] read per call. Cache keys as before the Hermes voice slice. */
    constructor(
        speech: SpeechClient,
        cache: TtsCache,
        voice: () -> String,
        model: () -> String,
        format: SpeechFormat.Pcm = SpeechFormat.Pcm(),
    ) : this(cache, OpenRouterSpeechSource(speech, voice, model, format).let { s -> { s } }, format)

    fun key(text: String): String = key(source().identity(), text)

    private fun key(id: VoiceIdentity?, text: String): String =
        TtsCache.key(id?.model ?: PENDING, id?.voice ?: PENDING, format, text)

    /** True if [text] is cached (no read, no request). False while the source's identity is unknown. */
    fun isCached(text: String): Boolean = source().identity()?.let { cache.contains(key(it, text)) } ?: false

    /** The clip if it is cached (no request). */
    fun cached(text: String): AudioClip? = source().identity()?.let { cache.get(key(it, text)) }

    /** The clip, from the cache or one TTS request. */
    suspend fun clip(text: String): OpenRouterResult<AudioClip> {
        val src = source()
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
