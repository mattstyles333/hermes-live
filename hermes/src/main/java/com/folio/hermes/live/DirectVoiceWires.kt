package com.folio.hermes.live

import com.folio.hermes.await
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The five wires the phone may call itself when Hermes's `voice-config` says `direct`, with the
 * profile's key (from [VoiceSide.Direct], memory only). Shapes as `tools/voice_client_config.py`
 * and the server's own provider code (`tools/transcription_cloud.py`, `tools/tts_tool_*.py`):
 *
 * - STT `openai-multipart`: `POST {base_url}/audio/transcriptions`, multipart `file`, `model`,
 *   `language`?, `prompt`? (the user's words, only for OpenAI and Groq, which document it), Bearer → `{text}`.
 * - STT `xai-stt`: `POST {base_url}/stt`, multipart `file`, `language`?, `format=true`, Bearer → `{text}`. No words.
 * - STT `elevenlabs-stt`: `POST {base_url}/speech-to-text`, multipart `file`, `model_id`,
 *   `language_code`?, `xi-api-key` → `{text}`. No words (the server sends none either).
 * - TTS `openai-speech`: `POST {base_url}/audio/speech` JSON `{model, input, voice, response_format:"pcm", speed?, …extra_body}`,
 *   Bearer → raw 24 kHz 16-bit mono PCM (OpenAI); a server that answers WAV or MP3 instead is sniffed.
 * - TTS `elevenlabs-tts`: `POST {base_url}/text-to-speech/{voice_id}?output_format=pcm_24000` JSON
 *   `{text, model_id}`, `xi-api-key` → raw 24 kHz PCM.
 *
 * Nothing here logs; the key goes only in the request header to [VoiceSide.Direct.baseUrl].
 */
class DirectVoiceWires(private val client: OkHttpClient = OkHttpClient()) {
    /** One 16 kHz mono WAV through [side]'s STT. `Ok("")`: no speech. */
    suspend fun transcribe(side: VoiceSide.Direct, wav: ByteArray, terms: List<String>, timeoutMs: Long): OpenRouterResult<String> {
        val request = sttRequest(side, wav, terms) ?: return OpenRouterResult.Failed(FailureKind.BadBody)
        return run(request, timeoutMs) { r ->
            val text = runCatching { Json.parseToJsonElement(r.body.string()).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (text == null) OpenRouterResult.Failed(FailureKind.BadBody, r.code) else OpenRouterResult.Ok(text.trim())
        }
    }

    /** [text] in [voice] (null: the configured one) through [side]'s TTS. */
    suspend fun speak(side: VoiceSide.Direct, text: String, voice: String?, timeoutMs: Long): OpenRouterResult<SpokenBytes> {
        val request = ttsRequest(side, text, voice) ?: return OpenRouterResult.Failed(FailureKind.BadBody)
        return run(request, timeoutMs) { r ->
            val bytes = r.body.source().let { src ->
                src.request(MAX_CLIP_BYTES + 1)
                if (src.buffer.size > MAX_CLIP_BYTES) null else src.buffer.readByteArray()
            }
            if (bytes == null || bytes.isEmpty()) OpenRouterResult.Failed(FailureKind.BadBody, r.code)
            else OpenRouterResult.Ok(SpokenBytes(bytes, r.header("Content-Type")))
        }
    }

    private suspend fun <T> run(request: Request, timeoutMs: Long, read: (Response) -> OpenRouterResult<T>): OpenRouterResult<T> = try {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val c = client.newCall(request)
                val handle = currentCoroutineContext().job.invokeOnCompletion { c.cancel() }
                try {
                    c.await().use { r -> if (r.isSuccessful) read(r) else OpenRouter.statusFailure(r.code) }
                } finally {
                    handle.dispose()
                }
            } ?: OpenRouterResult.Failed(FailureKind.Timeout)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        OpenRouter.ioFailure(e)
    }

    companion object {
        /** 20 MB: far beyond one sentence. */
        const val MAX_CLIP_BYTES = 20L * 1024 * 1024
        const val PCM_RATE = 24_000
        private val WAV = "audio/wav".toMediaType()

        /** Providers whose `openai-multipart` endpoint documents `prompt` (vocabulary hints). */
        val PROMPT_PROVIDERS = setOf("openai", "groq")
        const val MAX_PROMPT_CHARS = 600

        private fun url(side: VoiceSide.Direct, path: String) = (side.baseUrl.trimEnd('/') + path).toHttpUrlOrNull()

        /** The STT request for [side] (tests read it back); null for a wire this client does not speak. */
        internal fun sttRequest(side: VoiceSide.Direct, wav: ByteArray, terms: List<String>): Request? {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            val b = Request.Builder().header("Accept", "application/json")
            when (side.wire) {
                SttWire.OPENAI -> {
                    side.model?.let { form.addFormDataPart("model", it) }
                    side.language?.let { form.addFormDataPart("language", it) }
                    prompt(side, terms)?.let { form.addFormDataPart("prompt", it) }
                    form.addFormDataPart("response_format", "json")
                    b.url(url(side, "/audio/transcriptions") ?: return null)
                    bearer(b, side)
                }
                SttWire.XAI -> {
                    side.language?.let { form.addFormDataPart("language", it) }
                    form.addFormDataPart("format", "true")
                    b.url(url(side, "/stt") ?: return null)
                    bearer(b, side)
                }
                SttWire.ELEVENLABS -> {
                    form.addFormDataPart("model_id", side.model ?: "scribe_v2")
                    side.language?.let { form.addFormDataPart("language_code", it) }
                    b.url(url(side, "/speech-to-text") ?: return null)
                    b.header("xi-api-key", side.apiKey)
                }
                else -> return null
            }
            return b.post(form.build()).build()
        }

        /** The TTS request for [side]; null for a wire this client does not speak. */
        internal fun ttsRequest(side: VoiceSide.Direct, text: String, voice: String?): Request? {
            val chosen = voice?.trim()?.ifEmpty { null } ?: side.voice
            val b = Request.Builder().header("Accept", "audio/*")
            when (side.wire) {
                TtsWire.OPENAI -> {
                    val body = buildJsonObject {
                        side.extraBody.forEach { (k, v) -> put(k, v) }
                        side.model?.let { put("model", it) }
                        put("input", text)
                        chosen?.let { put("voice", it) }
                        put("response_format", "pcm")
                        side.speed?.takeIf { it > 0 && it != 1.0 }?.let { put("speed", it) }
                    }
                    b.url(url(side, "/audio/speech") ?: return null)
                    bearer(b, side)
                    b.post(body.toString().toRequestBody(JSON))
                }
                TtsWire.ELEVENLABS -> {
                    val id = chosen ?: return null
                    val u = url(side, "/text-to-speech/$id")?.newBuilder()?.addQueryParameter("output_format", "pcm_$PCM_RATE")?.build() ?: return null
                    val body = buildJsonObject {
                        put("text", text)
                        side.model?.let { put("model_id", it) }
                    }
                    b.url(u).header("xi-api-key", side.apiKey)
                    b.post(body.toString().toRequestBody(JSON))
                }
                else -> return null
            }
            return b.build()
        }

        private fun bearer(b: Request.Builder, side: VoiceSide.Direct) {
            if (side.apiKey.isNotBlank()) b.header("Authorization", "Bearer ${side.apiKey}")
        }

        /** OpenAI/Groq `prompt`: "Vocabulary: a, b, c." within [MAX_PROMPT_CHARS]; null otherwise. */
        internal fun prompt(side: VoiceSide.Direct, terms: List<String>): String? {
            if (side.provider !in PROMPT_PROVIDERS) return null
            val words = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            if (words.isEmpty()) return null
            val out = StringBuilder("Vocabulary: ")
            for ((i, w) in words.withIndex()) {
                val piece = (if (i == 0) "" else ", ") + w
                if (out.length + piece.length + 1 > MAX_PROMPT_CHARS) break
                out.append(piece)
            }
            return if (out.length == "Vocabulary: ".length) null else out.append('.').toString()
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
