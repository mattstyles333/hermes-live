package com.folio.hermeslive.call

import android.view.KeyEvent
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ClarifyQuestion
import com.folio.hermes.live.LiveTranscriptFragment
import com.folio.hermes.live.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class CallHelpersTest {
    private fun tone(amplitude: Double, samples: Int = 480): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 440 * i / 48_000.0) * amplitude * 32767).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun rmsOfSilenceAndAFullScaleSine() {
        assertEquals(-120.0, EnergyVad.rmsDbfs(ByteArray(960)), 0.0)
        assertEquals(-3.0, EnergyVad.rmsDbfs(tone(1.0)), 0.2)
        assertEquals(-23.0, EnergyVad.rmsDbfs(tone(0.1)), 0.3)
    }

    @Test fun vadNeedsOnsetAndHangs() {
        val vad = EnergyVad()
        repeat(50) { vad.offer(-65.0, 10) }
        assertFalse(vad.offer(-25.0, 10))
        repeat(11) { vad.offer(-25.0, 10) }
        assertTrue(vad.speaking)
        repeat(50) { vad.offer(-65.0, 10) }
        assertTrue(vad.speaking)
        repeat(20) { vad.offer(-65.0, 10) }
        assertFalse(vad.speaking)
    }

    @Test fun vadIgnoresAOneFrameClick() {
        val vad = EnergyVad()
        repeat(50) { vad.offer(-65.0, 10) }
        vad.offer(-10.0, 10)
        repeat(5) { vad.offer(-65.0, 10) }
        assertFalse(vad.speaking)
    }

    @Test fun levelGateHasHysteresis() {
        val gate = LevelGate()
        assertFalse(gate.offer(0.015, 0))
        assertTrue(gate.offer(0.05, 100))
        assertTrue(gate.offer(0.015, 200))
        assertTrue(gate.offer(0.0, 300))
        assertTrue(gate.offer(0.0, 700))
        assertFalse(gate.offer(0.0, 760))
    }

    @Test fun remoteLevelReadsAudioLevelOrEnergy() {
        val reader = RemoteLevelReader()
        val video = StatsEntry("inbound-rtp", mapOf("kind" to "video", "audioLevel" to 0.9))
        assertNull(reader.read(listOf(video)))
        assertEquals(0.25, reader.read(listOf(video, StatsEntry("inbound-rtp", mapOf("kind" to "audio", "audioLevel" to 0.25))))!!, 1e-9)
        val energy = RemoteLevelReader()
        assertNull(energy.read(listOf(StatsEntry("inbound-rtp", mapOf("mediaType" to "audio", "totalAudioEnergy" to 1.0, "totalSamplesDuration" to 10.0)))))
        val level = energy.read(listOf(StatsEntry("inbound-rtp", mapOf("mediaType" to "audio", "totalAudioEnergy" to "1.0144", "totalSamplesDuration" to 10.15))))
        assertEquals(0.3098, level!!, 1e-3)
    }

    @Test fun routesPreferHeadsetsAndFallBack() {
        val ear = RouteOption(1, RouteKind.Earpiece, "")
        val spk = RouteOption(2, RouteKind.Speaker, "")
        val bt = RouteOption(3, RouteKind.Bluetooth, "Buds")
        val wired = RouteOption(4, RouteKind.Wired, "")
        assertEquals(spk, RouteSelector.auto(listOf(ear, spk), FallbackRoute.Speaker))
        assertEquals(ear, RouteSelector.auto(listOf(ear, spk), FallbackRoute.Earpiece))
        assertEquals(bt, RouteSelector.auto(listOf(ear, spk, wired, bt), FallbackRoute.Speaker))
        assertEquals("Buds", bt.label)

        val manualEar = RouteChoice(ear, manual = true)
        assertEquals(manualEar, RouteSelector.onDevicesChanged(listOf(ear, spk), listOf(ear, spk), manualEar, FallbackRoute.Speaker))
        val plugged = RouteSelector.onDevicesChanged(listOf(ear, spk), listOf(ear, spk, wired), manualEar, FallbackRoute.Speaker)
        assertEquals(RouteChoice(wired, false), plugged)
        val unplugged = RouteSelector.onDevicesChanged(listOf(ear, spk, wired), listOf(ear, spk), plugged, FallbackRoute.Speaker)
        assertEquals(RouteChoice(spk, false), unplugged)
        assertEquals(RouteChoice(ear, false), RouteSelector.onNoisy(listOf(ear, spk, wired), FallbackRoute.Earpiece))
    }

    @Test fun spokenApprovalNeedsTheWholeUtterance() {
        val offered = listOf(ApprovalChoice.Once, ApprovalChoice.Deny)
        assertEquals(ApprovalChoice.Once, SpokenReplies.approval("Approve.", offered))
        assertEquals(ApprovalChoice.Once, SpokenReplies.approval("Hermes, approve it", offered))
        assertEquals(ApprovalChoice.Deny, SpokenReplies.approval("Don't allow!", offered))
        assertNull(SpokenReplies.approval("I don't think I approve of that", offered))
        assertNull(SpokenReplies.approval("approve", listOf(ApprovalChoice.Deny)))
    }

    @Test fun clarifyMatchesChoicesOrPassesFreeText() {
        val q = ClarifyQuestion("q", "Which?", listOf("Friday night", "Saturday"), false)
        assertEquals("Saturday", SpokenReplies.clarify("Saturday", q))
        assertEquals("Friday night", SpokenReplies.clarify("Let's do Friday night.", q))
        assertEquals("Friday night", SpokenReplies.clarify("friday", q))
        assertNull(SpokenReplies.clarify("Sunday", q))
        assertEquals("At seven", SpokenReplies.clarify(" At seven ", q.copy(choices = emptyList())))
    }

    @Test fun headsetButtonsMuteOnPressAndEndOnHold() {
        val hook = KeyEvent.KEYCODE_HEADSETHOOK
        assertNull(HeadsetButtons.map(hook, KeyEvent.ACTION_DOWN, 0, false, false))
        assertEquals(HeadsetCommand.ToggleMute, HeadsetButtons.map(hook, KeyEvent.ACTION_UP, 0, false, false))
        assertEquals(HeadsetCommand.EndCall, HeadsetButtons.map(hook, KeyEvent.ACTION_DOWN, 1, true, false))
        assertNull(HeadsetButtons.map(hook, KeyEvent.ACTION_DOWN, 2, true, true))
        assertNull(HeadsetButtons.map(hook, KeyEvent.ACTION_UP, 0, false, true))
        assertNull(HeadsetButtons.map(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0, false, false))
    }

    @Test fun captionsMergeTurnsAndFeedHistory() {
        val log = CaptionLog()
        log.append(LiveTranscriptFragment(Speaker.User, " Hello", 0, 100))
        log.append(LiveTranscriptFragment(Speaker.User, " there", 100, 200))
        log.append(LiveTranscriptFragment(Speaker.Assistant, "Hi!", 200, 300))
        log.append(LiveTranscriptFragment(Speaker.Assistant, "", 300, 300))
        assertEquals(listOf(Caption(Speaker.User, "Hello there"), Caption(Speaker.Assistant, "Hi!")), log.captions)
        assertEquals(2, log.history().size)
    }

    @Test fun orderedSenderHoldsEverythingBehindACommentaryLead() = runTest {
        val wire = mutableListOf<String>()
        var lead = 300L
        val sender = OrderedSender(backgroundScope, { wire += it; true }, { lead })
        val say = """{"content":"x","type":"session.commentary.append"}"""
        val close = """{"type":"session.close"}"""
        sender.send("""{"type":"session.thinking.append"}""")
        assertEquals(1, wire.size)
        sender.send(say)
        sender.send(close)
        assertEquals(1, wire.size)
        assertEquals(2, sender.pending)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf(say, close), wire.drop(1))
        lead = 0
        sender.send(say)
        assertEquals(4, wire.size)
    }

    @Test fun summariesEstimateCost() {
        assertEquals(0.05, CallSummary(120_000, 60.0, "").estimatedCostUsd, 1e-9)
        assertEquals(0.1, CallSummary(120_000, null, "").estimatedCostUsd, 1e-9)
    }
}
