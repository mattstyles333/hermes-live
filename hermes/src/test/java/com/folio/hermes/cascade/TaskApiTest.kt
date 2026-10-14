package com.folio.hermes.cascade

import com.folio.hermes.ApprovalRequest
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared task API ([CascadeTasks], grok-voice slice) through the actor on the env `:live`
 * ships ([CascadeCore.env]), with no Bill call: the Grok engine's path. Fake Hermes, virtual clock.
 */
class TaskApiTest {
    private val bank = PhraseBank.load()

    private inner class Rig(val scope: TestScope, val store: Store = MemoryStore(), val server: FakeServer = FakeServer(), settings: CascadeSettings = CascadeSettings()) : AutoCloseable {
        val job = SupervisorJob()
        val clock = TestClock(scope)
        val hermes = ClientHermes(server)
        val appEffects = mutableListOf<Effect>()
        val notices = mutableListOf<TaskNotice>()
        val events = mutableListOf<CascadeEvent>()
        val core = CascadeCore.create(
            scope = CoroutineScope(scope.coroutineContext + job),
            ports = CascadePorts(FakeOpenRouter(clock), hermes, hermes, FakeAudioOut(clock), store, clock),
            bank = bank, settings = { settings }, telemetry = TelemetrySink.None, onAppEffect = { appEffects += it },
            zone = ZoneOffset.UTC, seed = 7,
        )
        val tasks get() = core.tasks
        val state: State get() = core.actor.state.value

        init {
            CoroutineScope(scope.coroutineContext + job).launch(start = CoroutineStart.UNDISPATCHED) { core.tasks.notices.collect { notices += it } }
            CoroutineScope(scope.coroutineContext + job).launch(start = CoroutineStart.UNDISPATCHED) { core.actor.events.collect { events += it } }
        }

        override fun close() = job.cancel()
    }

    @Test fun startStoresTheTaskBeforeItsSubmitAndRepliesOnceStored() = runTest {
        val store = MemoryStore()
        Rig(this, store).use { rig ->
            val reply = rig.tasks.startTask("Check stock of RB2140 in Leeds.", "Stock check", "Checking stock.", key = "call_1")
            assertEquals(TaskReply.Started("t1", "Stock check", queued = false), reply)
            assertTrue("stored when the reply came", StoreCodec.decodeTask(store.get(StoreKeys.task("t1"))) != null)
            advanceUntilIdle()
            assertEquals(listOf("Check stock of RB2140 in Leeds."), rig.server.prompts)
            assertFalse("no Bill call was started", rig.state.started)
            assertTrue("nothing is spoken", rig.state.speech.isEmpty)
            assertEquals(SendState.Accepted, rig.state.tasks["t1"]!!.send)
        }
    }

    @Test fun theSameKeyNeverStartsTwice() = runTest {
        Rig(this).use { rig ->
            rig.tasks.startTask("Email Safilo about the order.", "Safilo email", key = "call_9")
            val again = rig.tasks.startTask("Email Safilo about the order.", "Safilo email", key = "call_9")
            assertEquals(TaskReply.Started("t1", "Safilo email", queued = false, existing = true), again)
            advanceUntilIdle()
            assertEquals(1, rig.server.prompts.size)
        }
    }

    @Test fun overTheCapATaskWaitsOnThePhoneAndStartsWhenASlotFrees() = runTest {
        Rig(this, settings = CascadeSettings(maxTasks = 1)).use { rig ->
            rig.tasks.startTask("first job", "First")
            val second = rig.tasks.startTask("second job", "Second")
            assertEquals(TaskReply.Started("t2", "Second", queued = true), second)
            advanceUntilIdle()
            assertEquals("queued", rig.tasks.status("t2").single().status)
            assertEquals(listOf("first job"), rig.server.prompts)
            rig.server.complete("s1", "First is done.")
            advanceUntilIdle()
            assertEquals(listOf("first job", "second job"), rig.server.prompts)
        }
    }

    @Test fun updatesQueueOrRedirectAndAreSentOncePerKey() = runTest {
        Rig(this).use { rig ->
            rig.tasks.startTask("check stock", "Stock")
            advanceUntilIdle()
            assertEquals(TaskReply.Updated("t1", "queue"), rig.tasks.updateTask("t1", "also Leeds", UpdateMode.Queue, key = "u1"))
            assertEquals(TaskReply.Updated("t1", "already_sent"), rig.tasks.updateTask("t1", "also Leeds", UpdateMode.Queue, key = "u1"))
            assertEquals(TaskReply.Updated("t1", "interrupt_first"), rig.tasks.updateTask("t1", "only Leeds", UpdateMode.Redirect, key = "u2"))
            assertEquals(TaskReply.NotFound("t9"), rig.tasks.updateTask("t9", "hello"))
            advanceUntilIdle()
            assertEquals(listOf("check stock", "also Leeds", "only Leeds"), rig.server.prompts)
        }
    }

