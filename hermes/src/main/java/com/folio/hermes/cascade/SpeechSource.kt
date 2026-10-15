package com.folio.hermes.cascade

import com.folio.hermes.openrouter.OpenRouterException
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechAudio
import com.folio.hermes.openrouter.SpeechClient
import com.folio.hermes.openrouter.SpeechDelivery
import com.folio.hermes.openrouter.SpeechFormat
import java.io.ByteArrayOutputStream

/**
 * What a clip is cached under besides its text ([TtsCache.key]). For OpenRouter it is exactly
 * today's (model id, voice), so caches made before the Hermes voice slice stay valid; Hermes
 * sources use `hermes:<provider>/<model>` or `hermes:relay/…` as [model] (no OpenRouter id starts
 * with `hermes:`), so a change of source, provider, model or voice re-renders the stock lines.
 */
data class VoiceIdentity(val model: String, val voice: String)

/** A rendered clip and the identity it was rendered as (a fallback's, when the fallback spoke). */
class SpokenClip(val clip: AudioClip, val identity: VoiceIdentity)

/** Where Bill's voice comes from (OpenRouter, or the user's Hermes server). */
interface SpeechSource {
    /** The identity a clip would be cached under now; null while not known yet (see [prepare]). */
    fun identity(): VoiceIdentity?

    /** Learn [identity] (a Hermes source fetches its config). Default: nothing to do. */
    suspend fun prepare() {}

    /** Render [text] (one request, whole clip). Failures carry no content. */
    suspend fun synth(text: String): OpenRouterResult<SpokenClip>
}

/** OpenRouter TTS (today's voice): `POST /audio/speech`, whole clip, [voice] and [model] read per call. */
class OpenRouterSpeechSource(
    private val speech: SpeechClient,
    private val voice: () -> String,
    private val model: () -> String,
    private val format: SpeechFormat.Pcm = SpeechFormat.Pcm(),
) : SpeechSource {
    override fun identity(): VoiceIdentity = VoiceIdentity(model(), voice())

    override suspend fun synth(text: String): OpenRouterResult<SpokenClip> {
        val id = identity()
        return try {
            var actual: SpeechFormat = format
            val out = ByteArrayOutputStream()
            speech.speak(text, format, SpeechDelivery.WholeClip, id.voice, id.model).collect { part ->
                when (part) {
                    is SpeechAudio.Started -> actual = part.format
                    is SpeechAudio.Chunk -> out.write(part.bytes)
                    is SpeechAudio.Finished -> Unit
                }
            }
            OpenRouterResult.Ok(SpokenClip(AudioClip(out.toByteArray(), actual as? SpeechFormat.Pcm ?: format), id))
        } catch (e: OpenRouterException) {
            e.failure
        }
    }
}
