package com.folio.hermes

/**
 * Reads the dashboard session cookies from `Set-Cookie` headers.
 *
 * The server names them `hermes_session_at` / `hermes_session_rt` / `hermes_session_provider`,
 * prefixed `__Host-` on gated HTTPS or `__Secure-` behind a proxy prefix, bare over HTTP.
 * Python's cookie writer wraps values that contain `=` (base64 padding) in double quotes with
 * backslash escapes, so values are unquoted here.
 */
object HermesCookies {
    const val ACCESS = "hermes_session_at"
    const val REFRESH = "hermes_session_rt"
    const val PROVIDER = "hermes_session_provider"
    private val PREFIXES = listOf("__Host-", "__Secure-")

    /** One parsed `Set-Cookie`. [maxAgeSeconds] is null when the header has no Max-Age. */
    data class Cookie(val name: String, val value: String, val maxAgeSeconds: Long?) {
        override fun toString(): String = "Cookie($name, maxAge=$maxAgeSeconds, value=<redacted>)"
    }

    /** The session cookies found in one response. */
    data class Session(
        val accessToken: String?,
        val refreshToken: String?,
        val accessMaxAgeSeconds: Long?,
        val provider: String?,
    ) {
        override fun toString(): String =
            "Session(access=${accessToken != null}, refresh=${refreshToken != null}, maxAge=$accessMaxAgeSeconds, provider=$provider)"
    }

    /** Parse every header and pick the session cookies (bare name, any prefix). Deletions are ignored. */
    fun session(setCookieHeaders: List<String>): Session {
        val cookies = setCookieHeaders.mapNotNull(::parse)
            .filter { it.value.isNotEmpty() && (it.maxAgeSeconds == null || it.maxAgeSeconds > 0) }
            .associateBy { it.name }
        val access = cookies[ACCESS]
        return Session(
            accessToken = access?.value,
            refreshToken = cookies[REFRESH]?.value,
            accessMaxAgeSeconds = access?.maxAgeSeconds,
            provider = cookies[PROVIDER]?.value,
        )
    }

    /** Parse one `Set-Cookie` value; the name comes back without a `__Host-`/`__Secure-` prefix. */
    fun parse(header: String): Cookie? {
        val parts = header.split(';')
        val pair = parts.first()
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        var name = pair.substring(0, eq).trim()
        PREFIXES.firstOrNull { name.startsWith(it) }?.let { name = name.removePrefix(it) }
        val value = unquote(pair.substring(eq + 1).trim())
        var maxAge: Long? = null
        for (attr in parts.drop(1)) {
            val a = attr.trim()
            val aEq = a.indexOf('=')
            if (aEq > 0 && a.substring(0, aEq).trim().equals("max-age", ignoreCase = true)) {
                maxAge = a.substring(aEq + 1).trim().toLongOrNull()
            }
        }
        return Cookie(name, value, maxAge)
    }

    /** Undo Python `http.cookies` quoting: surrounding quotes, `\"`, `\\` and `\ooo` octal escapes. */
    internal fun unquote(raw: String): String {
        if (raw.length < 2 || !raw.startsWith('"') || !raw.endsWith('"')) return raw
        val inner = raw.substring(1, raw.length - 1)
        val out = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                val octal = inner.substring(i + 1, minOf(i + 4, inner.length))
                if (octal.length == 3 && octal.all { it in '0'..'7' }) {
                    out.append(octal.toInt(8).toChar())
                    i += 4
                } else {
                    out.append(inner[i + 1])
                    i += 2
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
