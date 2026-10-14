package com.folio.hermeslive.data

import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.TtsCache
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.telemetry.TelemetrySink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * xAI text to speech for a Grok voice preview: `POST https://api.x.ai/v1/tts` with `text`,
 * `voice_id`, `language` and `output_format {codec: pcm, sample_rate: 24000}` (docs.x.ai
 * 2026-10-14; raw 16-bit mono PCM back). A preview line is about 50 characters, a fraction of a
 * cent. Never logs the key or the text. Not run against xAI here (no key in this environment).
 */
class XaiTts(
    client: OkHttpClient,
    private val url: String = "https://api.x.ai/v1/tts",
    private val telemetry: TelemetrySink = TelemetrySink.Default,
) {
    private val http = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun speak(key: String, voice: String, text: String): AudioClip? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("text", text)
            put("voice_id", voice)
            put("language", "en")
            putJsonObject("output_format") {
                put("codec", "pcm")
                put("sample_rate", RATE)
            }
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build()
        var status: Int? = null
        val clip = try {
            http.newCall(request).execute().use { r ->
                status = r.code
                if (r.isSuccessful) r.body.bytes().takeIf { it.size >= 2 }?.let { AudioClip(if (it.size % 2 == 0) it else it.copyOf(it.size - 1), SpeechFormat.Pcm(RATE)) } else null
            }
        } catch (e: IOException) {
            null
        }
        runCatching { telemetry.event("xai.tts", mapOf("ok" to (clip != null), "http_status" to status), clip == null) }
        clip
    }

    companion object {
        const val RATE = 24_000
        const val CACHE_MODEL = "xai-tts"
    }
}

/** What a preview did. */
sealed interface PreviewOutcome {
    data object Played : PreviewOutcome

    /** No key for this engine's voices. */
    data object NoKey : PreviewOutcome

    /** The request failed (offline, refused, unknown voice). */
    data object Failed : PreviewOutcome
}

/**
 * "Preview" in Settings › Voice: one short sample line in a voice, through that engine's TTS,
 * cached ([TtsCache], keyed by model, voice and text) so a second preview of the same voice makes
 * no request.
 */
class VoicePreview(
    private val cache: () -> TtsCache,
    /** Custom: OpenRouter TTS for (model, voice), or null without a usable key. */
    private val openRouterClip: (suspend (model: String, voice: String, text: String) -> AudioClip?)?,
    /** Grok: the xAI key, or null. */
    private val xaiKey: () -> String?,
    private val xai: XaiTts,
    private val play: suspend (AudioClip) -> Unit,
) {
    suspend fun custom(model: String, voice: String): PreviewOutcome {
        val fetch = openRouterClip ?: return PreviewOutcome.NoKey
        val clip = cached(model, voice) { fetch(model, voice, LINE) } ?: return PreviewOutcome.Failed
        play(clip)
        return PreviewOutcome.Played
    }

    suspend fun grok(voice: String): PreviewOutcome {
        val key = xaiKey()?.trim()?.takeIf { it.isNotEmpty() }
        val known = cache().get(TtsCache.key(XaiTts.CACHE_MODEL, voice, FORMAT_24K, LINE))
        if (known == null && key == null) return PreviewOutcome.NoKey
        val clip = known ?: cached(XaiTts.CACHE_MODEL, voice, FORMAT_24K) { xai.speak(key!!, voice, LINE) } ?: return PreviewOutcome.Failed
        play(clip)
        return PreviewOutcome.Played
    }

    private suspend fun cached(model: String, voice: String, format: SpeechFormat = SpeechFormat.Pcm(), fetch: suspend () -> AudioClip?): AudioClip? {
        val c = cache()
        val k = TtsCache.key(model, voice, format, LINE)
        c.get(k)?.let { return it }
        val clip = fetch() ?: return null
        runCatching { c.put(k, clip) }
        return clip
    }

    companion object {
        /** The sample line: no names (any voice may say it). */
        const val LINE = "Hi. This is how I'll sound when I tell you what your agents found."
        private val FORMAT_24K = SpeechFormat.Pcm(XaiTts.RATE)
    }
}
