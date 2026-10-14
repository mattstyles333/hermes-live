package com.folio.hermes.cascade

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeTokens
import com.folio.hermes.HermesGateway
import com.folio.hermes.bool
import com.folio.hermes.fence
import com.folio.hermes.string
import com.folio.hermes.cascade.ports.GatewayHermesPort
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A TaskBoard on a real gateway against [FakeHermesServer]. */
internal class BoardRig(maxTasks: Int = 4, busy: String? = "interrupt") : AutoCloseable {
    /** The server's busy mode for `config.get` (null: the server does not say). A field, so a test changes it without a second rig. */
    @Volatile var busy: String? = busy
    val fake = FakeHermesServer()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config = fake.config()
    val gateway = HermesGateway(config, FakeTokens(), scope = scope)
    val created = AtomicInteger()
    val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val results = CopyOnWriteArrayList<TaskResult>()
    val requests = CopyOnWriteArrayList<TaskRequest>()
    val events = CopyOnWriteArrayList<CascadeEvent>()
    @Volatile var max = maxTasks
    @Volatile var socket: FakeHermesServer.ServerSide? = null

    /** What the server does with each `prompt.submit` (default: start streaming). */
    @Volatile var onSubmit: (FakeHermesServer.ServerSide, kotlinx.serialization.json.JsonElement, JsonObject) -> Unit = { s, id, p ->
        val sid = p.string("session_id")!!
        running += sid
        s.reply(id, """{"status":"streaming"}""")
        s.event("message.start", sid)
    }

    val board = TaskBoard(
        scope = scope,
        maxTasks = { max },
        port = GatewayHermesPort(gateway, config),
    )

    private val rigVersion = MutableStateFlow(0L)
    private val fencesSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fenceCount = AtomicInteger()

    init {
        fake.responders["session.create"] = { s, id, _ ->
            socket = s
            val n = created.incrementAndGet()
            s.reply(id, """{"session_id":"rt$n","stored_session_id":"stored-$n","info":{}}""")
        }
        fake.responders["prompt.submit"] = { s, id, p -> onSubmit(s, id, p) }
        fake.responders["session.interrupt"] = { s, id, p ->
            val sid = p.string("session_id")!!
            s.reply(id, """{"status":"interrupted"}""")
            if (running.remove(sid)) s.event("message.complete", sid, """{"text":"","status":"interrupted"}""")
        }
        fake.responders["config.get"] = { s, id, _ ->
            val mode = this.busy
            if (mode == null) s.error(id, -32601, "unknown method") else s.reply(id, """{"key":"busy","value":"$mode"}""")
        }
        scope.launch {
            board.results.collect {
                if (it.taskId.startsWith(FENCE)) fencesSeen += "results:${it.taskId}" else results += it
                changed()
            }
        }
        scope.launch {
            board.requests.collect {
                requests += it
                changed()
            }
        }
        // Subscribed before the rig is used: the bus has no replay, so a late collector misses the first events.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            board.bus.events.collect {
                if (it is CascadeEvent.TaskStarted && it.id.startsWith(FENCE)) fencesSeen += "events:${it.id}" else events += it
                changed()
            }
        }
    }

    private fun changed() = rigVersion.update { it + 1 }

    /** Emits on every change a test can wait for: the board's table, what the rig collected, frames the fake handled. */
    val changes: Flow<Long> = merge(board.version, rigVersion, fake.activity)

    suspend fun eventually(what: String, check: () -> Boolean) = eventually(changes, what = what, check = check)

    /**
     * Deterministic "nothing more happened" point, instead of a sleep. A gateway round trip
     * ([fence]): every frame the client sent before it has reached the fake, and every frame the
     * fake sent before its reply has been read. Then a marker through the board's results and bus:
     * once the rig has collected both, it has every result and event the board made before them.
     */
    suspend fun fence() {
        gateway.fence()
        val id = "$FENCE${fenceCount.incrementAndGet()}"
        board.fence(id)
        eventually("fence $id") { "results:$id" in fencesSeen && "events:$id" in fencesSeen }
    }

    fun frames(method: String): List<JsonObject> = sockets().flatMap { it.frames }.filter { it.string("method") == method }

    private val seen = CopyOnWriteArrayList<FakeHermesServer.ServerSide>()
    fun sockets(): List<FakeHermesServer.ServerSide> {
        while (true) seen += fake.sockets.poll() ?: break
        return seen
    }

    fun complete(sid: String, text: String) {
        running.remove(sid)
        socket!!.event("message.complete", sid, """{"text":"$text","status":"complete"}""")
    }

    override fun close() {
        gateway.disconnect()
        scope.cancel()
        fake.close()
    }

    private companion object {
        const val FENCE = "fence-"
    }
}

/**
 * Waits until [check] holds, re-checking it each time [changes] emits (no polling). [changes]
 * must emit after every state change [check] reads; a [kotlinx.coroutines.flow.StateFlow]
 * counter bumped after the change does, and its current value makes the first check immediate.
 */
