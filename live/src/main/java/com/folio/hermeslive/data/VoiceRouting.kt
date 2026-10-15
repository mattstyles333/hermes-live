package com.folio.hermeslive.data

import com.folio.hermes.cascade.BillVoice
import com.folio.hermes.cascade.OpenRouterSpeechSource
import com.folio.hermes.cascade.SpeechSource
import com.folio.hermes.cascade.TtsCache
import com.folio.hermes.cascade.VoiceIdentity
import com.folio.hermes.live.HermesSpeechSource
import com.folio.hermes.live.HermesVoice
import com.folio.hermes.live.HermesVoiceConfig
import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.live.VoiceSide
import com.folio.hermes.openrouter.SpeechClient

/** Where Custom's voice comes from now. [wire] is telemetry's `voice_source`. */
enum class VoiceRoute(val wire: String) {
    HermesDirect("hermes_direct"),
    HermesRelay("hermes_relay"),
    OpenRouter("openrouter"),

    /** No source at all: the call says so before it starts. */
    None("none"),
}

/** Where a cloud speech clip goes now. [wire] is telemetry's `voice_source`. */
enum class SttRoute(val wire: String) {
    HermesDirect("hermes_direct"),
    HermesRelay("hermes_relay"),
    OpenRouter("openrouter"),

    /** On device only (cloud off, or no cloud source). */
    OnDevice("device"),
}

/**
 * The Hermes voice slice's choice, pure: Hermes when it is preferred ([SourceChoice.prefersHermes])
 * and its config (in memory) offers the side; else OpenRouter while a key is usable; else nothing
 * (TTS: [VoiceRoute.None], STT: on device).
 */
object VoiceRouting {
    fun tts(choice: SourceChoice, hermes: HermesVoiceConfig?, openRouterUsable: Boolean): VoiceRoute {
        val side = hermes?.tts?.takeIf { choice.prefersHermes && it.available }
        return when {
            side is VoiceSide.Direct -> VoiceRoute.HermesDirect
            side != null -> VoiceRoute.HermesRelay
            openRouterUsable -> VoiceRoute.OpenRouter
            else -> VoiceRoute.None
        }
    }

    fun stt(cloud: Boolean, choice: SourceChoice, hermes: HermesVoiceConfig?, openRouterUsable: Boolean): SttRoute {
        if (!cloud) return SttRoute.OnDevice
        val side = hermes?.stt?.takeIf { choice.prefersHermes && it.available }
        return when {
            side is VoiceSide.Direct -> SttRoute.HermesDirect
            side != null -> SttRoute.HermesRelay
            openRouterUsable -> SttRoute.OpenRouter
            else -> SttRoute.OnDevice
        }
    }

    /** The voice the picked Hermes voice applies to (a direct wire's provider), or null. */
    fun hermesTtsProvider(cfg: HermesVoiceConfig?): String? = (cfg?.tts as? VoiceSide.Direct)?.provider
}

/**
 * Bill's voice, routed per line: Hermes's when it is preferred and its config says it has a TTS
 * (before the first fetch Hermes is asked, so the config is learned first), else OpenRouter's.
 * OpenRouter is also the fallback for a line Hermes fails, while its key is usable.
 */
class RoutedVoice(
    private val settings: () -> LiveSettings,
    private val hermes: HermesVoices,
    private val openRouter: SpeechSource,
    private val openRouterUsable: () -> Boolean,
) {
    /** The source to ask first now. */
    fun primary(): SpeechSource = hermesSource() ?: openRouter

    /** OpenRouter when Hermes speaks first and a key is usable, else nothing. */
    fun fallback(): SpeechSource? = if (hermesSource() != null && openRouterUsable()) openRouter else null

    private fun hermesSource(): SpeechSource? {
        val s = settings()
        if (!s.voiceFrom.prefersHermes) return null
        val v: HermesVoice = hermes.voice() ?: return null
        val cfg = v.cached()
        val usable = when {
            cfg != null -> cfg.tts.available
            // Not asked yet: ask (the source fetches the config before the cache is read).
            else -> v.state.value is HermesVoiceState.Unknown
        }
        if (!usable) return null
        return HermesSpeechSource(v) { s.hermesVoiceFor(VoiceRouting.hermesTtsProvider(v.cached())) }
    }

    /** The route the next line takes (telemetry and the call's pre-start check). */
    fun route(): VoiceRoute {
        val s = settings()
        val cfg = if (s.voiceFrom.prefersHermes) hermes.voice()?.cached() else null
        return VoiceRouting.tts(s.voiceFrom, cfg, openRouterUsable())
    }

    /** What the stock lines are rendered as now, or null while Hermes is preferred but not known yet. */
    fun identity(): VoiceIdentity? = primary().identity()

    /** A [BillVoice] on these sources and [cache]. */
    fun billVoice(cache: TtsCache): BillVoice = BillVoice(cache, ::primary, fallback = ::fallback)

    companion object {
        /** OpenRouter's voice from the settings (today's source). */
        fun openRouterSource(speech: SpeechClient, settings: () -> LiveSettings): SpeechSource =
            OpenRouterSpeechSource(speech, { settings().normalized().billVoice }, { settings().normalized().ttsModel })
    }
}
