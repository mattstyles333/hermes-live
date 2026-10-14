package com.folio.hermeslive.call.ondemand

import com.folio.hermeslive.call.FocusMode
import com.folio.hermeslive.call.GapMusicPolicy
import com.folio.hermeslive.call.GapSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandControllerTest {
    // ── no first words lost ──────────────────────────────────────────────────────────────────

    @Test fun firstWordsReachTheSessionThroughThePreRoll() {
        val r = Rig().connected()
        r.closeByIdle()
        assertEquals(SessionView.Listening, r.controller.ui.value.view)
        r.link.received.clear()
        r.feed(3_000) // quiet room, closed: nothing goes to the cloud
        assertTrue(r.link.received.isEmpty())

        // The user starts speaking at sample S; Silero fires 250 ms later.
        val speechStart = r.sampleIndex
        r.feed(240)
        r.vad!!.speaking = true
        r.feed(20)
        r.feed(VAD_FRAME_MS)
        assertEquals("one open, for speech", OpenReason.Speech, r.link.opens.last().reason)
        assertEquals(SessionView.Connecting, r.controller.ui.value.view)
        r.feed(800) // the session takes 0.8 s to resume; the user keeps talking
        assertTrue("nothing before the link is ready", r.link.received.isEmpty())
        r.link.ready()
        r.feed(1_000)

        val got = r.link.received
        val first = got.first().toInt()
        val firstIndex = speechStart - ((speechStart - first) % Rig.MOD + Rig.MOD) % Rig.MOD
        assertTrue("the pre-roll reaches back before the first word", firstIndex <= speechStart)
        assertTrue("about 1.5 s of pre-roll", speechStart + 260 * 16 - firstIndex >= 1_400 * 16)
        // Contiguous, in order, up to the last sample fed: no gap where the open happened.
        got.forEachIndexed { i, s -> assertEquals("sample $i", ((firstIndex + i) % Rig.MOD).toShort(), s) }
        assertEquals(r.sampleIndex - 1, firstIndex + got.size - 1)
        assertEquals(SessionView.Connected, r.controller.ui.value.view)
    }

    @Test fun preRollKeepsOnlyTheLastStretch() {
        val ring = PcmRing(5)
        ring.append(shortArrayOf(1, 2, 3))
        ring.append(shortArrayOf(4, 5, 6, 7))
        assertEquals(listOf<Short>(3, 4, 5, 6, 7), ring.snapshot().toList())
        ring.append(ShortArray(12) { (it + 10).toShort() })
        assertEquals(listOf<Short>(17, 18, 19, 20, 21), ring.snapshot().toList())
        val q = PcmQueue(4)
        q.append(shortArrayOf(1, 2, 3))
        q.append(shortArrayOf(4, 5))
        assertEquals(1L, q.dropped)
        assertEquals(listOf<Short>(2, 3, 4, 5), q.drain().toList())
    }

    @Test fun aLongOpenKeepsTheNewestAudioAndSaysHowMuchWasDropped() {
        val r = Rig(OnDemandSettings(maxPendingMs = 1_000))
        r.connected()
        r.closeByIdle()
        r.vad!!.speaking = true
        r.feed(3_000)
        r.link.ready()
        r.feed(20)
        val open = r.sink.events.last { it.first == "ondemand.resume" }
        assertTrue((open.second["dropped_ms"] as Long) > 1_000)
    }

    // ── reopen on a result ───────────────────────────────────────────────────────────────────

    @Test fun aWaitingResultReopensTheSessionAndHoldsTheMusic() {
        val r = Rig().connected()
        r.closeByIdle()
        val opens = r.link.opens.size
        r.link.pendingNotice = true
        r.idle(150)
        assertEquals(opens + 1, r.link.opens.size)
        assertEquals(OpenReason.Result, r.link.opens.last().reason)
        assertTrue("music held the moment a result is coming", r.controller.gap(GapSignals(delegationPending = true)).remoteSpeaking)
        r.link.ready()
        r.idle(150)
        assertEquals(SessionView.Connected, r.controller.ui.value.view)
        // The link injects and speaks it; then all idle again: closes after 8 s.
        r.link.talking = true
        r.link.pendingNotice = false
        r.idle(5_000)
        r.link.talking = false
        val closes = r.link.closes
        r.idle(7_900)
        assertEquals(closes, r.link.closes)
        r.idle(300)
        assertEquals(closes + 1, r.link.closes)
    }

    @Test fun aFailedOpenForAResultRetriesAfterAPauseButSpeechRetriesAtOnce() {
        val r = Rig().connected()
        r.closeByIdle()
        r.link.openResult = false
        r.link.pendingNotice = true
        r.idle(150)
        val tries = r.link.opens.size
        r.idle(1_500)
        assertEquals("no hammering", tries, r.link.opens.size)
        r.idle(1_800)
        assertEquals(tries + 1, r.link.opens.size)
        r.link.pendingNotice = false
        r.link.openResult = true
        r.link.phase = LinkPhase.Closed
        r.vad!!.speaking = true
        r.feed(VAD_FRAME_MS)
        assertEquals(OpenReason.Speech, r.link.opens.last().reason)
        assertTrue(r.sink.events.any { it.first == "ondemand.open" && it.second["ok"] == false && it.third })
    }

    @Test fun offlineAWaitingResultWaitsForTheNetworkInsteadOfRetrying() {
        val r = Rig().connected()
        r.closeByIdle()
        val opens = r.link.opens.size
        r.link.reachable = false
        r.link.pendingNotice = true
        r.idle(30_000)
        assertEquals("no reopen loop while offline", opens, r.link.opens.size)
        r.link.reachable = true
        r.idle(150)
        assertEquals(OpenReason.Result, r.link.opens.last().reason)
        assertEquals(opens + 1, r.link.opens.size)
    }

    @Test fun anOpenThatNeverBecomesReadyTimesOut() {
        val r = Rig(OnDemandSettings(openTimeoutMs = 5_000))
        r.controller.start()
        r.idle(5_100)
        assertEquals(1, r.link.closes)
        assertTrue(r.sink.events.any { it.first == "ondemand.open" && it.second["code"] == "timeout" })
    }

    // ── close only when everything is idle ──────────────────────────────────────────────────

    @Test fun closesOnlyAfterTheWholeIdleWindow() {
        val r = Rig().connected()
        r.feed(7_800)
        assertEquals(0, r.link.closes)
        r.feed(400)
        assertEquals(1, r.link.closes)
        assertEquals(SessionView.Listening, r.controller.ui.value.view)
        assertTrue(r.controller.ui.value.dimmed)
    }

    @Test fun eachIdleConditionAloneKeepsTheSessionOpen() {
        val holds: List<Pair<String, (Rig, Boolean) -> Unit>> = listOf(
            "model busy" to { r, on -> r.link.idle = !on },
            "playback" to { r, on -> r.link.talking = on },
            "server hears the user" to { r, on -> r.link.hearsUser = on },
            "phone hears the user" to { r, on -> r.vad!!.speaking = on },
            "notice waiting" to { r, on -> r.link.pendingNotice = on },
        )
        for ((name, set) in holds) {
            val r = Rig().connected()
            set(r, true)
            r.feed(20_000)
            assertEquals(name, 0, r.link.closes)
            set(r, false)
            r.feed(7_900)
            assertEquals("$name: the window restarts when it clears", 0, r.link.closes)
            r.feed(300)
            assertEquals(name, 1, r.link.closes)
        }
    }

    @Test fun activityInsideTheWindowRestartsIt() {
        val r = Rig().connected()
        r.feed(6_000)
        r.link.idle = false
        r.feed(150)
        r.link.idle = true
        r.feed(7_800)
        assertEquals(0, r.link.closes)
        r.feed(400)
        assertEquals(1, r.link.closes)
    }

    @Test fun neverClosesWithTheSettingOff() {
        val r = Rig(OnDemandSettings(enabled = false)).connected()
        r.feed(120_000)
        assertEquals(0, r.link.closes)
        assertFalse(r.controller.ui.value.dimmed)
        assertEquals(SessionView.Connected, r.controller.ui.value.view)
    }

    @Test fun withTheSettingOffThePhoneNeverBuildsOrRunsTheDetector() {
        var built = 0
        val c = OnDemandController(FakeLink(), OnDemandSettings(enabled = false), vadFactory = { built++; FakeVad() }, context = { null }, clock = { 0L })
        c.start()
        repeat(100) { c.onMic(ShortArray(320), 320) }
        assertEquals(0, built)
    }

    @Test fun neverClosesWithoutAVoiceDetector() {
        val r = Rig(vad = null).connected()
        r.feed(60_000)
        assertEquals("the phone could not listen, so the session stays", 0, r.link.closes)
        assertFalse(r.controller.closes)
        assertTrue(r.sink.events.any { it.first == "ondemand.vad" && it.third })
    }

    @Test fun aThrowingVoiceDetectorAlsoKeepsTheSessionOpen() {
        val r = Rig(detector = object : com.folio.hermeslive.stt.VoiceDetector {
            override fun accept(frame: FloatArray): Boolean = error("native")
            override fun reset() = Unit
            override fun release() = Unit
        }).connected()
        r.feed(30_000)
        assertEquals(0, r.link.closes)
    }

    // ── music ────────────────────────────────────────────────────────────────────────────────

    @Test fun musicComesBackWhileClosedAndStopsOnSpeechOrAResult() {
        val r = Rig().connected()
        val policy = GapMusicPolicy()
        val working = GapSignals(delegationPending = true, headset = true)
        fun mode() = policy.update(r.controller.gap(working), r.now).mode
        r.closeByIdle()
        r.feed(1_600)
        assertEquals("closed, tasks running, quiet: music back", FocusMode.Release, mode())
        r.vad!!.speaking = true
        r.feed(VAD_FRAME_MS)
        assertEquals("the phone hears speech: music pauses at once", FocusMode.Hold, mode())
        r.vad!!.speaking = false
        r.link.ready()
        r.closeByIdle()
        r.feed(1_600)
        assertEquals(FocusMode.Release, mode())
        r.link.pendingNotice = true
        assertEquals("a result is about to be spoken: music pauses before it opens", FocusMode.Hold, mode())
        r.idle(150)
        assertEquals(FocusMode.Hold, mode())
    }

    @Test fun onTheSpeakerTheGapDucks() {
        val r = Rig().connected()
        val policy = GapMusicPolicy()
        r.closeByIdle()
        r.feed(1_600)
        assertEquals(FocusMode.Duck, policy.update(r.controller.gap(GapSignals(delegationPending = true, headset = false)), r.now).mode)
    }

    // ── debounce ─────────────────────────────────────────────────────────────────────────────

    @Test fun oneOpenAtATimeAndNoCloseUntilReadyAndIdle() {
        val r = Rig().connected()
        r.closeByIdle()
        val opens = r.link.opens.size
        // Speech flaps while the session opens: one open.
        repeat(10) {
            r.vad!!.speaking = true
            r.feed(40)
            r.vad!!.speaking = false
            r.feed(40)
        }
        r.link.pendingNotice = true
        r.idle(600)
        assertEquals(opens + 1, r.link.opens.size)
        r.link.pendingNotice = false
        // Still opening after 9 s (slow network): never closed while opening.
        r.feed(9_000)
        assertEquals(1, r.link.closes)
        r.link.ready()
        r.feed(7_800)
        assertEquals(1, r.link.closes)
        r.feed(400)
        assertEquals(2, r.link.closes)
    }

    @Test fun speechRightAfterACloseReopensAtOnce() {
        val r = Rig().connected()
        r.closeByIdle()
        r.vad!!.speaking = true
        r.feed(VAD_FRAME_MS)
        assertEquals(OpenReason.Speech, r.link.opens.last().reason)
        assertEquals(2, r.link.opens.size)
    }

    @Test fun mutedAudioNeitherOpensNorReachesThePreRoll() {
        val r = Rig().connected()
        r.closeByIdle()
        r.controller.setMuted(true)
        r.vad!!.speaking = true
        r.feed(2_000)
        assertEquals(1, r.link.opens.size)
        assertFalse(r.controller.phoneHearsSpeech)
    }

    // ── mic handoff (links that own the mic while open) ─────────────────────────────────────

    @Test fun aLinkThatOwnsTheMicGetsTheHeldAudioOnceThenTheMicAndClosesLater() {
        val calls = mutableListOf<String>()
        val mic = object : MicHandoff {
            override fun release() { calls += "release" }
            override fun reclaim() { calls += "reclaim" }
        }
        val r = Rig(link = FakeLink(LinkAudio.OwnsMic), mic = mic)
        r.connected()
        assertEquals("the start open hands the mic over once ready", listOf("release"), calls)
        assertTrue("no held audio at the start", r.link.received.isEmpty())
        r.closeByIdle()
        assertEquals(listOf("release", "reclaim"), calls)
        r.feed(2_000)
        val speechStart = r.sampleIndex
        r.vad!!.speaking = true
        r.feed(VAD_FRAME_MS)
        assertEquals(OpenReason.Speech, r.link.opens.last().reason)
        assertEquals("the host keeps the mic while the session opens", listOf("release", "reclaim"), calls)
        r.feed(1_200) // WebRTC takes a while; the user keeps talking
        r.link.ready()
        r.feed(20)
        r.tickNow()
        assertEquals(listOf("release", "reclaim", "release"), calls)
        val got = r.link.received
        val firstIndex = speechStart - ((speechStart - got.first().toInt()) % Rig.MOD + Rig.MOD) % Rig.MOD
        assertTrue("the pre-roll reaches back", speechStart - firstIndex >= 1_400 * 16)
        got.forEachIndexed { i, s -> assertEquals("sample $i", ((firstIndex + i) % Rig.MOD).toShort(), s) }
        val held = got.size
        // The host's capture stops (the link has the mic): no frames, and the session still closes when idle.
        r.vad!!.speaking = false
        r.idle(7_900)
        assertEquals(1, r.link.closes)
        r.idle(300)
        assertEquals("closes although the phone stopped listening mid-speech", 2, r.link.closes)
        assertEquals(listOf("release", "reclaim", "release", "reclaim"), calls)
        assertEquals("live audio never goes to a link that owns the mic", held, r.link.received.size)
    }

    @Test fun aMutedUserStillFinishesAnOpenOnTheCallThread() {
        val r = Rig()
        r.controller.setMuted(true)
        r.controller.start()
        r.link.ready()
        r.idle(150)
        assertEquals(SessionView.Connected, r.controller.ui.value.view)
    }

    // ── telemetry and hang-up ────────────────────────────────────────────────────────────────

    @Test fun telemetryNamesReasonsAndTimesOnly() {
        val r = Rig().connected()
        r.closeByIdle()
        r.vad!!.speaking = true
        r.feed(500)
        r.link.ready()
        r.feed(20)
        r.vad!!.speaking = false
        r.controller.stop()
        assertEquals(listOf("ondemand.open", "ondemand.close", "ondemand.resume", "ondemand.close"), r.sink.names())
        val allowed = setOf("reason", "ms", "ok", "dropped_ms", "code")
        r.sink.events.forEach { (name, attrs, _) ->
            assertTrue("$name: $attrs", attrs.keys.all { it in allowed })
            assertTrue(attrs.values.all { it is Number || it is Boolean || it in setOf("start", "speech", "result", "idle", "hang_up") })
        }
        assertEquals("speech", r.sink.events[2].second["reason"])
        assertTrue((r.sink.events[2].second["ms"] as Long) in 450L..520L)
        assertEquals("hang_up", r.sink.events[3].second["reason"])
    }

    @Test fun hangUpClosesAndReleasesTheDetectorOnTheMicThread() {
        val r = Rig().connected()
        r.feed(100)
        r.controller.stop()
        r.controller.micClosed()
        assertEquals(1, r.link.closes)
        assertTrue(r.vad!!.released)
        r.vad!!.speaking = true
        r.feed(500)
        assertEquals("nothing reopens after the hang-up", 1, r.link.opens.size)
    }

    @Test fun theClosedTimeIsCounted() {
        val r = Rig().connected()
        r.closeByIdle()
        r.idle(60_000)
        assertTrue(r.controller.ui.value.closedMs >= 60_000)
    }

    private companion object {
        /** Silero's frame is 32 ms; mic chunks are 20 ms: 40 ms always completes one. */
        const val VAD_FRAME_MS = 40L
    }
}
