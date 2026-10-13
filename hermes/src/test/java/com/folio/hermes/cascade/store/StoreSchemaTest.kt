package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.ActionQueue
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskRun
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.ports.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored schema: round trips, forward and backward compatibility, and what restore recovers. No real data. */
class StoreSchemaTest {
    private val wall = 1_760_000_000_000L

    private fun task(id: String, turnId: String? = null, send: SendState = SendState.Sending, stored: String? = null, state: TaskState = TaskState.Running) =
        TaskRecord(
            id = id, label = "stock check", startedAt = wall, state = state, active = 1, accepted = send == SendState.Accepted,
            runs = mapOf(1L to TaskRun(RunKind.Start, false)), brief = "Check RB2140 stock.", turnId = turnId,
            storedSessionId = stored, send = send, events = listOf(TaskEvent.Submitted(wall, "start"), TaskEvent.Tool(wall + 5, "web search", true, "3 hits")),
        )

    private fun started(s: State) = s.copy(wallMs = wall + 60_000)

    @Test fun aTaskRoundTrips() {
        val t = task("t3", turnId = "u2", send = SendState.Accepted, stored = "s9").copy(result = "4 in stock", unread = true, steps = 2, submitAttempts = 1, offline = true)
        val back = StoreCodec.decodeTask(StoreCodec.encodeTask(t))!!
        assertEquals(t.copy(lastEventAt = wall + 5), back)
    }

    @Test fun aFutureFieldAVersionBumpAndUnknownNamesStillLoad() {
        val json = """{"v":7,"id":"t1","label":"x","state":"paused","send":"teleported","shiny":{"a":1},
            "events":[{"t":"hologram","at":1},{"t":"started","at":2,"colour":"red"}],
            "runs":[{"id":4,"kind":"warp"}]}"""
        val t = StoreCodec.decodeTask(json)!!
        assertEquals("t1", t.id)
        assertEquals("an unknown state is not running", TaskState.Failed, t.state)
        assertEquals(SendState.Sending, t.send)
        assertEquals(listOf<TaskEvent>(TaskEvent.Started(2)), t.events)
        assertEquals(RunKind.Start, t.runs.getValue(4).kind)
        val core = StoreCodec.decode(CoreDto.serializer(), """{"v":9,"turns":5,"later":[1,2],"outbox":{"held":[{"turnId":"u5","text":"hi","mood":"?"}]}}""")!!
        assertEquals(5, core.turns)
        assertEquals("u5", core.outbox.held.single().turnId)
    }

    @Test fun anOlderValueWithMissingFieldsLoads() {
        val t = StoreCodec.decodeTask("""{"id":"t2"}""")!!
        assertEquals(TaskState.Running, t.state)
        assertNull(t.brief)
    }

    @Test fun corruptValuesAreSkippedNeverThrown() {
        val store = MemoryStore()
        store.put(StoreKeys.CORE, "{not json")
        store.put(StoreKeys.task("t1"), "\u0000")
        store.put(StoreKeys.task("t2"), StoreCodec.encodeTask(task("t2", send = SendState.Accepted, stored = "s1")))
        val s = DurablePersistence.restore(store, State(wallMs = wall))
        assertEquals(listOf("t2"), s.tasks.tasks.map { it.id })
        assertTrue(s.conversation.id != null)
    }

    @Test fun nothingIsWrittenBeforeTheFirstRestoreOrWhenNothingDurableChanged() {
        val blank = State(wallMs = wall)
        assertTrue(DurablePersistence.save(blank, blank.copy(pauseProgress = 0.5f)).isEmpty())
        val s = DurablePersistence.restore(MemoryStore(), blank)
        assertTrue(DurablePersistence.save(s, s.copy(pauseProgress = 0.5f, nowMs = 99)).isEmpty())
    }

