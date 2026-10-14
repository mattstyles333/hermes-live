package com.folio.hermeslive.cascade

import android.content.res.AssetManager
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.PlaybackMonitor
import com.folio.hermeslive.stt.CloudStt
import com.folio.hermeslive.stt.ModelManager
import com.folio.hermeslive.stt.ModelSet
import com.folio.hermeslive.stt.SherpaEngines
import com.folio.hermeslive.stt.SherpaSpeechToText
import com.folio.hermeslive.stt.SttCatalog
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
        highAccuracy: Boolean,
        /** The cloud pass, used only while `CascadeSettings.cloudSttModel` is set; null: on device only. */
        cloud: CloudStt?,
    ): CallSpeech

    companion object {
        /**
         * sherpa-onnx (S-C): Silero VAD from the assets, Zipformer streaming, and Parakeet-110M as
         * the second pass, or Parakeet-0.6B with High accuracy on when it is downloaded
         * (§11b). Hotwords are the user's vocabulary, the task labels and the learned
         * corrections (R-STT-7), read at each segment. In cloud mode the same words go to the
         * cloud model when it takes them.
         */
        fun sherpa(assets: AssetManager, models: ModelManager): CallSpeechFactory = CallSpeechFactory { source, playback, settings, hotwords, highAccuracy, cloud ->
            val accurate = if (highAccuracy && models.isInstalled(SttCatalog.parakeet06b)) SttCatalog.parakeet06b else SttCatalog.parakeet110m
            val engines = SherpaEngines.load(assets, models, SttEngineConfig(accurate = accurate))
            val stt = SherpaSpeechToText(engines, source, playback, settings = settings, hotwords = hotwords, cloud = cloud)
            object : CallSpeech, SpeechToText by stt {
                override fun start() = stt.start()
                override fun release() = stt.release()
                override fun holdToTalk(pressed: Boolean) = stt.holdToTalk(pressed)
                override val inputLevelDb: Double get() = stt.inputLevelDb.value
            }
        }

        /** The models a Bill call needs: the standard pair (High accuracy falls back to it when its model is missing). */
        fun ready(models: ModelManager): Boolean = models.isReady(ModelSet.STANDARD)
    }
}
