package com.folio.hermeslive.stt

import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.BargeIn
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.NoiseFloor
import com.folio.hermeslive.audio.PlaybackMonitor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A confirmed cut-in, for telemetry and tests: Bill had played [playedMs] of the clip. */
data class CutInReport(val playedMs: Long, val continuousMs: Long, val atSample: Long)

/** Pipeline knobs read at each segment start. */
data class PipelineConfig(
    /** Fast-pass words below this `ysProbs` confidence are uncertain (`CascadeSettings.uncertainBelow`; bench: 0.45). */
    val uncertainBelow: Double = 0.45,
    /** In an accurate transcript, words both passes agree on are uncertain below this (bench: 0, i.e. never). */
    val agreedUncertainBelow: Double = 0.0,
    val bargeInMs: Long = 250L,
    /** Audio before the VAD fired that joins the segment (the VAD needs `minSpeech` to fire). */
    val preRollMs: Long = 480L,
    /** After a cut-in, audio before the confirming window that joins the segment. */
    val cutInPreRollMs: Long = 100L,
    /** A segment longer than this is split with a mid-speech `Final` (no `SpeechEnded`). */
    val maxSegmentMs: Long = 30_000L,
    /** A new segment needs the recent level this far above the noise floor. */
    val onsetMarginDb: Double = 6.0,
)

/**
 * The capture thread's rules, one 32 ms frame at a time (pure apart from the engines):
 *
 * - **Segments.** The VAD opens a segment (with pre-roll from a 2 s ring) and closes it after
 *   `minSilence`. While open, the streaming model decodes it and [SttEvent.Partial]s are sent
 *   when the words change. At the end: tail padding, then [SttEvent.Final] (words with
 *   `ysProbs` confidence, uncertain below the threshold) **before** [SttEvent.SpeechEnded], so
 *   the text never changes after the pause starts. The segment's PCM goes to the [SecondPass].
 * - **Echo safety (R-SPK-4/6).** While Bill plays, nothing is sent until the [BargeIn] gate
 *   confirms a cut-in; then [SttEvent.UserSpeech] once, and the segment starts at the cut-in
 *   point (the confirming window plus a little pre-roll), not at the echo before it.
 * - **Noise floor.** Learnt over the first 2 s and after a route change; a segment only starts
 *   when the recent level is [PipelineConfig.onsetMarginDb] above it.
 * - **Hold-to-talk (R-STT-8).** Pressed: [SttEvent.HoldToTalk] (true), a segment starts at once
 *   if none is open, and the VAD does not end it. Released: `Final`, `HoldToTalk(false)`, then
 *   `SpeechEnded`. Pressing while Bill plays is a cut-in (`UserSpeech(bargeInMs)` first).
 */
