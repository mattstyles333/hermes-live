package com.folio.hermeslive.stt

/**
 * The live-model guard (speech-model picker slice): a live model that cannot keep up on this
 * phone is swapped for the fallback (Zipformer) for the rest of the call.
 *
 * - **Too slow:** over the first [windowMs] of speech (segment audio only; silence would hide a
 *   slow model), the streaming decode time over that audio is above [maxRtf].
 * - **Falling behind:** the capture loop is more than [maxLagMs] behind the wall clock (the mic's
 *   buffer is backing up, so audio is being lost).
 *
 * Pure; the capture thread feeds it. It trips once.
 */
class LiveGuard(
    private val maxRtf: Double = 0.8,
    private val windowMs: Long = 10_000L,
    private val maxLagMs: Long = 2_000L,
) {
    enum class Reason(val wire: String) { SLOW("slow"), BEHIND("behind") }

    private var speechSamples = 0L
    private var decodeNanos = 0L

    /** Set once tripped. */
    var tripped: Reason? = null
        private set

    /** The streaming RTF over the speech seen so far (decode time / speech audio). */
    val rtf: Double get() = if (speechSamples == 0L) 0.0 else decodeNanos / 1e9 / (speechSamples.toDouble() / AudioRate.HZ)

    /** One frame of segment audio ([samples]) took [nanos] to decode. True when this trips the guard. */
    fun decoded(samples: Int, nanos: Long): Boolean {
        if (tripped != null || speechSamples * 1000 / AudioRate.HZ >= windowMs) return false
        speechSamples += samples
        decodeNanos += nanos
        if (speechSamples * 1000 / AudioRate.HZ >= windowMs && rtf > maxRtf) {
            tripped = Reason.SLOW
            return true
        }
        return false
    }

    /** The loop has processed [audioMs] of input in [wallMs] since it started. True when this trips the guard. */
    fun progress(audioMs: Long, wallMs: Long): Boolean {
        if (tripped != null || wallMs - audioMs <= maxLagMs) return false
        tripped = Reason.BEHIND
        return true
    }
}
