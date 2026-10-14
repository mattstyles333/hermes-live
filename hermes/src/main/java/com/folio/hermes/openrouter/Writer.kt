package com.folio.hermes.openrouter

import com.folio.hermes.HermesJson
import com.folio.hermes.await
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonArray
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
 * The writer model on chat completions (`deepseek/deepseek-v4.1-flash`, reasoning off, as in
 * Folio). It writes every spoken line and every agent prompt; Jev never writes text.
 * A failed call retries [OpenRouterConfig.writerRetries] times after [OpenRouterConfig.retryBackoffMs].
 */
class WriterClient internal constructor(private val router: OpenRouter) {
    /** One whole answer (trimmed, non-empty). */
    suspend fun write(system: String, prompt: String, maxTokens: Int = 200): OpenRouterResult<String> {
        val key = router.currentKey() ?: return router.noKey(LABEL)
        val request = router.postJson("/chat/completions", key, body(router.config.writerModel, system, prompt, maxTokens, stream = false))
        return router.retrying(router.config.writerRetries) {
            router.call(LABEL, request, router.config.writerTimeoutMs) { response ->
                parseChat(response.body.string())?.let { OpenRouterResult.Ok(it) }
                    ?: OpenRouterResult.Failed(FailureKind.BadBody, response.code)
            }
        }
    }

    /**
     * Stream the answer as text deltas (server-sent events). Throws [OpenRouterException] on
     * failure. Retries only while nothing has been emitted, so a caller never hears a line twice.
     * Cancelling collection cancels the HTTP call.
     */
    fun stream(system: String, prompt: String, maxTokens: Int = 200): Flow<String> = flow {
        val key = router.currentKey() ?: throw OpenRouterException(router.noKey(STREAM_LABEL))
        val request = router.postJson(
            "/chat/completions", key, body(router.config.writerModel, system, prompt, maxTokens, stream = true), "text/event-stream",
        )
        var attemptsLeft = router.config.writerRetries
        while (true) {
            var emitted = false
            val started = System.nanoTime()
            var firstMs: Long? = null
            try {
                val call = router.http.newCall(request)
                val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                try {
                    call.await().use { response ->
                        if (!response.isSuccessful) throw OpenRouterException(OpenRouter.statusFailure(response.code))
                        val source = response.body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when (val event = sseLine(line)) {
                                SseLine.Skip -> Unit
                                SseLine.Done -> break
                                is SseLine.Delta -> if (event.text.isNotEmpty()) {
                                    if (firstMs == null) firstMs = (System.nanoTime() - started) / 1_000_000
                                    emitted = true
                                    emit(event.text)
                                }
                                is SseLine.Error -> throw OpenRouterException(OpenRouterResult.Failed(FailureKind.Http, event.status))
                            }
                        }
                    }
                } finally {
                    handle.dispose()
                }
                router.report(STREAM_LABEL, OpenRouterResult.Ok(Unit), (System.nanoTime() - started) / 1_000_000, mapOf("first_ms" to firstMs))
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val failure = OpenRouter.ioFailure(e)
                router.report(STREAM_LABEL, failure, (System.nanoTime() - started) / 1_000_000)
                if (emitted || attemptsLeft <= 0 || !failure.retryable) throw OpenRouterException(failure)
                attemptsLeft--
                delay(router.config.retryBackoffMs)
            }
        }
    }.flowOnInOrder(router.io)

    /** [stream] cut into speakable pieces by [SentenceSplitter] (lead first). */
    fun sentences(system: String, prompt: String, maxTokens: Int = 200): Flow<String> = flow {
        val splitter = SentenceSplitter()
        stream(system, prompt, maxTokens).collect { delta -> splitter.push(delta).forEach { emit(it) } }
        splitter.finish().forEach { emit(it) }
    }

    internal sealed interface SseLine {
        data object Skip : SseLine
        data object Done : SseLine
        data class Delta(val text: String) : SseLine
        data class Error(val status: Int?) : SseLine
    }

    internal companion object {
        const val LABEL = "writer"
        const val STREAM_LABEL = "writer_stream"

        fun body(model: String, system: String, prompt: String, maxTokens: Int, stream: Boolean): JsonObject = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            if (stream) put("stream", true)
            // V4.1 Flash reasons at "high" by default; a spoken line doesn't need it.
            putJsonObject("reasoning") { put("enabled", false) }
        }

        fun parseChat(body: String): String? = runCatching {
            val obj = HermesJson.parseToJsonElement(body).jsonObject
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        /** One SSE line: `: comment` keep-alives skip, `data: [DONE]` ends, `data: {…}` carries a delta or an error. */
        fun sseLine(line: String): SseLine {
            if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) return SseLine.Skip
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") return SseLine.Done
            val obj = runCatching { HermesJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return SseLine.Skip
            (obj["error"] as? JsonObject)?.let { err ->
                return SseLine.Error((err["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull())
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return SseLine.Skip
            if ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull == "error") return SseLine.Error(null)
            val text = ((choice["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
            return if (text.isNullOrEmpty()) SseLine.Skip else SseLine.Delta(text)
        }
    }
}
