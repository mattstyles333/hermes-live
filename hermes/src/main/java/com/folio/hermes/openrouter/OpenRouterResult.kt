package com.folio.hermes.openrouter

import java.io.IOException

/** Why an OpenRouter call gave nothing usable. */
enum class FailureKind(val wire: String) {
    /** No key saved: refused before any request. */
    NoKey("no_key"),

    /** No response at all (no network, DNS, refused connection). The turn can wait in the outbox. */
    Offline("offline"),

    /** Connected but no (complete) answer in time. */
    Timeout("timeout"),

    /** HTTP 401 or 403: the key is wrong or revoked. */
    Refused("refused"),

    /** Any other non-2xx answer (402 credits, 429, 5xx…). */
    Http("http"),

    /** 2xx with a body this client could not use. */
    BadBody("bad_body"),
    ;

    /** Worth one more try after a backoff. */
    val retryable: Boolean get() = this == Offline || this == Timeout || this == Http
}

/** The result of one OpenRouter call. Never carries the key or any response text in a failure. */
sealed interface OpenRouterResult<out T> {
    data class Ok<T>(val value: T) : OpenRouterResult<T>

    data class Failed(val kind: FailureKind, val httpStatus: Int? = null) : OpenRouterResult<Nothing> {
        /** 429 and 5xx retry; other 4xx do not. */
        val retryable: Boolean
            get() = kind.retryable && (kind != FailureKind.Http || httpStatus == null || httpStatus == 429 || httpStatus >= 500)
    }

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

/** A streamed call failed; [failure] says why (no content in the message). */
class OpenRouterException(val failure: OpenRouterResult.Failed) :
    IOException("openrouter ${failure.kind.wire}${failure.httpStatus?.let { " ($it)" } ?: ""}")
