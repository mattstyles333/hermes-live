package com.folio.hermes

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Password sign-in to the Hermes dashboard and the session's upkeep.
 *
 * - [signIn]: `POST /auth/password-login`. The body is only `{ok, next}`; the tokens arrive as
 *   `Set-Cookie` (see [HermesCookies]), expiry from the access cookie's Max-Age.
 * - Renewal ([accessToken] near expiry, or [refreshAfterUnauthorized] after a 401):
 *   `POST /auth/native/refresh {refresh_token, provider}`. 401 there means the refresh token is
 *   dead: sign in again silently with the stored password. 503/5xx or a network error keeps the
 *   stored session (it is still used while unexpired). A refused password or 429 stops and asks
 *   the user ([AuthState.NeedsSignIn] / [HermesAuthException.RateLimited]); nothing loops.
 *
 * Off state: with an empty [TokenStore] nothing is ever sent; [accessToken] throws
 * [HermesAuthException.SignInRequired].
 */
class HermesAuth(
    private val config: HermesConfig,
    private val store: TokenStore,
    private val client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AccessTokenProvider {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)

    /** Session state for the UI. Call [restore] once at start to load it from the store. */
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Load the stored session into [state] without any network call. */
    suspend fun restore(): AuthState = mutex.withLock {
        val tokens = store.loadTokens()
        _state.value = tokens?.let { AuthState.SignedIn(it.userId, it.expiresAtMs) } ?: AuthState.SignedOut
        _state.value
    }

    /** Sign in with a user name and password; stores the tokens and the credentials for silent re-login. */
    suspend fun signIn(username: String, password: String): HermesTokens = mutex.withLock {
        val credentials = HermesCredentials(username, password)
        val tokens = try {
            passwordLogin(credentials)
        } catch (e: HermesAuthException.InvalidCredentials) {
            _state.value = AuthState.NeedsSignIn("Hermes did not accept that user name and password.")
            throw e
        }
        store.saveCredentials(credentials)
        save(tokens)
        tokens
    }

    /** Forget the session and the stored password. Basic sessions are stateless, so nothing is sent. */
    suspend fun signOut() = mutex.withLock {
        store.saveTokens(null)
        store.saveCredentials(null)
        _state.value = AuthState.SignedOut
    }

    override suspend fun accessToken(): String = session(config.tokenRefreshMarginMs).accessToken

    /**
     * The stored session with at least [minValidityMs] left, renewing it first when needed.
     * Hermes Live's connection provider calls this with 5 minutes before handing a token to Folio.
     */
    suspend fun session(minValidityMs: Long = config.tokenRefreshMarginMs): HermesTokens = mutex.withLock {
        val tokens = store.loadTokens() ?: throw signInRequired("Sign in to Hermes.")
        if (tokens.isFresh(clock(), minValidityMs)) return@withLock tokens
        renew(tokens)
    }

    override suspend fun refreshAfterUnauthorized(rejected: String): String = mutex.withLock {
        val tokens = store.loadTokens() ?: throw signInRequired("Sign in to Hermes.")
        if (tokens.accessToken != rejected) return@withLock tokens.accessToken
        renew(tokens, forced = true).accessToken
    }

    /** Refresh, else silent re-login, else ask. Caller holds [mutex]. */
    private suspend fun renew(current: HermesTokens, forced: Boolean = false): HermesTokens {
        val now = clock()
        val refreshed = try {
            refresh(current)
        } catch (e: HermesAuthException.Unavailable) {
            if (!forced && current.isUnexpired(now)) return current
            throw e
        } catch (e: HermesAuthException.Protocol) {
            throw e
        } catch (e: IOException) {
            if (e is HermesAuthException) throw e
            if (!forced && current.isUnexpired(now)) return current
            throw e
        }
        if (refreshed != null) return save(refreshed)
        // The refresh token is dead. Sign in again with the stored password, once.
        val credentials = store.loadCredentials()
            ?: throw signInRequired("Your Hermes session ended. Sign in again.").also { store.saveTokens(null) }
        val relogged = try {
            passwordLogin(credentials)
        } catch (e: HermesAuthException.InvalidCredentials) {
            store.saveTokens(null)
            store.saveCredentials(null)
            _state.value = AuthState.NeedsSignIn("Hermes no longer accepts the saved password. Sign in again.")
            throw e
        }
        return save(relogged)
    }

    private suspend fun save(tokens: HermesTokens): HermesTokens {
        store.saveTokens(tokens)
        _state.value = AuthState.SignedIn(tokens.userId, tokens.expiresAtMs)
        return tokens
    }

    private fun signInRequired(reason: String): HermesAuthException.SignInRequired {
        _state.value = if (_state.value is AuthState.SignedOut) AuthState.SignedOut else AuthState.NeedsSignIn(reason)
        return HermesAuthException.SignInRequired(reason)
    }

    private suspend fun passwordLogin(credentials: HermesCredentials): HermesTokens {
        val body = buildJsonObject {
            put("provider", config.authProvider)
            put("username", credentials.username)
            put("password", credentials.password)
            put("next", "")
        }.toString()
        val request = Request.Builder()
            .url(config.endpoint.resolve("/auth/password-login"))
            .post(body.jsonBody())
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).await()
        response.use {
            when (it.code) {
                in 200..299 -> Unit
                401 -> throw HermesAuthException.InvalidCredentials()
                404 -> throw HermesAuthException.Protocol("This Hermes server has no '${config.authProvider}' password sign-in.")
                429 -> throw HermesAuthException.RateLimited()
                else -> throw HermesAuthException.Unavailable(it.code, detail(it.readText(4_000)))
            }
            val session = HermesCookies.session(it.headers("Set-Cookie"))
            val access = session.accessToken
                ?: throw HermesAuthException.Protocol("Hermes signed in but sent no session cookie.")
            val ttlMs = session.accessMaxAgeSeconds?.let { s -> s * 1000 } ?: config.defaultAccessTtlMs
            return HermesTokens(
                accessToken = access,
                refreshToken = session.refreshToken.orEmpty(),
                expiresAtMs = clock() + ttlMs,
                provider = session.provider ?: config.authProvider,
                userId = credentials.username,
            )
        }
    }

    /** New tokens, or null when the server says the refresh token is dead (401). */
    private suspend fun refresh(current: HermesTokens): HermesTokens? {
        if (current.refreshToken.isEmpty()) return null
        val body = buildJsonObject {
            put("refresh_token", current.refreshToken)
            put("provider", current.provider)
        }.toString()
        val request = Request.Builder()
            .url(config.endpoint.resolve("/auth/native/refresh"))
            .post(body.jsonBody())
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).await()
        val code = response.code
        val text = response.readText(64_000)
        return when (code) {
            in 200..299 -> {
                val wire = runCatching { HermesJson.decodeFromString<RefreshResponse>(text) }.getOrNull()
                    ?: throw HermesAuthException.Protocol("Hermes sent an unreadable token refresh.")
                HermesTokens(
                    accessToken = wire.accessToken,
                    refreshToken = wire.refreshToken.ifEmpty { current.refreshToken },
                    expiresAtMs = wire.expiresAt * 1000,
                    provider = wire.provider.ifEmpty { current.provider },
                    userId = wire.userId.ifEmpty { current.userId },
                )
            }
            400, 401 -> null
            429 -> throw HermesAuthException.RateLimited()
            else -> throw HermesAuthException.Unavailable(code, detail(text))
        }
    }

    private fun detail(text: String): String =
        runCatching { HermesJson.decodeFromString<ErrorBody>(text) }.getOrNull()
            ?.let { it.detail?.asText()?.ifEmpty { null } ?: it.error }
            ?: text.take(200)

    @Serializable
    internal data class RefreshResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("token_type") val tokenType: String = "Bearer",
        /** Unix seconds. */
        @SerialName("expires_at") val expiresAt: Long,
        val provider: String = "",
        @SerialName("user_id") val userId: String = "",
    )

    @Serializable
    internal data class ErrorBody(val error: String? = null, val detail: JsonElement? = null)
}
