package com.folio.hermes

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesConversationTest {
    private val fake = FakeHermesServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = fake.config()
    private val gateway = HermesGateway(config, FakeTokens(), scope = scope)
    private val created = AtomicInteger()
    private val stored = mutableListOf<String>()
    private val conversation = HermesConversation(gateway, config, onStoredSessionChanged = { stored += it })
    private val serverRunning: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Before fun setUp() {
        fake.responders["session.create"] = { s, id, _ ->
            val n = created.incrementAndGet()
            s.reply(id, """{"session_id":"rt$n","stored_session_id":"stored-$n","message_count":0,"messages":[],"info":{}}""")
        }
        // Like the server: an interrupt only produces a completion when a turn is running.
        fake.responders["session.interrupt"] = { s, id, params ->
            val sid = params.string("session_id")!!
            s.reply(id, """{"status":"interrupted"}""")
            if (serverRunning.remove(sid)) s.event("message.complete", sid, """{"text":"","status":"interrupted"}""")
        }
    }

    @After fun tearDown() {
        gateway.disconnect()
        scope.cancel()
        fake.close()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun FakeHermesServer.ServerSide.reply(id: kotlinx.serialization.json.JsonElement, result: String, then: FakeHermesServer.ServerSide.() -> Unit) {
        reply(id, result)
        then()
    }

    @Test fun turnStreamsTextToolsAndCompletion() = test {
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.event("message.start", sid) // the run thread can beat the ack
            s.reply(id, """{"status":"streaming"}""") {
                event("message.delta", sid, """{"text":"Sunny "}""")
                event("tool.start", sid, """{"tool_id":"c1","name":"web_search"}""")
                event("message.delta", "other-session", """{"text":"NOT OURS"}""")
                event("tool.complete", sid, """{"tool_id":"c1","name":"web_search","summary":"1 result"}""")
                event("message.delta", sid, """{"text":"today."}""")
                event("message.complete", sid, """{"text":"Sunny today.","status":"complete","usage":{}}""")
            }
        }
        val updates = conversation.turn("weather?").toList()
        assertEquals(
            listOf(
                TurnUpdate.Accepted("rt1", SubmitStatus.Streaming),
                TurnUpdate.Started,
                TurnUpdate.Text("Sunny ", "Sunny "),
                TurnUpdate.ToolStarted("c1", "web_search", "web search"),
                TurnUpdate.ToolCompleted("c1", "web_search", "1 result"),
                TurnUpdate.Text("today.", "Sunny today."),
                TurnUpdate.Completed("Sunny today.", TurnStatus.Complete, null),
            ),
            updates,
        )
        assertEquals(listOf("stored-1"), stored)
        assertTrue(!conversation.isBusy)
    }

    @Test fun anOlderTurnsCompletionIsIgnored() = test {
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.event("message.complete", sid, """{"text":"old answer","status":"interrupted"}""")
            s.reply(id, """{"status":"streaming"}""") {
                event("message.start", sid)
                event("message.complete", sid, """{"text":"new answer"}""")
            }
        }
        val last = conversation.turn("hi").toList().last()
        assertEquals(TurnUpdate.Completed("new answer", TurnStatus.Complete, null), last)
    }

    @Test fun emptyFinalTextKeepsTheStream() = test {
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.reply(id, """{"status":"streaming"}""") {
                event("message.start", sid)
                event("message.delta", sid, """{"text":"streamed"}""")
                event("message.complete", sid, """{"text":""}""")
            }
        }
        assertEquals(TurnUpdate.Completed("streamed", TurnStatus.Complete, null), conversation.turn("hi").toList().last())
    }

    @Test fun queuedSkipsTheRunningTurnsCompletion() = test {
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.reply(id, """{"status":"queued"}""") {
                event("message.delta", sid, """{"text":"old tail"}""")
                event("message.complete", sid, """{"text":"old","status":"interrupted"}""")
                event("message.start", sid)
                event("message.complete", sid, """{"text":"queued answer"}""")
            }
        }
        val updates = conversation.turn("hi").toList()
        assertEquals(TurnUpdate.Completed("queued answer", TurnStatus.Complete, null), updates.last())
        assertTrue(updates.none { it is TurnUpdate.Text })
    }

    @Test fun reapedRuntimeIsResumedAndRetried() = test {
        val submits = AtomicInteger()
        fake.responders["prompt.submit"] = { s, id, params ->
            if (submits.incrementAndGet() == 1) {
                s.error(id, 4001, "session not found")
            } else {
                val sid = params.string("session_id")
                s.reply(id, """{"status":"streaming"}""") {
                    event("message.start", sid)
                    event("message.complete", sid, """{"text":"ok"}""")
                }
            }
        }
        fake.responders["session.resume"] = { s, id, params ->
            assertEquals("stored-1", params.string("session_id"))
            s.reply(id, """{"session_id":"rt-resumed","session_key":"stored-1","running":false,"status":"idle","message_count":2,"messages":[]}""")
        }
        val updates = conversation.turn("hi").toList()
        assertEquals(TurnUpdate.Accepted("rt-resumed", SubmitStatus.Streaming), updates.first())
        assertEquals(TurnUpdate.Completed("ok", TurnStatus.Complete, null), updates.last())
    }

    @Test fun busyIsRetried() = test {
        val submits = AtomicInteger()
        fake.responders["prompt.submit"] = { s, id, params ->
            if (submits.incrementAndGet() < 3) {
                s.error(id, 4009, "session busy")
            } else {
                val sid = params.string("session_id")
                s.reply(id, """{"status":"streaming"}""") {
                    event("message.start", sid)
                    event("message.complete", sid, """{"text":"done"}""")
                }
            }
        }
        assertEquals(TurnUpdate.Completed("done", TurnStatus.Complete, null), conversation.turn("hi").toList().last())
        assertEquals(3, submits.get())
    }

    @Test fun leaseRefusalFails() = test {
        fake.responders["prompt.submit"] = { s, id, _ -> s.error(id, 4090, "session already has a live owner", """{"reason":"SESSION_NOT_OWNED"}""") }
        val last = conversation.turn("hi").toList().last()
        assertTrue(last is TurnUpdate.Failed)
    }

    @Test fun errorBeforeStartFailsTheTurn() = test {
        fake.responders["prompt.submit"] = { s, id, params ->
            s.reply(id, """{"status":"streaming"}""") {
                event("error", params.string("session_id"), """{"message":"agent init failed"}""")
            }
        }
        assertEquals(TurnUpdate.Failed("agent init failed"), conversation.turn("hi").toList().last())
    }

    @Test fun approvalPassesThroughTheTurn() = test {
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.reply(id, """{"status":"streaming"}""") {
                event("message.start", sid)
                send("""{"jsonrpc":"2.0","id":"srq-a","method":"approval","params":{"session_id":"$sid","request_id":"apr-1","command":"rm x","description":"","choices":["once","deny"]}}""")
                event("request.cancel", sid, """{"id":"srq-a","method":"approval","reason":"timeout"}""")
                event("message.complete", sid, """{"text":"Stopped.","status":"complete"}""")
            }
        }
        val updates = conversation.turn("clean up").toList()
        val approval = updates.filterIsInstance<TurnUpdate.ApprovalNeeded>().single().request
        assertEquals("apr-1", approval.requestId)
        assertEquals(TurnUpdate.RequestWithdrawn("srq-a", "timeout"), updates.filterIsInstance<TurnUpdate.RequestWithdrawn>().single())
    }

    @Test fun droppedSocketWithAFinishedTurnFailsAndTheNextTurnRebinds() = test {
        val submits = AtomicInteger()
        fake.responders["session.resume"] = { s, id, params ->
            s.reply(id, """{"session_id":"rt-again","session_key":"${params.string("session_id")}","running":false,"status":"idle","messages":[]}""")
        }
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            if (submits.incrementAndGet() == 1) {
                s.reply(id, """{"status":"streaming"}""") {
                    event("message.start", sid)
                    ws.close(1001, "restart")
                }
            } else {
                s.reply(id, """{"status":"streaming"}""") {
                    event("message.start", sid)
                    event("message.complete", sid, """{"text":"back"}""")
                }
            }
        }
        val first = conversation.turn("one").toList().last()
        assertEquals(TurnUpdate.Failed("Lost the connection to Hermes."), first)
        val second = conversation.turn("two").toList()
        assertEquals(TurnUpdate.Accepted("rt-again", SubmitStatus.Streaming), second.first())
        assertEquals(TurnUpdate.Completed("back", TurnStatus.Complete, null), second.last())
        assertEquals(1, created.get())
    }

    @Test fun droppedSocketMidTurnResumesAndKeepsReading() = test {
        fake.responders["session.resume"] = { s, id, params ->
            s.reply(id, """{"session_id":"rt-again","session_key":"${params.string("session_id")}","running":true,"status":"streaming","messages":[],""" +
                """"open_requests":[{"id":"srq-x","method":"approval","params":{"session_id":"rt-again","request_id":"apr-2","command":"deploy","choices":["once","deny"]}}]}""")
            s.event("message.delta", "rt-again", """{"text":" world"}""")
            s.event("message.complete", "rt-again", """{"text":"hello world"}""")
        }
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.reply(id, """{"status":"streaming"}""") {
                event("message.start", sid)
                event("message.delta", sid, """{"text":"hello"}""")
                ws.close(1001, "network blip")
            }
        }
        val updates = conversation.turn("long job").toList()
        assertEquals(TurnUpdate.Completed("hello world", TurnStatus.Complete, null), updates.last())
        assertEquals("apr-2", updates.filterIsInstance<TurnUpdate.ApprovalNeeded>().single().request.requestId)
        assertEquals(1, created.get())
    }

    private fun streamWithoutFinishingFirst() {
        val submits = AtomicInteger()
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")!!
            s.reply(id, """{"status":"streaming"}""") {
                event("message.start", sid)
                if (submits.incrementAndGet() == 1) {
                    serverRunning.add(sid)
                    event("message.delta", sid, """{"text":"partial"}""")
                } else {
                    event("message.complete", sid, """{"text":"fresh"}""")
                }
            }
        }
    }

    private suspend fun methods() = fake.sockets.peek()!!.frames.mapNotNull { it.string("method") }.filter { it != "gateway.ping" }

    @Test fun interruptFirstStopsARunningTurnAndWaitsForIt() = test {
        streamWithoutFinishingFirst()
        conversation.turn("first").take(3).toList() // the caller gives up mid-stream; the server keeps running
        assertTrue(conversation.isBusy)
        val updates = conversation.turn("new question", interruptFirst = true).toList()
        assertEquals(TurnUpdate.Completed("fresh", TurnStatus.Complete, null), updates.last())
        assertEquals(listOf("client.capabilities", "session.create", "prompt.submit", "session.interrupt", "prompt.submit"), methods())
    }

    @Test fun anIdleSessionIsNotInterrupted() = test {
        streamWithoutFinishingFirst()
        conversation.turn("first").take(3).toList()
        // The server finishes the abandoned turn on its own.
        val sid = conversation.ensureSession()
        serverRunning.remove(sid)
        // The gateway clears the running flag before it emits the event, so the event is the sync point.
        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filterIsInstance<GatewayEvent.MessageComplete>().first { it.sessionId == sid }
        }
        fake.sockets.peek()!!.event("message.complete", sid, """{"text":"partial done"}""")
        completed.await()
        assertFalse(conversation.isBusy)
        val started = System.nanoTime()
        val updates = conversation.turn("next", interruptFirst = true).toList()
        assertEquals(TurnUpdate.Completed("fresh", TurnStatus.Complete, null), updates.last())
        assertTrue((System.nanoTime() - started) / 1_000_000 < config.interruptSettleMs)
        assertEquals(listOf("client.capabilities", "session.create", "prompt.submit", "prompt.submit"), methods())
    }

    @Test fun storedSessionThatCannotResumeStartsFresh() = test {
        fake.responders["session.resume"] = { s, id, _ -> s.error(id, 4007, "session not found") }
        fake.responders["prompt.submit"] = { s, id, params ->
            val sid = params.string("session_id")
            s.reply(id, """{"status":"streaming"}""") {
                event("message.start", sid)
                event("message.complete", sid, """{"text":"hi"}""")
            }
        }
        val again = HermesConversation(gateway, config, storedSessionId = "draft-never-prompted")
        assertEquals("rt1", again.ensureSession())
        assertEquals("stored-1", again.storedSessionId)
    }
}
