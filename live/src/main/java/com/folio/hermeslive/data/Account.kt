package com.folio.hermeslive.data

import com.folio.hermes.AuthState
import com.folio.hermes.HermesAuth
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesEndpoint
import com.folio.hermes.HermesTokens
import com.folio.hermes.live.VoiceLiveApi
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermes.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** What the app knows about the user's Hermes account. */
sealed interface AccountState {
    data object SignedOut : AccountState
    data class SignedIn(val server: String, val username: String) : AccountState
    data class NeedsSignIn(val server: String, val reason: String) : AccountState
}

/** Result of a sign-in attempt, for the form. */
sealed interface SignInResult {
    data class Ok(val status: VoiceLiveStatus?) : SignInResult
    data class Failed(val message: String) : SignInResult
}

/**
 * The account: server address, [HermesAuth] over the Keystore store, sign in / out and the
 * voice-live status. Off state: until [signIn] succeeds there is no endpoint and nothing dials.
 */
class Account(
    private val settings: SettingsStore,
    private val tokens: SecureTokenStore,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    /** Sign-in, sign-out or a dead session: the connection provider notifies Folio. */
    private val onSessionChanged: () -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var auth: HermesAuth? = null
    @Volatile private var authFor: String? = null
    private var watcher: Job? = null
    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    private val _voice = MutableStateFlow<VoiceLiveStatus?>(null)

    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Last voice-live status read (null until read, or when the server is too old). */
    val voiceStatus: StateFlow<VoiceLiveStatus?> = _voice.asStateFlow()

    /** Load the stored session without any network call. */
    suspend fun restore() = mutex.withLock {
        val url = settings.serverUrl.value
        if (url == null || !tokens.hasSession()) {
            _state.value = AccountState.SignedOut
            return@withLock
        }
        val a = authLocked(url) ?: return@withLock
        when (val s = a.restore()) {
            is AuthState.SignedIn -> _state.value = AccountState.SignedIn(display(url), settings.username ?: s.userId)
            else -> _state.value = AccountState.SignedOut
        }
    }

    /** The config for the signed-in server, or null when signed out. */
    fun config(): HermesConfig? = settings.serverUrl.value?.let { url ->
        (HermesEndpoint.parse(url) as? HermesEndpoint.Parsed.Valid)?.endpoint?.let(::HermesConfig)
    }

    /** The auth already built for the signed-in server (after [restore] or [signIn]), without waiting. */
    fun currentAuth(): HermesAuth? = auth?.takeIf { authFor != null && authFor == settings.serverUrl.value }

    /** The auth for the signed-in server, or null when signed out. */
    suspend fun auth(): HermesAuth? = mutex.withLock { settings.serverUrl.value?.let { authLocked(it) } }

    /** A session with at least [minValidityMs] left (refreshing first), for the connection provider. */
    suspend fun session(minValidityMs: Long): HermesTokens {
        val a = auth() ?: throw HermesAuthException.SignInRequired()
        return a.session(minValidityMs)
    }

    /** A new access token now, whatever the old one's expiry (the caller got a 401 with it). */
    suspend fun forceRefresh(): HermesTokens {
        val a = auth() ?: throw HermesAuthException.SignInRequired()
        val current = a.session(0)
        a.refreshAfterUnauthorized(current.accessToken)
        return a.session(0)
    }

    suspend fun signIn(rawUrl: String, username: String, password: String): SignInResult {
        val endpoint = when (val parsed = HermesEndpoint.parse(rawUrl)) {
            is HermesEndpoint.Parsed.Invalid -> {
                Telemetry.event("auth.signin", mapOf("ok" to false, "reason" to "invalid_address"))
                return SignInResult.Failed(parsed.message)
            }
            is HermesEndpoint.Parsed.Valid -> parsed.endpoint
        }
        if (username.isBlank() || password.isEmpty()) return SignInResult.Failed("Enter your Hermes user name and password.")
        val url = endpoint.toString()
        val a = mutex.withLock {
            if (authFor != url) {
                // A different server: forget the old session first.
                tokens.wipe()
            }
            authLocked(url, force = true)!!
        }
        return try {
            a.signIn(username.trim(), password)
            settings.setAccount(url, username.trim())
            _state.value = AccountState.SignedIn(display(url), username.trim())
            onSessionChanged()
            val voice = refreshVoiceStatus()
            Telemetry.event("auth.signin", mapOf("ok" to true, "cleartext" to url.startsWith("http://"), "voice_available" to voice?.available))
            SignInResult.Ok(voice)
        } catch (e: HermesAuthException) {
            Telemetry.warn("auth.signin", null, signInFailure(e))
            SignInResult.Failed(e.message ?: "Could not sign in.")
        } catch (e: java.io.IOException) {
            Telemetry.warn("auth.signin", null, mapOf("ok" to false, "reason" to "network", "type" to e.javaClass.simpleName))
            SignInResult.Failed(networkMessage(e))
        }
    }

    /** Forget everything: tokens, password, the Keystore key, the server and the conversation. */
    suspend fun signOut() = mutex.withLock {
        runCatching { auth?.signOut() }
        watcher?.cancel()
        auth = null
        authFor = null
        tokens.wipe()
        settings.clearAccount()
        _voice.value = null
        _state.value = AccountState.SignedOut
        onSessionChanged()
    }

    /** `GET /api/audio/voice-live/status`; null when signed out or unreachable. */
    suspend fun refreshVoiceStatus(): VoiceLiveStatus? {
        val config = config() ?: return null
        val a = auth() ?: return null
        val status = runCatching { VoiceLiveApi(config, a, client).status() }.getOrNull()
        _voice.value = status
        return status
    }

    private fun authLocked(url: String, force: Boolean = false): HermesAuth? {
        if (!force && authFor == url) return auth
        val endpoint = (HermesEndpoint.parse(url) as? HermesEndpoint.Parsed.Valid)?.endpoint ?: return null
        val a = HermesAuth(HermesConfig(endpoint), tokens, client)
        auth = a
        authFor = url
        watcher?.cancel()
        watcher = scope.launch {
            a.state.collect { s ->
                if (s is AuthState.NeedsSignIn && settings.serverUrl.value != null) {
                    Telemetry.warn("auth.refresh_failed", null, mapOf("state" to "needs_sign_in"))
                    _state.value = AccountState.NeedsSignIn(display(url), s.reason)
                    onSessionChanged()
                }
            }
        }
        return a
    }

    companion object {
        /** ok=false plus an enum reason; never the server's text. */
        fun signInFailure(e: HermesAuthException): Map<String, Any?> = mapOf(
            "ok" to false,
            "reason" to when (e) {
                is HermesAuthException.InvalidCredentials -> "invalid_credentials"
                is HermesAuthException.RateLimited -> "rate_limited"
                is HermesAuthException.Unavailable -> "unavailable"
                is HermesAuthException.SignInRequired -> "sign_in_required"
                is HermesAuthException.Protocol -> "protocol"
            },
            "http_status" to (e as? HermesAuthException.Unavailable)?.httpCode,
        )

        fun display(url: String): String = url.removePrefix("https://").removePrefix("http://")

        fun networkMessage(e: Throwable): String {
            val text = e.message.orEmpty()
            return when {
                text.contains("cleartext", ignoreCase = true) ->
                    "Android refused plain http:// to that address. Use the *.ts.net name or https://."
                text.contains("Unable to resolve host", ignoreCase = true) -> "Could not find that server. Check the address and network."
                text.contains("timeout", ignoreCase = true) -> "The server did not answer in time."
                else -> "Could not reach the server."
            }
        }
    }
}
