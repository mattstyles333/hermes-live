package com.folio.hermes.live

import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.SpeechSource
import com.folio.hermes.cascade.SpokenClip
import com.folio.hermes.cascade.VoiceIdentity
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings knows about Hermes's voice now (no key, no URL). */
sealed interface HermesVoiceState {
    /** Not asked yet (signed out, another engine, or the app just started). */
    data object Unknown : HermesVoiceState

    /** The server could not say: [kind] is a failure kind's wire name (`offline`, `refused`, `http`, `none`: no voice-config route). */
    data class Unavailable(val kind: String) : HermesVoiceState

    data class Ready(val view: HermesVoiceView) : HermesVoiceState
}

/** Which way a Hermes voice request went, for telemetry: `hermes_direct` or `hermes_relay`. */
enum class HermesRoute(val wire: String) { Direct("hermes_direct"), Relay("hermes_relay") }

/**
 * The user's Hermes server as a voice source: its `voice-config` held **in memory only** (the
 * provider keys in it never touch disk, logs or telemetry), refreshed when [config] is asked with
 * `force` (app start and call start) and otherwise at most every [ttlMs] (30 min). A failed fetch
 * keeps a config it already had for transient failures (offline, timeout, 5xx) and drops it for
 * the rest; it is not retried within [retryMs] unless forced. [forget] drops it (sign-out).
 *
 * [speak] and [transcribe] go direct when the config says so (with one forced refetch and retry
 * after a 401/403: the key may have rotated), else through the relay routes. Telemetry:
 * `hermes_voice.config`, `hermes_voice.tts`, `hermes_voice.stt` with `voice_source`
 * (`hermes_direct` | `hermes_relay`), wire, provider, ms, ok, failure kind and HTTP status only.
 */
