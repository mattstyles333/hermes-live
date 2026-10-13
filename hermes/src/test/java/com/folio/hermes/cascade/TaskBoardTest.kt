package com.folio.hermes.cascade

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeTokens
import com.folio.hermes.HermesGateway
import com.folio.hermes.bool
import com.folio.hermes.string
import com.folio.hermes.telemetry.TelemetrySink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        open = TaskBoard.sessions(gateway, config),
        busyMode = { gateway.busyMode() },
        telemetry = TelemetrySink.None,
    )

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
            if (busy == null) s.error(id, -32601, "unknown method") else s.reply(id, """{"key":"busy","value":"$busy"}""")
        }
        scope.launch { board.results.collect { results += it } }
        scope.launch { board.requests.collect { requests += it } }
        scope.launch { board.bus.events.collect { events += it } }
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
}

internal suspend fun eventually(timeoutMs: Long = 5_000, what: String = "condition", check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!check()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
        delay(10)
    }
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

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun startRunning(label: String = "stock check"): TaskSnapshot {
        val r = rig.board.start("Check RB2140 stock.", label, "User: check stock")
        val task = (r as StartResult.Started).task
        eventually(what = "task running") { rig.board.snapshot(task.id)!!.events.any { it is TaskEvent.Started } }
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
        assertTrue(rig.events.contains(CascadeEvent.TaskStarted(task.id)))
    }

    @Test fun statusNeverSubmits() = test {
        val task = startRunning()
        val sid = "rt1"
        rig.socket!!.event("tool.start", sid, """{"tool_id":"c1","name":"web_search"}""")
        rig.socket!!.event("message.delta", sid, """{"text":"Looking at the warehouse"}""")
        eventually(what = "tool logged") { rig.board.snapshot(task.id)!!.currentTool == "web search" }
        repeat(20) {
            rig.board.snapshot(task.id)
            rig.board.snapshots()
            rig.board.briefs()
        }
        delay(300)
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
        eventually(what = "second submit") { rig.frames("prompt.submit").size == 2 }
        val params = rig.frames("prompt.submit")[1]["params"] as JsonObject
        assertEquals(true, params.bool("queued"))
        assertEquals(0, rig.frames("session.interrupt").size)
        assertEquals(1, rig.board.snapshot(task.id)!!.pendingUpdates)
    }

    @Test fun aQueuedUpdateGivesTwoResultsInOrder() = test {
        val task = startRunning()
        rig.onSubmit = { s, id, p ->
            val sid = p.string("session_id")!!
            s.reply(id, """{"status":"queued"}""")
            s.event("message.complete", sid, """{"text":"first answer"}""")
            s.event("message.start", sid)
            s.event("message.complete", sid, """{"text":"second answer"}""")
        }
        rig.board.update(task.id, "and the blue ones", UpdateMode.Queue, "")
        eventually(what = "two results") { rig.results.size == 2 }
        assertEquals(listOf("first answer", "second answer"), rig.results.map { it.text })
        eventually(what = "done") { rig.board.snapshot(task.id)!!.state == TaskState.Done }
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
        eventually(what = "result") { rig.results.isNotEmpty() }
        delay(200)
        assertEquals(listOf("redirected answer"), rig.results.map { it.text })
        assertNull("redirect follows the server's busy mode", (rig.frames("prompt.submit")[1]["params"] as JsonObject)["queued"])
        assertEquals(0, rig.frames("session.interrupt").size)
        assertEquals(1, rig.frames("config.get").size)
    }

    @Test fun aRedirectWithQueueBusyModeInterruptsThenSubmits() = test {
        rig.close()
        rig = BoardRig(busy = "queue")
        val task = startRunning()
        rig.onSubmit = { s, id, p ->
            val sid = p.string("session_id")!!
            s.reply(id, """{"status":"streaming"}""")
            s.event("message.start", sid)
            s.event("message.complete", sid, """{"text":"new answer"}""")
        }
        assertEquals(UpdateResult.Sent("interrupt_first"), rig.board.update(task.id, "stop, do this instead", UpdateMode.Redirect, ""))
        eventually(what = "result") { rig.results.isNotEmpty() }
        delay(200)
        assertEquals(1, rig.frames("session.interrupt").size)
        assertEquals("the interrupted turn is not a result", listOf("new answer"), rig.results.map { it.text })
        eventually(what = "done") { rig.board.snapshot(task.id)!!.state == TaskState.Done }
    }

    @Test fun unknownBusyModeFallsBackToInterruptFirst() = test {
        rig.close()
        rig = BoardRig(busy = null)
        val task = startRunning()
        assertEquals(UpdateResult.Sent("interrupt_first"), rig.board.update(task.id, "change", UpdateMode.Redirect, ""))
    }

    @Test fun cancelInterruptsAndTheInterruptedEndIsNotAResult() = test {
        val task = startRunning()
        assertEquals(CancelResult.Cancelled, rig.board.cancel(task.id))
        eventually(what = "interrupt sent") { rig.frames("session.interrupt").size == 1 }
        delay(300)
        assertTrue(rig.results.isEmpty())
        assertEquals(TaskState.Cancelled, rig.board.snapshot(task.id)!!.state)
        assertEquals(TaskUiStatus.Cancelled, rig.board.snapshot(task.id)!!.toUi().status)
        assertTrue(rig.events.contains(CascadeEvent.TaskCancelled(task.id)))
        assertEquals(CancelResult.AlreadyFinished, rig.board.cancel(task.id))
        assertEquals(CancelResult.NotFound, rig.board.cancel("t99"))
    }

    @Test fun theCapHoldsWhenTwoStartsRace() = test {
        rig.max = 2
        startRunning("first")
        val latch = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<StartResult>()
        val threads = List(2) { i ->
            Thread {
                latch.await()
                outcomes += rig.board.start("job $i", "job $i", "")
            }.apply { start() }
        }
        latch.countDown()
        threads.forEach { it.join() }
        assertEquals(1, outcomes.count { it is StartResult.Started })
        assertEquals(StartResult.AtCap(2), outcomes.single { it is StartResult.AtCap })
        assertTrue(rig.board.isFull)
        eventually(what = "two sessions") { rig.created.get() == 2 }
        delay(200)
        assertEquals(2, rig.created.get())
    }

    @Test fun aResultIsKeptUnreadUntilRead() = test {
        val task = startRunning()
        rig.complete("rt1", "RB2140: 14 in stock")
        eventually(what = "result") { rig.results.size == 1 }
        val snap = rig.board.snapshot(task.id)!!
        assertEquals("RB2140: 14 in stock", snap.result)
        assertTrue(snap.unread)
        rig.board.markRead(task.id)
        assertFalse(rig.board.snapshot(task.id)!!.unread)
        // The result goes out as the turn completes; the task turns Done when its flow ends, a moment later.
        eventually(what = "done") { rig.board.snapshot(task.id)!!.toUi().status == TaskUiStatus.Done }
    }

    @Test fun approvalsReachTheCardWithTheLabel() = test {
        val task = startRunning("price email")
        rig.socket!!.send(
            """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","request_id":"r1","command":"send email","description":"Send","choices":["once","deny"]}}""",
        )
        eventually(what = "request") { rig.requests.isNotEmpty() }
        assertEquals("price email", rig.requests.single().label)
        assertEquals(TaskUiStatus.Waiting, rig.board.snapshot(task.id)!!.toUi().status)
    }
}