    @Test fun cancelOneOrAll() = runTest {
        Rig(this, settings = CascadeSettings(maxTasks = 2)).use { rig ->
            listOf("a", "b", "c").forEach { rig.tasks.startTask("job $it", "Job $it") }
            advanceUntilIdle()
            assertEquals(TaskReply.Cancelled(listOf("t1")), rig.tasks.cancelTask("t1"))
            assertEquals(TaskReply.TooLate("t1"), rig.tasks.cancelTask("t1"))
            advanceUntilIdle()
            // t3 started in t1's slot; "all" stops t2 and t3.
            val all = rig.tasks.cancelTask(CascadeTasks.ALL) as TaskReply.Cancelled
            assertEquals(setOf("t2", "t3"), all.taskIds.toSet())
            advanceUntilIdle()
            assertTrue(rig.state.tasks.tasks.all { it.state == TaskState.Cancelled })
            assertEquals(TaskReply.Cancelled(emptyList()), rig.tasks.cancelTask(CascadeTasks.ALL))
        }
    }

    @Test fun cancelAllDropsQueuedTasksFirstSoNoneLaunches() = runTest {
        Rig(this, settings = CascadeSettings(maxTasks = 1)).use { rig ->
            rig.tasks.startTask("job a", "A")
            rig.tasks.startTask("job b", "B")
            advanceUntilIdle()
            rig.tasks.cancelTask(CascadeTasks.ALL)
            advanceUntilIdle()
            assertEquals("the queued task never reached Hermes", listOf("job a"), rig.server.prompts)
        }
    }

    @Test fun statusComesFromTheLogAndReadResultMarksItHeard() = runTest {
        Rig(this).use { rig ->
            rig.tasks.startTask("check stock", "Stock")
            advanceUntilIdle()
            val running = rig.tasks.status().single()
            assertEquals("running", running.status)
            assertFalse(running.hasResult)
            assertNull(rig.tasks.readResult("t1"))
            rig.server.complete("s1", "Four in stock in Leeds. Two in York.")
            advanceUntilIdle()
            val done = rig.tasks.status("t1").single()
            assertEquals("done", done.status)
            assertTrue(done.hasResult && done.unread)
            assertEquals("Four in stock in Leeds. Two in York.", rig.tasks.readResult("t1"))
            advanceUntilIdle()
            assertFalse("read", rig.tasks.status("t1").single().unread)
            assertEquals("status never prompts Hermes", listOf("check stock"), rig.server.prompts)
        }
    }

    @Test fun noticesCarryResultsAndApprovalsAsTheyArrive() = runTest {
        Rig(this).use { rig ->
            rig.tasks.startTask("check stock", "Stock")
            advanceUntilIdle()
            val approval = ApprovalRequest("srq-1", "s1", "a1", "rm", "remove", emptyList(), "terminal", JsonObject(emptyMap()))
            rig.server.sessions.getValue("s1").live.single().trySend(TurnUpdate.ApprovalNeeded(approval))
            advanceUntilIdle()
            rig.server.complete("s1", "All done.")
            advanceUntilIdle()
            assertEquals(
                listOf(TaskNotice.NeedsYou("t1", "Stock", "approval"), TaskNotice.Finished("t1", "Stock", "All done.", ok = true)),
                rig.notices,
            )
            assertTrue("off-call notifications unchanged", rig.appEffects.contains(Effect.Notify("task_done", "t1")))
        }
    }

    @Test fun aFirstFailedSubmitThatTheRegistryResendsIsNoFailureNotice() = runTest {
        Rig(this).use { rig ->
            rig.hermes.failSubmits = 1
            rig.tasks.startTask("check stock", "Stock")
            advanceUntilIdle()
            assertEquals("resent once", listOf("check stock"), rig.server.prompts)
            assertTrue("no failed notice: ${rig.notices}", rig.notices.none { it is TaskNotice.Finished })
            assertEquals(SendState.Accepted, rig.state.tasks["t1"]!!.send)
        }
    }

    @Test fun aHeardResultIsNeverReplayedByALaterBillCall() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        Rig(this, store, server).use { rig ->
            rig.tasks.startTask("check stock", "Stock")
            rig.tasks.startTask("email Safilo", "Safilo")
            advanceUntilIdle()
            server.complete("s1", "Four in stock.")
            server.complete("s2", "Sent.")
            advanceUntilIdle()
            assertEquals(2, rig.state.offCall.results.size)
            assertEquals(TaskReply.Noted, rig.tasks.heard("t1"))
        }
        // The next process: Bill starts a call; only the unheard result is spoken.
        Rig(this, store, server).use { rig ->
            rig.core.start()
            advanceUntilIdle()
            if (rig.state.conversation.resume != null) rig.core.actor.send(Event.ResumeChosen(ResumeChoice.Play))
            advanceUntilIdle()
            assertEquals(listOf("t2"), rig.events.filterIsInstance<CascadeEvent.ResultQueued>().map { it.id })
        }
    }

    @Test fun commandsWaitForTheStoredStateAndReplyNotReadyNever() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        Rig(this, store, server).use { it.tasks.startTask("check stock", "Stock"); advanceUntilIdle() }
        Rig(this, store, server).use { rig ->
            // The first command of a new process restores first, so the stored task is kept and numbering continues.
            val reply = rig.tasks.startTask("email Safilo", "Safilo")
            assertEquals(TaskReply.Started("t2", "Safilo", queued = false), reply)
            runCurrent()
            assertEquals(listOf("t1", "t2"), rig.state.tasks.tasks.map { it.id })
        }
    }
}
