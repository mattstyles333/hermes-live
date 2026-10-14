package com.folio.hermeslive.stt

/*
 * The speech engines behind small interfaces, so the pipeline's rules (segments, events,
 * gating, second pass) are JVM-tested with fakes. [SherpaEngines] implements them with
 * sherpa-onnx; none of them is thread-safe: the capture thread owns the VAD and the streaming
 * recogniser, the second-pass thread owns the offline recogniser.
 */

/** Voice activity: [accept] one frame, true while speech is detected (after `minSpeech`, until `minSilence`). */
interface VoiceDetector {
    fun accept(frame: FloatArray): Boolean
    fun reset()
    fun release()
}

/** The streaming recogniser (R-STT-1). One [StreamingSession] per speech segment. */
interface StreamingEngine {
    val model: SttModel?

    /** A new stream with [hotwords] (sherpa format, "" for none). */
    fun open(hotwords: String): StreamingSession

    fun release()
}

interface StreamingSession {
    /** Feed 16 kHz samples and decode what is ready. */
    fun accept(samples: FloatArray)

    /** The current hypothesis, display-cased, with per-word confidence. */
    fun words(): List<FastWord>

    /** Feed tail padding and decode the rest (the segment ended). */
    fun finish()

    fun release()
}

/** The offline accurate pass (R-STT-2). */
interface OfflineEngine {
    val model: SttModel?

    /** The text of [samples] (16 kHz), with [hotwords] (sherpa format, "" for none). */
    fun decode(samples: FloatArray, hotwords: String): String

    fun release()
}

/** What the speech-to-text needs, loaded once per call (or kept across calls). */
class SttEngines(
    val vad: VoiceDetector,
    streaming: StreamingEngine,
    val offline: OfflineEngine?,
    /** Hotwords argument builders: the model, decoding and vocab decide whether any are passed. */
    streamingHotwords: (List<String>) -> String = { "" },
    val offlineHotwords: (List<String>) -> String = { "" },
) {
    /** The live model now (the live-model guard may swap it for the fallback mid-call, between segments). */
    @Volatile var streaming: StreamingEngine = streaming
        private set

    @Volatile var streamingHotwords: (List<String>) -> String = streamingHotwords
        private set

    /** Swap the live model (capture thread, no segment open). Returns the old one, for the caller to release. */
    fun swapStreaming(engine: StreamingEngine, hotwords: (List<String>) -> String): StreamingEngine {
        val old = streaming
        streaming = engine
        streamingHotwords = hotwords
        return old
    }

    fun release() {
        vad.release()
        streaming.release()
        offline?.release()
    }
}
