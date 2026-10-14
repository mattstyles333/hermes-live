package com.folio.hermeslive.stt

import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.BargeIn
import com.folio.hermeslive.audio.BargeInConfig
import com.folio.hermeslive.audio.NoiseFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live model (speech-model picker slice): the guard, the swap at a quiet moment, Moonshine as streaming. */
class LiveModelTest {
    // ── the guard ───────────────────────────────────────────────────────────────────────────

    @Test fun aSlowLiveModelTripsAfterTenSecondsOfSpeech() {
        val g = LiveGuard(maxRtf = 0.8, windowMs = 10_000)
        val frameNanos = 32_000_000L // 32 ms of audio per frame
        repeat(312) { assertFalse(g.decoded(512, (frameNanos * 0.9).toLong())) } // 9.98 s of speech
        assertNull("not before the window", g.tripped)
        assertTrue(g.decoded(512, (frameNanos * 0.9).toLong()))
        assertEquals(LiveGuard.Reason.SLOW, g.tripped)
        assertTrue(g.rtf > 0.8)
        assertFalse("trips once", g.decoded(512, frameNanos))
    }

    @Test fun aModelThatKeepsUpNeverTrips() {
        val g = LiveGuard()
        repeat(2_000) { g.decoded(512, 10_000_000L) } // RTF about 0.3 over 64 s
        assertNull(g.tripped)
    }

    @Test fun fallingBehindTheClockTrips() {
        val g = LiveGuard(maxLagMs = 2_000)
        assertFalse(g.progress(audioMs = 5_000, wallMs = 6_500))
        assertTrue(g.progress(audioMs = 5_000, wallMs = 7_100))
        assertEquals(LiveGuard.Reason.BEHIND, g.tripped)
    }

    // ── the swap ────────────────────────────────────────────────────────────────────────────

    /** Times decode by a fake clock: each frame "takes" [nanosPerFrame]. */
    private class Rig(nanosPerFrame: Long) {
        val events = ArrayList<SttEvent>()
        val slow = FakeStreaming()
        val fallback = FakeStreaming()
        val engines = SttEngines(FakeVad(), slow, null)
        val guard = LiveGuard(windowMs = 320)
        var tripped: LiveGuard.Reason? = null
        private var clock = 0L
        val pipeline = SttPipeline(
            engines, SecondPass(null, { "" }, { 0.5 }, executor = DirectExecutor()), BargeIn(BargeInConfig()), NoiseFloor(), FakePlayback(),
            { PipelineConfig() }, { emptyList() }, { events += it },
            guard = guard, onSlow = { tripped = it }, nanos = { clock.also { clock += nanosPerFrame / 2 } },
        )
    }

    @Test fun theFallbackTakesOverOnlyBetweenSegments() {
        val r = Rig(nanosPerFrame = 64_000_000L) // RTF 2
        r.pipeline.frame(silence())
        r.pipeline.frame(silence())
        frames(14).forEach(r.pipeline::frame)
        assertEquals(LiveGuard.Reason.SLOW, r.tripped)
        r.pipeline.swapLiveWhenQuiet(r.fallback) { "" }
        frames(4).forEach(r.pipeline::frame)
        assertTrue("the open segment finishes on the old model", r.fallback.sessions.isEmpty())
        List(6) { silence() }.forEach(r.pipeline::frame)
        frames(8).forEach(r.pipeline::frame)
        assertEquals("the next segment is the fallback's", 1, r.fallback.sessions.size)
        assertEquals(r.fallback, r.engines.streaming)
        assertEquals(1, r.slow.sessions.size)
    }

    // ── Moonshine as the live stage ─────────────────────────────────────────────────────────

    @Test fun moonshineDecodesTheGrowingSegmentAndSpacesItsDecodes() {
        val decoded = ArrayList<Int>()
        var now = 0L
        val engine = MoonshineStreaming(null, decode = { samples ->
            decoded += samples.size
            now += 200_000_000L // each decode takes 200 ms
            "Check the stock."
        }, minStepMs = 320L, nanos = { now })
        val s = engine.open("")
        repeat(10) { s.accept(FloatArray(512)) } // 320 ms: first decode
        assertEquals(listOf(5_120), decoded)
        repeat(10) { s.accept(FloatArray(512)) } // 320 ms more, but the last decode took 200 ms: wait for 400 ms
        assertEquals(1, decoded.size)
        repeat(3) { s.accept(FloatArray(512)) }
        assertEquals("the whole segment, again", listOf(5_120, 11_776), decoded)
        s.finish()
        assertEquals(2, decoded.size)
        s.accept(FloatArray(512))
        s.finish()
        assertEquals("finish decodes what is new", 12_288, decoded.last())
        assertEquals(listOf("Check", "the", "stock."), s.words().map { it.text })
        assertTrue("no confidence", s.words().all { it.confidence == null })
    }
}
