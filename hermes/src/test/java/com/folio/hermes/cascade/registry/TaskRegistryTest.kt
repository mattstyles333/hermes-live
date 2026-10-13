package com.folio.hermes.cascade.registry

import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskRun
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.store.CoreDto
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The task registry and restart recovery (R-DUR-2..4, R-TASK-7, R-CONV-3 logic) on the actor with fake ports. */
class TaskRegistryTest {
    private val wall = 1_760_000_000_000L

    private fun TestScope.rig(store: MemoryStore = MemoryStore(), server: FakeServer = FakeServer(), recover: Boolean = true) =
        DurableRig(this, store, server, recover = recover)

    private fun stored(store: MemoryStore, task: TaskRecord, turns: Int = 1) {
        store.put(StoreKeys.task(task.id), StoreCodec.encodeTask(task))
        store.put(StoreKeys.CORE, StoreCodec.encode(CoreDto.serializer(), CoreDto(turns = turns, counter = 1, runCounter = 1)))
    }

    private fun running(send: SendState, stored: String?) = TaskRecord(
        id = "t1", label = "stock check", startedAt = wall, active = 1, accepted = send == SendState.Accepted,
        runs = mapOf(1L to TaskRun(RunKind.Start, false)), brief = "check stock of RB2140", turnId = "u1",
        storedSessionId = stored, send = send, events = listOf(TaskEvent.Submitted(wall, "start")),
    )

