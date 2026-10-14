package com.folio.hermes.cascade.conversation

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.HeldTurn
import com.folio.hermes.cascade.OutboxState
import com.folio.hermes.cascade.RecentTurn
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.registry.DurableRig
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.store.ConversationDto
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversations, the Resume card, the briefing and history (R-CONV-1/2/4). Made-up content only. */
class ConversationsTest {
    private val wall = 1_760_000_000_000L
    private val minute = 60_000L

    private fun store(lastCallAt: Long) = MemoryStore().apply { put(StoreKeys.CALL_END, lastCallAt.toString()) }

    private fun state(now: Long = wall, vararg tasks: TaskRecord, recent: Boolean = false, held: Boolean = false) = State(
        wallMs = now,
        tasks = TaskTable(tasks.toList(), counter = tasks.size),
        recent = if (recent) listOf(RecentTurn("hello", 0)) else emptyList(),
        outbox = if (held) OutboxState().hold("u9", HeldTurn("call Bob", 0)).first else OutboxState(),
        conversation = com.folio.hermes.cascade.ConversationState(id = "c1"),
    )

    private fun task(id: String, state: TaskState = TaskState.Running, unread: Boolean = false, send: SendState = SendState.Accepted, waiting: Boolean = false) =
        TaskRecord(id, "job $id", wall, state = state, unread = unread, send = send, waiting = waiting, result = if (unread) "done" else null)

    @Test fun resumeIsOfferedForEachReasonAndDefaultsToContinueWithinHalfAnHour() {
        val recent = store(wall - 10 * minute)
        assertNull("nothing in it: no card", DurableConversations.candidate(recent, state()))
        assertNull("no call yet: no card", DurableConversations.candidate(MemoryStore(), state(recent = true)))
        val unheard = DurableConversations.candidate(recent, state(wall, task("t1", TaskState.Done, unread = true)))!!
        assertEquals(1, unheard.unheardResults)
        assertEquals(ResumeChoice.Continue, unheard.defaultChoice)
        val held = DurableConversations.candidate(recent, state(held = true))!!
        assertEquals(1, held.heldTurns)
        val waiting = DurableConversations.candidate(recent, state(wall, task("t1"), task("t2", TaskState.Failed, send = SendState.NotSent)))!!
        assertEquals(2, waiting.waitingTasks)
        assertEquals("c1", DurableConversations.candidate(recent, state(recent = true))!!.conversationId)
        val old = DurableConversations.candidate(store(wall - 31 * minute), state(recent = true))!!
        assertEquals(ResumeChoice.New, old.defaultChoice)
        assertEquals(wall - 31 * minute, old.lastEndedWallMs)
    }

    @Test fun theBriefingIsCappedAndMostUrgentFirst() {
        val s = state(
            wall,
            task("t1", waiting = true), task("t2", TaskState.Done, unread = true), task("t3", TaskState.Done, unread = true),
            task("t4", TaskState.Failed, send = SendState.NotSent), task("t5"), held = true,
        )
        val items = Briefing.items(s)
        assertEquals(3, items.size)
        assertEquals(listOf("job t1 needs you.", "2 results are ready: job t2 and job t3.", "job t4 was not sent."), items)
        assertEquals(2, Briefing.items(s.copy(settings = CascadeSettings(briefingItems = 2))).size)
        assertTrue("running tasks alone are not a briefing", Briefing.items(state(wall, task("t5"))).isEmpty())
    }

    @Test fun aCallOpensWithTheBriefingWhenSomethingIsWaiting() = runTest {
        DurableRig(this).use { r ->
            r.hermes.failSubmits = 2
            r.say("check stock of RB2140")
            advanceUntilIdle()
            r.actor.stop()
            advanceUntilIdle()
            r.actor.start()
            advanceUntilIdle()
            val card = r.state.conversation.resume!!
            assertEquals(ResumeChoice.Continue, card.defaultChoice)
            assertEquals(1, card.waitingTasks)
            assertEquals("on screen, said after the choice", listOf("check stock was not sent."), r.state.ceo.briefing)
            assertTrue(r.events.none { it is CascadeEvent.Briefing })
            r.actor.send(Event.ResumeChosen(ResumeChoice.Continue))
            advanceUntilIdle()
            assertNull(r.state.conversation.resume)
            assertTrue(CascadeEvent.Briefing(1) in r.events)
            assertTrue(CascadeEvent.LinePlayed(com.folio.hermes.cascade.AppLines.WELCOME_BACK) in r.events)
            assertTrue("Continue keeps recent", r.state.recent.isNotEmpty())
        }
    }