internal suspend fun eventually(changes: Flow<*>, timeoutMs: Long = 15_000, what: String = "condition", check: () -> Boolean) {
    withTimeoutOrNull(timeoutMs) {
        changes.first { check() }
        true
    } ?: throw AssertionError("timed out waiting for $what")
}

class TaskBoardTest {
    private lateinit var rig: BoardRig

    @Before fun setUp() {
        rig = BoardRig()
    }

    @After fun tearDown() {
        // Read-only on server config, always.
        assertTrue(rig.sockets().flatMap { it.frames }.none { it.string("method")?.startsWith("config.set") == true })
        rig.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(40_000) { block() } }

    private suspend fun startRunning(label: String = "stock check"): TaskSnapshot {
        val r = rig.board.start("Check RB2140 stock.", label, "User: check stock")
        val task = (r as StartResult.Started).task
        rig.eventually("task running") { rig.board.snapshot(task.id)!!.events.any { it is TaskEvent.Started } }
        return task
    }

    @Test fun startSubmitsAVoiceLiveTurnOnItsOwnSession() = test {
        val task = startRunning()
        val submit = rig.frames("prompt.submit").single()["params"] as JsonObject
        assertEquals("voice-live", submit.string("surface"))
        assertEquals("User: check stock", submit.string("voice_context"))
        assertNull("a first submit is not queued", submit["queued"])
        val create = rig.frames("session.create").single()["params"] as JsonObject
        assertEquals("Voice: stock check", create.string("title"))
        assertEquals(TaskState.Running, rig.board.snapshot(task.id)!!.state)
        // The bus reaches rig.events through an async collector.
        rig.eventually("started event") { rig.events.contains(CascadeEvent.TaskStarted(task.id)) }
    }

    @Test fun statusNeverSubmits() = test {
        val task = startRunning()
        val sid = "rt1"
        rig.socket!!.event("tool.start", sid, """{"tool_id":"c1","name":"web_search"}""")
        rig.socket!!.event("message.delta", sid, """{"text":"Looking at the warehouse"}""")
        rig.eventually("tool logged") { rig.board.snapshot(task.id)!!.currentTool == "web search" }
        repeat(20) {
            rig.board.snapshot(task.id)
            rig.board.snapshots()
            rig.board.briefs()
        }
        rig.fence()
        assertEquals(1, rig.frames("prompt.submit").size)
        assertEquals(0, rig.frames("session.interrupt").size)
        val snap = rig.board.snapshot(task.id)!!
        assertEquals("using web search", snap.lastEvent)
        assertEquals("Looking at the warehouse", snap.progress)
        assertEquals(TaskUiStatus.Running, snap.toUi().status)
    }

    @Test fun updatesAreQueuedByDefaultOnTheWire() = test {
        val task = startRunning()
        rig.onSubmit = { s, id, _ -> s.reply(id, """{"status":"queued"}""") }
        assertEquals(UpdateResult.Sent("queue"), rig.board.update(task.id, "also check RB3025", UpdateMode.Queue, ""))
        rig.eventually("second submit") { rig.frames("prompt.submit").size == 2 }
        val params = rig.frames("prompt.submit")[1]["params"] as JsonObject
        assertEquals(true, params.bool("queued"))
        assertEquals(0, rig.frames("session.interrupt").size)
        assertEquals(1, rig.board.snapshot(task.id)!!.pendingUpdates)
    }

    /*
     * The flake (spec v2 §14 item 20), reproduced at 24 of 200 runs under 48-way load:
     * - the fake sent the running turn's end, the queued turn's start and its end in one burst.
     *   Each run reads them on its own flow (its own coroutine), so the second run could post its
     *   result before the first: results out of order. A real queued turn starts only after the
     *   running one ends, so the server now sends the queued turn once the first result is in;
     * - `TaskFinished` reaches `rig.events` through an async collector after the state turns
     *   Done, so the count was read too early. It is now awaited.
     * - the rig's event collector subscribed lazily on another thread, so a fast first event
     *   (`TaskStarted`) could be emitted before it listened, and was lost; it now subscribes at once;
     * - `unknownBusyMode…` built a second rig (two servers, two gateways) inside one test; the busy
     *   mode is now a field of the one rig, and `eventually` waits up to 15 s on a loaded machine.
     */
    @Test fun aQueuedUpdateGivesTwoResultsInOrder() = test {
        val task = startRunning()
        rig.onSubmit = { s, id, p ->
            val sid = p.string("session_id")!!
            s.reply(id, """{"status":"queued"}""")
            s.event("message.complete", sid, """{"text":"first answer"}""")
        }
        rig.board.update(task.id, "and the blue ones", UpdateMode.Queue, "")
        rig.eventually("first result") { rig.results.size == 1 }
        rig.socket!!.event("message.start", "rt1")
        rig.socket!!.event("message.complete", "rt1", """{"text":"second answer"}""")
        rig.eventually("two results") { rig.results.size == 2 }
        assertEquals(listOf("first answer", "second answer"), rig.results.map { it.text })
        rig.eventually("done") { rig.board.snapshot(task.id)!!.state == TaskState.Done }
        rig.eventually("finished event") { rig.events.count { it == CascadeEvent.TaskFinished(task.id, true) } == 1 }
        rig.fence()
        assertEquals(1, rig.events.count { it == CascadeEvent.TaskFinished(task.id, true) })
        assertEquals(0, rig.board.snapshot(task.id)!!.pendingUpdates)
    }