    @Test fun restartRebindsTheSessionAndRecoversAResultThatFinishedWhileKilled() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        rig(store, server).use { first ->
            first.say("check stock of RB2140")
            advanceUntilIdle()
            val t = first.state.tasks["t1"]!!
            assertEquals(SendState.Accepted, t.send)
            assertEquals("s1", t.storedSessionId)
        }
        server.complete("s1", "Four in stock at the Leeds store.")
        rig(store, server).use { second ->
            advanceUntilIdle()
            assertEquals("re-bound through HermesPort.open with the stored id", listOf(Triple("t1", "check stock", "s1")), second.hermes.opened)
            assertEquals(listOf("s1"), second.hermes.probes)
            val t = second.state.tasks["t1"]!!
            assertEquals(TaskState.Done, t.state)
            assertEquals("Four in stock at the Leeds store.", t.result)
            assertTrue("the recovered result is queued for speech", CascadeEvent.ResultQueued("t1") in second.events)
            assertEquals("never submitted again", listOf("check stock of RB2140"), server.prompts)
        }
    }

    @Test fun aTaskStillRunningAtRestartFinishesThroughTheRebind() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        rig(store, server).use { it.say("check stock of RB2140"); advanceUntilIdle() }
        rig(store, server).use { second ->
            advanceUntilIdle()
            assertEquals(TaskState.Running, second.state.tasks["t1"]!!.state)
            server.complete("s1", "Done.")
            advanceUntilIdle()
            assertEquals(TaskState.Done, second.state.tasks["t1"]!!.state)
            assertEquals(1, server.prompts.size)
        }
    }

    @Test fun aStoredSessionThatNeverGotThePromptIsSentOnceIntoAFreshSession() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        server.create()
        stored(store, running(SendState.Sending, "s1"))
        rig(store, server).use { r ->
            advanceUntilIdle()
            // Never prompted: sent again exactly once, into a new session created and stored first.
            assertEquals(listOf("check stock of RB2140"), server.prompts)
            assertTrue(server.sessions.getValue("s1").prompts.isEmpty())
            val t = r.state.tasks["t1"]!!
            assertEquals("s2", t.storedSessionId)
            assertEquals(listOf("check stock of RB2140"), server.sessions.getValue("s2").prompts)
            assertEquals(SendState.Accepted, t.send)
            assertEquals("not a failed submit", 0, t.submitAttempts)
        }
    }

    @Test fun anUnknownSessionWhileSendingIsNotSentAndNeverResentOnItsOwn() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        stored(store, running(SendState.Sending, "s404"))
        rig(store, server).use { r ->
            advanceUntilIdle()
            val t = r.state.tasks["t1"]!!
            assertEquals(SendState.NotSent, t.send)
            assertEquals(TaskState.Failed, t.state)
            assertTrue(server.prompts.isEmpty())
            assertTrue(CascadeEvent.TaskNotSent("t1") in r.events)
        }
    }

    @Test fun twoFailedSubmitsLeaveTheTaskNotSentAndRetryable() = runTest {
        rig().use { r ->
            r.hermes.failSubmits = 2
            r.say("check stock of RB2140")
            advanceUntilIdle()
            val t = r.state.tasks["t1"]!!
            assertEquals(SendState.NotSent, t.send)
            assertEquals(2, t.submitAttempts)
            assertEquals(TaskState.Failed, t.state)
            assertEquals(1, r.events.count { it == CascadeEvent.TaskNotSent("t1") })
            assertTrue(r.server.prompts.isEmpty())
            // Durable: a restart still shows it NotSent, with its brief.
            assertEquals(SendState.NotSent, StoreCodec.decodeTask(r.store.get(StoreKeys.task("t1")))!!.send)

            r.actor.send(Event.TaskTapped("t1", TaskTap.Retry))
            advanceUntilIdle()
            val again = r.state.tasks["t1"]!!
            assertEquals(SendState.Accepted, again.send)
            assertEquals(TaskState.Running, again.state)
            assertEquals("the same task, sent once", listOf("check stock of RB2140"), r.server.prompts)
        }
    }

    @Test fun oneFailedSubmitIsResentQuietly() = runTest {
        rig().use { r ->
            r.hermes.failSubmits = 1
            r.say("check stock of RB2140")
            advanceUntilIdle()
            val t = r.state.tasks["t1"]!!
            assertEquals(SendState.Accepted, t.send)
            assertEquals(TaskState.Running, t.state)
            assertEquals(listOf("check stock of RB2140"), r.server.prompts)
            assertTrue("no failure is queued for speech", r.state.resultUtterances.isEmpty())
        }
    }

    @Test fun retryingAFailedTaskStartsANewTaskWithTheSameBrief() = runTest {
        rig().use { r ->
            r.say("check stock of RB2140")
            advanceUntilIdle()
            r.server.complete("s1", "", com.folio.hermes.TurnStatus.Error)
            advanceUntilIdle()
            assertEquals(TaskState.Failed, r.state.tasks["t1"]!!.state)
            r.actor.send(Event.TaskTapped("t1", TaskTap.Retry))
            advanceUntilIdle()
            val t2 = r.state.tasks["t2"]!!
            assertEquals("check stock of RB2140", t2.brief)
            assertEquals(TaskState.Running, t2.state)
            assertEquals(listOf("check stock of RB2140", "check stock of RB2140"), r.server.prompts)
        }
    }

    @Test fun aResultBetweenCallsIsRecordedUnreadNotifiesAndSurvivesARestart() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        rig(store, server).use { r ->
            r.say("check stock of RB2140")
            advanceUntilIdle()
            r.actor.stop()
            advanceUntilIdle()
            server.complete("s1", "Four in stock.")
            advanceUntilIdle()
            assertEquals(listOf(Effect.Notify(DurableTaskRegistry.NOTIFY_DONE, "t1")), r.appEffects.filterIsInstance<Effect.Notify>())
            assertTrue(r.state.tasks["t1"]!!.unread)
            assertEquals(listOf("t1"), r.state.offCall.results.map { it.taskId })
        }
        rig(store, server).use { r ->
            advanceUntilIdle()
            // Item 7 (R-CONV-2): never over the Resume card; spoken after Continue (or Play).
            assertNotNull(r.state.conversation.resume)
            assertTrue("not while the Resume card shows", CascadeEvent.ResultQueued("t1") !in r.events)
            r.actor.send(Event.ResumeChosen(ResumeChoice.Continue))
            advanceUntilIdle()
            assertEquals("spoken once after Continue", 1, r.events.count { it == CascadeEvent.ResultQueued("t1") })
            assertEquals("Four in stock.", r.state.tasks["t1"]!!.result)
        }
    }

    @Test fun aRestartedTurnIsNeverStartedTwice() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        rig(store, server).use { it.say("check stock of RB2140"); advanceUntilIdle() }
        rig(store, server).use { r ->
            assertEquals("t1", DurableTaskRegistry.taskForTurn(r.state, "u1"))
            r.say("email Safilo about the order")
            advanceUntilIdle()
            assertEquals("turn ids continue after a restart", "u2", r.state.tasks["t2"]!!.turnId)
            assertEquals(2, server.prompts.size)
        }
    }
}
