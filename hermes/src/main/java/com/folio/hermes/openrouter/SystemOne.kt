package com.folio.hermes.openrouter

import com.folio.hermes.HermesJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One Jev answer to a `choice` question: the picked [choice], its [confidence] and every option's probability. */
@Serializable
data class JevAnswer(
    val type: String = "",
    val choice: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double = 0.0,
)

@Serializable
internal data class SystemOneResponse(val answers: Map<String, JevAnswer> = emptyMap())

/** One `choice` question for System One: Jev picks one key of [criteria] (option → description). */
data class JevQuestion(val instructions: String, val criteria: Map<String, String>)

/**
 * Jev on OpenRouter System One (`POST /systemone {model, state, questions}`). Jev picks options;
 * it never writes text. Same shapes as Folio's `data/OpenRouter.kt`. No retry here: the
 * TurnTaker's commit call is the retry, and a turn cannot wait 2 s more.
 */
class SystemOneClient internal constructor(private val router: OpenRouter) {
    /** Ask [questions] (name → question) about [state] in one call. Ok holds at least one answer. */
    suspend fun decide(
        state: JsonObject,
        questions: Map<String, JevQuestion>,
        timeoutMs: Long = router.config.jevTimeoutMs,
    ): OpenRouterResult<Map<String, JevAnswer>> {
        val key = router.currentKey() ?: return router.noKey(LABEL)
        val request = router.postJson("/systemone", key, body(router.config.jevModel, state, questions))
        return router.call(LABEL, request, timeoutMs) { response ->
            parse(response.body.string())?.let { OpenRouterResult.Ok(it) } ?: OpenRouterResult.Failed(FailureKind.BadBody, response.code)
        }
    }

    internal companion object {
        const val LABEL = "jev"

        fun body(model: String, state: JsonObject, questions: Map<String, JevQuestion>): JsonObject = buildJsonObject {
            put("model", model)
            put("state", state)
            putJsonObject("questions") {
                questions.forEach { (name, q) ->
                    putJsonObject(name) {
                        put("type", "choice")
                        put("instructions", q.instructions)
                        putJsonObject("criteria") { q.criteria.forEach { (option, description) -> put(option, description) } }
                    }
                }
            }
        }

        fun parse(body: String): Map<String, JevAnswer>? =
            runCatching { HermesJson.decodeFromString(SystemOneResponse.serializer(), body).answers }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
    }
}
