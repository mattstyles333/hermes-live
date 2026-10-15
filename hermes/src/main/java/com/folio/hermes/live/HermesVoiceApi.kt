package com.folio.hermes.live

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesJson
import com.folio.hermes.await
import com.folio.hermes.jsonBody
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterResult
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** `GET /api/audio/elevenlabs/voices`: [available] false when the profile has no ElevenLabs key. */
data class ElevenLabsVoices(val available: Boolean, val voices: List<ElevenLabsVoice>)

/** One voice: [label] is the server's "Name (category)". */
@Serializable
data class ElevenLabsVoice(val voice_id: String, val name: String = voice_id, val label: String = name)

/**
 * Hermes's voice routes (the deployed `hermes_cli/web_routers/audio.py`, read 2026-10-15), with
 * the dashboard's Bearer token (a 401 refreshes once, as [VoiceLiveApi]). Profile: the launch
 * profile (no `?profile=`), as every other route the app calls.
 *
 * - [voiceConfig]: `GET /api/audio/voice-config` → `{ok, stt, tts}` ([HermesVoiceConfig]); the
 *   keys in it are kept in memory only by the caller ([HermesVoice]).
 * - [transcribe]: `POST /api/audio/transcribe {data_url, mime_type}` → `{ok, transcript, provider}`;
 *   25 MB cap (413); "no speech" is `ok` with `""`; other failures 400 `{detail}`.
 * - [speak]: `POST /api/audio/speak {text}` → `{ok, data_url, mime_type, provider}`; the audio is
 *   the profile's provider's file (MP3 by default; Ogg, WAV or FLAC by extension). The body has no
 *   voice field: the server's configured voice always speaks.
 * - [elevenLabsVoices]: `GET /api/audio/elevenlabs/voices` → `{available, voices:[{voice_id, name, label}]}`.
 *
 * Failures are [OpenRouterResult.Failed] kinds (the cascade's one failure vocabulary): signed out
 * is [FailureKind.NoKey], a refused token [FailureKind.Refused]. Never logs a body.
 */
