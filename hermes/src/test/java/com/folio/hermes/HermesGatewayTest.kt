package com.folio.hermes

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HermesGatewayTest {
    private val fake = FakeHermesServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tokens = FakeTokens()
    private val gateway = HermesGateway(fake.config(), tokens, scope = scope)

    @After fun tearDown() {
        gateway.disconnect()
        scope.cancel()
        fake.close()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test fun nothingDialsUntilAsked() = test {
        assertEquals(GatewayConnection.Idle, gateway.connection.value)
        assertEquals(0, fake.server.requestCount)
        assertEquals(0, tokens.calls.get())
    }

    @Test fun signedOutStopsWithoutAnyRequest() = test {
        tokens.signedIn = false
        try {
            gateway.awaitReady()
            fail()
        } catch (e: HermesAuthException.SignInRequired) {
            // expected
        }
        val state = gateway.connection.value as GatewayConnection.Stopped
        assertTrue(state.signInRequired)
        assertEquals(0, fake.server.requestCount)
    }

    @Test fun ticketHandshakeReadyAndCapabilities() = test {
        val ready = gateway.awaitReady()
        assertEquals("epoch-1", ready.replayEpoch)
        val ticketRequest = fake.server.takeRequest()
        assertEquals("/api/auth/ws-ticket", ticketRequest.url.encodedPath)
        assertEquals("POST", ticketRequest.method)
        assertEquals("Bearer at-1", ticketRequest.headers["Authorization"])
        assertEquals("t-1", fake.upgrades.poll(1, TimeUnit.SECONDS)!!.queryParameter("ticket"))
        val caps = fake.nextSocket().nextCall("client.capabilities")
        assertEquals(JsonPrimitive(true), caps.obj("params")!!["server_requests"])
        assertEquals("2.0", caps.string("jsonrpc"))
    }

    @Test fun olderServerWithoutCapabilitiesStillConnects() = test {
        fake.capabilitiesUnsupported = true
        gateway.awaitReady()
        assertEquals(1, fake.tickets.get())
    }

    @Test fun runningStateFollowsTheWire() = test {
        fake.responders["prompt.submit"] = { s, id, _ -> s.reply(id, """{"status":"streaming"}""") }
        gateway.awaitReady()
        val socket = fake.nextSocket()
        assertFalse(gateway.isRunning("s1"))
        gateway.submitPrompt("s1", "hi")
        assertTrue(gateway.isRunning("s1"))
        // The gateway sets the running flag before it emits the event, so the event is the sync point.
        val completed = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.MessageComplete>().first() }
        socket.event("message.complete", "s1", """{"text":"done"}""")
        completed.await()
        assertFalse(gateway.isRunning("s1"))
        val started = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.MessageStart>().first() }
        socket.event("message.start", "s1")
        started.await()
        assertTrue(gateway.isRunning("s1"))
    }

    @Test fun expiredBearerRefreshesOnceForTheTicket() = test {
        fake.acceptedTokens.clear()
        fake.acceptedTokens.add("at-2")
        gateway.awaitReady()
        assertEquals(listOf("at-1"), tokens.refreshes)
        assertEquals(1, fake.tickets.get())
    }

    @Test fun clientPingsAndAnswersServerPings() = test {
        gateway.awaitReady()
        val socket = fake.nextSocket()
        socket.nextCall("gateway.ping", 2_000)
        socket.send("""{"jsonrpc":"2.0","id":"srv-1","method":"gateway.ping","params":{}}""")
        val reply = socket.nextResponse("srv-1")
        assertEquals("""{"ok":true}""", reply["result"].toString())
    }

    @Test fun silentServerTriggersReconnectWithAFreshTicket() = test {
        fake.answerPings = false
        val first = gateway.awaitReady()
        fake.nextSocket()
        val second = gateway.connection.first { it is GatewayConnection.Ready && it.generation > first.generation }
        assertTrue(second is GatewayConnection.Ready)
        assertEquals(2, fake.tickets.get())
        assertEquals("t-2", fake.upgrades.drop(1).first().queryParameter("ticket"))
    }

    @Test fun dropFailsWaitingCallsAndReconnects() = test {
        val first = gateway.awaitReady()
        val socket = fake.nextSocket()
        val call = async { runCatching { gateway.call("session.list", JsonObject(emptyMap())) } }
        socket.nextCall("session.list")
        socket.ws.close(1001, "going away")
        val result = call.await()
        assertTrue(result.exceptionOrNull() is GatewayUnavailableException)
        gateway.connection.first { it is GatewayConnection.Ready && it.generation > first.generation }
    }

    /**
     * A call made the moment a drop fails the previous one waits for the next connection; it
     * does not fail at once on the old Ready state. Unconfined forces the window a busy CI runner
     * hit by chance: the second call runs inside the drop's own cleanup, before the connection
     * loop has moved off Ready.
     */
    @Test fun aCallMadeAsADropFailsTheLastOneWaitsForTheNextConnection() = test {
        val first = gateway.awaitReady()
        val socket = fake.nextSocket()
        val next = async(Dispatchers.Unconfined) {
            val lost = runCatching { gateway.call("session.list", JsonObject(emptyMap())) }
            lost.exceptionOrNull() to gateway.call(FakeHermesServer.FENCE, JsonObject(emptyMap()))
        }
        socket.nextCall("session.list")
        socket.ws.close(1001, "going away")
        val (lost, answer) = next.await()
        assertTrue(lost.toString(), lost is GatewayUnavailableException)
        assertEquals(JsonObject(emptyMap()), answer)
        val now = gateway.connection.value as GatewayConnection.Ready
        assertTrue("answered on the new connection", now.generation > first.generation)
    }

    /**
     * Drops and [HermesGateway.disconnect] racing waiting calls (the race shipped in 1.4.0). The old
     * failPending copied the calls with `toList()`, which throws NoSuchElementException when the map
     * empties between its size check and its iterator: out of disconnect(), or out of the connection
     * loop into the scope's handler (a crash on Android: the app's scopes have no handler). It then
     * cleared the map, so a call that registered after the copy was never failed and waited out its
     * whole timeout.
     *
     * Each round, one call waits on the wire (a single entry, the size `toList()` tripped on), calls
     * with 0-2 ms timeouts come and go, and [RACE_SWEEPERS] threads start at once: one drops the
     * socket from the server, the rest call disconnect() (which also cancels the loop, whose cleanup
     * sweeps too), and one of them then calls connect(). Round 0 parks [RACE_GROW_TABLE] calls first
     * so the map's table grows (it never shrinks): `clear()` then walks the whole table before it
     * lowers the count, which widens the window enough that the old code failed 9 runs in 10.
     * The fake then answers [HANG], and a disconnect() and connect() settle the round, so every call
     * must end (failed by a sweep, timed out, or answered) well inside [RACE_RPC_TIMEOUT_MS].
     * Real dispatchers and sockets; about 2 s.
     */
    @Test fun dropsAndDisconnectsRacingCallsNeverThrowOrStrandACall() {
        val quick = FakeHermesServer(noDelay = true) // a reconnect per round: 40 ms less each
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
        val config = quick.config().copy(rpcTimeoutMs = RACE_RPC_TIMEOUT_MS, reconnectBaseDelayMs = 1, reconnectMaxDelayMs = 5)
        val racy = HermesGateway(config, tokens, scope = raceScope)
        val disconnectErrors = CopyOnWriteArrayList<Throwable>()
        val ended = CopyOnWriteArrayList<Pair<Throwable?, Long>>()
        val answer = AtomicBoolean(false)
        val hung = MutableStateFlow(0)
        quick.responders[HANG] = { s, id, _ ->
            if (answer.get()) runCatching { s.reply(id, "{}") } else hung.update { it + 1 }
        }
        fun disconnect() = runCatching { racy.disconnect() }.onFailure { disconnectErrors += it }
        fun dropServerSockets() = generateSequence { quick.sockets.poll() }.forEach { it.ws.close(1001, "going away") }
        fun CoroutineScope.timedCall(timeoutMs: Long) = launch {
            val started = System.nanoTime()
            val error = try {
                racy.call(HANG, JsonObject(emptyMap()), timeoutMs)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }
            ended += error to (System.nanoTime() - started) / 1_000_000
        }
        try {
            runBlocking(Dispatchers.Default) {
                repeat(RACE_ROUNDS) { round ->
                    answer.set(false)
                    racy.awaitReady()
                    val before = hung.value
                    val waiting = if (round == 0) RACE_GROW_TABLE else 1
                    val calls = MutableList(waiting) { timedCall(RACE_RPC_TIMEOUT_MS) }
                    withTimeout(2_000) { hung.first { it >= before + waiting } }
                    calls += List(RACE_SHORT_CALLS) { i -> timedCall(i % 3L) }
                    val arrived = AtomicInteger()
                    List(RACE_SWEEPERS) { j ->
                        thread {
                            arrived.incrementAndGet()
                            while (arrived.get() < RACE_SWEEPERS) Thread.onSpinWait()
                            when (j) {
                                0 -> dropServerSockets()
                                1 -> disconnect().also { racy.connect() }
                                else -> disconnect()
                            }
                        }
                    }.forEach { it.join() }
                    answer.set(true)
                    disconnect()
                    racy.connect()
                    val settled = withTimeoutOrNull(RACE_RPC_TIMEOUT_MS / 4) { calls.joinAll() }
                    if (settled == null) fail("round $round: a call was never completed; uncaught=$uncaught disconnect=$disconnectErrors")
                }
            }
        } finally {
            disconnect()
            raceScope.cancel()
            quick.close()
        }
        assertEquals("uncaught", emptyList<Throwable>(), uncaught)
        assertEquals("disconnect() threw", emptyList<Throwable>(), disconnectErrors)
        assertEquals(RACE_ROUNDS * (1 + RACE_SHORT_CALLS) + RACE_GROW_TABLE - 1, ended.size)
        ended.forEach { (error, ms) ->
            assertTrue(error.toString(), error == null || error is GatewayUnavailableException)
            assertTrue("took $ms ms", ms < RACE_RPC_TIMEOUT_MS / 4)
        }
    }

    @Test fun refusedHandshakesStopAfterTheLimit() = test {
        fake.refuseUpgrade = true
        val stopped = gateway.run {
            connect()
            connection.first { it is GatewayConnection.Stopped } as GatewayConnection.Stopped
        }
        assertFalse(stopped.signInRequired)
        assertEquals(3, fake.tickets.get())
    }

    @Test fun eventsAreTyped() = test {
        gateway.awaitReady()
        val socket = fake.nextSocket()
        // UNDISPATCHED: both collectors are subscribed before the server sends anything.
        val collected = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.MessageComplete>().first() }
        val start = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.ToolStart>().first() }
        socket.event("tool.start", "s1", """{"tool_id":"call_1","name":"web_search","context":"weather","args":{"q":"x"}}""")
        socket.event("message.complete", "s1", """{"text":{"rich":true},"status":"interrupted","usage":{},"response_transformed":true}""")
        assertEquals(GatewayEvent.ToolStart("s1", "call_1", "web_search", "weather"), start.await())
        val complete = collected.await()
        assertEquals("""{"rich":true}""", complete.text)
        assertEquals(TurnStatus.Interrupted, complete.status)
        assertTrue(complete.responseTransformed)
    }

    @Test fun approvalIsSurfacedAndAnsweredByResponseFrame() = test {
        gateway.awaitReady()
        val socket = fake.nextSocket()
        val event = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.ApprovalRequested>().first() }
        socket.send(
            """{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"approval","params":{"session_id":"s1","request_id":"apr-1",""" +
                """"command":"rm -rf ***","description":"Delete build output","choices":["once","session","always","deny"],"tool_name":"terminal"}}""",
        )
        val request = event.await().request
        assertEquals("srq-0123456789ab", request.id)
        assertEquals("apr-1", request.requestId)
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always, ApprovalChoice.Deny), request.choices)
        // Nothing is sent until the user answers: after a fence round trip, anything sent before it has arrived.
        gateway.fence()
        assertTrue(socket.frames.none { it.string("id") == "srq-0123456789ab" })
        gateway.answerApproval(request, ApprovalChoice.Deny)
        val reply = socket.nextResponse("srq-0123456789ab")
        assertEquals("""{"choice":"deny","all":false}""", reply["result"].toString())
        assertNull(reply["method"])
    }

    @Test fun clarifyAnswersCarryQuestionIds() = test {
        gateway.awaitReady()
        val socket = fake.nextSocket()
        val event = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.ClarifyRequested>().first() }
        socket.send(
            """{"jsonrpc":"2.0","id":"srq-c1","method":"clarify","params":{"session_id":"s1","questions":[""" +
                """{"qid":"q1","question":"Which repo?","choices":["a","b"],"multi_select":false},{"qid":"q2","question":"Why?"}]}}""",
        )
        val request = event.await().request
        assertEquals(listOf("q1", "q2"), request.questions.map { it.qid })
        assertEquals(listOf("a", "b"), request.questions[0].choices)
        gateway.answerClarify(request, mapOf("q1" to "a", "q2" to null))
        assertEquals("""{"answers":{"q1":"a","q2":null}}""", socket.nextResponse("srq-c1")["result"].toString())
    }

    @Test fun requestsThisClientCannotAnswerAreDeclinedAtOnce() = test {
        gateway.awaitReady()
        val socket = fake.nextSocket()
        socket.send("""{"jsonrpc":"2.0","id":"srq-s1","method":"sudo","params":{"session_id":"s1","command":"apt"}}""")
        socket.send("""{"jsonrpc":"2.0","id":"srq-p1","method":"preview.read","params":{"session_id":"s1"}}""")
        assertEquals("-32601", socket.nextResponse("srq-s1")["error"]!!.jsonObject["code"].toString())
        assertEquals("4404", socket.nextResponse("srq-p1")["error"]!!.jsonObject["code"].toString())
    }

    @Test fun rpcErrorsAreTyped() = test {
        fake.responders["prompt.submit"] = { s, id, _ -> s.error(id, 4001, "session not found") }
        gateway.awaitReady()
        try {
            gateway.submitPrompt("gone", "hi")
            fail()
        } catch (e: HermesRpcException) {
            assertTrue(e.isSessionNotFound)
            assertEquals("prompt.submit", e.method)
        }
    }

    @Test fun methodShapes() = test {
        fake.responders["session.create"] = { s, id, _ ->
            s.reply(id, """{"session_id":"ab12cd34","stored_session_id":"20261012_101010_abcdef","message_count":0,"messages":[],"info":{"model":"m1","lazy":true}}""")
        }
        fake.responders["session.resume"] = { s, id, _ ->
            s.reply(id, """{"session_id":"ef56","resumed":"20261012_101010_abcdef","session_key":"20261012_101010_abcdef","message_count":4,"messages":[],"messages_omitted":true,"running":true,"status":"streaming","info":{},"inflight":null,""" +
                """"open_requests":[{"id":"srq-r1","method":"approval","params":{"session_id":"ef56","request_id":"apr-9","command":"ls","choices":["once","deny"]}}]}""")
        }
        fake.responders["prompt.submit"] = { s, id, _ -> s.reply(id, """{"status":"streaming"}""") }
        fake.responders["session.interrupt"] = { s, id, _ -> s.reply(id, """{"status":"interrupted"}""") }
        gateway.awaitReady()
        val socket = fake.nextSocket()

        val created = gateway.createSession(SessionOptions(title = "Folio"))
        assertEquals(CreatedSession("ab12cd34", "20261012_101010_abcdef", "m1"), created)
        assertEquals("""{"title":"Folio"}""", socket.nextCall("session.create")["params"].toString())

        val redelivered = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.filterIsInstance<GatewayEvent.ApprovalRequested>().first() }
        val resumed = gateway.resumeSession(created.storedSessionId)
        assertEquals("ef56", resumed.sessionId)
        assertTrue(resumed.running)
        assertEquals(1, resumed.openRequests)
        assertEquals("apr-9", redelivered.await().request.requestId)
        assertEquals("""{"session_id":"20261012_101010_abcdef","omit_messages":true}""", socket.nextCall("session.resume")["params"].toString())

        assertEquals(SubmitStatus.Streaming, gateway.submitPrompt("ef56", "hello").status)
        assertEquals("""{"session_id":"ef56","text":"hello"}""", socket.nextCall("prompt.submit")["params"].toString())
        gateway.submitPrompt("ef56", "what time is it", PromptSurface.VoiceLive, "User: what time is it")
        assertEquals(
            """{"session_id":"ef56","text":"what time is it","surface":"voice-live","voice_context":"User: what time is it"}""",
            socket.nextCall("prompt.submit")["params"].toString(),
        )
        assertEquals("interrupted", gateway.interrupt("ef56"))
    }

    @Test fun submitStatusesAreMapped() {
        fun status(json: String) = GatewayFrames.submitStatus(HermesJson.parseToJsonElement(json).jsonObject)
        assertEquals(SubmitStatus.Queued, status("""{"status":"queued"}"""))
        assertEquals(SubmitStatus.Redirected, status("""{"status":"redirected","text":"x"}"""))
        assertEquals(SubmitStatus.Steered, status("""{"status":"steered"}"""))
        assertEquals(SubmitStatus.VoiceStopped, status("""{"voice_stopped":true}"""))
        assertEquals(SubmitStatus.Unknown, status("""{}"""))
    }

    private companion object {
        /** A method the fake leaves unanswered until the race test settles a round. */
        const val HANG = "test.hang"
        // The race test's sizes: on the old code, 100 rounds hit the race several times per run.
        const val RACE_ROUNDS = 100
        const val RACE_SHORT_CALLS = 6
        const val RACE_GROW_TABLE = 1024
        const val RACE_SWEEPERS = 6
        const val RACE_RPC_TIMEOUT_MS = 10_000L
    }
}
