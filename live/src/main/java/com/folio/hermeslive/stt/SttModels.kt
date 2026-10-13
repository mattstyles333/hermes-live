package com.folio.hermeslive.stt

/**
 * The speech-to-text models (spec v2 §11b, "sherpa-onnx findings"). The URLs and sha256 pins
 * are the S-C0 spike's (k2-fsa `asr-models` GitHub release, checked 2026-10-13). Silero VAD is
 * bundled as an asset; the rest download on first enable through [ModelManager].
 */
enum class ModelRole { STREAMING, OFFLINE }

enum class ModelKind {
    /** icefall streaming Zipformer2 transducer: hotwords with modified_beam_search + bpe.vocab. */
    ZIPFORMER_STREAMING,

    /** NeMo cache-aware streaming transducer: greedy only, no hotwords in sherpa-onnx v1.13.8 (native exit). */
    NEMOTRON_STREAMING,

    /** NeMo TDT transducer, offline: hotwords with modified_beam_search + a tokens.txt vocab. */
    PARAKEET_TDT,
}

/** How a model's bpe.vocab (needed to turn hotword text into tokens) is made on the phone. */
enum class VocabSource { NONE, SENTENCEPIECE_MODEL, TOKENS_EQUAL_SCORES }

data class SttModel(
    val id: String,
    val role: ModelRole,
    val kind: ModelKind,
    val fileName: String,
    val sha256: String,
    /** Archive size: what the download costs. */
    val bytes: Long,
    val license: String,
    /** Paths relative to the model folder (archive top-level folder stripped). Only these are kept. */
    val keep: List<String>,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String = "tokens.txt",
    val modelType: String = "",
    val vocabSource: VocabSource = VocabSource.NONE,
    /** sentencepiece model used to export bpe.vocab ([VocabSource.SENTENCEPIECE_MODEL]). */
    val bpeModel: String = "",
    /** Tokens are upper case (LibriSpeech): hotwords are upper-cased to match, output is re-cased. */
    val upperCase: Boolean = false,
) {
    val url: String get() = RELEASE_BASE + fileName
    val isArchive: Boolean get() = fileName.endsWith(".tar.bz2")
    val vocabFile: String get() = if (vocabSource == VocabSource.NONE) "" else BPE_VOCAB
    val hotwords: Boolean get() = kind != ModelKind.NEMOTRON_STREAMING && vocabSource != VocabSource.NONE

    companion object {
        const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
        const val BPE_VOCAB = "bpe.vocab"
    }
}

/** What Settings offers (S-E): the default pair, and the optional high-accuracy second pass. */
enum class ModelSet(val models: List<SttModel>) {
    /** Streaming Zipformer-en + Parakeet-TDT-110M second pass (spec §11b default). */
    STANDARD(listOf(SttCatalog.zipformer, SttCatalog.parakeet110m)),

    /** Parakeet-TDT-0.6B-v2 second pass ("High accuracy"). */
    HIGH_ACCURACY(listOf(SttCatalog.parakeet06b)),

    /** Nemotron streaming (measured by S-C; NVIDIA Open Model License needs a notice). Not offered by default. */
    NEMOTRON(listOf(SttCatalog.nemotron)),
    ;

    val bytes: Long get() = models.sumOf { it.bytes }
}

object SttCatalog {
    /** Bundled in `assets/` (MIT, 0.6 MB); never downloaded. */
    const val VAD_ASSET = "silero_vad.onnx"
    const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

    private const val Z = "-epoch-99-avg-1-chunk-16-left-128"

    // sherpa-onnx's own Android config for this model: int8 encoder, fp32 decoder and joiner.
    val zipformer = SttModel(
        id = "zipformer-en-2023-06-26",
        role = ModelRole.STREAMING,
        kind = ModelKind.ZIPFORMER_STREAMING,
        fileName = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        sha256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
        bytes = 310_414_022,
        license = "Apache-2.0",
        keep = listOf("encoder$Z.int8.onnx", "decoder$Z.onnx", "joiner$Z.onnx", "tokens.txt", "bpe.model"),
        encoder = "encoder$Z.int8.onnx",
        decoder = "decoder$Z.onnx",
        joiner = "joiner$Z.onnx",
        modelType = "zipformer2",
        vocabSource = VocabSource.SENTENCEPIECE_MODEL,
        bpeModel = "bpe.model",
        upperCase = true,
    )

    val nemotron = SttModel(
        id = "nemotron-0.6b-160ms",
        role = ModelRole.STREAMING,
        kind = ModelKind.NEMOTRON_STREAMING,
        fileName = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2",
        sha256 = "0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b",
        bytes = 463_945_198,
        license = "NVIDIA Open Model License",
        keep = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
    )

    val parakeet110m = SttModel(
        id = "parakeet-tdt-110m",
        role = ModelRole.OFFLINE,
        kind = ModelKind.PARAKEET_TDT,
        fileName = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
        sha256 = "f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b",
        bytes = 108_035_095,
        license = "CC-BY-4.0",
        keep = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        modelType = "nemo_transducer",
        vocabSource = VocabSource.TOKENS_EQUAL_SCORES,
    )

    val parakeet06b = SttModel(
        id = "parakeet-tdt-0.6b-v2",
        role = ModelRole.OFFLINE,
        kind = ModelKind.PARAKEET_TDT,
        fileName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
        bytes = 482_468_385,
        license = "CC-BY-4.0",
        keep = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        modelType = "nemo_transducer",
        vocabSource = VocabSource.TOKENS_EQUAL_SCORES,
    )

    val all: List<SttModel> = listOf(zipformer, nemotron, parakeet110m, parakeet06b)

    fun byId(id: String): SttModel? = all.firstOrNull { it.id == id }
}
