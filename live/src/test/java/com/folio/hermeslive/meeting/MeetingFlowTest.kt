package com.folio.hermeslive.meeting

import com.folio.hermeslive.tasks.HeldSubmit
import com.folio.hermes.cascade.ports.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The screens' steps: intro, taking notes, ending, sending; the models gate; a meeting cut off by a kill. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetingFlowTest {
    private class Rig(val scope: TestScope, val store: MemoryStore = MemoryStore()) {
        val log = memoryLog(store)
        val tasks = FakeTasks()
        var models = true
        val speeches = ArrayList<FakeMeetingSpeech>()
        val outbox = MeetingOutbox(log, { tasks }, { FakeWriter() }, { ZoneId.of("UTC") }, { WALL0 + scope.testScheduler.currentTime }, Events())
        val flow = MeetingFlow(
            log, outbox, { MeetingSettings(enabled = true) }, { models },
            loadSpeech = { FakeMeetingSpeech().also { speeches += it } },
            clock = TestClock(scope), scope = scope.backgroundScope, zone = { ZoneId.of("UTC") },
        )
        val speech get() = speeches.last()
    }

    private fun TestScope.taking(rig: Rig, title: String = "Weekly"): FakeMeetingSpeech {
        assertTrue(rig.flow.open("app"))
        assertEquals(MeetingScreen.Intro(loading = true), rig.flow.screen.value)
        assertTrue(rig.flow.busy)
        rig.flow.start(title) // ignored while loading
        runCurrent()
        rig.scope.backgroundScope.launchServiceStarted(rig)
        runCurrent()
        assertEquals(MeetingScreen.Intro(loading = false), rig.flow.screen.value)
        rig.flow.start(title)
        runCurrent()
        val s = rig.flow.screen.value as MeetingScreen.Taking
        assertEquals(title, s.live.meta.title)
        assertEquals(MeetingPhase.Recording, s.live.meta.phase)
        return rig.speech
    }

    private fun CoroutineScope.launchServiceStarted(rig: Rig) = launch { rig.flow.serviceStarted() }

    @Test fun withoutSpeechModelsItSaysPrepareThemFirstAndNeverOpensTheMic() = runTest {
        val rig = Rig(this)
        rig.models = false
        assertFalse(rig.flow.open("app"))
        assertEquals(MeetingScreen.NeedsModels, rig.flow.screen.value)
        assertTrue(rig.speeches.isEmpty())
        assertFalse(rig.flow.busy)
        rig.flow.close()
        assertEquals(MeetingScreen.None, rig.flow.screen.value)
    }

    @Test fun aWholeMeetingFromIntroToSentWithTheMicReleasedAtTheEnd() = runTest {
        val rig = Rig(this)
        val speech = taking(rig)
        speech.say("we agreed the budget")
        runCurrent()
        advanceTimeBy(60_000)
        rig.flow.mark()
        runCurrent()
        val live = (rig.flow.screen.value as MeetingScreen.Taking).live
        assertEquals(1, live.segments)
        assertEquals(1, live.marks)
        rig.flow.end()
        runCurrent()
        val ending = rig.flow.screen.value as MeetingScreen.Ending
        assertTrue(ending.canSpeak)
        assertEquals(1, ending.segments)
        rig.flow.add("Priya and me")
        rig.flow.send()
        runCurrent()
        val done = rig.flow.screen.value as MeetingScreen.Done
        assertEquals(MeetingOutbox.Outcome.Sent("task-meeting-${done.meta.id}-1"), done.outcome)
        val brief = rig.tasks.calls.single().brief
        assertTrue(brief.contains("Context: Weekly"))
        assertTrue(brief.contains("Added by me after the meeting: Priya and me"))
        assertTrue(brief.contains("we agreed the budget"))
        assertTrue(speech.released)
        assertFalse(rig.flow.busy)
        rig.flow.close()
        assertEquals(MeetingScreen.None, rig.flow.screen.value)
    }

    @Test fun offlineAtTheEndTheNotesAreHeldOnThePhone() = runTest {
        val rig = Rig(this)
        taking(rig)
        rig.tasks.reply = { HeldSubmit.Offline }
        rig.flow.end()
        runCurrent()
        rig.flow.send()
        runCurrent()
        val done = rig.flow.screen.value as MeetingScreen.Done
        assertEquals(MeetingOutbox.Outcome.Held("offline"), done.outcome)
        assertEquals(MeetingPhase.Queued, rig.log.meta(done.meta.id)!!.phase)
    }

    @Test fun leftAtAnythingToAddTheMicIsFreedButSendStillWorks() = runTest {
        val rig = Rig(this)
        val speech = taking(rig)
        rig.flow.end()
        runCurrent()
        rig.flow.releaseMic()
        runCurrent()
        val ending = rig.flow.screen.value as MeetingScreen.Ending
        assertFalse(ending.canSpeak)
        assertTrue(speech.released)
        assertFalse(rig.flow.busy)
        rig.flow.send()
        runCurrent()
        assertTrue(rig.flow.screen.value is MeetingScreen.Done)
        assertEquals(1, rig.tasks.calls.size)
    }

    @Test fun keepOnPhoneSendsNothing() = runTest {
        val rig = Rig(this)
        taking(rig)
        rig.flow.end()
        runCurrent()
        rig.flow.save()
        runCurrent()
        val done = rig.flow.screen.value as MeetingScreen.Done
        assertEquals(MeetingPhase.Saved, rig.log.meta(done.meta.id)!!.phase)
        assertTrue(rig.tasks.calls.isEmpty())
        assertEquals(listOf(done.meta.id), rig.flow.history().map { it.id })
    }

    @Test fun aMeetingCutOffByAKillIsOfferedAgainAndCanBeEndedAndSent() = runTest {
        val store = MemoryStore()
        val first = Rig(this, store)
        val speech = taking(first)
        speech.say("before the kill")
        runCurrent()
        advanceTimeBy(10_000)
        // A new process: the screen offers the meeting back.
        val again = Rig(this, store)
        again.flow.restore()
        val cut = again.flow.screen.value as MeetingScreen.Interrupted
        assertEquals(1, cut.segments)
        assertFalse(again.flow.open("app"))
        again.flow.endInterrupted()
        val ending = again.flow.screen.value as MeetingScreen.Ending
        assertFalse(ending.canSpeak)
        assertEquals(MeetingPhase.Ended, again.log.meta(cut.meta.id)!!.phase)
        again.flow.send()
        runCurrent()
        assertTrue(again.tasks.calls.single().brief.contains("before the kill"))
    }

    @Test fun resumingACutOffMeetingRecordsOnInTheSameTranscript() = runTest {
        val store = MemoryStore()
        val first = Rig(this, store)
        taking(first).say("one")
        runCurrent()
        val again = Rig(this, store)
        again.flow.restore()
        again.flow.resumeInterrupted()
        runCurrent()
        val live = (again.flow.screen.value as MeetingScreen.Taking).live
        assertEquals(1, live.segments)
        again.speech.say("two")
        runCurrent()
        val id = live.meta.id
        assertEquals(listOf(1, 2), again.log.transcript(id).segments.map { it.n })
    }

    @Test fun discardDeletesTheTranscript() = runTest {
        val rig = Rig(this)
        taking(rig).say("private words")
        runCurrent()
        rig.flow.end()
        runCurrent()
        rig.flow.discard()
        runCurrent()
        assertEquals(MeetingScreen.None, rig.flow.screen.value)
        assertTrue(rig.log.all().isEmpty())
    }

    @Test fun aMicThatWontOpenShowsWhyAndFreesEverything() = runTest {
        val rig = Rig(this)
        assertTrue(rig.flow.open("app"))
        backgroundScope.launchServiceStarted(rig)
        runCurrent()
        rig.speech.failStart = true
        rig.flow.start("x")
        runCurrent()
        assertTrue(rig.flow.screen.value is MeetingScreen.Failed)
        assertTrue(rig.speech.released)
        assertFalse(rig.flow.busy)
    }

    @Test fun anInMeetingTaskGoesToTheOutboxWithItsOwnBrief() = runTest {
        val rig = Rig(this)
        taking(rig).say("Bill start a task to draft the reorder")
        advanceTimeBy(MeetingSession.QUIET_MS + 1)
        runCurrent()
        val call = rig.tasks.calls.single()
        assertTrue(call.brief.startsWith("Goal: Draft the reorder."))
        assertEquals("Meeting task · Draft the reorder", call.label)
        assertTrue(call.key.endsWith("-task-1"))
    }
}
