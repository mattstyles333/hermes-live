package com.folio.hermes.telemetry

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where diagnostics go. The default is baked in at build time (`BuildConfig.DIAGNOSTICS_URL`,
 * from the CI variable `FOLIO_DIAGNOSTICS_URL`; empty in local and fork builds). A user override
 * wins; the Diagnostics switch turns everything off. Only `https://`, or `http://` on a
 * `*.ts.net` name (the apps' cleartext rule), is accepted.
 */
object DiagnosticsEndpoint {
    const val LOGS_PATH = "/v1/logs"

    /** The endpoint in force: null when switched off or nothing is configured. An unusable override is ignored. */
    fun effective(enabled: Boolean, override: String?, buildDefault: String?): String? {
        if (!enabled) return null
        val own = override?.trim()?.takeIf { it.isNotEmpty() && problem(it) == null }
        return own ?: buildDefault?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Why [raw] can't be used, or null when it can (blank is fine: it means "use the default"). */
    fun problem(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val url = text.toHttpUrlOrNull() ?: return "Enter a full address, starting with https://"
        return when {
            url.username.isNotEmpty() || url.password.isNotEmpty() -> "Leave the user name and password out of the address."
            url.query != null || url.fragment != null -> "Leave out anything after ? or #."
            url.scheme == "https" -> null
            url.host.endsWith(".ts.net") -> null
            else -> "Use https:// (plain http:// only for a *.ts.net name)."
        }
    }

    /** The OTLP logs URL for [raw] (appending `/v1/logs` unless it is already there), or null when unusable. */
    fun logsUrl(raw: String?): HttpUrl? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || problem(text) != null) return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.encodedPath.trimEnd('/').endsWith(LOGS_PATH)) return url
        val base = url.encodedPath.trimEnd('/')
        return url.newBuilder().encodedPath(base + LOGS_PATH).build()
    }
}
