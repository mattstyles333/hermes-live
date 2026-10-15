package com.folio.hermeslive.stt

import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.data.HermesVoices
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SpeechRecognition
import com.folio.hermeslive.data.SttRoute
import com.folio.hermeslive.data.VoiceRouting

/**
 * The cloud pass from where Settings › Voice › "Cloud speech recognition from" says (Hermes voice
 * slice), at the same integration point as before: one segment's in-memory WAV as it ends, the
 * on-device text standing on any failure or when it is slow ([SecondPass] never waits longer).
 *
 * Hermes (direct to its provider, or relayed through `POST /api/audio/transcribe`) when preferred
 * and its config offers STT; a Hermes failure falls back to OpenRouter's [openRouter] pass within
 * the same time budget while a key is usable; no source: null (on device). Offline: no request.
 * Words ([terms]): only where the wire takes them ([com.folio.hermes.live.DirectVoiceWires.prompt]).
 */
class SourcedCloudStt(
    private val settings: () -> LiveSettings,
    private val hermes: HermesVoices,
    /** OpenRouter's pass (today's), or null without a key. */
    private val openRouter: CloudStt?,
    private val openRouterUsable: () -> Boolean,
    private val online: () -> Boolean,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : CloudStt {
    /** Where the next clip goes (Settings and telemetry). */
    fun route(): SttRoute {
        val s = settings()
        val cfg = if (s.sttFrom.prefersHermes) hermes.cached() else null
        return VoiceRouting.stt(s.speechRecognition == SpeechRecognition.Cloud, s.sttFrom, cfg, openRouterUsable() && openRouter != null)
    }

    override suspend fun transcribe(wav: ByteArray, model: String, terms: List<String>, timeoutMs: Long): String? {
        if (!online()) return null
        val started = clock()
        val s = settings()
        val voice = if (s.sttFrom.prefersHermes) hermes.voice() else null
        if (voice != null) {
            val cfg = voice.config()
            if (cfg != null && cfg.stt.available) {
                val r = voice.transcribe(wav, terms, timeoutMs)
                if (r is OpenRouterResult.Ok) return r.value.takeIf { it.isNotBlank() }
                val left = timeoutMs - (clock() - started)
                if (openRouter == null || !openRouterUsable() || left < MIN_FALLBACK_MS) return null
                runCatching { telemetry.event(EVENT_FALLBACK, mapOf("kind" to (r as OpenRouterResult.Failed).kind.wire, "left_ms" to left), false) }
                return openRouter.transcribe(wav, model, terms, left)
            }
        }
        if (openRouter == null || !openRouterUsable()) return null
        return openRouter.transcribe(wav, model, terms, timeoutMs)
    }

    companion object {
        /** Too little time left for a second upload: the on-device text stands. */
        const val MIN_FALLBACK_MS = 800L
        const val EVENT_FALLBACK = "cloud_stt.fallback"
    }
}
