package com.folio.hermeslive.stt

/**
 * An offline model as the live (streaming) stage: the open segment is decoded again as it grows,
 * so partials follow speech, and once more when it ends (sherpa-onnx v1.13.8 has no streaming
 * Moonshine). Pure apart from [decode] (unit-tested with a fake).
 *
 * - **Windows.** Moonshine v2 in sherpa-onnx v1.13.8 returns nothing for more than about 9 s of
 *   audio (an attention shape error, measured on the host: 9.0 s decodes, 9.5 s is empty). So a
 *   segment is cut into windows of at most [maxWindowMs], each cut at the quietest 32 ms in its
 *   last [cutSearchMs], and a finished window's text is kept; only the open window is decoded
 *   again. This also bounds each decode's cost.
 * - **Spacing.** Decodes are spaced by at least [minStepMs] of new audio and by twice the last
 *   decode's time, so decoding takes at most about half the wall time.
 * - No per-word confidence: words have none.
 */
class MoonshineStreaming(
    override val model: SttModel?,
    private val decode: (FloatArray) -> String,
    private val release: () -> Unit = {},
    private val minStepMs: Long = 320L,
    private val maxWindowMs: Long = 8_000L,
    private val cutSearchMs: Long = 2_000L,
    private val nanos: () -> Long = System::nanoTime,
) : StreamingEngine {
    override fun open(hotwords: String): StreamingSession = Session()

    override fun release() = release.invoke()

    private inner class Session : StreamingSession {
        private var window = FloatArray(AudioRate.HZ * 2)
        private var samples = 0
        private var decodedAt = 0
        private var lastDecodeMs = 0L
        private var text = ""
        private val done = ArrayList<String>()

        override fun accept(samples: FloatArray) {
            append(samples)
            val max = (maxWindowMs * AudioRate.HZ / 1000).toInt()
            if (this.samples >= max) cut()
            val newMs = (this.samples - decodedAt) * 1000L / AudioRate.HZ
            if (newMs >= maxOf(minStepMs, 2 * lastDecodeMs)) run()
        }

        private fun append(more: FloatArray) {
            if (samples + more.size > window.size) window = window.copyOf(maxOf(window.size * 2, samples + more.size))
            System.arraycopy(more, 0, window, samples, more.size)
            samples += more.size
        }

        /** Close the window at its quietest moment near the end; its text is kept, the rest opens the next one. */
        private fun cut() {
            val frame = SttPipeline.FRAME
            val from = (samples - (cutSearchMs * AudioRate.HZ / 1000).toInt()).coerceAtLeast(frame)
            var at = samples
            var quietest = Double.MAX_VALUE
            var i = from
            while (i + frame <= samples) {
                var e = 0.0
                for (k in i until i + frame) e += window[k] * window[k]
                if (e < quietest) {
                    quietest = e
                    at = i + frame / 2
                }
                i += frame
            }
            val closed = decode(window.copyOf(at)).trim()
            if (closed.isNotEmpty()) done += closed
            val rest = window.copyOfRange(at, samples)
            samples = 0
            decodedAt = 0
            text = ""
            append(rest)
        }

        private fun run() {
            val t0 = nanos()
            text = decode(window.copyOf(samples)).trim()
            lastDecodeMs = (nanos() - t0) / 1_000_000
            decodedAt = samples
        }

        override fun words(): List<FastWord> = TextNorm.words((done + text).joinToString(" ")).map { FastWord(it, null) }

        override fun finish() {
            if (samples > 0 && decodedAt != samples) run()
        }

        override fun release() {
            window = FloatArray(0)
            samples = 0
        }
    }
}
