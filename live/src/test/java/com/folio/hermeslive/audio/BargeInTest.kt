package com.folio.hermeslive.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {
    private val floor = -70.0

    @Test fun notPlayingIsAlwaysOpen() {
        val gate = BargeIn()
        repeat(10) { assertEquals(BargeIn.Decision.Open, gate.frame(-20.0, true, false, Dbfs.FLOOR, floor)) }
    }

    @Test fun learnsTheEchoGainOverBillsFirstTwoSeconds() {
        val gate = BargeIn()
        val frames = BargeInConfig().learnFrames
        repeat(frames - 1) { gate.frame(-35.0, true, true, -20.0, floor) }
        assertFalse(gate.echo.isLearnt)
        gate.frame(-35.0, true, true, -20.0, floor)
        assertTrue(gate.echo.isLearnt)
        assertEquals(-15.0, gate.echo.gainDb, 1e-9)
    }

    @Test fun echoAtTheLearntGainNeverCutsIn() {
        val gate = BargeIn()
        repeat(500) { i ->
            // Bill's level moves; the echo follows it 15 dB down.
            val pb = -20.0 - (i % 7)
            assertEquals(BargeIn.Decision.Hold, gate.frame(pb - 15.0, true, true, pb, floor))
        }
    }

    @Test fun userOverEchoCutsInOnceAndReportsTheRun() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-35.0, true, true, -20.0, floor) }
        val decisions = (0 until 12).map { gate.frame(-18.0, true, true, -20.0, floor) }
        val cut = decisions.filterIsInstance<BargeIn.Decision.CutIn>()
        assertEquals(1, cut.size)
        assertTrue(cut.single().continuousMs >= 250)
        assertEquals(BargeInConfig().windowFrames, cut.single().framesBack)
        // After the cut-in the user is heard until playback ends.
        assertTrue(decisions.dropWhile { it !is BargeIn.Decision.CutIn }.drop(1).all { it == BargeIn.Decision.Open })
    }

    @Test fun acceptedAudioStartsWhereTheUsersRunBegan() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-35.0, true, true, -20.0, floor) }
        // A soft start: every other frame clears the estimate (below the quorum), then a firm voice.
        repeat(10) { i -> assertEquals(BargeIn.Decision.Hold, gate.frame(if (i % 2 == 0) -18.0 else -30.0, true, true, -20.0, floor)) }
        var cut: BargeIn.Decision.CutIn? = null
        repeat(8) { if (cut == null) cut = gate.frame(-15.0, true, true, -20.0, floor) as? BargeIn.Decision.CutIn }
        assertTrue("framesBack ${cut?.framesBack}", cut!!.framesBack in 12..16)
    }

    @Test fun loudButNotSpeechDoesNotCutIn() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-35.0, false, true, -20.0, floor) }
        repeat(20) { assertEquals(BargeIn.Decision.Hold, gate.frame(-10.0, false, true, -20.0, floor)) }
    }

    @Test fun usesTheLoudestRecentPlaybackForTheEchoPathDelay() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-35.0, true, true, -20.0, floor) }
        // Bill just went quiet, but his last loud word is still coming back from the room.
        repeat(3) { assertEquals(BargeIn.Decision.Hold, gate.frame(-35.0, true, true, -60.0, floor)) }
    }

    @Test fun hangoverKeepsGatingAfterPlaybackStops() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-35.0, true, true, -20.0, floor) }
        val tail = (0 until BargeInConfig().hangoverFrames).map { gate.frame(-35.0, true, false, Dbfs.FLOOR, floor) }
        assertTrue(tail.all { it == BargeIn.Decision.Hold })
        assertEquals(BargeIn.Decision.Open, gate.frame(-35.0, true, false, Dbfs.FLOOR, floor))
        assertEquals(BargeIn.Decision.Open, gate.frame(-35.0, true, false, Dbfs.FLOOR, floor))
    }

    @Test fun mustAlsoBeatTheNoiseFloor() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-60.0, true, true, -20.0, -30.0) }
        repeat(20) { assertEquals(BargeIn.Decision.Hold, gate.frame(-27.0, true, true, -20.0, -30.0)) }
    }

    @Test fun routeChangeRelearnsTheGain() {
        val gate = BargeIn()
        repeat(80) { gate.frame(-35.0, true, true, -20.0, floor) }
        assertTrue(gate.echo.isLearnt)
        gate.routeChanged()
        assertFalse(gate.echo.isLearnt)
        assertEquals(BargeInConfig().defaultGainDb, gate.echo.gainDb, 1e-9)
    }

    @Test fun noiseFloorLearnsALowPercentileAndRelearns() {
        val nf = NoiseFloor()
        assertEquals(Dbfs.FLOOR, nf.db, 0.0)
        repeat(70) { i -> nf.accept(if (i % 5 == 0) -20.0 else -50.0) }
        assertTrue(nf.isLearnt)
        assertEquals(-50.0, nf.db, 0.0)
        nf.relearn()
        assertFalse(nf.isLearnt)
    }

    @Test fun pcmLevels() {
        val bytes = ByteArray(200) { if (it % 2 == 0) 0 else 0x40 } // 0x4000 = half scale
        assertEquals(-6.02, Dbfs.ofPcm16(bytes, 0, bytes.size), 0.01)
        assertEquals(0f, Dbfs.toUnit(-80.0))
        assertEquals(1f, Dbfs.toUnit(0.0))
    }
}
