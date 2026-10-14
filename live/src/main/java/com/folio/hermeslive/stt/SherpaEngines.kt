package com.folio.hermeslive.stt

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** Which models and how many threads. Defaults: spec §11b (Zipformer + Parakeet-110M). */
data class SttEngineConfig(
    val streaming: SttModel = SttCatalog.zipformer,
    /** Null: no second pass (the fast text is used, `pass: fast`). */
    val accurate: SttModel? = SttCatalog.parakeet110m,
    /** modified_beam_search (needed for hotwords) or greedy_search. Nemotron is always greedy. */
    val decoding: String = Hotwords.BEAM,
    val streamingThreads: Int = 2,
    val accurateThreads: Int = 2,
    /** Silero VAD: speech must last this long to fire (also the cut-in's VAD part, `bargeInMs`). */
    val vadMinSpeechS: Float = 0.25f,
    /** Silero VAD: silence this long ends speech (adds directly to the pause, R-Q-3). */
    val vadMinSilenceS: Float = 0.25f,
    /** Raised from sherpa's 5 s so dictation stays one segment (spec: sherpa-onnx findings). */
    val vadMaxSpeechS: Float = 60f,
    val vadThreshold: Float = 0.5f,
    /**
     * Zeros fed after a segment ends, so the streaming model decodes its last word. 0.66 s as in
     * sherpa-onnx's own examples; was 0.3 s until 2026-10-14, which clipped a final plosive
     * ("… in stock" heard as "… in sto") on 5 of 6 saved smoke requests at pace 0 (0 of 6 at
     * 0.66), and a clipped request is what Jev then judged unfinished (`TailPadDeviceTest`).
     */
    val tailPadS: Double = TAIL_PAD_S,
) {
    companion object {
        const val TAIL_PAD_S = 0.66
    }
}

/**
 * sherpa-onnx v1.13.8 behind the [Engines] interfaces. sherpa-onnx exits the process on a
 * missing file or an unsupported option, so every path is checked first and hotwords go only
 * where [Hotwords.supported] allows. Silero VAD comes from the bundled asset.
 */
object SherpaEngines {
    fun load(assets: AssetManager, models: ModelManager, config: SttEngineConfig): SttEngines {
        val vad = vad(assets, config)
        val streaming = streaming(models, config)
        val offline = config.accurate?.let { offline(models, it, config) }
        val sVocab = models.hasVocab(config.streaming)
        val sDecoding = Hotwords.decodingFor(config.streaming.kind, config.decoding)
        val oVocab = config.accurate?.let(models::hasVocab) == true
        return SttEngines(
            vad = vad,
            streaming = streaming,
            offline = offline,
            streamingHotwords = { Hotwords.argument(it, config.streaming, sDecoding, sVocab) },
            offlineHotwords = { list ->
                config.accurate?.let { Hotwords.argument(list, it, Hotwords.decodingFor(it.kind, config.decoding), oVocab) }.orEmpty()
            },
        )
    }

    private fun need(vararg paths: String) {
        val missing = paths.filter { it.isNotEmpty() && !File(it).isFile }
        check(missing.isEmpty()) { "missing model files: ${missing.joinToString { File(it).name }}" }
    }

