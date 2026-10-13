package com.folio.hermeslive.data

import com.folio.hermes.telemetry.TelemetrySink

/**
 * R-Q-2 as a second line behind "never pass text": a [TelemetrySink] that lets through event
 * names, codes and timings only. Numbers, booleans and enums pass. A string passes only when it
 * looks like a code (lowercase letters, digits and `_ . : / -`, at most [MAX_CODE] characters);
 * anything else (a sentence, a key, a file name with spaces) becomes `"redacted"`. An event name
 * that is not code-shaped becomes `"redacted"` too.
 *
 * Every shell component reports through this (`CascadeTelemetryPrivacyTest`).
 */
class SafeTelemetry(private val inner: TelemetrySink = TelemetrySink.Default) : TelemetrySink {
    override fun event(name: String, attrs: Map<String, Any?>, warn: Boolean) {
        val safeName = if (EVENT.matches(name)) name else REDACTED
        val safe = LinkedHashMap<String, Any?>(attrs.size)
        attrs.forEach { (k, v) -> if (CODE.matches(k)) safe[k] = clean(v) }
        runCatching { inner.event(safeName, safe, warn) }
    }

    companion object {
        const val REDACTED = "redacted"
        const val MAX_CODE = 40
        private val EVENT = Regex("^[a-z][a-z0-9_.]{0,63}$")
        private val CODE = Regex("^[a-z0-9][a-z0-9_.:/-]{0,${MAX_CODE - 1}}$")

        fun clean(v: Any?): Any? = when (v) {
            null, is Boolean, is Int, is Long, is Short, is Byte -> v
            is Double -> if (v.isFinite()) v else null
            is Float -> if (v.isFinite()) v else null
            is Number -> v.toLong()
            is Enum<*> -> v.name.lowercase().takeIf { CODE.matches(it) } ?: REDACTED
            is String -> if (CODE.matches(v)) v else REDACTED
            else -> REDACTED
        }
    }
}
