package com.folio.hermeslive.data

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.live.BridgeSettings
import com.folio.hermeslive.call.FallbackRoute
import com.folio.hermeslive.call.GapSettings

/** Hermes Live's settings. Defaults are the shipped behaviour (see LiveSettingsTest). */
data class LiveSettings(
    /** Pause other players while someone talks; let them play while Hermes works. */
    val musicInGaps: Boolean = true,
    /** On the speaker, duck music in the gaps instead of resuming it fully. */
    val duckOnSpeaker: Boolean = true,
    /** Short tones for connected / muted / ended. */
    val earcons: Boolean = true,
    /** Light haptics with the earcons. */
    val haptics: Boolean = true,
    /** Answer approvals by voice ("approve" / "deny"). Off: approvals need a tap. */
    val spokenApprovals: Boolean = false,
    /** Hang up after this many minutes with nobody talking and no Hermes work. 0 = never. */
    val idleTimeoutMinutes: Int = 3,
    /** Longest call, minutes. 0 = no cap. */
    val callCapMinutes: Int = 30,
    /** Opening the app starts talking (when signed in). */
    val startOnOpen: Boolean = true,
    /** Keep the screen on during a call. */
    val keepScreenOn: Boolean = false,
    /** Route without a headset, and after one disconnects. */
    val fallbackRoute: FallbackRoute = FallbackRoute.Speaker,
    /** Send crash reports and errors (never content) to the diagnostics endpoint. */
    val diagnostics: Boolean = true,
    /** Optional OTLP/HTTP endpoint that replaces the built-in one. Empty = the built-in default. */
    val diagnosticsUrl: String = "",

    // ── Settings › Voice (cascade, spec v2; off unless [voiceEngine] is cascade and a key is saved) ──
    /** Which voice runs a call. Default GPT-Live: the cascade stays off (R-Q-1). */
    val voiceEngine: VoiceEngine = VoiceEngine.GptLive,
    /** Pause before Jev decides, ms (400–3000). R-RT-1. */
    val pauseMs: Int = DEFAULT_PAUSE_MS,
    /** Voice tasks running at once (1–6). R-TASK-1. */
    val maxTasks: Int = DEFAULT_MAX_TASKS,
    /** OpenRouter TTS voice. */
    val billVoice: String = DEFAULT_VOICE,
    /** OpenRouter TTS model. */
    val ttsModel: String = DEFAULT_TTS_MODEL,
    /** Let Jev pick the short `listen` lines while you talk (off: it risks talking over you). */
    val backchannel: Boolean = false,
    /** Hold-to-talk (headset or orb) instead of the pause ring. R-STT-8. */
    val holdToTalk: Boolean = false,
    /** Your words for the speech models and the writer, one phrase each. R-STT-6/7. */
    val vocabulary: List<String> = emptyList(),
    /** Keep the on-device decision log (1,000 turns or 14 days). R-Q-2. */
    val decisionLog: Boolean = true,
    /** Capture with the phone's call processing (VOICE_COMMUNICATION + echo canceller). §11b. */
    val echoCancel: Boolean = true,
    /** Use the larger second-pass speech model ("High accuracy"; a bigger download). §11b. */
    val highAccuracySpeech: Boolean = false,
) {
    /** The same settings with every Voice field inside its range (what is saved and used). */
    fun normalized(): LiveSettings = copy(
        pauseMs = pauseMs.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS),
        maxTasks = maxTasks.coerceIn(MIN_TASKS, MAX_TASKS),
        billVoice = billVoice.trim().ifEmpty { DEFAULT_VOICE },
        ttsModel = ttsModel.trim().ifEmpty { DEFAULT_TTS_MODEL },
        vocabulary = cleanVocabulary(vocabulary),
    )

    /**
     * The cascade's knobs from these settings (spec v2 §12: S0 owns the fields, the user sets
     * these). Everything else keeps the spec defaults in [base].
     */
    fun cascadeSettings(base: CascadeSettings = CascadeSettings()): CascadeSettings {
        val n = normalized()
        return base.copy(
            pauseMs = n.pauseMs.toLong(),
            maxTasks = n.maxTasks,
            voice = n.billVoice,
            ttsModel = n.ttsModel,
            backchannel = n.backchannel,
            holdToTalk = n.holdToTalk,
            vocabulary = n.vocabulary,
            decisionLog = n.decisionLog,
        )
    }

    fun gapSettings(): GapSettings = GapSettings(enabled = musicInGaps, duckOnSpeaker = duckOnSpeaker)

    fun bridgeSettings(): BridgeSettings = BridgeSettings(
        idleHangUpMs = idleTimeoutMinutes.coerceAtLeast(0) * 60_000L,
        callCapMs = callCapMinutes.coerceAtLeast(0) * 60_000L,
        goodbyeMaxMs = GOODBYE_MAX_MS,
        idleWarningMs = if (idleTimeoutMinutes > 0) IDLE_WARNING_MS else 0L,
    )

    companion object {
        const val DEFAULT_PAUSE_MS = 1_000
        const val MIN_PAUSE_MS = CascadeSettings.MIN_PAUSE_MS.toInt()
        const val MAX_PAUSE_MS = CascadeSettings.MAX_PAUSE_MS.toInt()
        const val DEFAULT_MAX_TASKS = 4
        const val MIN_TASKS = 1
        const val MAX_TASKS = 6
        const val DEFAULT_VOICE = "bill"
        const val DEFAULT_TTS_MODEL = "elevenlabs/eleven-v4-turbo"
        const val MAX_VOCABULARY = 200
        const val MAX_PHRASE_CHARS = 60
        val PAUSE_CHOICES = listOf(400, 600, 800, 1_000, 1_200, 1_500, 2_000, 2_500, 3_000)

        /** One phrase per line: trimmed, inner spaces collapsed, blanks and repeats dropped, capped. */
        fun cleanVocabulary(lines: List<String>): List<String> = lines.asSequence()
            .flatMap { it.split('\n').asSequence() }
            .map { it.trim().replace(WHITESPACE, " ").take(MAX_PHRASE_CHARS) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_VOCABULARY)
            .toList()

        private val WHITESPACE = Regex("\\s+")

        const val GOODBYE_MAX_MS = 4_000L
        const val IDLE_WARNING_MS = 30_000L
        val IDLE_CHOICES = listOf(0, 1, 2, 3, 5, 10)
        val CAP_CHOICES = listOf(0, 10, 15, 30, 45, 60)

        /**
         * Placeholder for the server field. The repo and its Releases are public: no real host
         * is ever built in; the user types their own Hermes dashboard address.
         */
        const val SERVER_HINT = "https://hermes.example.com"

        /** Tailnet shape (cleartext is allowed for *.ts.net only). */
        const val TAILNET_HINT = "http://<machine>.<tailnet>.ts.net:9119"

        /** Placeholder for the diagnostics override; never a real host (public repo). */
        const val DIAGNOSTICS_HINT = "https://logs.example.com"
    }
}

/** Which voice runs a call. [wire] is what is saved. */
enum class VoiceEngine(val wire: String) {
    /** OpenAI GPT-Live through Hermes (today's call). */
    GptLive("gpt-live"),

    /** On-phone speech-to-text, Jev, Hermes agents and Bill's voice (spec v2). */
    Cascade("cascade"),
    ;

    companion object {
        fun parse(wire: String?): VoiceEngine = entries.firstOrNull { it.wire == wire } ?: GptLive
    }
}
