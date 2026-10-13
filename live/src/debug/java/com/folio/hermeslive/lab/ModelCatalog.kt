package com.folio.hermeslive.lab

/**
 * The STT Lab's model candidates (spike S-C0, spec v2 "sherpa-onnx findings").
 *
 * Every model comes from the official k2-fsa `asr-models` GitHub release and is checked
 * against a sha256 pinned here (computed by hand from the release asset on 2026-10-13;
 * GitHub's own digest agrees for every asset that has one). Only the files in [keep] are
 * extracted; the archive is deleted afterwards. Nothing downloads until the user taps.
 */
enum class ModelRole { VAD, STREAMING, OFFLINE }

enum class ModelKind {
    SILERO_VAD,

    /** icefall streaming Zipformer2 transducer: hotwords with modified_beam_search + bpe.vocab. */
    ZIPFORMER_STREAMING,

    /** NeMo cache-aware streaming transducer: no hotwords in sherpa-onnx v1.13.8 (native exit). */
    NEMOTRON_STREAMING,

    /** NeMo TDT transducer, offline: hotwords with modified_beam_search + a tokens.txt vocab. */
    PARAKEET_TDT,
}

/** How a model's bpe.vocab (needed to turn hotword text into tokens) is made on the phone. */
enum class VocabSource { NONE, SENTENCEPIECE_MODEL, TOKENS_EQUAL_SCORES }

data class LabModel(
    val id: String,
    val title: String,
    val role: ModelRole,
    val kind: ModelKind,
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val license: String,
    /** Paths relative to the model folder (archive top-level folder stripped). */
    val keep: List<String>,
    val encoder: String = "",
    val decoder: String = "",
    val joiner: String = "",
    val tokens: String = "",
    val modelType: String = "",
    val vocabSource: VocabSource = VocabSource.NONE,
    /** sentencepiece model used to export bpe.vocab ([VocabSource.SENTENCEPIECE_MODEL]). */
    val bpeModel: String = "",
    val testWavs: List<String> = emptyList(),
    /** "name.wav REFERENCE TEXT" lines, LibriSpeech style; empty when the archive has none. */
    val transcripts: String = "",
    /** Tokens are upper case (LibriSpeech): hotwords are upper-cased to match. */
    val upperCaseHotwords: Boolean = false,
    val big: Boolean = false,
    /**
     * Hotword self-test: a phrase that changes this model's output on [probeWav] (checked on
     * desktop sherpa-onnx 1.13.8 with the same bpe.vocab): proof that hotwords reach the decoder.
     */
    val probeWav: String = "",
    val probeHotword: String = "",
) {
    val url: String get() = RELEASE_BASE + fileName
    val isArchive: Boolean get() = fileName.endsWith(".tar.bz2")
    val vocabFile: String get() = if (vocabSource == VocabSource.NONE) "" else BPE_VOCAB
}

const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
const val BPE_VOCAB = "bpe.vocab"

object ModelCatalog {
    val sileroVad = LabModel(
        id = "silero-vad",
        title = "Silero VAD",
        role = ModelRole.VAD,
        kind = ModelKind.SILERO_VAD,
        fileName = "silero_vad.onnx",
        sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        bytes = 643_854,
        license = "MIT",
        keep = listOf("silero_vad.onnx"),
    )

    private const val Z = "-epoch-99-avg-1-chunk-16-left-128"

    // sherpa-onnx's own Android config for this model: int8 encoder, fp32 decoder and joiner.
    val zipformer = LabModel(
        id = "zipformer-en-2023-06-26",
        title = "Zipformer en 2023-06-26 (streaming, int8)",
        role = ModelRole.STREAMING,
        kind = ModelKind.ZIPFORMER_STREAMING,
        fileName = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        sha256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
        bytes = 310_414_022,
        license = "Apache-2.0",
        keep = listOf(
            "encoder$Z.int8.onnx", "decoder$Z.onnx", "joiner$Z.onnx", "tokens.txt", "bpe.model",
            "test_wavs/0.wav", "test_wavs/1.wav", "test_wavs/8k.wav", "test_wavs/trans.txt",
        ),
        encoder = "encoder$Z.int8.onnx",
        decoder = "decoder$Z.onnx",
        joiner = "joiner$Z.onnx",
        tokens = "tokens.txt",
        modelType = "zipformer2",
        vocabSource = VocabSource.SENTENCEPIECE_MODEL,
        bpeModel = "bpe.model",
        testWavs = listOf("test_wavs/0.wav", "test_wavs/1.wav", "test_wavs/8k.wav"),
        transcripts = "test_wavs/trans.txt",
        upperCaseHotwords = true,
        // Without it: "...DISHONORED BOSOM..."; with it: "...DISHONOURED BOSOM...".
        probeWav = "test_wavs/1.wav",
        probeHotword = "DISHONOURED BOSOM :3",
    )

    val nemotron = LabModel(
        id = "nemotron-0.6b-160ms",
        title = "Nemotron 0.6B 160 ms (streaming, int8, big)",
        role = ModelRole.STREAMING,
        kind = ModelKind.NEMOTRON_STREAMING,
        fileName = "sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2",
        sha256 = "0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b",
        bytes = 463_945_198,
        license = "NVIDIA Open Model License",
        keep = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt",
            "test_wavs/0.wav", "test_wavs/1.wav", "test_wavs/8k.wav", "test_wavs/trans.txt",
        ),
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
        testWavs = listOf("test_wavs/0.wav", "test_wavs/1.wav", "test_wavs/8k.wav"),
        transcripts = "test_wavs/trans.txt",
        big = true,
    )

    val parakeet110m = LabModel(
        id = "parakeet-tdt-110m",
        title = "Parakeet TDT 110M (offline, int8)",
        role = ModelRole.OFFLINE,
        kind = ModelKind.PARAKEET_TDT,
        fileName = "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
        sha256 = "f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b",
        bytes = 108_035_095,
        license = "CC-BY-4.0",
        keep = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt",
            "test_wavs/0.wav", "test_wavs/en-english.wav",
        ),
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
        modelType = "nemo_transducer",
        vocabSource = VocabSource.TOKENS_EQUAL_SCORES,
        testWavs = listOf("test_wavs/0.wav", "test_wavs/en-english.wav"),
        // Without it: "...see it any more..."; with it: "...see it anymore...".
        probeWav = "test_wavs/0.wav",
        probeHotword = "anymore :4",
    )

    val parakeet06b = LabModel(
        id = "parakeet-tdt-0.6b-v2",
        title = "Parakeet TDT 0.6B v2 (offline, int8, big)",
        role = ModelRole.OFFLINE,
        kind = ModelKind.PARAKEET_TDT,
        fileName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
        bytes = 482_468_385,
        license = "CC-BY-4.0",
        keep = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt",
            "test_wavs/0.wav",
        ),
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
        modelType = "nemo_transducer",
        vocabSource = VocabSource.TOKENS_EQUAL_SCORES,
        testWavs = listOf("test_wavs/0.wav"),
        big = true,
        probeWav = "test_wavs/0.wav",
        probeHotword = "anymore :4",
    )

    val all: List<LabModel> = listOf(sileroVad, zipformer, nemotron, parakeet110m, parakeet06b)
    val streaming: List<LabModel> = all.filter { it.role == ModelRole.STREAMING }
    val offline: List<LabModel> = all.filter { it.role == ModelRole.OFFLINE }

    fun byId(id: String): LabModel? = all.firstOrNull { it.id == id }
}
