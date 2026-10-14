package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.ports.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segment persistence, the accurate pass, pause, resume after a kill, marks and the wake phrase (virtual time, fake speech). */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetingSessionTest {
    private fun CoroutineScope.launchNow(block: suspend () -> Unit) = launch(start = CoroutineStart.UNDISPATCHED) { block() }

    private class Rig(val scope: TestScope, val store: MemoryStore = MemoryStore()) {
        val log = memoryLog(store)
        val speech = FakeMeetingSpeech()
        val acks = ArrayList<Ack>()
        val tasks = ArrayList<Pair<String, Int>>()
        val events = Events()
        var wake = true

        fun session(meta: MeetingMeta = MeetingMeta("m1", startedAt = WALL0), cleaner: SegmentCleaner? = null) = MeetingSession(
            log, speech, TestClock(scope), scope.backgroundScope, meta,
            wakeWord = { wake }, cleaner = { cleaner },
            onTask = { request, _, seg -> tasks += request to seg },
            ack = { acks += it }, telemetry = events,
        )
    }

    private fun TestScope.started(rig: Rig, title: String = "Supplier call"): MeetingSession {
        val s = rig.session()
        backgroundScope.launchNow { s.begin(title) }
        return s
    }


    @Test fun eachSegmentIsStoredAtOnceThenImprovedByTheAccuratePassOnQuiet() = runTest {
        val rig = Rig(this)
        val s = started(rig)
        runCurrent()
        assertEquals(MeetingPhase.Recording, rig.log.meta("m1")!!.phase)
        assertEquals("m1", rig.log.active()?.id)
        rig.speech.accurateNext("We ship forty units to Kestrel")
        rig.speech.say("we ship forty units to kestle")
        runCurrent()
        // Stored before any accurate pass: a kill now loses nothing that was said.
        assertEquals(listOf("we ship forty units to kestle"), rig.log.transcript("m1").segments.map { it.text })
        assertEquals(0, rig.speech.accurateCalls)
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertEquals(1, rig.speech.accurateCalls)
        val seg = rig.log.transcript("m1").segments.single()
        assertEquals("We ship forty units to Kestrel", seg.text)
        assertEquals("accurate", seg.pass)
        assertEquals(1, s.live.value.segments)
        assertEquals("We ship forty units to Kestrel", s.live.value.lines.last().text)
    }

    @Test fun speechResumingBeforeTheQuietWaitsAndOneRevisionCoversTheRange() = runTest {
        val rig = Rig(this)
        started(rig)
        runCurrent()
        rig.speech.accurateNext("First part. Second part.", segments = 2)
        rig.speech.say("first part")
        advanceTimeBy(500)
        rig.speech.say("second part")
        runCurrent()
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertEquals(1, rig.speech.accurateCalls)
        val t = rig.log.transcript("m1")
        assertEquals(listOf("First part. Second part."), t.segments.map { it.text })
        assertEquals(1, t.segments.single().n)
    }

    @Test fun aSegmentCountThatDoesNotMatchKeepsTheFastText() = runTest {
        val rig = Rig(this)
        started(rig)
        runCurrent()
        // The accurate pass covered two segments, but only one Final was consumed: never guess.
        rig.speech.accurateNext("something else entirely", segments = 2)
        rig.speech.say("hello there")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertEquals("hello there", rig.log.transcript("m1").segments.single().text)
    }

    @Test fun continuousSpeechFlushesAfterFourSegments() = runTest {
        val rig = Rig(this)
        started(rig)
        runCurrent()
        repeat(4) { rig.speech.split("part $it") }
        runCurrent()
        advanceTimeBy(MeetingSession.SPLIT_FLUSH_MS + 1)
        runCurrent()
        assertEquals(1, rig.speech.accurateCalls)
        assertEquals(4, rig.log.transcript("m1").segments.size)
    }

    @Test fun pauseFlushesTheAccuratePassBeforeTheMicStopsAndResumeCarriesOn() = runTest {
        val rig = Rig(this)
        val s = started(rig)
        runCurrent()
        rig.speech.accurateNext("Before the pause.")
        rig.speech.say("before the pause")
        runCurrent()
        s.pause()
        // Stopping capture drops held audio: the accurate pass must come first.
        assertEquals(listOf("start", "accurate", "stop"), rig.speech.log)
        assertEquals("Before the pause.", rig.log.transcript("m1").segments.single().text)
        assertEquals(MeetingPhase.Paused, rig.log.meta("m1")!!.phase)
        rig.speech.say("said while paused")
        runCurrent()
        assertEquals(1, rig.log.transcript("m1").segments.size)
        advanceTimeBy(60_000)
        s.record()
        rig.speech.say("after the pause")
        runCurrent()
        val t = rig.log.transcript("m1")
        assertEquals(listOf(1, 2), t.segments.map { it.n })
        val meta = rig.log.meta("m1")!!
        assertEquals(60_000L, meta.pausedMs)
        assertEquals(listOf("pause", "resume"), t.gaps.map { it.why })
    }

    @Test fun afterAKillANewSessionResumesTheSameMeetingWithItsNumberingAndTheLostTimeAsPaused() = runTest {
        val store = MemoryStore()
        val rig = Rig(this, store)
        started(rig)
        runCurrent()
        rig.speech.say("one")
        rig.speech.say("two")
        runCurrent()
        // The process dies here: no flush, no end. Two minutes later the app opens again.
        advanceTimeBy(120_000)
        val again = Rig(this, store)
        val cut = again.log.active()!!
        assertEquals(MeetingPhase.Recording, cut.phase)
        val s2 = again.session(cut)
        s2.resumeAfterKill()
        again.speech.say("three")
        runCurrent()
        val t = again.log.transcript("m1")
        assertEquals(listOf("one", "two", "three"), t.segments.map { it.text })
        assertEquals(listOf(1, 2, 3), t.segments.map { it.n })
        assertEquals(listOf("resume_after_kill", "resume"), t.gaps.map { it.why })
        // About two minutes lost to the kill count as paused, not meeting time.
        val meta = again.log.meta("m1")!!
        assertTrue("paused ${meta.pausedMs}", meta.pausedMs in 119_000L..121_000L)
        assertEquals(3, s2.live.value.segments)
    }

    @Test fun endFlushesStopsAndFreesTheMic() = runTest {
        val rig = Rig(this)
        val s = started(rig)
        runCurrent()
        rig.speech.say("last words")
        runCurrent()
        advanceTimeBy(30_000)
        val ended = s.end()
        assertEquals(MeetingPhase.Ended, ended.phase)
        assertEquals(WALL0 + 30_000, ended.endedAt)
        assertEquals(30_000L, ended.elapsedMs(ended.endedAt!!))
        assertNull(rig.log.active())
        assertEquals(listOf("start", "accurate", "stop"), rig.speech.log)
        assertTrue("meeting.ended" in rig.events.names())
    }

    @Test fun theMarkButtonCoversTheThirtySecondsBeforeIt() = runTest {
        val rig = Rig(this)
        val s = started(rig)
        runCurrent()
        rig.speech.say("old news")
        runCurrent()
        advanceTimeBy(40_000)
        rig.speech.say("this matters")
        rig.speech.say("so does this")
        runCurrent()
        advanceTimeBy(5_000)
        s.mark()
        val m = rig.log.transcript("m1").markers.single()
        assertEquals(MarkKind.Mark, m.kind)
        assertEquals(2, m.from)
        assertEquals(3, m.to)
        assertEquals(listOf(Ack.Mark), rig.acks)
        assertEquals(1, s.live.value.marks)
        assertEquals(MarkKind.Mark, s.live.value.lines.last().kind)
    }

    @Test fun billNoteThatBecomesAMarkerOnThePreviousSegmentAndStaysInTheTranscript() = runTest {
        val rig = Rig(this)
        started(rig)
        runCurrent()
        rig.speech.say("Priya will send the revised quote by Friday")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        rig.speech.say("Bill note that as an action for Priya")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        val t = rig.log.transcript("m1")
        val note = t.markers.single()
        assertEquals(MarkKind.Note, note.kind)
        assertEquals("Note that as an action for Priya", note.text)
        assertEquals(1, note.from)
        assertEquals(2, note.seg)
        assertEquals(2, t.segments.size)
        assertEquals(listOf(Ack.Note), rig.acks)
        assertTrue(rig.tasks.isEmpty())
    }

    @Test fun billStartATaskGoesToTheTaskHook() = runTest {
        val rig = Rig(this)
        started(rig)
        runCurrent()
        rig.speech.say("Bill start a task to draft the Kestrel reorder")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertEquals(listOf("Draft the Kestrel reorder" to 1), rig.tasks)
        assertEquals(MarkKind.Task, rig.log.transcript("m1").markers.single().kind)
        assertEquals(listOf(Ack.Task), rig.acks)
    }

    @Test fun billAloneArmsTheNextSegment() = runTest {
        val rig = Rig(this)
        started(rig)
        runCurrent()
        rig.speech.say("Bill")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        rig.speech.say("the budget is fifty thousand")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertEquals(listOf(Ack.Armed, Ack.Note), rig.acks)
        assertEquals("The budget is fifty thousand", rig.log.transcript("m1").markers.single().text)
    }

    @Test fun withTheWakeWordOffNothingIsAnInstruction() = runTest {
        val rig = Rig(this)
        rig.wake = false
        started(rig)
        runCurrent()
        rig.speech.say("Bill start a task to draft the reorder")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertTrue(rig.log.transcript("m1").markers.isEmpty())
        assertTrue(rig.tasks.isEmpty())
        assertTrue(rig.acks.isEmpty())
    }

    @Test fun anAcceptedCleanupIsOneMoreRevision() = runTest {
        val rig = Rig(this)
        val s = rig.session(cleaner = { heard, _ -> heard.text.replace("Kestle", "Kestrel") })
        backgroundScope.launchNow { s.begin("") }
        runCurrent()
        rig.speech.accurateNext("Call Kestle tomorrow")
        rig.speech.say("call kestle tomorrow")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        val seg = rig.log.transcript("m1").segments.single()
        assertEquals("Call Kestrel tomorrow", seg.text)
        assertEquals("clean", seg.pass)
    }

    @Test fun aCapturedSentenceIsNotMeetingSpeech() = runTest {
        val rig = Rig(this)
        val s = rig.session()
        var heard: String? = null
        backgroundScope.launchNow { heard = s.captureOne() }
        runCurrent()
        rig.speech.accurateNext("Weekly supplier call")
        rig.speech.say("weekly supplier call")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        assertEquals("Weekly supplier call", heard)
        assertTrue(rig.log.transcript("m1").segments.isEmpty())
        assertEquals(listOf("start", "accurate", "stop"), rig.speech.log)
        assertFalse(rig.log.all().any())
    }

    @Test fun telemetryCarriesNoText() = runTest {
        val rig = Rig(this)
        val s = started(rig, title = "Secret project Falcon")
        runCurrent()
        rig.speech.say("Bill note that Falcon launches in March")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        s.mark()
        s.pause()
        s.end()
        val names = rig.events.names()
        assertTrue(names.containsAll(listOf("meeting.started", "meeting.wake", "meeting.mark", "meeting.paused", "meeting.ended")))
        val values = rig.events.all.flatMap { it.second.values }.map { it.toString().lowercase() }
        assertFalse(values.toString(), values.any { "falcon" in it || "march" in it || "secret" in it })
    }
}