    @Test fun onlyChangedTasksAreWrittenAndTasksBeforeTheCore() {
        val s = DurablePersistence.restore(MemoryStore(), State(wallMs = wall)).copy(tasks = TaskTable(listOf(task("t1"), task("t2", turnId = "u2")), counter = 2, runCounter = 1))
        val t2 = s.tasks.tasks[1].copy(send = SendState.Accepted, accepted = true)
        val after = s.copy(tasks = s.tasks.copy(tasks = listOf(s.tasks.tasks[0], t2)))
        val keys = DurablePersistence.save(s, after).filterIsInstance<Effect.Persist>().map { it.key }
        assertEquals(listOf(StoreKeys.task("t2"), StoreKeys.CORE), keys)
        // Streaming text alone is not stored.
        val streaming = after.copy(tasks = after.tasks.copy(tasks = listOf(after.tasks.tasks[0].copy(progress = "Looking…"), t2)))
        assertTrue(DurablePersistence.save(after, streaming).isEmpty())
    }

    @Test fun aCommittedTurnIsStoredAsHeldWhileItsActionRuns() {
        val s = DurablePersistence.restore(MemoryStore(), State(wallMs = wall))
        val action = ActionState("u1", "check stock", Decision(Route.NewTask, null, false), 10, DecisionRecord(turnId = "u1", atMs = wall, outcome = "done"))
        val after = s.copy(actions = ActionQueue(active = action))
        val core = DurablePersistence.save(s, after).filterIsInstance<Effect.Persist>().single { it.key == StoreKeys.CORE }
        assertEquals("u1", StoreCodec.decode(CoreDto.serializer(), core.value)!!.outbox.held.single().turnId)
    }

    @Test fun restoreSettlesWhatAKillLeftBehind() {
        val store = MemoryStore()
        store.put(StoreKeys.task("t1"), StoreCodec.encodeTask(task("t1", turnId = "u1")))
        store.put(StoreKeys.task("t2"), StoreCodec.encodeTask(task("t2", turnId = "u2", send = SendState.Accepted)))
        store.put(StoreKeys.task("t3"), StoreCodec.encodeTask(task("t3", turnId = "u3", stored = "s3")))
        store.put(StoreKeys.task("t4"), StoreCodec.encodeTask(task("t4", turnId = "u4", send = SendState.Accepted, state = TaskState.Done).copy(active = 0, runs = emptyMap())))
        val core = CoreDto(
            outbox = OutboxDto(held = listOf(HeldDto("u1", "check stock"), HeldDto("u5", "call Bob")), awaiting = listOf("u3"), sent = listOf("u2")),
            turns = 4, counter = 3, runCounter = 1,
        )
        store.put(StoreKeys.CORE, StoreCodec.encode(CoreDto.serializer(), core))
        val s = DurablePersistence.restore(store, State(wallMs = wall + 1_000))
        val byId = s.tasks.tasks.associateBy { it.id }
        assertEquals("Sending, no stored session: NotSent, never resent on its own", SendState.NotSent, byId.getValue("t1").send)
        assertEquals(TaskState.Failed, byId.getValue("t1").state)
        assertTrue(byId.getValue("t1").runs.isEmpty())
        assertEquals("accepted but no session to re-bind: failed (retry)", TaskState.Failed, byId.getValue("t2").state)
        assertEquals(SendState.Accepted, byId.getValue("t2").send)
        assertEquals("bound to a stored session: kept for recovery", TaskState.Running, byId.getValue("t3").state)
        assertEquals(setOf(1L), byId.getValue("t3").runs.keys)
        assertEquals(TaskState.Done, byId.getValue("t4").state)
        assertEquals("a held turn with a task belongs to the registry; the other is resent", listOf("u5"), s.outbox.ids())
        assertTrue("u1" in s.outbox.sent && "u3" in s.outbox.sent)
        assertEquals("turn ids continue past every stored one", 5, s.turn.turns)
        assertEquals(4, s.tasks.counter)
        
    }
}