    @Test fun aRedirectGivesOneResult() = test {
        val task = startRunning()
        rig.onSubmit = { s, id, p ->
            val sid = p.string("session_id")!!
            s.reply(id, """{"status":"redirected"}""")
            s.event("message.complete", sid, """{"text":"redirected answer"}""")
        }
        assertEquals(UpdateResult.Sent("redirect"), rig.board.update(task.id, "actually only the blue ones", UpdateMode.Redirect, ""))
        rig.eventually("result") { rig.results.isNotEmpty() }
        rig.fence()
        assertEquals(listOf("redirected answer"), rig.results.map { it.text })
        assertNull("redirect follows the server's busy mode", (rig.frames("prompt.submit")[1]["params"] as JsonObject)["queued"])
        assertEquals(0, rig.frames("session.interrupt").size)
        assertEquals(1, rig.frames("config.get").size)
    }

    @Test fun aRedirectWithQueueBusyModeInterruptsThenSubmits() = test {
        rig.busy = "queue"
        val task = startRunning()
        rig.onSubmit = { s, id, p ->
            val sid = p.string("session_id")!!
            s.reply(id, """{"status":"streaming"}""")
            s.event("message.start", sid)
            s.event("message.complete", sid, """{"text":"new answer"}""")
        }
        assertEquals(UpdateResult.Sent("interrupt_first"), rig.board.update(task.id, "stop, do this instead", UpdateMode.Redirect, ""))
        rig.eventually("result") { rig.results.isNotEmpty() }
        rig.eventually("done") { rig.board.snapshot(task.id)!!.state == TaskState.Done }
        rig.fence()
        assertEquals(1, rig.frames("session.interrupt").size)
        assertEquals("the interrupted turn is not a result", listOf("new answer"), rig.results.map { it.text })
    }

    @Test fun unknownBusyModeFallsBackToInterruptFirst() = test {
        rig.busy = null
        val task = startRunning()
        assertEquals(UpdateResult.Sent("interrupt_first"), rig.board.update(task.id, "change", UpdateMode.Redirect, ""))
    }

    @Test fun cancelInterruptsAndTheInterruptedEndIsNotAResult() = test {
        val task = startRunning()
        assertEquals(CancelResult.Cancelled, rig.board.cancel(task.id))
        rig.eventually("interrupt sent") { rig.frames("session.interrupt").size == 1 }
        rig.eventually("cancelled event") { rig.events.contains(CascadeEvent.TaskCancelled(task.id)) }
        rig.fence()
        assertTrue(rig.results.isEmpty())
        assertEquals(TaskState.Cancelled, rig.board.snapshot(task.id)!!.state)
        assertEquals(TaskUiStatus.Cancelled, rig.board.snapshot(task.id)!!.toUi().status)
        assertEquals(CancelResult.AlreadyFinished, rig.board.cancel(task.id))
        assertEquals(CancelResult.NotFound, rig.board.cancel("t99"))
    }

    /*
     * Was `theCapHoldsWhenTwoStartsRace` (two threads racing `start`): that raced the driver's own
     * lock, not the production rules, and was the §14 item 20 flake. The cap itself is TaskTable's
     * rule; here, one thread: the start at the cap is refused and sends nothing.
     */
    @Test fun aStartAtTheCapIsRefusedAndSendsNothing() = test {
        rig.max = 2
        startRunning("first")
        assertTrue(rig.board.start("job 1", "job 1", "") is StartResult.Started)
        assertTrue(rig.board.isFull)
        assertEquals(StartResult.AtCap(2), rig.board.start("job 2", "job 2", ""))
        rig.eventually("two sessions") { rig.created.get() == 2 }
        rig.fence()
        assertEquals(2, rig.created.get())
        assertEquals(2, rig.board.snapshots().size)
    }

    @Test fun aResultIsKeptUnreadUntilRead() = test {
        val task = startRunning()
        rig.complete("rt1", "RB2140: 14 in stock")
        rig.eventually("result") { rig.results.size == 1 }
        val snap = rig.board.snapshot(task.id)!!
        assertEquals("RB2140: 14 in stock", snap.result)
        assertTrue(snap.unread)
        rig.board.markRead(task.id)
        assertFalse(rig.board.snapshot(task.id)!!.unread)
        // The result goes out as the turn completes; the task turns Done when its flow ends, a moment later.
        rig.eventually("done") { rig.board.snapshot(task.id)!!.toUi().status == TaskUiStatus.Done }
    }

    @Test fun approvalsReachTheCardWithTheLabel() = test {
        val task = startRunning("price email")
        rig.socket!!.send(
            """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","request_id":"r1","command":"send email","description":"Send","choices":["once","deny"]}}""",
        )
        rig.eventually("request") { rig.requests.isNotEmpty() }
        assertEquals("price email", rig.requests.single().label)
        assertEquals(TaskUiStatus.Waiting, rig.board.snapshot(task.id)!!.toUi().status)
    }
}
