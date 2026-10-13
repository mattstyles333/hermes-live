package com.folio.hermes.telemetry

/**
 * Strips anything that could be content or a credential from text bound for telemetry.
 *
 * Callers already send only event names and safe attributes (codes, enums, durations, counts);
 * this is the second line: every message, attribute and stack trace passes through it. It
 * redacts bearer tokens, JWTs, `key=value` secrets, e-mail addresses (user names), URL query
 * strings and fragments, URL user info, long hex and base64 runs, and the payload that
 * kotlinx.serialization quotes after "JSON input:". Then it truncates.
 *
 * Every pattern is ICU-safe (Android compiles regexes with ICU; see AndroidRegexTest).
 */
object Scrub {
    const val MESSAGE_MAX = 500
    const val ATTRIBUTE_MAX = 200

    private val rules: List<Pair<Regex, String>> = listOf(
        // kotlinx.serialization quotes the input it failed on: that is a payload.
        Regex("JSON input:[\\s\\S]*") to "JSON input: <redacted>",
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+") to "Bearer <redacted>",
        Regex("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}(\\.[A-Za-z0-9_-]*)?") to "<jwt>",
        Regex("(?i)\\b(access_token|refresh_token|id_token|token|password|passwd|pwd|secret|cookie|set-cookie|session|sessionid|authorization|api[_-]?key|key)(\"?\\s*[:=]\\s*\"?)[^\\s,;\"'&}]+") to "$1$2<redacted>",
        Regex("(?i)\\b(https?://[^\\s?#\"'<>]*)[?#][^\\s\"'<>]*") to "$1?<redacted>",
        Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s@:]+(:[^/\\s@]*)?@") to "$1<user>@",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "<email>",
        Regex("\\b[0-9a-fA-F]{32,}\\b") to "<hex>",
        // Token-like runs: 40+ base64 characters mixing digits, upper and lower case (paths and identifiers don't).
        Regex("(?=[A-Za-z0-9+/_-]*[0-9])(?=[A-Za-z0-9+/_-]*[A-Z])(?=[A-Za-z0-9+/_-]*[a-z])[A-Za-z0-9+/_-]{40,}={0,2}") to "<redacted>",
    )

    /** A message or free-text value: redacted, then cut to [max] characters. */
    fun message(text: String?, max: Int = MESSAGE_MAX): String {
        if (text.isNullOrEmpty()) return ""
        return truncate(text(text), max)
    }

    /** Redact without the short cut (stack traces, thread dumps); the caller caps the length. */
    fun text(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    fun truncate(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"
}
