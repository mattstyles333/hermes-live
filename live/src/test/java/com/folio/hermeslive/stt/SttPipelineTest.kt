package com.folio.hermeslive.stt

import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.BargeIn
import com.folio.hermeslive.audio.BargeInConfig
import com.folio.hermeslive.audio.NoiseFloor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttPipelineTest {
    private class Rig(
        confidence: (Int) -> Double? = { 0.9 },
        config: PipelineConfig = PipelineConfig(),
        offlineText: (FloatArray) -> String? = { "accurate words" },
    ) {
        val events = ArrayList<SttEvent>()
        val cutIns = ArrayList<CutInReport>()
        val vad = FakeVad()
        val streaming = FakeStreaming(confidence)
        val offline = FakeOffline(offlineText)
        val playback = FakePlayback()
        var hotwords = listOf<String>()
        val second = SecondPass(offline, { "" }, { 0.5 }, executor = DirectExecutor())
        val gate = BargeIn(BargeInConfig(bargeInMs = 250))
        val engines = SttEngines(vad, streaming, offline, streamingHotwords = { it.joinToString("\n") })
        val pipeline = SttPipeline(engines, second, gate, NoiseFloor(), playback, { config }, { hotwords }, { events += it }, onCutIn = { cutIns += it })

        fun feed(frames: List<FloatArray>) = frames.forEach(pipeline::frame)
        fun names() = events.map { it::class.simpleName }
    }

    @Test fun finalComesBeforeSpeechEndedAndNoTextAfter() {
        val r = Rig()
        r.feed(List(10) { silence() } + frames(12) + List(6) { silence() })
        val names = r.names()
        assertEquals("SpeechStarted", names.first())
        val final = names.indexOf("Final")
        val ended = names.indexOf("SpeechEnded")
        assertTrue(final in 0 until ended)
        assertEquals(ended, names.lastIndex)
        assertTrue(names.subList(0, final).drop(1).all { it == "Partial" })
        val f = r.events[final] as SttEvent.Final
        assertEquals("W1 w2 w3", f.text)
        assertTrue(r.streaming.sessions.single().finished)
        assertTrue(r.streaming.sessions.single().released)
    }

    @Test fun preRollKeepsTheOnsetTheVadNeededToFire() {
        val r = Rig()
        r.feed(List(20) { silence() } + frames(8) + List(6) { silence() })
        // The VAD fires on the 3rd loud frame; all 8 loud frames reach the stream.
        assertEquals(8, r.streaming.sessions.single().frames.count { it[0] != 0f })
    }

    @Test fun confidenceBelowThresholdMarksWordsUncertain() {
        val r = Rig(confidence = { if (it == 2) 0.3 else 0.95 })
        r.feed(frames(12) + List(6) { silence() })
        val f = r.events.filterIsInstance<SttEvent.Final>().single()
        assertEquals(listOf(false, true, false), f.words.map { it.uncertain })
        assertEquals(0.3, f.words[1].confidence!!, 1e-9)
    }

    @Test fun echoAloneNeverBecomesUserSpeech() {
        val r = Rig()
        r.playback.isPlaying = true
        r.playback.db = -20.0
        // The mic hears Bill 6 dB down; the VAD thinks it is speech.
        r.feed(List(120) { tone(0.05f) })
        assertTrue(r.events.isEmpty())
        assertTrue(r.streaming.sessions.isEmpty())
        assertTrue(r.gate.echo.isLearnt)
    }

    @Test fun confirmedCutInStartsAtTheCutInPoint() {
        val r = Rig()
        r.playback.isPlaying = true
        r.playback.db = -20.0
        r.playback.played = 1_234
        r.feed(List(80) { tone(0.05f) })
        r.feed(List(20) { tone(0.3f) })
        val names = r.names()
        assertEquals("UserSpeech", names[0])
        assertEquals("SpeechStarted", names[1])
        assertTrue((r.events[0] as SttEvent.UserSpeech).continuousMs >= 250)
        assertEquals(1_234L, r.cutIns.single().playedMs)
        // The stream starts at the confirming window plus a little pre-roll, not at the echo before it.
        val session = r.streaming.sessions.single()
        val echoFrames = session.frames.count { it[0] == 0.05f }
        assertTrue("echo frames in the segment: $echoFrames", echoFrames <= BargeInConfig().windowFrames + 4)
        assertEquals(20, session.frames.count { it[0] == 0.3f })
    }

    @Test fun cutInIsConfirmedWithinTheBargeInWindow() {
        val r = Rig()
        r.playback.isPlaying = true
        r.feed(List(80) { tone(0.05f) })
        var at = -1
        for (i in 0 until 30) {
            r.pipeline.frame(tone(0.3f))
            if (at < 0 && r.events.any { it is SttEvent.UserSpeech }) at = i
        }
        // 5 of the 8-frame window above the estimate: 160 ms after the user's onset.
        assertTrue("confirmed at frame $at", at in 3..8)
    }

    @Test fun quietUserUnderLoudEchoIsHeld() {
        val r = Rig()
        r.playback.isPlaying = true
        r.feed(List(80) { tone(0.05f) })
        // 2 dB above the echo: not enough to be told apart from it.
        r.feed(List(30) { tone(0.063f) })
        assertTrue(r.events.isEmpty())
    }

    @Test fun echoTailIsHeldForTheHangover() {
        val r = Rig()
        r.playback.isPlaying = true
        r.feed(List(70) { tone(0.05f) })
        r.playback.isPlaying = false
        r.feed(List(5) { tone(0.05f) })
        assertTrue(r.events.isEmpty())
        r.feed(List(10) { silence() } + frames(12) + List(6) { silence() })
        assertEquals("SpeechStarted", r.names().first())
    }

    @Test fun holdToTalkNeverEndsOnSilenceAndCommitsOnRelease() {
        val r = Rig()
        r.pipeline.holdToTalk(true)
        r.feed(frames(8) + List(20) { silence() } + frames(8))
        assertFalse(r.events.any { it is SttEvent.SpeechEnded })
        r.pipeline.holdToTalk(false)
        r.feed(listOf(silence()))
        val names = r.names()
        assertEquals(listOf("HoldToTalk", "SpeechStarted"), names.take(2))
        assertTrue((r.events[0] as SttEvent.HoldToTalk).pressed)
        assertEquals(listOf("Final", "HoldToTalk", "SpeechEnded"), names.takeLast(3))
        assertFalse((r.events[names.lastIndex - 1] as SttEvent.HoldToTalk).pressed)
    }

    @Test fun holdToTalkWhileBillPlaysIsACutIn() {
        val r = Rig()
        r.playback.isPlaying = true
        r.feed(List(10) { tone(0.05f) })
        r.pipeline.holdToTalk(true)
        r.feed(listOf(tone(0.05f)))
        assertEquals(listOf("HoldToTalk", "UserSpeech", "SpeechStarted"), r.names().take(3))
    }

    @Test fun noiseNearTheLearntFloorDoesNotStartASegment() {
        val r = Rig()
        r.vad.forced = false
        r.feed(List(70) { tone(0.05f) })
        // The VAD is fooled by the room noise: the floor gate keeps it out.
        r.vad.forced = true
        r.feed(List(20) { tone(0.05f) })
        assertTrue(r.events.isEmpty())
        r.feed(List(4) { tone(0.3f) })
        assertEquals("SpeechStarted", r.names().first())
    }

    @Test fun accuratePassCoversTheTurnAndIsConsumed() = runBlocking {
        val r = Rig(offlineText = { "Accurate words here" })
        r.feed(frames(8) + List(6) { silence() } + frames(8) + List(6) { silence() })
        assertEquals(2, r.second.heldCount)
        val t = r.second.accurate()
        assertNotNull(t)
        assertEquals(TranscriptPass.Accurate, t!!.pass)
        assertEquals("Accurate words here Accurate words here", t.text)
        assertEquals(2, r.offline.decoded.size)
        assertNull(r.second.accurate())
    }

    @Test fun droppedTurnsNeverReachTheNextAccuratePass() = runBlocking {
        val r = Rig()
        r.feed(frames(8) + List(6) { silence() })
        r.second.drop()
        assertNull(r.second.accurate())
    }

    @Test fun failedSecondPassFallsBackToFastWords() = runBlocking {
        val r = Rig(offlineText = { null })
        r.feed(frames(8) + List(6) { silence() })
        val t = r.second.accurate()!!
        assertEquals(TranscriptPass.Fast, t.pass)
        assertEquals("W1 w2", t.text)
    }

    @Test fun blankSegmentIsNotHeld() {
        val r = Rig()
        r.feed(frames(3) + List(6) { silence() })
        assertTrue(r.events.any { it is SttEvent.SpeechEnded })
        assertFalse(r.events.any { it is SttEvent.Final })
        assertEquals(0, r.second.heldCount)
    }

    @Test fun hotwordsArePassedPerStream() {
        val r = Rig()
        r.hotwords = listOf("alpha")
        r.feed(frames(8) + List(6) { silence() })
        r.hotwords = listOf("alpha", "bravo")
        r.feed(frames(8) + List(6) { silence() })
        assertEquals(listOf("alpha", "alpha\nbravo"), r.streaming.sessions.map { it.hotwords })
    }

    @Test fun longSegmentIsSplitWithAFinalButNoSpeechEnded() {
        val r = Rig(config = PipelineConfig(maxSegmentMs = 640))
        r.feed(frames(30))
        assertTrue(r.events.any { it is SttEvent.Final })
        assertFalse(r.events.any { it is SttEvent.SpeechEnded })
        assertTrue(r.streaming.sessions.size >= 2)
        assertTrue(r.pipeline.inSegment)
    }

    @Test fun closeForgetsHeldAudioAndSendsNothing() = runBlocking {
        val r = Rig()
        r.feed(frames(8) + List(6) { silence() } + frames(5))
        val n = r.events.size
        r.pipeline.close()
        assertEquals(n, r.events.size)
        assertNull(r.second.accurate())
    }
}
