package com.folio.hermes.realtime

/**
 * xAI Grok Voice (speech-to-speech) settings. Verified against docs.x.ai on 2026-10-14
 * (`developers/model-capabilities/audio/speech-to-speech`, the voice API reference and
 * `voice-realtime.ws.json`): `wss://api.x.ai/v1/realtime?model=…`, `Authorization: Bearer <key>`,
 * PCM 16-bit little-endian mono at 24 kHz in and out, `server_vad` turns, a 120-minute session
 * cap, $0.08 per minute. There is no key here: [GrokRealtime] asks for it on each connect.
 */
data class GrokConfig(
    /** No query string: [GrokRealtime] adds `model` and, when resuming, `conversation_id`. */
    val url: String = "wss://api.x.ai/v1/realtime",
    val model: String = GrokModels.DEFAULT,
    /** A built-in voice ([GrokVoices.BUILT_IN]) or a custom voice id (8 lowercase letters or digits). */
    val voice: String = GrokVoices.DEFAULT,
    /** `audio/pcm` at this rate, in and out. */
    val sampleRate: Int = 24_000,
    /** Opt in to `resumption`: a dropped socket reconnects with `conversation_id` and the history replays. */
    val resumption: Boolean = true,
    /** `audio.input.transcription.language_hint` (BCP-47), or null. */
    val languageHint: String? = "en",
    /** `audio.input.transcription.keyterms` (≤ 100 terms of ≤ 50 characters): the user's vocabulary. */
    val keyterms: List<String> = emptyList(),
    /** The role of a task notice item (`conversation.item.create` message). */
    val noticeRole: String = "system",
    /** Reconnects after an unexpected drop while online, before giving up. */
    val reconnectAttempts: Int = 3,
    val reconnectDelayMs: Long = 1_000L,
    val connectTimeoutMs: Long = 10_000L,
    /** WebSocket ping frames (OkHttp), so a dead socket is noticed during a quiet call. */
    val pingIntervalMs: Long = 15_000L,
)

object GrokModels {
    /** "Alias for grok-voice-3.0" (docs, 2026-10-14). */
    const val DEFAULT = "grok-voice-latest"
    val KNOWN = listOf(DEFAULT, "grok-voice-3.0")
}

object GrokVoices {
    const val DEFAULT = "eve"

    /** The voices the docs list (case-insensitive); `GET /v1/tts/voices` has the live list. */
    val BUILT_IN = listOf(
        "eve", "ara", "rex", "sal", "leo", "aurora", "liora", "atlas", "carina", "zagan", "helix", "orion", "luna", "iris",
        "altair", "zenith", "perseus", "helios", "lux", "kepler", "rigel", "cosmo", "celeste", "ursa", "sirius", "lumen", "castor", "naksh",
    )

    private val CUSTOM = Regex("^[a-z0-9]{8}$")

    /** A custom voice id from `POST /v1/custom-voices`. */
    fun isCustomId(id: String): Boolean = CUSTOM.matches(id.trim())

    /** [id] if it is a known voice or a custom id, else the default. */
    fun of(id: String?): String {
        val v = id?.trim()?.lowercase().orEmpty()
        return if (v in BUILT_IN || isCustomId(v)) v else DEFAULT
    }
}

object GrokPricing {
    /** grok-voice-3.0 with `server_vad`, billed for session duration (docs pricing, 2026-10-14). */
    const val USD_PER_MINUTE = 0.08

    /** Session cap: the server ends a session at 120 minutes (`max_duration`). */
    const val SESSION_CAP_MS = 120 * 60_000L
}
