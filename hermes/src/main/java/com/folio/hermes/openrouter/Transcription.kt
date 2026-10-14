package com.folio.hermes.openrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * How a cloud speech-to-text model takes the user's words (vocabulary, task labels, learned
 * corrections), as OpenRouter forwards them. Data, not code: the hub picks models by benchmark.
 */
sealed interface SttVocabulary {
    /** Under `provider.options.<provider>.<field>` as a list of terms (e.g. AssemblyAI `keyterms_prompt`). */
    data class ProviderTerms(val provider: String, val field: String) : SttVocabulary

    /** Under `provider.options.<provider>.<field>` as one sentence ("Vocabulary: a, b, c."). */
    data class ProviderPrompt(val provider: String, val field: String) : SttVocabulary
}

/** One selectable cloud speech-to-text model. [vocabulary] null: the model is sent no words. */
data class CloudSttModel(
    val id: String,
    val label: String,
    /** One line for Settings (speed, accuracy). */
    val note: String,
    /** About this much per hour of speech, US dollars (OpenRouter's list price), or null if unknown. */
    val dollarsPerHour: Double?,
    val vocabulary: SttVocabulary? = null,
    /** Most terms sent per request. */
    val maxTerms: Int = 100,
)

/**
 * The models Settings offers (hub bench 2026-10-14, clean corpus; the hub re-benchmarks and sets
 * the default). Keyterms were probed live through OpenRouter on 2026-10-14: AssemblyAI's
 * `keyterms_prompt` fixed "Holden"/"Haldon" → "Halden" (2 of 2); ElevenLabs `keyterms` /
 * `biased_keywords` and OpenAI `prompt` changed nothing, so those models get no words.
 */
object CloudSttModels {
    val ASSEMBLYAI = CloudSttModel(
        "assemblyai/universal-3-5-pro", "AssemblyAI Universal 3.5 Pro", "Fastest (about 0.5 s), 2.4% word errors on the bench. Uses your words.",
        dollarsPerHour = 0.45, vocabulary = SttVocabulary.ProviderTerms("assemblyai", "keyterms_prompt"),
    )
    val ELEVENLABS = CloudSttModel(
        "elevenlabs/scribe-v2", "ElevenLabs Scribe v2", "Most accurate on the bench (2.0%), slower (about 0.9 s).",
        dollarsPerHour = 0.22,
    )
    val OPENAI = CloudSttModel(
        "openai/gpt-transcribe", "OpenAI GPT Transcribe", "2.3% word errors, about 0.8 s.",
        dollarsPerHour = 0.27,
    )

    val ALL: List<CloudSttModel> = listOf(ASSEMBLYAI, ELEVENLABS, OPENAI)
    val DEFAULT: CloudSttModel = ASSEMBLYAI

    /** The listed model, or an unlisted (custom) id with no vocabulary and no price. */
    fun of(id: String): CloudSttModel = ALL.firstOrNull { it.id == id } ?: CloudSttModel(id, id, "Custom model.", dollarsPerHour = null)
}

/**
 * `POST /audio/transcriptions` (OpenAI-compatible multipart: `model`, `file`, and `provider` as a
 * JSON string when the model takes the user's words). The response is `{"text", "usage"}`.
 *
 * Privacy: the audio goes in the request body only; it is never logged or kept. Telemetry is
 * `cloud_stt.ok` / `cloud_stt.timeout` / `cloud_stt.error` with the model id, ms, bytes and a
 * failure kind; never the text, the words or the key. No key: no request.
 */
class TranscriptionClient internal constructor(private val or: OpenRouter) {
    /** Transcribe one 16 kHz mono WAV ([wav]). [terms]: the user's words, sent only if [model] takes them. */
    suspend fun transcribe(wav: ByteArray, model: CloudSttModel, terms: List<String>, timeoutMs: Long): OpenRouterResult<String> {
        val started = System.nanoTime()
        val k = or.currentKey() ?: return OpenRouterResult.Failed(FailureKind.NoKey).also { report(model, it, 0L, wav.size) }
        val request = or.builder(PATH, k).header("Accept", "application/json").post(body(wav, model, terms)).build()
        val result = or.call<String>("stt", request, timeoutMs, reportsItself = true) { response ->
            val text = runCatching {
                Json.parseToJsonElement(response.body.string()).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (text == null) OpenRouterResult.Failed(FailureKind.BadBody) else OpenRouterResult.Ok(text.trim())
        }
        report(model, result, (System.nanoTime() - started) / 1_000_000, wav.size)
        return result
    }

    private fun report(model: CloudSttModel, result: OpenRouterResult<*>, ms: Long, bytes: Int) {
        val failed = result as? OpenRouterResult.Failed
        val name = when {
            failed == null -> EVENT_OK
            failed.kind == FailureKind.Timeout -> EVENT_TIMEOUT
            else -> EVENT_ERROR
        }
        val attrs = linkedMapOf<String, Any?>("model" to model.id, "ms" to ms, "bytes" to bytes)
        failed?.let {
            attrs["kind"] = it.kind.wire
            it.httpStatus?.let { s -> attrs["http_status"] = s }
        }
        runCatching { or.telemetry.event(name, attrs, warn = failed != null && failed.kind != FailureKind.NoKey) }
    }

    companion object {
        const val PATH = "/audio/transcriptions"
        const val EVENT_OK = "cloud_stt.ok"
        const val EVENT_TIMEOUT = "cloud_stt.timeout"
        const val EVENT_ERROR = "cloud_stt.error"
        private val WAV = "audio/wav".toMediaType()

        /** The multipart body (tests read it back). */
        internal fun body(wav: ByteArray, model: CloudSttModel, terms: List<String>): MultipartBody {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model.id)
                .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            provider(model, terms)?.let { b.addFormDataPart("provider", it) }
            return b.build()
        }

        /** `{"options":{"<provider>":{"<field>": …}}}` for [model]'s vocabulary, or null (no words, or none it takes). */
        internal fun provider(model: CloudSttModel, terms: List<String>): String? {
            val v = model.vocabulary ?: return null
            val words = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(model.maxTerms)
            if (words.isEmpty()) return null
            val (provider, field) = when (v) {
                is SttVocabulary.ProviderTerms -> v.provider to v.field
                is SttVocabulary.ProviderPrompt -> v.provider to v.field
            }
            return buildJsonObject {
                putJsonObject("options") {
                    putJsonObject(provider) {
                        when (v) {
                            is SttVocabulary.ProviderTerms -> put(field, JsonArray(words.map(::JsonPrimitive)))
                            is SttVocabulary.ProviderPrompt -> put(field, "Vocabulary: " + words.joinToString(", ") + ".")
                        }
                    }
                }
            }.toString()
        }
    }
}
