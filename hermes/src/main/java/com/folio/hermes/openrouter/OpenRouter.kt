package com.folio.hermes.openrouter

import com.folio.hermes.await
import com.folio.hermes.jsonBody
import com.folio.hermes.telemetry.TelemetrySink
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * OpenRouter for the cascade: [jev] decides (System One), [writer] writes the spoken lines and
 * agent prompts (chat completions), [speech] turns a line into audio (`/audio/speech`).
 *
 * Off state: [key] is asked before every call; null or blank refuses with [FailureKind.NoKey]
 * and no request is made. The key goes only into the `Authorization` header: never into a
 * result, an exception message or telemetry. Telemetry (`openrouter.call`) carries the call
 * name, ok, failure kind, HTTP status and durations, never text.
 *
 * Every call runs its network I/O on [io], so callers may be on the main thread.
 */
class OpenRouter(
    val config: OpenRouterConfig = OpenRouterConfig(),
    private val key: () -> String?,
    client: OkHttpClient = OkHttpClient(),
    internal val telemetry: TelemetrySink = TelemetrySink.Default,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
) {
    internal val http: OkHttpClient = client.newBuilder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.streamReadTimeoutMs, TimeUnit.MILLISECONDS)
        // HTTP/2 pings keep a warm connection alive through a call and drop a dead one early.
        .pingInterval(config.pingIntervalMs, TimeUnit.MILLISECONDS)
        .build()

    val jev: SystemOneClient = SystemOneClient(this)
    val writer: WriterClient = WriterClient(this)
    val speech: SpeechClient = SpeechClient(this)

    /** True while a key is saved (no request). */
    val hasKey: Boolean get() = !key()?.trim().isNullOrEmpty()

    /** `GET /key`: Ok(true) if OpenRouter accepts the key, Ok(false) if it refuses it (401/403). */
    suspend fun checkKey(): OpenRouterResult<Boolean> {
        val k = currentKey() ?: return noKey("key")
        val request = builder("/key", k).get().build()
        return call("key", request, config.jevTimeoutMs.coerceAtLeast(5_000L), readsErrors = true) { response ->
            when (response.code) {
                in 200..299 -> OpenRouterResult.Ok(true)
                401, 403 -> OpenRouterResult.Ok(false)
                else -> OpenRouterResult.Failed(FailureKind.Http, response.code)
            }
        }
    }

    /**
     * Open (or keep open) the connection to OpenRouter ahead of a call, so the first Jev call does
     * not pay DNS, TCP and TLS (measured on the emulator: about 550 ms). One small `GET /key`;
     * its answer is ignored. No key: no request, false.
     */
    suspend fun warm(): Boolean {
        val k = currentKey() ?: return false
        val request = builder("/key", k).get().build()
        return call("warm", request, config.connectTimeoutMs + config.jevTimeoutMs, readsErrors = true) { OpenRouterResult.Ok(true) } is OpenRouterResult.Ok
    }

    internal fun currentKey(): String? = key()?.trim()?.takeIf { it.isNotEmpty() }

    internal fun noKey(label: String): OpenRouterResult.Failed {
        val failed = OpenRouterResult.Failed(FailureKind.NoKey)
        report(label, failed, 0L)
        return failed
    }

    internal fun builder(path: String, key: String): Request.Builder = Request.Builder()
        .url(config.baseUrl.trimEnd('/') + path)
        .header("Authorization", "Bearer $key")
        .header("HTTP-Referer", config.referer)
        .header("X-Title", config.title)

    internal fun postJson(path: String, key: String, body: JsonObject, accept: String = "application/json"): Request =
        builder(path, key).header("Accept", accept).post(body.toString().jsonBody()).build()

    /**
     * Run [request] within [timeoutMs] and let [read] turn a response (any status) into a result.
     * A non-2xx status is handled here unless [read] wants it ([readsErrors]).
     */
    internal suspend fun <T> call(
        label: String,
        request: Request,
        timeoutMs: Long,
        readsErrors: Boolean = false,
        extra: (OpenRouterResult<T>) -> Map<String, Any?> = { emptyMap() },
        read: (Response) -> OpenRouterResult<T>,
    ): OpenRouterResult<T> {
        val started = System.nanoTime()
        val result: OpenRouterResult<T> = try {
            withContext(io) {
                withTimeoutOrNull(timeoutMs) {
                    http.newCall(request).await().use { response ->
                        if (!readsErrors && !response.isSuccessful) statusFailure(response.code) else read(response)
                    }
                } ?: OpenRouterResult.Failed(FailureKind.Timeout)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ioFailure(e)
        }
        report(label, result, (System.nanoTime() - started) / 1_000_000, extra(result))
        return result
    }

    /** Try [block]; if it fails in a retryable way, wait [OpenRouterConfig.retryBackoffMs] and try again, up to [retries] times. */
    internal suspend fun <T> retrying(retries: Int, block: suspend () -> OpenRouterResult<T>): OpenRouterResult<T> {
        var result = block()
        var left = retries
        while (left > 0 && result is OpenRouterResult.Failed && result.retryable) {
            left--
            delay(config.retryBackoffMs)
            result = block()
        }
        return result
    }

    internal fun report(label: String, result: OpenRouterResult<*>, ms: Long, extra: Map<String, Any?> = emptyMap()) {
        val failed = result as? OpenRouterResult.Failed
        val attrs = linkedMapOf<String, Any?>("call" to label, "ok" to (failed == null), "ms" to ms)
        failed?.let {
            attrs["kind"] = it.kind.wire
            it.httpStatus?.let { s -> attrs["http_status"] = s }
        }
        attrs.putAll(extra)
        runCatching { telemetry.event(EVENT, attrs, warn = failed != null && failed.kind != FailureKind.NoKey) }
    }

    internal companion object {
        const val EVENT = "openrouter.call"

        fun statusFailure(code: Int): OpenRouterResult.Failed = when (code) {
            401, 403 -> OpenRouterResult.Failed(FailureKind.Refused, code)
            408 -> OpenRouterResult.Failed(FailureKind.Timeout, code)
            else -> OpenRouterResult.Failed(FailureKind.Http, code)
        }

        fun ioFailure(e: IOException): OpenRouterResult.Failed = when (e) {
            is OpenRouterException -> e.failure
            // A connect timeout never reached OpenRouter: that is offline, not a slow answer.
            is SocketTimeoutException ->
                if (e.message?.contains("connect", ignoreCase = true) == true) OpenRouterResult.Failed(FailureKind.Offline)
                else OpenRouterResult.Failed(FailureKind.Timeout)
            else -> OpenRouterResult.Failed(FailureKind.Offline)
        }
    }
}
