package com.folio.hermes.realtime

import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.FakeAudioOut
import com.folio.hermes.cascade.FakeOpenRouter
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.realtime.FakeRealtimeServer.Companion.type
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Grok Voice session against a fake realtime WebSocket server ([FakeRealtimeServer]) and the
 * real task API on fake Hermes: the session config, tool calls (outputs, then one
 * `response.create` after playback), result injection only when idle, barge-in, reconnect with
 * resumption, offline, and the off state (no key, no request).
 */
class GrokSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeRealtimeServer()
    private val hermes = FakeServer()
    private val telemetry = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val client = ClientHermes(hermes)
    private val core = CascadeCore.create(
        scope = scope,
        ports = CascadePorts(FakeOpenRouter(Clock.System), client, client, FakeAudioOut(Clock.System), MemoryStore(), Clock.System),
        bank = PhraseBank.load(), settings = { CascadeSettings() }, telemetry = TelemetrySink.None, zone = ZoneOffset.UTC,
    )
    private val playback = FakePlayback()
    private val ended = CountDownLatch(1)

    @Volatile private var online = true
    private var key: String? = "test-key"

    private fun session(): GrokSession {
        val config = GrokConfig(url = fake.url, voice = "ara", reconnectDelayMs = 10, keyterms = listOf("RB2140"))
        val sink = TelemetrySink { name, attrs, _ -> synchronized(telemetry) { telemetry += name to attrs } }
        val realtime = GrokRealtime(
            OkHttpClient(), key = { key }, config = { config },
            spec = { SessionSpec(GrokTools.INSTRUCTIONS, GrokTools.DEFINITIONS) }, scope = scope, online = { online }, telemetry = sink,
        )
        return GrokSession(realtime, core.tasks, playback, scope, { config }, onEndCall = { ended.countDown() }, telemetry = sink)
    }

    private fun connected(): GrokSession {
        fake.accept()
        val s = session()
        assertTrue(s.start())
        fake.awaitConnection()
        fake.take("session.update")
        runBlocking { withTimeout(5_000) { s.ui.first { it.link is LinkState.Open } } }
        return s
    }

    @After fun tearDown() {
        scope.cancel()
        fake.close()
    }

    @Test fun theSessionConfigIsSentOnSessionCreated() {
        fake.accept()
        val s = session()
        assertTrue(s.start())
        val request = fake.request()
        assertEquals("Bearer test-key", request.headers["Authorization"])
        assertEquals("grok-voice-latest", request.url.queryParameter("model"))
        val update = fake.take("session.update")["session"]!!.jsonObject
        assertEquals(GrokTools.INSTRUCTIONS, update["instructions"]!!.jsonPrimitive.content)
        assertEquals("ara", update["voice"]!!.jsonPrimitive.content)
        assertEquals(GrokTools.NAMES, update["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("function", update["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("server_vad", update["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val input = update["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("audio/pcm", input["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(24_000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("RB2140"), input["transcription"]!!.jsonObject["keyterms"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(24_000, update["audio"]!!.jsonObject["output"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, update["resumption"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        s.stop()
    }

    @Test fun toolCallsRunOnceAndOneResponseCreateFollowsThePlayback() {
        val s = connected()
        fake.responseCreated("r1")
        fake.audioDelta("r1", "a1", 200)
        fake.functionCall("r1", "call_1", GrokTools.START_TASK, """{"brief":"Check stock of RB2140 in Leeds.","label":"Stock check"}""")
        fake.functionCall("r1", "call_2", GrokTools.TASK_STATUS, "{}")
        // The same call again, the cookbook's way: deduped by call_id.
        fake.send("""{"type":"response.output_item.done","response_id":"r1","item":{"type":"function_call","call_id":"call_1","name":"start_task","arguments":"{}"}}""")
        fake.responseDone("r1")
        val first = fake.take("conversation.item.create")["item"]!!.jsonObject
        assertEquals("call_1", first["call_id"]!!.jsonPrimitive.content)
        assertTrue(first["output"]!!.jsonPrimitive.content.contains("\"task_id\":\"t1\""))
        assertEquals("call_2", fake.take("conversation.item.create")["item"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
        val mark = fake.mark()
        assertTrue("no response.create while Grok's audio still plays", fake.fence(mark).none { it.type == "response.create" })
        playback.drain()
        fake.take("response.create")
        val after = fake.fence(fake.mark())
        assertTrue("exactly one response.create", after.none { it.type == "response.create" })
        assertEquals("the brief reached Hermes once", listOf("Check stock of RB2140 in Leeds."), hermes.prompts)
        assertEquals(2, fake.log.count { it.type == "conversation.item.create" })
        s.stop()
    }

    @Test fun aFinishedTaskIsInjectedOnlyWhenGrokIsIdleAndThenMarkedHeard() {
        val s = connected()
        runBlocking { core.tasks.startTask("check stock", "Stock check", key = "k1") }
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.storedSessionId != null && hermes.prompts.isNotEmpty() } } }
        // Grok is mid-answer: the result must wait.
        fake.responseCreated("r1")
        fake.audioDelta("r1", "a1", 300)
        hermes.complete("s1", "Four in stock in Leeds. Two in York.")
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.result != null } } }
        assertTrue("nothing injected mid-response", fake.fence(fake.mark()).none { it.type == "conversation.item.create" })
        fake.responseDone("r1")
        assertTrue("nothing injected while the audio plays", fake.fence(fake.mark()).none { it.type == "conversation.item.create" })
        fake.speechStarted()
        fake.fence(fake.mark())
        playback.drain()
        assertTrue("nothing injected while the user speaks", fake.fence(fake.mark()).none { it.type == "conversation.item.create" })
        fake.speechStopped()
        val note = fake.take("conversation.item.create")["item"]!!.jsonObject
        assertEquals("system", note["role"]!!.jsonPrimitive.content)
        val text = note["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text, text.startsWith("Task t1 \"Stock check\" finished.") && "Four in stock in Leeds." in text)
        fake.take("response.create")
        assertTrue(core.actor.state.value.tasks["t1"]!!.unread)
        fake.responseCreated("r2")
        fake.responseDone("r2")
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.unread == false } } }
        s.stop()
    }

    @Test fun bargeInStopsPlaybackDropsTheRestAndTruncates() {
        val s = connected()
        fake.responseCreated("r1")
        fake.audioDelta("r1", "a1", 500)
        fake.fence(fake.mark())
        val before = playback.written.size
        assertFalse(playback.idle.value)
        playback.played = 120
        fake.speechStarted()
        val truncate = fake.take("conversation.item.truncate")
        assertEquals("a1", truncate["item_id"]!!.jsonPrimitive.content)
        assertEquals(120, truncate["audio_end_ms"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, playback.stops)
        fake.audioDelta("r1", "a1", 100)
        fake.fence(fake.mark())
        assertEquals("the cancelled response's late audio is dropped", before, playback.written.size)
        fake.responseDone("r1", "cancelled")
        fake.responseCreated("r2")
        fake.audioDelta("r2", "a2", 100)
        fake.fence(fake.mark())
        assertEquals("the next response plays", before + 1, playback.written.size)
        s.stop()
    }

    @Test fun endCallSendsItsOutputNoResponseAndHangsUpAfterTheGoodbye() {
        val s = connected()
        fake.responseCreated("r1")
        fake.audioDelta("r1", "a1", 400)
        fake.functionCall("r1", "call_9", GrokTools.END_CALL, "{}")
        fake.responseDone("r1")
        fake.take("conversation.item.create")
        assertEquals("not before the goodbye has played", 1L, ended.count)
        playback.drain()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(fake.fence(fake.mark()).none { it.type == "response.create" })
        s.stop()
    }

    @Test fun aDropReconnectsWithTheConversationIdAndOptsInAgain() {
        val s = connected()
        fake.request()
        fake.accept("conv_1")
        fake.socket!!.close(1011, "server going away")
        fake.awaitConnection()
        val again = fake.request()
        assertEquals("conv_1", again.url.queryParameter("conversation_id"))
        val update = fake.take("session.update")["session"]!!.jsonObject
        assertEquals(true, update["resumption"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        runBlocking { withTimeout(5_000) { s.ui.first { (it.link as? LinkState.Open)?.resumed == true } } }
        s.stop()
    }

    @Test fun aDropWhileOfflineWaitsForTheNetwork() {
        val s = connected()
        fake.request()
        online = false
        fake.socket!!.close(1011, "gone")
        runBlocking { withTimeout(5_000) { s.ui.first { it.link == LinkState.Offline } } }
        online = true
        fake.accept()
        s.networkBack()
        assertEquals("conv_1", fake.request().url.queryParameter("conversation_id"))
        runBlocking { withTimeout(5_000) { s.ui.first { it.link is LinkState.Open } } }
        s.stop()
    }

    @Test fun withoutAKeyNothingConnects() {
        key = null
        val s = session()
        assertFalse(s.start())
        runBlocking { withTimeout(5_000) { s.ui.first { it.link == LinkState.Failed(GrokRealtime.NO_KEY) } } }
        assertEquals(0, fake.server.requestCount)
        s.stop()
    }

    @Test fun telemetryCarriesNoContent() {
        val s = connected()
        fake.responseCreated("r1")
        fake.functionCall("r1", "call_1", GrokTools.START_TASK, """{"brief":"SECRET-BRIEF","label":"SECRET-LABEL"}""")
        fake.responseDone("r1")
        fake.take("conversation.item.create")
        s.stop()
        val dump = synchronized(telemetry) { telemetry.toString() }
        assertFalse(dump, "SECRET" in dump || "test-key" in dump)
    }
}