    @Test fun withNoResumeCardTheBriefingIsSaidAtOnce() = runTest {
        val store = MemoryStore()
        store.put(StoreKeys.task("t1"), StoreCodec.encodeTask(task("t1", TaskState.Failed, send = SendState.NotSent)))
        DurableRig(this, store).use { r ->
            advanceUntilIdle()
            assertNull("no call before: no card", r.state.conversation.resume)
            assertTrue(CascadeEvent.Briefing(1) in r.events)
        }
    }

    @Test fun newClearsRecentAndTheSummaryAndKeepsTasksControllable() = runTest {
        DurableRig(this).use { r ->
            r.say("check stock of RB2140")
            advanceUntilIdle()
            r.actor.stop()
            advanceUntilIdle()
            advanceTimeBy(5 * minute)
            r.actor.start()
            advanceUntilIdle()
            val before = r.state.conversation.id!!
            assertTrue(r.state.recent.isNotEmpty())
            r.actor.send(Event.ResumeChosen(ResumeChoice.New))
            advanceUntilIdle()
            assertNotEquals(before, r.state.conversation.id)
            assertTrue(r.state.recent.isEmpty())
            assertEquals("", r.state.conversation.summary)
            val archived = StoreCodec.decode(ConversationDto.serializer(), r.store.get(StoreKeys.conversation(before)))!!
            assertEquals(listOf("t1"), archived.taskIds)
            assertEquals("check stock of RB2140", archived.recent.first().user)
            // The task stays: Jev still sees it, and a spoken cancel reaches it.
            assertEquals(TaskState.Running, r.state.tasks["t1"]!!.state)
            r.say("cancel the stock check", DurableRig.jev("action" to "cancel_task", "task" to "t1", "line" to "cancel.one", "enough" to "line_only"))
            advanceUntilIdle()
            assertEquals(listOf("t1"), r.hermes.interrupts)
            assertEquals(TaskState.Cancelled, r.state.tasks["t1"]!!.state)
            assertTrue("t1" in r.state.tasks.briefs(r.state.wallMs).map { it.id })
        }
    }

    @Test fun playReadsTheBriefingAndQueuesUnheardResults() = runTest {
        val store = store(wall - minute)
        store.put(StoreKeys.task("t1"), StoreCodec.encodeTask(task("t1", TaskState.Done, unread = true).copy(result = "Four in stock.")))
        DurableRig(this, store).use { r ->
            advanceUntilIdle()
            assertEquals(1, r.state.conversation.resume!!.unheardResults)
            assertTrue("nothing plays before the choice", CascadeEvent.ResultQueued("t1") !in r.events)
            r.actor.send(Event.ResumeChosen(ResumeChoice.Play))
            advanceUntilIdle()
            assertTrue(CascadeEvent.ResultQueued("t1") in r.events)
            assertTrue(CascadeEvent.ResultSpoken("t1") in r.events)
            assertTrue(CascadeEvent.Briefing(1) in r.events)
            assertTrue(CascadeEvent.LinePlayed(com.folio.hermes.cascade.AppLines.WHILE_AWAY) in r.events)
        }
    }

    @Test fun playOnACardReadsThatTask() = runTest {
        DurableRig(this).use { r ->
            r.say("check stock of RB2140")
            advanceUntilIdle()
            r.server.complete("s1", "Four in stock.")
            advanceUntilIdle()
            val before = r.events.count { it == CascadeEvent.ResultQueued("t1") }
            r.actor.send(Event.TaskTapped("t1", TaskTap.Play))
            advanceUntilIdle()
            assertEquals(before + 1, r.events.count { it == CascadeEvent.ResultQueued("t1") })
        }
    }

    @Test fun historyListsConversationsNewestFirstAndKeepsTheLastTen() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        val settings = CascadeSettings(conversationsKept = 3)
        DurableRig(this, store, server, settings).use { r ->
            for (i in 1..4) {
                r.say("note number $i", DurableRig.jev("action" to "reply", "line" to "ack.understood", "enough" to "line_only"))
                advanceUntilIdle()
                r.actor.stop()
                advanceUntilIdle()
                advanceTimeBy(minute)
                r.actor.start()
                advanceUntilIdle()
                r.actor.send(Event.ResumeChosen(ResumeChoice.New))
                advanceUntilIdle()
            }
            val all = ConversationHistory.list(store, r.state, kept = 10)
            assertEquals(5, all.size)
            assertTrue(all.first().current)
            assertEquals(listOf("note number 4", "note number 3", "note number 2", "note number 1"), all.drop(1).map { it.title })
        }
        DurableRig(this, store, server, settings).use { r ->
            val kept = ConversationHistory.list(store, r.state)
            assertEquals(3, kept.size)
            assertEquals("restore trims the stored history", 2, store.keys(StoreKeys.CONVERSATION_PREFIX).size)
        }
    }
}
