package com.folio.hermes

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The base URL of the user's own Hermes dashboard, checked once and then trusted.
 *
 * Rules: `https://` anywhere; cleartext `http://` only for tailnet hosts (`*.ts.net`, or an IPv4
 * literal in the CGNAT range `100.64.0.0/10` that Tailscale hands out). No credentials, query or
 * fragment. A path prefix is kept (the dashboard honours `X-Forwarded-Prefix`), without its
 * trailing slash. A bare host such as `hermes.example.com` is read as `https://hermes.example.com`.
 *
 * Nothing here resolves DNS: the check is on the text the user typed.
 */
class HermesEndpoint private constructor(
    /** Normalised base URL, never ending in `/`. */
    val baseUrl: HttpUrl,
) {
    /** True when the endpoint is plain `http://` (tailnet only). Apps need a network-security-config for it. */
    val isCleartext: Boolean get() = !baseUrl.isHttps

    /** Host shown to the user, e.g. `hermes.example.com`. */
    val host: String get() = baseUrl.host

    /** Absolute URL for a dashboard path such as `/api/auth/ws-ticket`, under any path prefix. */
    fun resolve(path: String): HttpUrl {
        require(path.startsWith("/")) { "path must start with /" }
        val prefix = baseUrl.encodedPath.trimEnd('/')
        return baseUrl.newBuilder().encodedPath(prefix + path).build()
    }

    /**
     * The `/api/ws` gateway URL for one single-use [ticket]. Returned as `http(s)` because OkHttp
     * upgrades those; the wire scheme is `ws(s)`.
     */
    fun gatewayUrl(ticket: String): HttpUrl =
        resolve("/api/ws").newBuilder().addQueryParameter("ticket", ticket).build()

    override fun toString(): String = baseUrl.toString().trimEnd('/')
    override fun equals(other: Any?): Boolean = other is HermesEndpoint && other.baseUrl == baseUrl
    override fun hashCode(): Int = baseUrl.hashCode()

    /** Why a typed base URL was refused. */
    enum class Problem { Blank, NotAUrl, UnsupportedScheme, CleartextNotAllowed, HasCredentials, HasQueryOrFragment }

    /** Result of [parse]. */
    sealed interface Parsed {
        /** The URL is usable. */
        data class Valid(val endpoint: HermesEndpoint) : Parsed

        /** The URL was refused; [message] is plain English for a settings field. */
        data class Invalid(val problem: Problem, val message: String) : Parsed
    }

    companion object {
        /** Check a user-typed base URL. Never throws. */
        fun parse(raw: String): Parsed {
            val text = raw.trim()
            if (text.isEmpty()) return invalid(Problem.Blank, "Enter the address of your Hermes server.")
            val schemeEnd = text.indexOf("://")
            val scheme = if (schemeEnd > 0) text.substring(0, schemeEnd).lowercase() else ""
            if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") {
                return invalid(Problem.UnsupportedScheme, "Use an https:// address.")
            }
            val withScheme = if (scheme.isEmpty()) "https://$text" else text
            val url = withScheme.toHttpUrlOrNull()
                ?: return invalid(Problem.NotAUrl, "That is not a web address.")
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return invalid(Problem.HasCredentials, "Leave the user name and password out of the address.")
            }
            if (url.query != null || url.fragment != null) {
                return invalid(Problem.HasQueryOrFragment, "Leave ? and # parts out of the address.")
            }
            if (!url.isHttps && !isTailnetHost(url.host)) {
                return invalid(
                    Problem.CleartextNotAllowed,
                    "Plain http:// is only allowed for Tailscale addresses (*.ts.net or 100.64.0.0/10).",
                )
            }
            val path = url.encodedPath.trimEnd('/')
            val normalised = url.newBuilder().encodedPath(path.ifEmpty { "/" }).build()
            return Parsed.Valid(HermesEndpoint(normalised))
        }

        /** Like [parse] but throws [IllegalArgumentException] with the refusal message. */
        fun of(raw: String): HermesEndpoint = when (val parsed = parse(raw)) {
            is Parsed.Valid -> parsed.endpoint
            is Parsed.Invalid -> throw IllegalArgumentException(parsed.message)
        }

        /** True for `*.ts.net` names and IPv4 literals in `100.64.0.0/10`. */
        fun isTailnetHost(host: String): Boolean {
            val h = host.lowercase().trimEnd('.')
            if (h.endsWith(".ts.net")) return true
            val octets = parseIpv4(h) ?: return false
            return octets[0] == 100 && octets[1] in 64..127
        }

        /** Tests only: accept any http(s) URL (MockWebServer is plain http on 127.0.0.1). */
        internal fun forTesting(raw: String): HermesEndpoint {
            val url = requireNotNull(raw.toHttpUrlOrNull()) { "bad url" }
            return HermesEndpoint(url.newBuilder().encodedPath(url.encodedPath.trimEnd('/').ifEmpty { "/" }).build())
        }

        private fun parseIpv4(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val out = IntArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
                val value = part.toInt()
                if (value > 255) return null
                out[i] = value
            }
            return out
        }

        private fun invalid(problem: Problem, message: String) = Parsed.Invalid(problem, message)
    }
}
