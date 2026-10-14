package com.folio.hermeslive.stt

/**
 * An offline model as the live (streaming) stage: the open segment is decoded again as it grows,
 * so partials follow speech, and once more in full when it ends. The cost of a decode grows with
 * the segment, so decodes are spaced by at least [minStepMs] of new audio **and** by twice the
 * last decode's time: decoding takes at most about half the wall time, whatever the length
 * (sherpa-onnx v1.13.8 has no streaming Moonshine). No per-word confidence: words have none.
 * Pure apart from [decode] (unit-tested with a fake).
 */
class MoonshineStreaming(
    override val model: SttModel?,
    private val decode: (FloatArray) -> String,
    private val release: () -> Unit = {},
    private val minStepMs: Long = 320L,
    private val nanos: () -> Long = System::nanoTime,
) : StreamingEngine {
    override fun open(hotwords: String): StreamingSession = Session()

    override fun release() = release.invoke()

    private inner class Session : StreamingSession {
        private val chunks = ArrayList<FloatArray>()
        private var samples = 0
        private var decodedAt = 0
        private var lastDecodeMs = 0L
        private var text = ""

        override fun accept(samples: FloatArray) {
            chunks += samples
            this.samples += samples.size
            val newMs = (this.samples - decodedAt) * 1000L / AudioRate.HZ
            if (newMs >= maxOf(minStepMs, 2 * lastDecodeMs)) run()
        }

        private fun run() {
            val t0 = nanos()
            text = decode(all())
            lastDecodeMs = (nanos() - t0) / 1_000_000
            decodedAt = samples
        }

        private fun all(): FloatArray {
            val out = FloatArray(samples)
            var at = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, at, c.size)
                at += c.size
            }
            return out
        }

        override fun words(): List<FastWord> = TextNorm.words(text).map { FastWord(it, null) }

        override fun finish() {
            if (samples > 0 && decodedAt != samples) run()
        }

        override fun release() {
            chunks.clear()
        }
    }
}
