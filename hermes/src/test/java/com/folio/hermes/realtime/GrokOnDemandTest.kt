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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grok on demand (ondemand slice) against the fake realtime server: park closes the socket and
 * keeps the conversation; a result waiting while parked is injected after the resume; resume uses
 * `conversation_id` inside xAI's 30-minute window and starts fresh with a context note after it,
 * or when the server could not keep the conversation.
 */
class GrokOnDemandTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeRealtimeServer()
    private val hermes = FakeServer()
    private val client = ClientHermes(hermes)
    private val core = CascadeCore.create(
        scope = scope,
        ports = CascadePorts(FakeOpenRouter(Clock.System), client, client, FakeAudioOut(Clock.System), MemoryStore(), Clock.System),
        bank = PhraseBank.load(), settings = { CascadeSettings() }, telemetry = TelemetrySink.None, zone = ZoneOffset.UTC,
    )
    private val playback = FakePlayback()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Volatile private var now = 1_000_000L
    private lateinit var realtime: GrokRealtime

    private fun connected(): GrokSession {
        val config = GrokConfig(url = fake.url, reconnectDelayMs = 10)
        val sink = TelemetrySink { name, attrs, _ -> synchronized(events) { events += name to attrs } }
        realtime = GrokRealtime(
            OkHttpClient(), key = { "test-key" }, config = { config },
            spec = { SessionSpec(GrokTools.INSTRUCTIONS, GrokTools.DEFINITIONS) }, scope = scope, telemetry = sink, clock = { now },
        )
        val s = GrokSession(realtime, core.tasks, playback, scope, { config }, onEndCall = {}, telemetry = sink, clock = { now })
        fake.accept()
        assertTrue(s.start())
        fake.awaitConnection()
        fake.take("session.update")
        runBlocking { withTimeout(5_000) { s.ui.first { it.link is LinkState.Open } } }
        fake.request()
        return s
    }

    @After fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private fun finishedTask() {
        runBlocking { core.tasks.startTask("check stock", "Stock check", key = "k1") }
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.storedSessionId != null && hermes.prompts.isNotEmpty() } } }
        hermes.complete("s1", "Four in stock in Leeds.")
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.result != null } } }
    }

    private fun awaitParked(s: GrokSession) = runBlocking { withTimeout(5_000) { s.ui.first { it.link == LinkState.Parked } } }

    private fun awaitOpen(s: GrokSession, resumed: Boolean) =
        runBlocking { withTimeout(5_000) { s.ui.first { (it.link as? LinkState.Open)?.resumed == resumed } } }

    private fun systemText(o: kotlinx.serialization.json.JsonObject): String =
        o["item"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    @Test fun parkClosesTheSocketAtOnceAndAResultWaitingMeanwhileIsToldAfterTheResume() {
        val s = connected()
        assertTrue(realtime.acceptsAudio)
        s.park()
        assertFalse("no audio once parked", realtime.acceptsAudio)
        assertEquals(LinkState.Parked, realtime.link.value)
        awaitParked(s)
        finishedTask()
        runBlocking { withTimeout(5_000) { while (!s.hasPendingNotice()) kotlinx.coroutines.yield() } }
        assertTrue(s.quiet())
        fake.accept()
        now += 5 * 60_000L
        assertTrue(s.resume { "unused: the conversation resumes" })
        assertEquals("conv_1", fake.request().url.queryParameter("conversation_id"))
        fake.awaitConnection()
        val update = fake.take("session.update")["session"]!!.jsonObject
        assertEquals("opted in again", "true", update["resumption"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        awaitOpen(s, resumed = true)
        val note = fake.take("conversation.item.create")
        val text = systemText(note)
        assertTrue(text, text.startsWith("Task t1 \"Stock check\" finished."))
        fake.take("response.create")
        assertTrue(synchronized(events) { events.none { it.first == "grok.context_note" } })
        s.stop()
    }

    @Test fun afterTheResumeWindowAFreshSessionGetsTheContextNoteFirst() {
        val s = connected()
        fake.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u1","transcript":"How many RB2140 in Leeds?"}""")
        fake.responseCreated("r1")
        fake.send("""{"type":"response.output_audio_transcript.delta","response_id":"r1","delta":"I'll check that."}""")
        fake.responseDone("r1")
        fake.fence(fake.mark())
        runBlocking { core.tasks.startTask("check stock", "Stock check", key = "k1") }
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.storedSessionId != null } } }
        s.park()
        awaitParked(s)
        now += GrokRealtime.RESUME_WINDOW_MS
        fake.accept("conv_2")
        assertTrue(s.resume { s.contextNote("Matt asked about stock.") })
        assertNull("too late to resume: no conversation_id", fake.request().url.queryParameter("conversation_id"))
        fake.awaitConnection()
        fake.take("session.update")
        awaitOpen(s, resumed = false)
        val text = systemText(fake.take("conversation.item.create"))
        assertTrue(text, text.startsWith("The voice session was reconnected"))
        assertTrue(text, "Summary so far: Matt asked about stock." in text)
        assertTrue(text, "t1 \"Stock check\" running" in text)
        val noted = synchronized(events) { events.first { it.first == "grok.context_note" } }
        assertEquals("only a length, never the text", setOf("chars"), noted.second.keys)
        s.stop()
    }

    @Test fun whenTheServerCouldNotKeepTheConversationTheNoteGoesInToo() {
        val s = connected()
        runBlocking { core.tasks.startTask("check stock", "Stock check", key = "k1") }
        runBlocking { withTimeout(5_000) { core.actor.state.first { it.tasks["t1"]?.storedSessionId != null } } }
        s.park()
        awaitParked(s)
        fake.accept("conv_new")
        assertTrue(s.resume { s.contextNote(null) })
        assertEquals("conv_1", fake.request().url.queryParameter("conversation_id"))
        fake.awaitConnection()
        fake.take("session.update")
        awaitOpen(s, resumed = false)
        val text = systemText(fake.take("conversation.item.create"))
        assertTrue(text, "t1 \"Stock check\" running" in text)
        assertTrue(synchronized(events) { events.any { it.first == "grok.resume_lost" } })
        s.stop()
    }

    @Test fun theContextNoteIsBounded() {
        val long = "x".repeat(5_000)
        val note = ContextNote.build(long, List(10) { SpokenExchange(long, long) }, emptyList())!!
        assertTrue(note.length <= ContextNote.MAX_CHARS)
        assertNull(ContextNote.build(null, emptyList(), emptyList()))
    }
}
