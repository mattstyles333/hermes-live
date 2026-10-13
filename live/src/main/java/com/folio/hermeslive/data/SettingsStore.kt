package com.folio.hermeslive.data

import android.content.SharedPreferences
import com.folio.hermes.telemetry.DiagnosticsEndpoint
import com.folio.hermeslive.call.FallbackRoute
import com.folio.hermeslive.call.SessionMemory
import com.folio.hermeslive.call.SessionMemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings, the server address and the Hermes session to resume, in plain SharedPreferences
 * (nothing secret: tokens and the password live in [SecureTokenStore]).
 */
class SettingsStore(private val prefs: SharedPreferences) : SessionMemoryStore {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LiveSettings> = _settings.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.getString(K_SERVER, null))

    /** The signed-in server's base URL, or null. */
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    /** The user name last signed in with (shown in Settings; not a secret). */
    val username: String? get() = prefs.getString(K_USER, null)

    fun update(transform: (LiveSettings) -> LiveSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(K_MUSIC, next.musicInGaps)
            .putBoolean(K_DUCK, next.duckOnSpeaker)
            .putBoolean(K_EARCONS, next.earcons)
            .putBoolean(K_HAPTICS, next.haptics)
            .putBoolean(K_SPOKEN_APPROVALS, next.spokenApprovals)
            .putInt(K_IDLE, next.idleTimeoutMinutes)
            .putInt(K_CAP, next.callCapMinutes)
            .putBoolean(K_START_ON_OPEN, next.startOnOpen)
            .putBoolean(K_KEEP_SCREEN_ON, next.keepScreenOn)
            .putString(K_FALLBACK, next.fallbackRoute.name)
            .putBoolean(K_DIAGNOSTICS, next.diagnostics)
            .putString(K_DIAGNOSTICS_URL, next.diagnosticsUrl.trim())
            .apply()
        _settings.value = next
    }

    fun setAccount(serverUrl: String?, username: String?) {
        prefs.edit().putString(K_SERVER, serverUrl).putString(K_USER, username).apply()
        _serverUrl.value = serverUrl
    }

    override fun load(): SessionMemory =
        SessionMemory(prefs.getString(K_SESSION, null), prefs.getLong(K_SESSION_AT, 0L))

    override fun save(memory: SessionMemory) {
        prefs.edit().putString(K_SESSION, memory.storedSessionId).putLong(K_SESSION_AT, memory.lastActiveAtMs).apply()
    }

    /** Sign-out: forget the account and the conversation; keep the call preferences. */
    fun clearAccount() {
        prefs.edit().remove(K_SERVER).remove(K_USER).remove(K_SESSION).remove(K_SESSION_AT).commit()
        _serverUrl.value = null
    }

    private fun read(): LiveSettings {
        val d = LiveSettings()
        return LiveSettings(
            musicInGaps = prefs.getBoolean(K_MUSIC, d.musicInGaps),
            duckOnSpeaker = prefs.getBoolean(K_DUCK, d.duckOnSpeaker),
            earcons = prefs.getBoolean(K_EARCONS, d.earcons),
            haptics = prefs.getBoolean(K_HAPTICS, d.haptics),
            spokenApprovals = prefs.getBoolean(K_SPOKEN_APPROVALS, d.spokenApprovals),
            idleTimeoutMinutes = prefs.getInt(K_IDLE, d.idleTimeoutMinutes),
            callCapMinutes = prefs.getInt(K_CAP, d.callCapMinutes),
            startOnOpen = prefs.getBoolean(K_START_ON_OPEN, d.startOnOpen),
            keepScreenOn = prefs.getBoolean(K_KEEP_SCREEN_ON, d.keepScreenOn),
            fallbackRoute = prefs.getString(K_FALLBACK, null)
                ?.let { name -> FallbackRoute.entries.firstOrNull { it.name == name } } ?: d.fallbackRoute,
            diagnostics = prefs.getBoolean(K_DIAGNOSTICS, d.diagnostics),
            diagnosticsUrl = prefs.getString(K_DIAGNOSTICS_URL, null) ?: d.diagnosticsUrl,
        )
    }

    companion object {
        const val PREFS = "hermes_live"

        /**
         * The diagnostics endpoint in force, read straight from [prefs] (the telemetry thread
         * asks before the app graph exists): off when switched off, else the override, else
         * [buildDefault] (`BuildConfig.DIAGNOSTICS_URL`, empty outside release CI).
         */
        fun diagnosticsEndpoint(prefs: SharedPreferences, buildDefault: String): String? =
            DiagnosticsEndpoint.effective(
                enabled = prefs.getBoolean(K_DIAGNOSTICS, true),
                override = prefs.getString(K_DIAGNOSTICS_URL, null),
                buildDefault = buildDefault,
            )

        private const val K_DIAGNOSTICS = "diagnostics"
        private const val K_DIAGNOSTICS_URL = "diagnostics_url"
        private const val K_MUSIC = "music_in_gaps"
        private const val K_DUCK = "duck_on_speaker"
        private const val K_EARCONS = "earcons"
        private const val K_HAPTICS = "haptics"
        private const val K_SPOKEN_APPROVALS = "spoken_approvals"
        private const val K_IDLE = "idle_minutes"
        private const val K_CAP = "cap_minutes"
        private const val K_START_ON_OPEN = "start_on_open"
        private const val K_KEEP_SCREEN_ON = "keep_screen_on"
        private const val K_FALLBACK = "fallback_route"
        private const val K_SERVER = "server_url"
        private const val K_USER = "username"
        private const val K_SESSION = "stored_session_id"
        private const val K_SESSION_AT = "stored_session_at"
    }
}