class SttPipeline(
    private val engines: SttEngines,
    private val secondPass: SecondPass,
    private val gate: BargeIn,
    private val floor: NoiseFloor,
    private val playback: PlaybackMonitor,
    private val config: () -> PipelineConfig,
    private val hotwords: () -> List<String>,
    private val emit: (SttEvent) -> Unit,
    private val onLevel: (Double) -> Unit = {},
    private val onCutIn: (CutInReport) -> Unit = {},
) {
    private class Segment(val session: StreamingSession, val holding: Boolean) {
        val pcm = ArrayList<FloatArray>()
        var samples = 0
        var lastPartial = ""
        var held = holding
    }

    private val ring = ArrayDeque<FloatArray>()
    private val levels = ArrayDeque<Double>()
    private var segment: Segment? = null
    private var samplesSeen = 0L
    private val holdRequest = AtomicReference<Boolean?>(null)
    private val routeChanged = AtomicBoolean(false)

    /** True while a segment is open (tests). */
    val inSegment: Boolean get() = segment != null

    /** Hold-to-talk pressed or released (any thread; applied at the next frame). */
    fun holdToTalk(pressed: Boolean) = holdRequest.set(pressed)

    /** The input route changed (any thread): relearn the noise floor and the echo gain. */
    fun routeChanged() = routeChanged.set(true)

    fun frame(samples: FloatArray) {
        if (routeChanged.getAndSet(false)) {
            floor.relearn()
            gate.routeChanged()
        }
        val cfg = config()
        samplesSeen += samples.size
        val level = Dbfs.of(samples)
        onLevel(level)
        ring.addLast(samples)
        while (ring.size > RING_FRAMES) ring.removeFirst()
        levels.addLast(level)
        while (levels.size > ONSET_FRAMES) levels.removeFirst()
        val speech = engines.vad.accept(samples)
        floor.accept(level)
        val decision = gate.frame(level, speech, playback.playing, playback.levelDb(), floor.db)
        val hold = holdRequest.getAndSet(null)

        val open = segment
        if (open == null) {
            when {
                hold == true -> {
                    emit(SttEvent.HoldToTalk(true))
                    if (decision !is BargeIn.Decision.Open) cutIn(cfg.bargeInMs)
                    start(framesOf(cfg.preRollMs), holding = true)
                }
                hold == false -> emit(SttEvent.HoldToTalk(false))
                speech && levels.max() > floor.db + cfg.onsetMarginDb -> when (decision) {
                    BargeIn.Decision.Open -> start(framesOf(cfg.preRollMs), holding = false)
                    BargeIn.Decision.Hold -> Unit
                    is BargeIn.Decision.CutIn -> {
                        cutIn(decision.continuousMs)
                        start(decision.framesBack + framesOf(cfg.cutInPreRollMs), holding = false)
                    }
                }
            }
            return
        }
        open.pcm += samples
        open.samples += samples.size
        open.session.accept(samples)
        partial(open)
        when {
            hold == true -> if (!open.held) {
                open.held = true
                emit(SttEvent.HoldToTalk(true))
            }
            hold == false && open.held -> end(open, released = true)
            !open.held && !speech -> end(open, released = false)
            open.samples >= cfg.maxSegmentMs * AudioRate.HZ / 1000 -> split(open)
        }
    }

    /** Close any open segment without events (the call stopped) and forget held audio. */
    fun close() {
        segment?.session?.release()
        segment = null
        ring.clear()
        secondPass.drop()
    }

    private fun cutIn(continuousMs: Long) {
        emit(SttEvent.UserSpeech(continuousMs))
        onCutIn(CutInReport(playback.playedMs(), continuousMs, samplesSeen))
    }

    private fun start(frames: Int, holding: Boolean) {
        val session = engines.streaming.open(engines.streamingHotwords(hotwords()))
        val s = Segment(session, holding)
        val pre = ring.toList().takeLast(frames.coerceAtLeast(1))
        for (chunk in pre) {
            s.pcm += chunk
            s.samples += chunk.size
            session.accept(chunk)
        }
        segment = s
        emit(SttEvent.SpeechStarted)
        partial(s)
    }

    private fun partial(s: Segment) {
        val text = s.session.words().joinToString(" ") { it.text }
        if (text.isNotBlank() && text != s.lastPartial) {
            s.lastPartial = text
            emit(SttEvent.Partial(text))
        }
    }

    private fun end(s: Segment, released: Boolean) {
        val fast = finish(s)
        if (released) emit(SttEvent.HoldToTalk(false))
        emit(SttEvent.SpeechEnded)
        segment = null
        secondPass.submit(concat(s), fast)
    }

    /** A long segment: send what is decoded as a `Final` and go on in a fresh stream. */
    private fun split(s: Segment) {
        val fast = finish(s)
        secondPass.submit(concat(s), fast)
        val next = Segment(engines.streaming.open(engines.streamingHotwords(hotwords())), s.held)
        segment = next
    }

    /** Tail padding, the final words, `Final` if any; releases the stream. */
    private fun finish(s: Segment): List<FastWord> {
        s.session.finish()
        val fast = s.session.words()
        s.session.release()
        if (fast.isNotEmpty()) {
            val threshold = config().uncertainBelow
            emit(
                SttEvent.Final(
                    Casing.sentence(fast.map { it.text }),
                    fast.map { w -> Word(w.text, w.confidence, uncertain = w.confidence != null && w.confidence < threshold) },
                ),
            )
        }
        return fast
    }

    private fun concat(s: Segment): FloatArray {
        val out = FloatArray(s.samples)
        var at = 0
        for (p in s.pcm) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        s.pcm.clear()
        return out
    }

    private fun framesOf(ms: Long): Int = ((ms + FRAME_MS - 1) / FRAME_MS).toInt()

    companion object {
        const val FRAME = 512
        const val FRAME_MS = 32L
        const val RING_FRAMES = 63
        const val ONSET_FRAMES = 8
    }
}