    fun vad(assets: AssetManager, config: SttEngineConfig): VoiceDetector {
        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = SttCatalog.VAD_ASSET,
                    threshold = config.vadThreshold,
                    minSilenceDuration = config.vadMinSilenceS,
                    minSpeechDuration = config.vadMinSpeechS,
                    windowSize = SttPipeline.FRAME,
                    maxSpeechDuration = config.vadMaxSpeechS,
                ),
                sampleRate = AudioRate.HZ,
                numThreads = 1,
            ),
        )
        return object : VoiceDetector {
            override fun accept(frame: FloatArray): Boolean {
                vad.acceptWaveform(frame)
                while (!vad.empty()) vad.pop()
                return vad.isSpeechDetected()
            }

            override fun reset() = vad.reset()

            override fun release() = vad.release()
        }
    }

    fun streaming(models: ModelManager, config: SttEngineConfig): StreamingEngine {
        val model = config.streaming
        require(model.role == ModelRole.STREAMING)
        if (model.kind == ModelKind.MOONSHINE) return moonshine(models, model, config)
        val decoding = Hotwords.decodingFor(model.kind, config.decoding)
        val encoder = models.path(model, model.encoder)
        val decoder = models.path(model, model.decoder)
        val joiner = models.path(model, model.joiner)
        val tokens = models.path(model, model.tokens)
        val vocab = if (models.hasVocab(model) && model.hotwords) models.path(model, model.vocabFile) else ""
        need(encoder, decoder, joiner, tokens, vocab)
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
            tokens = tokens,
            numThreads = config.streamingThreads,
            provider = "cpu",
            modelType = model.modelType,
        )
        // The bpe encoder is built only for modified_beam_search: set the unit with the vocab.
        if (vocab.isNotEmpty() && decoding == Hotwords.BEAM) {
            modelConfig.modelingUnit = "bpe"
            modelConfig.bpeVocab = vocab
        }
        val recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AudioRate.HZ, featureDim = 80),
                modelConfig = modelConfig,
                // The VAD ends segments; sherpa's own endpointing is off.
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(false, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 300.0f),
                ),
                enableEndpoint = false,
                decodingMethod = decoding,
                maxActivePaths = 4,
            ),
        )
        return object : StreamingEngine {
            override val model: SttModel = model

            override fun open(hotwords: String): StreamingSession = Session(recognizer, recognizer.createStream(hotwords), model.upperCase, config.tailPadS)

            override fun release() = recognizer.release()
        }
    }

    private class Session(val recognizer: OnlineRecognizer, val stream: OnlineStream, val upperCase: Boolean, val tailPadS: Double) : StreamingSession {
        override fun accept(samples: FloatArray) {
            stream.acceptWaveform(samples, AudioRate.HZ)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }

        override fun words(): List<FastWord> {
            val r = recognizer.getResult(stream)
            val words = TokenConfidence.words(r.tokens.toList(), r.ysProbs)
            return if (upperCase) words.map { it.copy(text = Casing.fromUpper(it.text)) } else words
        }

        override fun finish() {
            stream.acceptWaveform(FloatArray((AudioRate.HZ * tailPadS).toInt()), AudioRate.HZ)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }

        override fun release() = stream.release()
    }

    /** Moonshine v2 as the live model: simulated streaming over sherpa's offline recognizer. */
    private fun moonshine(models: ModelManager, model: SttModel, config: SttEngineConfig): StreamingEngine {
        val encoder = models.path(model, model.encoder)
        val decoder = models.path(model, model.decoder)
        val tokens = models.path(model, model.tokens)
        need(encoder, decoder, tokens)
        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AudioRate.HZ, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(encoder = encoder, mergedDecoder = decoder),
                    tokens = tokens,
                    numThreads = config.streamingThreads,
                    provider = "cpu",
                ),
                decodingMethod = Hotwords.GREEDY,
            ),
        )
        val decode: (FloatArray) -> String = { samples ->
            val s = recognizer.createStream()
            try {
                s.acceptWaveform(samples, AudioRate.HZ)
                recognizer.decode(s)
                recognizer.getResult(s).text.trim()
            } finally {
                s.release()
            }
        }
        return MoonshineStreaming(model, decode, release = { recognizer.release() })
    }

    fun offline(models: ModelManager, model: SttModel, config: SttEngineConfig): OfflineEngine {
        require(model.role == ModelRole.OFFLINE)
        val decoding = Hotwords.decodingFor(model.kind, config.decoding)
        val encoder = models.path(model, model.encoder)
        val decoder = models.path(model, model.decoder)
        val joiner = models.path(model, model.joiner)
        val tokens = models.path(model, model.tokens)
        val vocab = if (models.hasVocab(model) && model.hotwords) models.path(model, model.vocabFile) else ""
        need(encoder, decoder, joiner, tokens, vocab)
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
            tokens = tokens,
            numThreads = config.accurateThreads,
            provider = "cpu",
            modelType = model.modelType,
        )
        if (vocab.isNotEmpty() && decoding == Hotwords.BEAM) {
            modelConfig.modelingUnit = "bpe"
            modelConfig.bpeVocab = vocab
        }
        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = AudioRate.HZ, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = decoding,
                maxActivePaths = 4,
            ),
        )
        return object : OfflineEngine {
            override val model: SttModel = model

            override fun decode(samples: FloatArray, hotwords: String): String {
                val s = if (hotwords.isEmpty()) recognizer.createStream() else recognizer.createStream(hotwords)
                try {
                    s.acceptWaveform(samples, AudioRate.HZ)
                    recognizer.decode(s)
                    return recognizer.getResult(s).text.trim()
                } finally {
                    s.release()
                }
            }

            override fun release() = recognizer.release()
        }
    }
}
