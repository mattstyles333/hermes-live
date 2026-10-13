package com.folio.hermeslive.lab

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File

/**
 * Builds sherpa-onnx objects from downloaded files. sherpa-onnx exits the process on a
 * missing file or an unsupported option, so every path is checked here first and the
 * decoding method goes through [Hotwords.decodingFor].
 */
object Sherpa {
    const val SAMPLE_RATE = 16_000
    const val VAD_WINDOW = 512
    const val VAD_THRESHOLD = 0.5f
    const val VAD_MIN_SPEECH_S = 0.25f
    const val VAD_MIN_SILENCE_S = 0.25f

    // Above the 5 s default so long dictation is one segment (spec: sherpa-onnx findings).
    const val VAD_MAX_SPEECH_S = 30f

    // Matches the cascade's default pause_ms of 1000 (R-RT-1).
    const val ENDPOINT_TRAILING_SILENCE_S = 1.0f

    fun version(): Pair<String, String> = try {
        VersionInfo.version to VersionInfo.onnxruntimeVersion
    } catch (e: Throwable) {
        "unknown" to "unknown"
    }

    private fun need(vararg paths: String) {
        val missing = paths.filter { it.isNotEmpty() && !File(it).isFile }
        check(missing.isEmpty()) { "missing model files: ${missing.joinToString { File(it).name }}" }
    }

    fun vad(store: ModelStore): Vad {
        val model = ModelCatalog.sileroVad
        val path = store.path(model, model.fileName)
        need(path)
        return Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = path,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = VAD_MIN_SILENCE_S,
                    minSpeechDuration = VAD_MIN_SPEECH_S,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = VAD_MAX_SPEECH_S,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

    fun online(store: ModelStore, model: LabModel, threads: Int, requestedDecoding: String): OnlineRecognizer {
        require(model.role == ModelRole.STREAMING)
        val decoding = Hotwords.decodingFor(model.kind, requestedDecoding)
        val encoder = store.path(model, model.encoder)
        val decoder = store.path(model, model.decoder)
        val joiner = store.path(model, model.joiner)
        val tokens = store.path(model, model.tokens)
        val vocab = if (store.hasVocab(model)) store.path(model, model.vocabFile) else ""
        need(encoder, decoder, joiner, tokens, vocab)
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
            tokens = tokens,
            numThreads = threads,
            provider = "cpu",
            modelType = model.modelType,
        )
        // The bpe encoder is only built for modified_beam_search; set the unit with the vocab.
        if (vocab.isNotEmpty() && decoding == Hotwords.BEAM) {
            modelConfig.modelingUnit = "bpe"
            modelConfig.bpeVocab = vocab
        }
        return OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, ENDPOINT_TRAILING_SILENCE_S, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 30.0f),
                ),
                enableEndpoint = true,
                decodingMethod = decoding,
                maxActivePaths = 4,
            ),
        )
    }

    fun offline(store: ModelStore, model: LabModel, threads: Int, requestedDecoding: String): OfflineRecognizer {
        require(model.role == ModelRole.OFFLINE)
        val decoding = Hotwords.decodingFor(model.kind, requestedDecoding)
        val encoder = store.path(model, model.encoder)
        val decoder = store.path(model, model.decoder)
        val joiner = store.path(model, model.joiner)
        val tokens = store.path(model, model.tokens)
        val vocab = if (store.hasVocab(model)) store.path(model, model.vocabFile) else ""
        need(encoder, decoder, joiner, tokens, vocab)
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
            tokens = tokens,
            numThreads = threads,
            provider = "cpu",
            modelType = model.modelType,
        )
        if (vocab.isNotEmpty() && decoding == Hotwords.BEAM) {
            modelConfig.modelingUnit = "bpe"
            modelConfig.bpeVocab = vocab
        }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = decoding,
                maxActivePaths = 4,
            ),
        )
    }
}
