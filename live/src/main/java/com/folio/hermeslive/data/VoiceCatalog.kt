package com.folio.hermeslive.data

import com.folio.hermes.realtime.GrokVoices
import com.folio.hermes.telemetry.TelemetrySink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** One OpenRouter text-to-speech model and the voices it takes (`supported_voices`). */
data class TtsModelInfo(val id: String, val name: String, val voices: List<String>, val usdPerMillionChars: Double? = null)

/** One voice in a picker: [id] is what is sent, [name] what is shown. */
data class VoiceOption(val id: String, val name: String = id.replaceFirstChar { it.uppercase() })

/** Where a voice list came from (Settings shows it under the list). */
enum class VoiceSource { Live, Cached, BuiltIn }

data class VoiceList<T>(val items: List<T>, val source: VoiceSource)

/**
 * The voices each engine offers (ondemand slice, Settings › Voice):
 *
 * - **Custom (OpenRouter TTS):** `GET https://openrouter.ai/api/v1/models?output_modalities=speech`
 *   (public, no key sent; checked 2026-10-14: 35 speech models, each with `supported_voices`, e.g.
 *   `elevenlabs/eleven-v4-turbo` has 21 including `bill`). Cached in a file for [ttlMs]; a failed
 *   fetch falls back to the cache, then to the built-in default (Bill on ElevenLabs v4 Turbo).
 * - **Grok Voice (xAI):** `GET https://api.x.ai/v1/tts/voices` with the user's xAI key
 *   (`{"voices":[{"voice_id","name"}]}`, docs.x.ai 2026-10-14), cached the same way; without a
 *   key, or when it fails, the documented built-in voices ([GrokVoices.BUILT_IN]).
 *
 * Telemetry: counts, sources and status codes only.
 */
class VoiceCatalog(
    client: OkHttpClient,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DAY_MS,
    private val openRouterUrl: String = OPENROUTER_MODELS_URL,
    private val xaiUrl: String = XAI_VOICES_URL,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
) {
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /** The speech models and their voices; [force] skips a fresh cache. */
    suspend fun ttsModels(force: Boolean = false): VoiceList<TtsModelInfo> = withContext(Dispatchers.IO) {
        val file = File(cacheDir, OPENROUTER_CACHE)
        if (!force) fresh(file)?.let { body -> parseOpenRouter(body).takeIf { it.isNotEmpty() }?.let { return@withContext VoiceList(it, VoiceSource.Cached) } }
        val live = fetch(Request.Builder().url(openRouterUrl).header("Accept", "application/json").build(), "openrouter")
        val parsed = live?.let(::parseOpenRouter).orEmpty()
        if (parsed.isNotEmpty()) {
            save(file, live!!)
            return@withContext VoiceList(parsed, VoiceSource.Live)
        }
        stale(file)?.let(::parseOpenRouter)?.takeIf { it.isNotEmpty() }?.let { return@withContext VoiceList(it, VoiceSource.Cached) }
        VoiceList(listOf(DEFAULT_TTS), VoiceSource.BuiltIn)
    }

    /** Grok's voices: the live list with [key], else the documented built-in ones. */
    suspend fun grokVoices(key: String?, force: Boolean = false): VoiceList<VoiceOption> = withContext(Dispatchers.IO) {
        val builtIn = VoiceList(GrokVoices.BUILT_IN.map { VoiceOption(it) }, VoiceSource.BuiltIn)
        val file = File(cacheDir, XAI_CACHE)
        if (!force) fresh(file)?.let { body -> parseXai(body).takeIf { it.isNotEmpty() }?.let { return@withContext VoiceList(it, VoiceSource.Cached) } }
        val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext builtIn
        val live = fetch(Request.Builder().url(xaiUrl).header("Authorization", "Bearer $k").header("Accept", "application/json").build(), "xai")
        val parsed = live?.let(::parseXai).orEmpty()
        if (parsed.isNotEmpty()) {
            save(file, live!!)
            return@withContext VoiceList(parsed, VoiceSource.Live)
        }
        stale(file)?.let(::parseXai)?.takeIf { it.isNotEmpty() }?.let { VoiceList(it, VoiceSource.Cached) } ?: builtIn
    }

    private fun fetch(request: Request, what: String): String? {
        val started = clock()
        var status: Int? = null
        val body = try {
            http.newCall(request).execute().use { r ->
                status = r.code
                if (r.isSuccessful) r.body.string() else null
            }
        } catch (e: IOException) {
            null
        }
        runCatching { telemetry.event("voices.fetch", mapOf("source" to what, "ok" to (body != null), "http_status" to status, "ms" to clock() - started), body == null) }
        return body
    }

    private fun fresh(file: File): String? = file.takeIf { it.isFile && clock() - it.lastModified() < ttlMs }?.let { runCatching { it.readText() }.getOrNull() }

    private fun stale(file: File): String? = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    private fun save(file: File, body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            tmp.renameTo(file)
            file.setLastModified(clock())
        }
    }

    companion object {
        const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models?output_modalities=speech"
        const val XAI_VOICES_URL = "https://api.x.ai/v1/tts/voices"
        const val OPENROUTER_CACHE = "openrouter-speech-models.json"
        const val XAI_CACHE = "xai-voices.json"
        const val DAY_MS = 24 * 60 * 60_000L

        /** Bill on ElevenLabs v4 Turbo: the default, and what shows when the list cannot be fetched. */
        val DEFAULT_TTS = TtsModelInfo(LiveSettings.DEFAULT_TTS_MODEL, "ElevenLabs v4 Turbo", listOf(LiveSettings.DEFAULT_VOICE))

        private val json = Json { ignoreUnknownKeys = true }

        /** `data[]` with `supported_voices`; models without voices are left out. Never throws. */
        fun parseOpenRouter(body: String): List<TtsModelInfo> = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val voices = (o["supported_voices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
                if (voices.isEmpty()) return@mapNotNull null
                val prompt = (o["pricing"] as? JsonObject)?.get("prompt")?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: (it as? JsonPrimitive)?.doubleOrNull }
                TtsModelInfo(id, o.str("name") ?: id, voices.distinct(), prompt?.let { it * 1_000_000 })
            }
        }.getOrDefault(emptyList())

        /** `voices[]` of `{voice_id, name}`. Never throws. */
        fun parseXai(body: String): List<VoiceOption> = runCatching {
            json.parseToJsonElement(body).jsonObject["voices"]!!.jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("voice_id")?.lowercase() ?: return@mapNotNull null
                VoiceOption(id, o.str("name") ?: id.replaceFirstChar { it.uppercase() })
            }
        }.getOrDefault(emptyList())

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        /** The voice to keep after choosing [model]: the current one if it takes it, else Bill, else its first. */
        fun voiceFor(model: TtsModelInfo, current: String): String = when {
            current in model.voices -> current
            LiveSettings.DEFAULT_VOICE in model.voices -> LiveSettings.DEFAULT_VOICE
            else -> model.voices.first()
        }
    }
}
