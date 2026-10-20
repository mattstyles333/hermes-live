package com.folio.hermeslive.meeting

import com.folio.hermeslive.tasks.HeldSubmit
import com.folio.hermeslive.tasks.TaskSubmitter
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Held when it can't go, sent once when it can; the brief is stored before the submit; retention. */
class MeetingOutboxTest {
    private class Rig(val store: MemoryStore = MemoryStore()) {
        val log = memoryLog(store)
        val tasks = FakeTasks()
        val writer = FakeWriter()
        val events = Events()
        var now = WALL0 + 3_600_000L
        val outbox = MeetingOutbox(log, { tasks }, { writer }, { ZoneId.of("UTC") }, { now }, events)

        fun ended(id: String = "m1", segments: Int = 3, chars: Int = 40): MeetingMeta {
            val meta = MeetingMeta(id, title = "Weekly", startedAt = WALL0, phase = MeetingPhase.Ended, endedAt = WALL0 + 1_800_000)
            log.put(meta)
            repeat(segments) { i -> log.append(id, LogLine.Seg(i + 1, WALL0 + i * 1_000L, "x".repeat(chars) + " $i")) }
            return meta
        }
    }

    @Test fun offlineTheNotesAreHeldThenSentOnceWhenBackOnline() = runTest {
        val rig = Rig()
        rig.ended()
        rig.tasks.reply = { HeldSubmit.Offline }
        assertEquals(MeetingOutbox.Outcome.Held("offline"), rig.outbox.queue("m1"))
        val held = rig.log.meta("m1")!!
        assertEquals(MeetingPhase.Queued, held.phase)
        assertEquals("offline", held.heldReason)
        // The brief was stored before the submit: a retry sends exactly the same text.
        val stored = rig.log.brief("m1")!!
        assertEquals(stored, rig.tasks.calls.single().brief)
        rig.tasks.reply = { HeldSubmit.Sent("t-42") }
        assertEquals(1, rig.outbox.flush())
        val sent = rig.log.meta("m1")!!
        assertEquals(MeetingPhase.Sent, sent.phase)
        assertEquals("t-42", sent.taskId)
        assertEquals(2, rig.tasks.calls.size)
        assertEquals(stored, rig.tasks.calls[1].brief)
        // Same key on the retry: the task table can never start it twice.
        assertEquals(rig.tasks.calls[0].key, rig.tasks.calls[1].key)
        assertEquals("meeting-m1-1", rig.tasks.calls[1].key)
        assertEquals("Meeting notes · Weekly", rig.tasks.calls[1].label)
        // Nothing more to send.
        assertEquals(0, rig.outbox.flush())
        assertEquals(2, rig.tasks.calls.size)
        assertTrue("meeting.sent" in rig.events.names())
    }

    @Test fun aLongMeetingIsSummarisedOnceEvenWhenTheSubmitIsRetried() = runTest {
        val rig = Rig()
        rig.ended(segments = 800, chars = 120)
        rig.tasks.reply = { HeldSubmit.NotAvailable("no_reply") }
        assertEquals(MeetingOutbox.Outcome.Held("no_reply"), rig.outbox.queue("m1"))
        val calls = rig.writer.calls
        assertTrue(calls > 1)
        rig.tasks.reply = { HeldSubmit.Sent("t-1") }
        rig.outbox.flush()
        assertEquals(calls, rig.writer.calls)
        assertTrue(rig.tasks.calls.last().brief.contains("summarised in"))
    }

    @Test fun aLongMeetingEndedOfflineWaitsForTheWriterThenGoes() = runTest {
        val rig = Rig()
        rig.ended(segments = 800, chars = 120)
        rig.writer.result = { OpenRouterResult.Failed(FailureKind.Offline) }
        assertEquals(MeetingOutbox.Outcome.Held("offline"), rig.outbox.queue("m1"))
        assertTrue(rig.tasks.calls.isEmpty())
        assertNull(rig.log.brief("m1"))
        rig.writer.result = { OpenRouterResult.Ok("SUMMARY $it") }
        rig.outbox.flush()
        assertEquals(MeetingPhase.Sent, rig.log.meta("m1")!!.phase)
    }

    @Test fun billOffOrNotConnectedIsHeldWithItsReason() = runTest {
        val rig = Rig()
        rig.ended()
        rig.tasks.reply = { HeldSubmit.NotAvailable("bill_off") }
        assertEquals(MeetingOutbox.Outcome.Held("bill_off"), rig.outbox.queue("m1"))
        assertEquals("bill_off", rig.log.meta("m1")!!.heldReason)
        val none = MeetingOutbox(rig.log, { TaskSubmitter.None }, { MeetingWriter.None }, { ZoneId.of("UTC") }, { rig.now }, Events())
        assertEquals(MeetingOutbox.Outcome.Held("not_connected"), none.send("m1"))
    }

