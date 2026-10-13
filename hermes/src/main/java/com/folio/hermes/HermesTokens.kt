package com.folio.hermes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A dashboard session: the bearer the server accepts, the rotating refresh token and when the
 * access token lapses. [toString] never prints the tokens.
 */
@Serializable
data class HermesTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    /** Access-token expiry, epoch milliseconds (the server speaks seconds; converted at the wire). */
    @SerialName("expires_at_ms") val expiresAtMs: Long,
    val provider: String = "basic",
    @SerialName("user_id") val userId: String = "",
) {
    /** True when the access token has more than [marginMs] left at [nowMs]. */
    fun isFresh(nowMs: Long, marginMs: Long): Boolean = expiresAtMs - nowMs > marginMs

    /** True when the access token has not lapsed yet at [nowMs]. */
    fun isUnexpired(nowMs: Long): Boolean = expiresAtMs > nowMs

    /** Compact JSON for a [TokenStore] that keeps one encrypted string. */
    fun encode(): String = HermesJson.encodeToString(this)

    override fun toString(): String =
        "HermesTokens(user=$userId, provider=$provider, expiresAtMs=$expiresAtMs, tokens=<redacted>)"

    companion object {
        /** Inverse of [encode]; null for anything unreadable. */
        fun decode(text: String?): HermesTokens? =
            text?.let { runCatching { HermesJson.decodeFromString<HermesTokens>(it) }.getOrNull() }
    }
}

/** The dashboard user name and password, kept so a lapsed refresh token can sign in again silently. */
data class HermesCredentials(val username: String, val password: String) {
    override fun toString(): String = "HermesCredentials(username=$username, password=<redacted>)"
}

/**
 * Where the session lives between launches. Hermes Live supplies Keystore-backed storage;
 * tests use [InMemoryTokenStore]. Implementations must be safe to call from any thread.
 */
interface TokenStore {
    suspend fun loadTokens(): HermesTokens?
    suspend fun saveTokens(tokens: HermesTokens?)
    suspend fun loadCredentials(): HermesCredentials?
    suspend fun saveCredentials(credentials: HermesCredentials?)
}

/** A [TokenStore] that forgets everything when the process dies. For tests and previews. */
class InMemoryTokenStore(
    @Volatile private var tokens: HermesTokens? = null,
    @Volatile private var credentials: HermesCredentials? = null,
) : TokenStore {
    override suspend fun loadTokens(): HermesTokens? = tokens
    override suspend fun saveTokens(tokens: HermesTokens?) { this.tokens = tokens }
    override suspend fun loadCredentials(): HermesCredentials? = credentials
    override suspend fun saveCredentials(credentials: HermesCredentials?) { this.credentials = credentials }
}

/**
 * Supplies the bearer for every authenticated request. [HermesAuth] implements it with password
 * login and refresh; Folio implements it over Hermes Live's connection provider (token only).
 */
interface AccessTokenProvider {
    /** A usable access token. Throws [HermesAuthException.SignInRequired] when there is no session. */
    suspend fun accessToken(): String

    /**
     * The server answered 401 to [rejected]. Return a different token to retry with once, or throw
     * [HermesAuthException]. Must not return [rejected] again.
     */
    suspend fun refreshAfterUnauthorized(rejected: String): String
}

/** Why the client cannot authenticate. All are [java.io.IOException]s so network code can catch one type. */
sealed class HermesAuthException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause) {
    /** No usable session: the user must sign in (or Hermes Live is signed out). Nothing was sent. */
    class SignInRequired(message: String = "Sign in to Hermes.") : HermesAuthException(message)

    /** The server refused the user name or password (HTTP 401 on login). */
    class InvalidCredentials : HermesAuthException("Hermes did not accept that user name and password.")

    /** Too many login attempts from this address (HTTP 429). Do not retry in a loop. */
    class RateLimited : HermesAuthException("Too many sign-in attempts. Try again in a minute.")

    /** The auth provider or server is unreachable (HTTP 503 / 5xx); the stored session was kept. */
    class Unavailable(val httpCode: Int, detail: String) : HermesAuthException("Hermes sign-in is unavailable ($httpCode): $detail")

    /** The server answered something this client does not understand. */
    class Protocol(message: String) : HermesAuthException(message)
}

/** What the UI shows about the session. */
sealed interface AuthState {
    /** Never signed in, or signed out. */
    data object SignedOut : AuthState

    /** A stored session exists. */
    data class SignedIn(val userId: String, val expiresAtMs: Long) : AuthState

    /** Refresh and silent re-login both failed; ask the user to sign in again. */
    data class NeedsSignIn(val reason: String) : AuthState
}
