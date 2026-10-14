package com.folio.hermeslive.meeting

import android.content.res.AssetManager
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.CaptureSource
import com.folio.hermeslive.audio.MicAudioSource
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SpeechModelChoice
import com.folio.hermeslive.stt.CloudStt
import com.folio.hermeslive.stt.FallbackLive
import com.folio.hermeslive.stt.Hotwords
import com.folio.hermeslive.stt.LiveFallback
import com.folio.hermeslive.stt.ModelManager
import com.folio.hermeslive.stt.SherpaEngines
import com.folio.hermeslive.stt.SherpaSpeechToText
import com.folio.hermeslive.stt.SttEngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * [MeetingSpeech] on the call's own pipeline ([SherpaSpeechToText]: Silero VAD, the chosen live
 * model, the accurate pass per segment, cloud speech-to-text when the user chose it, the
 * live-model guard). The mic is opened with `VOICE_RECOGNITION` (no call processing: a room, not
 * a handset at the ear). Audio stays in memory until both passes are done; nothing is written.
 *
 * Pause stops capture and Resume starts it again on the same loaded models (no reload).
 */
class SherpaMeetingSpeech(private val stt: SherpaSpeechToText) : MeetingSpeech {
    override val events: Flow<SttEvent> get() = stt.events

    override suspend fun accurate(): AccurateBatch? {
        val t = stt.secondPass.accurate() ?: return null
        return AccurateBatch(t, stt.secondPass.lastTiming?.segments ?: 0)
    }

    override fun start() = stt.start()

    override fun stop() = stt.stop()

    override fun release() = stt.release()

    override val levelDb: StateFlow<Double> get() = stt.inputLevelDb

    companion object {
        /** True when a live and an accurate model are on the phone (the chosen ones or the fallbacks). */
        fun ready(models: ModelManager, speech: LiveSettings): Boolean = SpeechModelChoice.forCall(speech, models::isInstalled) != null

        /** Loads the models: off the main thread. Throws when none is installed. */
        fun load(
            assets: AssetManager,
            models: ModelManager,
            speech: LiveSettings,
            settings: () -> CascadeSettings,
            hotwords: () -> List<String>,
            cloud: CloudStt?,
            source: AudioSource = MicAudioSource(CaptureSource.VOICE_RECOGNITION),
            onFallback: (LiveFallback) -> Unit = {},
        ): SherpaMeetingSpeech {
            val (live, accurate) = SpeechModelChoice.forCall(speech, models::isInstalled) ?: error("no speech models installed")
            val config = SttEngineConfig(streaming = live, accurate = accurate)
            val engines = SherpaEngines.load(assets, models, config)
            val fallback = SpeechModelChoice.FALLBACK_LIVE.takeIf { it != live && models.isInstalled(it) }
            val loadFallback: (() -> FallbackLive)? = fallback?.let { fb ->
                {
                    val fbConfig = config.copy(streaming = fb)
                    val engine = SherpaEngines.streaming(models, fbConfig)
                    val decoding = Hotwords.decodingFor(fb.kind, fbConfig.decoding)
                    val vocab = models.hasVocab(fb)
                    FallbackLive(engine) { words -> Hotwords.argument(words, fb, decoding, vocab) }
                }
            }
            val stt = SherpaSpeechToText(
                engines, source, settings = settings, hotwords = hotwords, cloud = cloud,
                fallbackLive = loadFallback, onFallback = onFallback,
            )
            return SherpaMeetingSpeech(stt)
        }
    }
}