    @Test fun aResendIsANewRequestWithAFreshBrief() = runTest {
        val rig = Rig()
        rig.ended()
        rig.outbox.queue("m1")
        rig.log.put(rig.log.meta("m1")!!.copy(additions = listOf("Add the budget")))
        rig.outbox.queue("m1")
        assertEquals(listOf("meeting-m1-1", "meeting-m1-2"), rig.tasks.calls.map { it.key })
        assertTrue(rig.tasks.calls[1].brief.contains("Add the budget"))
        assertEquals("task-meeting-m1-2", rig.log.meta("m1")!!.taskId)
    }

    @Test fun sendingAHeldMeetingAgainFromHistoryKeepsItsKey() = runTest {
        val rig = Rig()
        rig.ended()
        // The first start may be in the task table already; only its reply was lost.
        rig.tasks.reply = { HeldSubmit.NotAvailable("no_reply") }
        rig.outbox.queue("m1")
        rig.tasks.reply = { HeldSubmit.Sent("t-1") }
        rig.outbox.queue("m1")
        assertEquals(listOf("meeting-m1-1", "meeting-m1-1"), rig.tasks.calls.map { it.key })
        assertEquals(1, rig.log.meta("m1")!!.sends)
    }

    @Test fun anotherAccountsWipeBeforeTheSendMeansNothingIsSent() = runTest {
        val rig = Rig()
        rig.ended()
        rig.tasks.reply = { HeldSubmit.Offline }
        rig.outbox.queue("m1")
        assertEquals(1, rig.tasks.calls.size)
        // Building Bill's runtime for a different account wipes the store first.
        val wiping = object : TaskSubmitter {
            override suspend fun prepare() {
                rig.store.keys(MeetingLog.PREFIX).forEach { rig.store.put(it, null) }
            }
            override suspend fun submit(brief: String, label: String, key: String): HeldSubmit = rig.tasks.submit(brief, label, key)
        }
        val outbox = MeetingOutbox(rig.log, { wiping }, { rig.writer }, { ZoneId.of("UTC") }, { rig.now }, Events())
        assertEquals(0, outbox.flush())
        assertEquals(MeetingOutbox.Outcome.Missing, outbox.send("m1"))
        assertEquals(1, rig.tasks.calls.size)
        assertTrue(rig.log.all().isEmpty())
    }

    @Test fun inTaskSubmitterAreHeldAndRetriedWithTheirOwnKey() = runTest {
        val rig = Rig()
        rig.tasks.reply = { HeldSubmit.Offline }
        val task = MeetingTask("m1", 7, "Goal: draft it.", "Meeting task · draft it", WALL0)
        assertEquals(MeetingOutbox.Outcome.Held("offline"), rig.outbox.task(task))
        rig.tasks.reply = { HeldSubmit.Sent("t-9") }
        rig.outbox.flush()
        assertEquals("t-9", rig.log.tasks("m1").single().taskId)
        assertEquals(listOf("meeting-m1-task-7", "meeting-m1-task-7"), rig.tasks.calls.map { it.key })
    }

    @Test fun retentionDeletesOldSentAndSavedMeetingsButKeepsUnsentAndLiveOnes() {
        val rig = Rig()
        val day = MeetingRetention.DAY_MS
        fun meta(id: String, phase: MeetingPhase, endedDaysAgo: Long?) = MeetingMeta(id, startedAt = rig.now - 40 * day, phase = phase, endedAt = endedDaysAgo?.let { rig.now - it * day })
        listOf(
            meta("old-sent", MeetingPhase.Sent, 31), meta("old-saved", MeetingPhase.Saved, 40), meta("old-ended", MeetingPhase.Ended, 31),
            meta("old-queued", MeetingPhase.Queued, 60), meta("new-sent", MeetingPhase.Sent, 29), meta("live", MeetingPhase.Recording, null),
        ).forEach {
            rig.log.put(it)
            rig.log.append(it.id, LogLine.Seg(1, it.startedAt, "text"))
            rig.log.putBrief(it.id, "brief")
        }
        assertEquals(3, MeetingRetention.prune(rig.log, rig.now, 30, rig.events))
        assertEquals(setOf("old-queued", "new-sent", "live"), rig.log.all().map { it.id }.toSet())
        assertTrue(rig.log.lines("old-sent").isEmpty())
        assertNull(rig.log.brief("old-sent"))
        assertEquals("retention", rig.events.all.single { it.first == "meeting.deleted" }.second["reason"])
        assertEquals(0, MeetingRetention.prune(rig.log, rig.now, 30))
    }
}
