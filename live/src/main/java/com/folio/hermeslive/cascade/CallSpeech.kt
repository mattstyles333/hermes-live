package com.folio.hermeslive.cascade

import android.content.res.AssetManager
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.PlaybackMonitor
import com.folio.hermeslive.stt.CloudStt
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SpeechModelChoice
import com.folio.hermeslive.stt.FallbackLive
import com.folio.hermeslive.stt.Hotwords
import com.folio.hermeslive.stt.LiveFallback
import com.folio.hermeslive.stt.ModelManager
import com.folio.hermeslive.stt.SherpaEngines
import com.folio.hermeslive.stt.SherpaSpeechToText
import com.folio.hermeslive.stt.SttEngineConfig

/** One call's speech-to-text: the port plus what the call needs to drive it. */
interface CallSpeech : SpeechToText {
    /** Open the source and start the capture thread. Throws if the source can't open. */
    fun start()

    /** Stop and free the models. Blocks (up to a running decode): call off the main thread. */
    fun release()

    /** Hold-to-talk on the orb or headset (R-STT-8). */
    fun holdToTalk(pressed: Boolean)

    /** Input level, dBFS. */
    val inputLevelDb: Double
}

/** Builds a call's [CallSpeech] (loads the models: off the main thread). */
fun interface CallSpeechFactory {
    fun create(
        source: AudioSource,
        playback: PlaybackMonitor,
        settings: () -> CascadeSettings,
        hotwords: () -> List<String>,
        /** Settings › Voice: the chosen live and accurate models ([SpeechModelChoice]). */
        speech: LiveSettings,
        /** The cloud pass, used only while `CascadeSettings.cloudSttModel` is set; null: on device only. */
        cloud: CloudStt?,
        /** The live-model guard swapped the live model for this call (capture thread or a loader thread). */
        onFallback: (LiveFallback) -> Unit,
    ): CallSpeech

    companion object {
        /**
         * sherpa-onnx (S-C): Silero VAD from the assets, the chosen live model (Zipformer,
         * Nemotron or Moonshine) and accurate model (Parakeet 110M or 0.6B), each falling back to
         * Zipformer / Parakeet-110M while the chosen one is not downloaded. Hotwords are the
         * user's vocabulary, the task labels and the learned corrections (R-STT-7), read at each
         * segment, where the model takes them. In cloud mode the same words go to the cloud model
         * when it takes them. A live model that can't keep up is swapped for Zipformer for the
         * rest of the call ([LiveGuard]), when Zipformer is installed.
         */
        fun sherpa(assets: AssetManager, models: ModelManager): CallSpeechFactory = CallSpeechFactory { source, playback, settings, hotwords, speech, cloud, onFallback ->
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
                engines, source, playback, settings = settings, hotwords = hotwords, cloud = cloud,
                fallbackLive = loadFallback, onFallback = onFallback,
            )
            object : CallSpeech, SpeechToText by stt {
                override fun start() = stt.start()
                override fun release() = stt.release()
                override fun holdToTalk(pressed: Boolean) = stt.holdToTalk(pressed)
                override val inputLevelDb: Double get() = stt.inputLevelDb.value
            }
        }

        /** A call can start: some live and some accurate model is on the phone (the chosen ones or the fallbacks). */
        fun ready(models: ModelManager, speech: LiveSettings): Boolean = SpeechModelChoice.forCall(speech, models::isInstalled) != null
    }
}