class HermesVoice(
    private val api: HermesVoiceApi,
    private val wires: DirectVoiceWires,
    private val decoder: AudioDecoder,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val retryMs: Long = RETRY_MS,
    private val speakTimeoutMs: Long = SPEAK_TIMEOUT_MS,
    /** After an offline or timed-out request, that side is skipped (fails at once) for this long. */
    private val downMs: Long = DOWN_MS,
) {
    @Volatile private var ttsDownUntil = 0L
    @Volatile private var sttDownUntil = 0L
    private val mutex = Mutex()
    @Volatile private var current: HermesVoiceConfig? = null
    @Volatile private var failedAtMs: Long? = null
    private val _state = MutableStateFlow<HermesVoiceState>(HermesVoiceState.Unknown)

    /** For Settings: what Hermes uses, or why it can't say. */
    val state: StateFlow<HermesVoiceState> = _state.asStateFlow()

    /** The config in memory, without a request (null: not fetched, or dropped). */
    fun cached(): HermesVoiceConfig? = current

    /** The config: the one in memory while fresh, else fetched now. [force]: fetch now (call start). */
    suspend fun config(force: Boolean = false): HermesVoiceConfig? = mutex.withLock {
        val now = nowMs()
        val c = current
        if (!force && c != null && now - c.fetchedAtMs < ttlMs) return c
        if (!force && c == null && failedAtMs?.let { now - it < retryMs } == true) return null
        fetch(now)
    }

    /** Drop the config and its keys (sign-out, another account). */
    fun forget() {
        current = null
        failedAtMs = null
        ttsDownUntil = 0L
        sttDownUntil = 0L
        _state.value = HermesVoiceState.Unknown
    }

    private suspend fun fetch(now: Long): HermesVoiceConfig? {
        val started = System.nanoTime()
        val result = api.voiceConfig()
        val ms = (System.nanoTime() - started) / 1_000_000
        val attrs = linkedMapOf<String, Any?>("ms" to ms)
        when (result) {
            is OpenRouterResult.Ok -> {
                val cfg = result.value
                current = cfg
                failedAtMs = if (cfg == null) now else null
                if (cfg != null) {
                    // Reachable again: try both sides at once.
                    ttsDownUntil = 0L
                    sttDownUntil = 0L
                }
                _state.value = cfg?.let { HermesVoiceState.Ready(it.view()) } ?: HermesVoiceState.Unavailable(NO_ROUTE)
                attrs["ok"] = cfg != null
                cfg?.let {
                    attrs["stt_mode"] = SideView.of(it.stt).mode
                    attrs["tts_mode"] = SideView.of(it.tts).mode
                    (it.stt as? VoiceSide.Direct)?.let { d -> attrs["stt_wire"] = d.wire; attrs["stt_provider"] = d.provider }
                    (it.tts as? VoiceSide.Direct)?.let { d -> attrs["tts_wire"] = d.wire; attrs["tts_provider"] = d.provider }
                } ?: run { attrs["kind"] = NO_ROUTE }
            }
            is OpenRouterResult.Failed -> {
                failedAtMs = now
                val transient = result.kind == FailureKind.Offline || result.kind == FailureKind.Timeout ||
                    (result.kind == FailureKind.Http && (result.httpStatus ?: 500) >= 500)
                if (!transient) current = null
                _state.value = current?.let { HermesVoiceState.Ready(it.view()) } ?: HermesVoiceState.Unavailable(result.kind.wire)
                attrs["ok"] = false
                attrs["kind"] = result.kind.wire
                result.httpStatus?.let { attrs["http_status"] = it }
                attrs["kept"] = current != null
            }
        }
        runCatching { telemetry.event(EVENT_CONFIG, attrs, warn = result is OpenRouterResult.Failed && result.kind != FailureKind.NoKey) }
        return current
    }

    /** The cache identity of [cfg]'s TTS with [voice] (the user's pick; only a direct wire can apply it). */
    fun ttsIdentity(cfg: HermesVoiceConfig, voice: String?): VoiceIdentity = when (val t = cfg.tts) {
        is VoiceSide.Direct -> VoiceIdentity("hermes:${t.provider}/${t.model ?: "-"}", voice?.trim()?.ifEmpty { null } ?: t.voice ?: "-")
        is VoiceSide.Relay -> VoiceIdentity("hermes:relay/${t.provider ?: "-"}/${t.model ?: "-"}", "configured")
    }

    /** [text] in Hermes's voice ([voice] applies to a direct wire only; the relay speaks the configured one). */
    suspend fun speak(text: String, voice: String?): OpenRouterResult<SpokenClip> {
        // Unreachable a moment ago: fail at once so the fallback speaks without a wait per line.
        if (nowMs() < ttsDownUntil) return OpenRouterResult.Failed(FailureKind.Offline)
        val cfg = config() ?: return OpenRouterResult.Failed(unavailableKind())
        val first = speakOnce(cfg, text, voice)
        val failed = first as? OpenRouterResult.Failed ?: return first
        if (failed.kind != FailureKind.Refused || cfg.tts !is VoiceSide.Direct) return first
        val again = config(force = true) ?: return first
        return speakOnce(again, text, voice)
    }

    private suspend fun speakOnce(cfg: HermesVoiceConfig, text: String, voice: String?): OpenRouterResult<SpokenClip> {
        val started = System.nanoTime()
        val side = cfg.tts
        val route = if (side is VoiceSide.Direct) HermesRoute.Direct else HermesRoute.Relay
        val result: OpenRouterResult<SpokenBytes> = when (side) {
            is VoiceSide.Direct -> wires.speak(side, text, voice, speakTimeoutMs)
            is VoiceSide.Relay -> if (side.available) api.speak(text, speakTimeoutMs) else OpenRouterResult.Failed(FailureKind.NoKey)
        }
        val out: OpenRouterResult<SpokenClip> = when (result) {
            is OpenRouterResult.Ok -> {
                val clip: AudioClip? = VoiceAudio.clip(result.value.bytes, result.value.contentType, decoder, askedPcm = side is VoiceSide.Direct)
                if (clip == null) OpenRouterResult.Failed(FailureKind.BadBody) else OpenRouterResult.Ok(SpokenClip(clip, ttsIdentity(cfg, voice)))
            }
            is OpenRouterResult.Failed -> result
        }
        val attrs = linkedMapOf<String, Any?>(
            "voice_source" to route.wire, "ms" to (System.nanoTime() - started) / 1_000_000, "ok" to (out is OpenRouterResult.Ok),
        )
        (side as? VoiceSide.Direct)?.let { attrs["wire"] = it.wire; attrs["provider"] = it.provider }
        (result as? OpenRouterResult.Ok)?.let { attrs["bytes"] = it.value.bytes.size; attrs["type"] = it.value.contentType?.substringBefore(';')?.take(24) }
        (out as? OpenRouterResult.Ok)?.let { attrs["audio_ms"] = it.value.clip.durationMs }
        (out as? OpenRouterResult.Failed)?.let { f -> attrs["kind"] = f.kind.wire; f.httpStatus?.let { attrs["http_status"] = it } }
        runCatching { telemetry.event(EVENT_TTS, attrs, warn = out is OpenRouterResult.Failed) }
        if (out is OpenRouterResult.Failed && out.kind.unreachable) ttsDownUntil = nowMs() + downMs
        return out
    }

    /**
     * One 16 kHz mono [wav] through Hermes's STT: direct (the user's [terms] only where the wire
     * takes them: [DirectVoiceWires.prompt]) or the relay (no words: the server's route has no field
     * for them). `Ok("")`: no speech.
     */
    suspend fun transcribe(wav: ByteArray, terms: List<String>, timeoutMs: Long): OpenRouterResult<String> {
        if (nowMs() < sttDownUntil) return OpenRouterResult.Failed(FailureKind.Offline)
        val cfg = config() ?: return OpenRouterResult.Failed(unavailableKind())
        val started = System.nanoTime()
        val side = cfg.stt
        val route = if (side is VoiceSide.Direct) HermesRoute.Direct else HermesRoute.Relay
        val limit = (side as? VoiceSide.Direct)?.timeoutS?.let { (it * 1_000).toLong().coerceAtLeast(1) }?.coerceAtMost(timeoutMs) ?: timeoutMs
        val out = when (side) {
            is VoiceSide.Direct -> wires.transcribe(side, wav, terms, limit)
            is VoiceSide.Relay -> if (side.available) api.transcribe(wav, "audio/wav", limit) else OpenRouterResult.Failed(FailureKind.NoKey)
        }
        if (out is OpenRouterResult.Failed && out.kind == FailureKind.Refused && side is VoiceSide.Direct) {
            // A rotated key: the next segment uses a fresh config (this one's time is spent).
            runCatching { config(force = true) }
        }
        val attrs = linkedMapOf<String, Any?>(
            "voice_source" to route.wire, "ms" to (System.nanoTime() - started) / 1_000_000, "ok" to (out is OpenRouterResult.Ok), "bytes" to wav.size,
        )
        (side as? VoiceSide.Direct)?.let { attrs["wire"] = it.wire; attrs["provider"] = it.provider }
        (out as? OpenRouterResult.Ok)?.let { attrs["empty"] = it.value.isBlank() }
        (out as? OpenRouterResult.Failed)?.let { f -> attrs["kind"] = f.kind.wire; f.httpStatus?.let { attrs["http_status"] = it } }
        runCatching { telemetry.event(EVENT_STT, attrs, warn = out is OpenRouterResult.Failed && out.kind != FailureKind.Timeout) }
        // Only an unreachable route trips it: one slow clip past the call's short budget is not "down".
        if (out is OpenRouterResult.Failed && out.kind == FailureKind.Offline) sttDownUntil = nowMs() + downMs
        return out
    }

    /** ElevenLabs voices from Hermes (its key stays on the server). */
    suspend fun elevenLabsVoices(): OpenRouterResult<ElevenLabsVoices> = api.elevenLabsVoices()

    private fun unavailableKind(): FailureKind = when (val s = _state.value) {
        is HermesVoiceState.Unavailable -> FailureKind.entries.firstOrNull { it.wire == s.kind } ?: FailureKind.Http
        else -> FailureKind.Offline
    }

    companion object {
        /** Refetch at most this often without a forced refresh. */
        const val TTL_MS = 30 * 60_000L

        /** After a failed fetch, wait this long before an unforced retry. */
        const val RETRY_MS = 60_000L
        const val SPEAK_TIMEOUT_MS = 20_000L
        const val DOWN_MS = 60_000L

        private val FailureKind.unreachable: Boolean get() = this == FailureKind.Offline || this == FailureKind.Timeout
        const val NO_ROUTE = "none"
        const val EVENT_CONFIG = "hermes_voice.config"
        const val EVENT_TTS = "hermes_voice.tts"
        const val EVENT_STT = "hermes_voice.stt"
    }
}

/** Bill's voice from Hermes ([HermesVoice.speak]); [voice] is the user's pick for a direct wire (null: Hermes's own). */
class HermesSpeechSource(private val hermes: HermesVoice, private val voice: () -> String? = { null }) : SpeechSource {
    override fun identity(): VoiceIdentity? = hermes.cached()?.let { hermes.ttsIdentity(it, voice()) }

    override suspend fun prepare() {
        hermes.config()
    }

    override suspend fun synth(text: String): OpenRouterResult<SpokenClip> = hermes.speak(text, voice())
}