class HermesVoiceApi(
    private val config: HermesConfig,
    private val tokens: AccessTokenProvider,
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Which direct `base_url`s the phone may call ([HermesVoiceConfig.phoneReachable]; tests widen it to MockWebServer). */
    private val reachable: (String) -> Boolean = HermesVoiceConfig::phoneReachable,
) {
    /** The profile's voice config; `Ok(null)` when the server has none (404: an older server) or answers oddly. */
    suspend fun voiceConfig(timeoutMs: Long = CONFIG_TIMEOUT_MS): OpenRouterResult<HermesVoiceConfig?> =
        call(timeoutMs, notFoundIsNull = true, { get(PATH_CONFIG, it) }) { r ->
            val body = runCatching { HermesJson.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
            OpenRouterResult.Ok(body?.let { HermesVoiceConfig.parse(it, nowMs(), reachable) })
        }

    /** One clip through the profile's STT on the server. `Ok("")`: no speech. */
    suspend fun transcribe(audio: ByteArray, mime: String = "audio/wav", timeoutMs: Long): OpenRouterResult<String> {
        val payload = HermesJson.encodeToString(TranscribeRequest.serializer(), TranscribeRequest("data:$mime;base64," + Base64.getEncoder().encodeToString(audio), mime))
        return call(timeoutMs, notFoundIsNull = false, { post(PATH_TRANSCRIBE, payload, it) }) { r ->
            val wire = runCatching { HermesJson.decodeFromString(TranscribeResponse.serializer(), r.body.string()) }.getOrNull()
            if (wire?.ok == true) OpenRouterResult.Ok(wire.transcript.orEmpty().trim()) else OpenRouterResult.Failed(FailureKind.BadBody, r.code)
        }.orFailed()
    }

    /** [text] in the profile's configured voice. */
    suspend fun speak(text: String, timeoutMs: Long = SPEAK_TIMEOUT_MS): OpenRouterResult<SpokenBytes> {
        val payload = HermesJson.encodeToString(SpeakRequest.serializer(), SpeakRequest(text))
        return call(timeoutMs, notFoundIsNull = false, { post(PATH_SPEAK, payload, it) }) { r ->
            val wire = runCatching { HermesJson.decodeFromString(SpeakResponse.serializer(), r.body.string()) }.getOrNull()
            val url = wire?.data_url
            val comma = url?.indexOf(',') ?: -1
            val bytes = if (wire?.ok == true && url != null && url.startsWith("data:") && comma > 0) {
                runCatching { Base64.getDecoder().decode(url.substring(comma + 1)) }.getOrNull()
            } else {
                null
            }
            if (bytes == null || bytes.isEmpty()) {
                OpenRouterResult.Failed(FailureKind.BadBody, r.code)
            } else {
                val mime = wire?.mime_type ?: url!!.substring(5, comma).substringBefore(';')
                OpenRouterResult.Ok(SpokenBytes(bytes, mime))
            }
        }.orFailed()
    }

    /** The profile's ElevenLabs voices (labels from the server). */
    suspend fun elevenLabsVoices(timeoutMs: Long = CONFIG_TIMEOUT_MS): OpenRouterResult<ElevenLabsVoices> =
        call(timeoutMs, notFoundIsNull = true, { get(PATH_VOICES, it) }) { r ->
            val wire = runCatching { HermesJson.decodeFromString(VoicesResponse.serializer(), r.body.string()) }.getOrNull()
            if (wire == null) OpenRouterResult.Failed(FailureKind.BadBody, r.code)
            else OpenRouterResult.Ok(ElevenLabsVoices(wire.available, wire.voices.filter { it.voice_id.isNotBlank() }))
        }.let { if (it is OpenRouterResult.Ok && it.value == null) OpenRouterResult.Ok(ElevenLabsVoices(false, emptyList())) else it.orFailed() }

    private fun get(path: String, token: String): Request =
        Request.Builder().url(config.endpoint.resolve(path)).header("Authorization", "Bearer $token").header("Accept", "application/json").get().build()

    private fun post(path: String, json: String, token: String): Request =
        Request.Builder().url(config.endpoint.resolve(path)).header("Authorization", "Bearer $token").header("Accept", "application/json").post(json.jsonBody()).build()

    /** One authorised request: 401 refreshes once; a 404 is `Ok(null)` when [notFoundIsNull]. */
    private suspend fun <T> call(
        timeoutMs: Long,
        notFoundIsNull: Boolean,
        build: (String) -> Request,
        read: (Response) -> OpenRouterResult<T>,
    ): OpenRouterResult<T?> = try {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val token = tokens.accessToken()
                var response = send(build(token))
                if (response.code == 401) {
                    response.close()
                    response = send(build(tokens.refreshAfterUnauthorized(token)))
                }
                response.use { r ->
                    when {
                        r.isSuccessful -> read(r)
                        r.code == 404 && notFoundIsNull -> OpenRouterResult.Ok(null)
                        else -> OpenRouter.statusFailure(r.code)
                    }
                }
            } ?: OpenRouterResult.Failed(FailureKind.Timeout)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: HermesAuthException.SignInRequired) {
        OpenRouterResult.Failed(FailureKind.NoKey)
    } catch (_: HermesAuthException) {
        OpenRouterResult.Failed(FailureKind.Refused)
    } catch (e: IOException) {
        OpenRouter.ioFailure(e)
    }

    /** Sends [request]; cancelling the coroutine (or its timeout) cancels the call, body read included. */
    private suspend fun send(request: Request): Response {
        val c = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { c.cancel() }
        return try {
            c.await()
        } finally {
            handle.dispose()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> OpenRouterResult<T?>.orFailed(): OpenRouterResult<T> = when (this) {
        is OpenRouterResult.Ok -> if (value == null) OpenRouterResult.Failed(FailureKind.Http, 404) else this as OpenRouterResult<T>
        is OpenRouterResult.Failed -> this
    }

    @Suppress("PropertyName")
    @Serializable internal data class TranscribeRequest(val data_url: String, val mime_type: String)

    @Suppress("PropertyName")
    @Serializable internal data class TranscribeResponse(val ok: Boolean = false, val transcript: String? = null, val provider: String? = null)

    @Serializable internal data class SpeakRequest(val text: String)

    @Suppress("PropertyName")
    @Serializable internal data class SpeakResponse(val ok: Boolean = false, val data_url: String? = null, val mime_type: String? = null, val provider: String? = null)

    @Serializable internal data class VoicesResponse(val available: Boolean = false, val voices: List<ElevenLabsVoice> = emptyList())

    companion object {
        const val PATH_CONFIG = "/api/audio/voice-config"
        const val PATH_TRANSCRIBE = "/api/audio/transcribe"
        const val PATH_SPEAK = "/api/audio/speak"
        const val PATH_VOICES = "/api/audio/elevenlabs/voices"
        const val CONFIG_TIMEOUT_MS = 10_000L
        const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
