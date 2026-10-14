package com.folio.hermeslive.ui

import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SpeechModelChoice
import com.folio.hermeslive.data.ModelState
import com.folio.hermeslive.data.PrepPhase
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.SpeechModelStatus
import com.folio.hermeslive.data.VoiceEngine

/** Bill's readiness for the idle screen's voice line (with the engine set to `cascade`). */
enum class BillReadiness { Ready, Preparing, NeedsModels, NeedsKey, KeyRefused }

/**
 * The idle screen's "Voice: …" line, for the chosen engine. GPT-Live shows the server's
 * voice-live status (as before). Bill shows his own readiness, never GPT-Live's: ready, preparing
 * (with progress), his speech models still to fetch, no key, or the key refused.
 *
 * "Ready" agrees with the call switch (`CallSwitch.plan`): a usable key and a live and an accurate
 * speech model installed (the chosen ones, or Zipformer and Parakeet-110M while those download),
 * and the stock lines render on first use, so neither holds a call back.
 */
object HomeVoiceLine {
    fun readiness(key: KeyStatus, prep: PrepState, models: List<SpeechModelStatus>, settings: LiveSettings = LiveSettings()): BillReadiness = when {
        !key.saved -> BillReadiness.NeedsKey
        key.check == KeyCheck.Refused -> BillReadiness.KeyRefused
        // Installed speech models decide (as `CallSwitch.plan` does); a phrase render alone never holds a call back.
        callable(settings, models) -> BillReadiness.Ready
        prep.phase == PrepPhase.Running -> BillReadiness.Preparing
        else -> BillReadiness.NeedsModels
    }

    /** The line, or null when there is nothing to say yet (GPT-Live before its status is read). */
    fun of(
        settings: LiveSettings,
        gptLive: VoiceLiveStatus?,
        key: KeyStatus,
        prep: PrepState,
        models: List<SpeechModelStatus>,
        xaiKey: KeyStatus = KeyStatus(),
    ): String? {
        if (settings.voiceEngine == VoiceEngine.Grok) {
            return "Voice: Grok · " + when {
                !xaiKey.saved -> "needs an xAI key (Settings › Voice)"
                xaiKey.check == KeyCheck.Refused -> "xAI refused the key (Settings › Voice)"
                else -> "ready"
            }
        }
        if (settings.voiceEngine != VoiceEngine.Cascade) return gptLive?.let { "Voice: ${voiceText(it)}" }
        return "Voice: Bill · " + when (readiness(key, prep, models, settings)) {
            BillReadiness.Ready -> "ready" + if (prep.phase == PrepPhase.Running) progress(prep, models).let { if (it.isEmpty()) "" else " ($it)" } else ""
            BillReadiness.Preparing -> "preparing" + progress(prep, models).let { if (it.isEmpty()) "…" else " ($it)" }
            BillReadiness.NeedsModels -> "needs his speech models" +
                missing(settings, models).sumOf { (it.bytesTotal - it.bytesDone).coerceAtLeast(0) }.let { if (it > 0) " (about ${EngineStatus.megabytes(it)})" else "" } +
                ", downloaded on Wi-Fi"
            BillReadiness.NeedsKey -> "needs an OpenRouter key (Settings › Voice)"
            BillReadiness.KeyRefused -> "OpenRouter refused the key (Settings › Voice)"
        }
    }

    /** "speech models 42%, lines 120 of 285": only the parts that are still under way. */
    private fun progress(prep: PrepState, models: List<SpeechModelStatus>): String {
        val fetching = models.filter { (it.state == ModelState.Downloading || it.state == ModelState.Verifying) && it.bytesTotal > 0 }
        val parts = mutableListOf<String>()
        if (fetching.isNotEmpty()) {
            val done = fetching.sumOf { it.bytesDone.coerceAtMost(it.bytesTotal) }
            val total = fetching.sumOf { it.bytesTotal }
            parts += "speech models ${done * 100 / total}%"
        }
        val lines = prep.phrases
        if (lines.total > 0 && !lines.finished) parts += "lines ${(lines.done + lines.cached).coerceAtMost(lines.total)} of ${lines.total}"
        return parts.joinToString(", ")
    }

    /** A call can start: a live and an accurate model on the phone (the chosen ones or the fallbacks; `SpeechModelChoice.forCall`). */
    private fun callable(settings: LiveSettings, models: List<SpeechModelStatus>): Boolean =
        SpeechModelChoice.forCall(settings) { m -> models.any { it.id == m.id && it.state == ModelState.Ready } } != null

    /** The chosen models still to fetch. */
    private fun missing(settings: LiveSettings, models: List<SpeechModelStatus>): List<SpeechModelStatus> {
        val ids = SpeechModelChoice.wanted(settings).map { it.id }.toSet()
        return models.filter { it.id in ids && it.state != ModelState.Ready }
    }
}
