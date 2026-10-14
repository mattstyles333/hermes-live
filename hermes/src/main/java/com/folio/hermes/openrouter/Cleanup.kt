package com.folio.hermes.openrouter

import com.folio.hermes.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the cleanup model gets for one committed turn (text only, never audio): [heard] (the best
 * transcript: the accurate pass, else the fast one), [other] (the fast pass), [unsure] (the words
 * the recogniser was unsure of) and [vocabulary] (the user's vocabulary, task labels and learned
 * corrections).
 */
data class CleanupInput(
    val heard: String,
    val other: String,
    val unsure: List<String> = emptyList(),
    val vocabulary: List<String> = emptyList(),
)

/**
 * Transcript cleanup on chat completions (hub decision 2026-10-14, `hermes-cascade-cleanup-bench`):
 * `deepseek/deepseek-v4.1-flash`, reasoning off, temperature 0, a JSON reply, and the bench's
 * constrained prompt ([SYSTEM], pinned by `CleanupClientTest`). No retry: the turn waits at most
 * `cleanupWaitMs` for it. Any failure, or a reply without a usable `text`, is a failure, and the
 * caller keeps the heard text. Telemetry (`openrouter.call`, call `cleanup`) never carries text.
 */
class CleanupClient internal constructor(private val router: OpenRouter) {
    suspend fun clean(input: CleanupInput, timeoutMs: Long): OpenRouterResult<String> {
        val key = router.currentKey() ?: return router.noKey(LABEL)
        val request = router.postJson("/chat/completions", key, body(router.config.cleanupModel, input))
        return router.call(LABEL, request, timeoutMs) { response ->
            WriterClient.parseChat(response.body.string())?.let(::parseText)?.let { OpenRouterResult.Ok(it) }
                ?: OpenRouterResult.Failed(FailureKind.BadBody, response.code)
        }
    }

    companion object {
        const val LABEL = "cleanup"
        const val MAX_TOKENS = 600

        /** The bench's prompt, word for word (`jevprobe/cleanup.py` `SYSTEM`). */
        const val SYSTEM: String =
            "You correct speech-to-text mistakes in one voice command a business owner said to their assistant. " +
                "You get: `heard` (the best transcript), `other` (a second recogniser's transcript of the same audio), `unsure` " +
                "(words the recogniser was unsure of), and `vocabulary` (names the user often says).\n" +
                "Rules:\n- Change a word only if it is in `unsure`, differs between `heard` and `other`, or is clearly a sound-alike " +
                "mis-hearing of a vocabulary name (e.g. 'bright well' -> 'Brightwell').\n" +
                "- Never replace an ordinary word with a vocabulary name unless they sound alike. Never invent names, numbers, dates or " +
                "quantities. Fix a clipped word only when the intended word is obvious from context (e.g. 'in sto' -> 'in stock').\n" +
                "- If unsure, keep the `heard` word. Keep everything else exactly as heard.\n" +
                "Reply with JSON only: {\"text\": \"<corrected command>\"}"

        /** The user message: the input as one JSON object (the bench's shape). */
        fun user(input: CleanupInput): String = buildJsonObject {
            put("heard", input.heard)
            put("other", input.other)
            putJsonArray("unsure") { input.unsure.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("vocabulary") { input.vocabulary.forEach { add(JsonPrimitive(it)) } }
        }.toString()

        fun body(model: String, input: CleanupInput): JsonObject = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", SYSTEM)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user(input))
                }
            }
            putJsonObject("reasoning") { put("enabled", false) }
        }

        private val OBJECT = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL)

        /** The `text` of the first JSON object in [content] (as the bench reads it); null if there is none. */
        fun parseText(content: String): String? = runCatching {
            val obj = HermesJson.parseToJsonElement(OBJECT.find(content)!!.value).jsonObject
            (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
