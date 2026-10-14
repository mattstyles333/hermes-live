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
        val next = transform(_settings.value).normalized()
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
            .putString(K_VOICE_ENGINE, next.voiceEngine.wire)
            .putInt(K_PAUSE_MS, next.pauseMs)
            .putInt(K_MAX_TASKS, next.maxTasks)
            .putString(K_BILL_VOICE, next.billVoice)
            .putString(K_TTS_MODEL, next.ttsModel)
            .putBoolean(K_BACKCHANNEL, next.backchannel)
            .putBoolean(K_HOLD_TO_TALK, next.holdToTalk)
            .putString(K_VOCABULARY, next.vocabulary.joinToString("\n"))
            .putBoolean(K_DECISION_LOG, next.decisionLog)
            .putBoolean(K_ECHO_CANCEL, next.echoCancel)
            .putBoolean(K_HIGH_ACCURACY, next.highAccuracySpeech)
            .putString(K_SPEECH_RECOGNITION, next.speechRecognition.wire)
            .putString(K_CLOUD_STT_MODEL, next.cloudSttModel)
            .putString(K_LIVE_MODEL, next.liveModel)
            .putString(K_ACCURATE_MODEL, next.accurateModel)
            .putBoolean(K_SPEECH_MODELS_CARD, next.speechModelsCard)
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
            voiceEngine = VoiceEngine.parse(prefs.getString(K_VOICE_ENGINE, null)),
            pauseMs = prefs.getInt(K_PAUSE_MS, d.pauseMs),
            maxTasks = prefs.getInt(K_MAX_TASKS, d.maxTasks),
            billVoice = prefs.getString(K_BILL_VOICE, null) ?: d.billVoice,
            ttsModel = prefs.getString(K_TTS_MODEL, null) ?: d.ttsModel,
            backchannel = prefs.getBoolean(K_BACKCHANNEL, d.backchannel),
            holdToTalk = prefs.getBoolean(K_HOLD_TO_TALK, d.holdToTalk),
            vocabulary = prefs.getString(K_VOCABULARY, null)?.split('\n') ?: d.vocabulary,
            decisionLog = prefs.getBoolean(K_DECISION_LOG, d.decisionLog),
            echoCancel = prefs.getBoolean(K_ECHO_CANCEL, d.echoCancel),
            highAccuracySpeech = prefs.getBoolean(K_HIGH_ACCURACY, d.highAccuracySpeech),
            speechRecognition = SpeechRecognition.parse(prefs.getString(K_SPEECH_RECOGNITION, null)),
            cloudSttModel = prefs.getString(K_CLOUD_STT_MODEL, null) ?: d.cloudSttModel,
            liveModel = prefs.getString(K_LIVE_MODEL, null),
            accurateModel = prefs.getString(K_ACCURATE_MODEL, null),
            speechModelsCard = prefs.getBoolean(K_SPEECH_MODELS_CARD, d.speechModelsCard),
        ).normalized()
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
        private const val K_VOICE_ENGINE = "voice_engine"
        private const val K_PAUSE_MS = "cascade_pause_ms"
        private const val K_MAX_TASKS = "cascade_max_tasks"
        private const val K_BILL_VOICE = "cascade_voice"
        private const val K_TTS_MODEL = "cascade_tts_model"
        private const val K_BACKCHANNEL = "cascade_backchannel"
        private const val K_HOLD_TO_TALK = "cascade_hold_to_talk"
        private const val K_VOCABULARY = "cascade_vocabulary"
        private const val K_DECISION_LOG = "cascade_decision_log"
        private const val K_ECHO_CANCEL = "cascade_echo_cancel"
        private const val K_HIGH_ACCURACY = "cascade_high_accuracy"
        private const val K_SPEECH_RECOGNITION = "cascade_speech_recognition"
        private const val K_CLOUD_STT_MODEL = "cascade_cloud_stt_model"
        private const val K_LIVE_MODEL = "cascade_live_model"
        private const val K_ACCURATE_MODEL = "cascade_accurate_model"
        private const val K_SPEECH_MODELS_CARD = "cascade_speech_models_card"
        private const val K_SERVER = "server_url"
        private const val K_USER = "username"
        private const val K_SESSION = "stored_session_id"
        private const val K_SESSION_AT = "stored_session_at"
    }
}
