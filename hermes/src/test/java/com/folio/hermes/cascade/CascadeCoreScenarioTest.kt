package com.folio.hermes.cascade

import com.folio.hermes.TurnStatus
import com.folio.hermes.cascade.conversation.DurableConversations
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.DurableTaskRegistry
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.cascade.routing.CeoHooks
import com.folio.hermes.cascade.routing.MemoSegmentHandler
import com.folio.hermes.cascade.store.DurablePersistence
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.cascade.writer.Summaries
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole CEO path through [CascadeCore] (the factory `:live` uses) with real persistence:
 * CEO routing, the durable seams, one `TaskRecovery`. A fake Hermes server outlives the app
 * processes; the store is shared, so a second [App] on it is a restart. Virtual clock, scripted
 * OpenRouter, no network, no keys.
 */
class CascadeCoreScenarioTest {
    private val bank = PhraseBank.load()

    /** Jev by what was said; the writer by which job it is asked for. */
    private class Scripted(val inner: FakeOpenRouter) : OpenRouterPort by inner {
        val routes = linkedMapOf<String, Map<String, JevAnswer>>()
        val plans = linkedMapOf<String, String>()
        val segments = mutableListOf<String>()

        override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> {
            val latest = state["latest"]?.jsonPrimitive?.contentOrNull.orEmpty()
            delay(10)
            val answers = routes.entries.firstOrNull { latest.contains(it.key, ignoreCase = true) }?.value
                ?: mapOf("turn" to answer("done"), "action" to answer("reply"))
            return OpenRouterResult.Ok(answers)
        }

        override suspend fun write(system: String, prompt: String): OpenRouterResult<String> {
            delay(10)
            return when (system) {
                Briefs.SYSTEM -> {
                    val transcript = prompt.substringAfter("Transcript: \"").substringBefore("\"\n")
                    OpenRouterResult.Ok(plans.entries.first { transcript.contains(it.key) }.value)
                }
                MemoSegmentHandler.SYSTEM -> {
                    segments += prompt
                    OpenRouterResult.Ok("""{"groups":[[1]]}""")
                }
                Summaries.SYSTEM -> OpenRouterResult.Ok("They run the shop's tasks by voice.")
                else -> inner.write(system, prompt)
            }
        }
    }

    private class Net : Network {
        val state = MutableStateFlow(true)
        override val online = state
    }

    /** One app process on [store], talking to [server]. [kill] stops it dead before a matching effect. */
    private inner class App(val scope: TestScope, val store: Store, val server: FakeServer, kill: (Effect) -> Boolean = { false }) : AutoCloseable {
        val job = SupervisorJob()
        val runScope = CoroutineScope(scope.coroutineContext + job)
        val clock = TestClock(scope)
        val openRouter = Scripted(FakeOpenRouter(clock).apply { cached += bank.slots.flatMap { it.lines } })
        val hermes = ClientHermes(server)
        val net = Net()
        val audio = FakeAudioOut(clock)
        val events = mutableListOf<CascadeEvent>()
        val appEffects = mutableListOf<Effect>()
        var ended = false
        val core = CascadeCore.create(
            scope = runScope,
            ports = CascadePorts(openRouter, hermes, hermes, audio, store, clock, net),
            bank = bank,
            settings = { CascadeSettings() },
            telemetry = TelemetrySink.None,
            onEndCall = { ended = true },
            onAppEffect = { appEffects += it },
            zone = ZoneOffset.UTC,
            gate = EffectGate { !kill(it) },
            seed = 11,
        )
        val actor get() = core.actor
        val state get() = actor.state.value

        init {
            runScope.launch { actor.events.collect { events += it } }
            scope.runCurrent()
        }

        fun say(text: String, words: List<Word> = emptyList()) {
            actor.speechStarted()
            actor.send(Event.Final(text, words))
            actor.speechEnded()
            scope.advanceTimeBy(2_000)
            scope.runCurrent()
        }

        override fun close() = job.cancel()
    }

    private fun done(vararg pairs: Pair<String, String>) = mapOf("turn" to answer("done")) + pairs.associate { (k, v) -> k to answer(v) }

    private val dictation = "Three things. One, check stock of RB2140. Two, email Sapphire about the order. Three, draft the Friday newsletter."

    private fun App.script() {
        openRouter.routes["Three things"] = done("action" to "new_task", "split" to "several", "line" to "none", "clarity" to "minor_doubt")
        openRouter.routes["How's it going"] = done("action" to "status", "line" to "none", "enough" to "line_then_writer")
        openRouter.routes["Scrap the newsletter"] = done("action" to "cancel_task", "task" to "t3", "line" to "cancel.one", "enough" to "line_only")
        openRouter.routes["Leeds invoice"] = done("action" to "new_task", "split" to "one", "line" to "none")
        openRouter.routes["Kestrel"] = done("action" to "new_task", "split" to "one", "line" to "none")
        openRouter.plans["RB2140"] = briefs(
            brief("Check stock of RB2140.", "stock check", "Checking RB2140 stock."),
            brief("Email Sapphire about the order.", "supplier email", "Emailing about the order."),
            brief("Draft the Friday newsletter.", "newsletter", "Drafting the newsletter."),
        )
        openRouter.plans["Leeds invoice"] = briefs(brief("Chase the Leeds invoice.", "Leeds invoice", "Chasing the Leeds invoice."))
        openRouter.plans["Kestrel"] = briefs(brief("Count the Kestrel aviators left.", "Kestrel count", "Counting Kestrels."))
    }

    /** The words as the fast pass heard them: "Sapphire" is unsure, with "Safilo" as the other reading. */
    private fun words(text: String) = text.split(' ').map { w ->
        if (w == "Sapphire") Word(w, confidence = 0.3, alternatives = listOf("Safilo"), uncertain = true) else Word(w, confidence = 0.95)
    }

    @Test fun theCeoPathSurvivesEverythingWithRealPersistence() = runTest {
        val store = MemoryStore()
        val server = FakeServer()

        App(this, store, server, kill = { e -> e is Effect.SubmitTask && "Kestrel" in e.text }).use { app ->
            app.script()
            // The factory's env: CEO routing on the durable seams.
            assertSame(DurablePersistence, app.core.env.persistence)
            assertSame(DurableTaskRegistry, app.core.env.registry)
            assertTrue(app.core.env.conversation is DurableConversations)
            assertTrue(app.core.env.handlers.hooks is CeoHooks)
            app.core.start()
            runCurrent()

            // 1. Dictate, split into 3: three tasks, each its own session, each prompted once.
            app.say(dictation, words(dictation))
            advanceUntilIdle()
            assertEquals(listOf("t1", "t2", "t3"), app.state.tasks.tasks.map { it.id })
            assertEquals(3, server.sessions.size)
            assertEquals(3, server.prompts.size)
            assertTrue(server.prompts.all { p -> server.prompts.count { it == p } == 1 })
            assertTrue(app.state.tasks.tasks.all { it.send == SendState.Accepted && it.storedSessionId != null })

            // 2. One correction on screen: the misheard word goes to its task as a queued update, and is learned.
            val ui = app.actor.ui.value.turn
            assertEquals("u1", ui.turnId)
            val index = ui.words.indexOfFirst { it.text == "Sapphire" }
            app.actor.send(Event.WordChosen(ui.turnId!!, index, "Safilo"))
            advanceUntilIdle()
            assertTrue(Effect.LearnWord("Sapphire", "Safilo") in app.appEffects)
            val correction = server.sessionFor("Email Sapphire about the order.".let { b -> server.prompts.first { it.startsWith("Goal: $b") || it.contains(b) } })
            assertEquals("only the email task hears it", 2, correction.prompts.size)
            assertTrue(correction.prompts.last().contains("\"Sapphire\" should be \"Safilo\""))
            assertEquals(4, server.prompts.size)

            // 3. Status never submits (R-TASK-3).
            app.say("How's it going?")
            advanceUntilIdle()
            assertEquals(4, server.prompts.size)

            // 4. Cancel the newsletter.
            app.say("Scrap the newsletter.")
            advanceUntilIdle()
            assertEquals(TaskState.Cancelled, app.state.tasks["t3"]!!.state)
            assertTrue("t3" in app.hermes.interrupts)

            // 5. An offline memo: memo mode, held, nothing sent.
            app.net.state.value = false
            runCurrent()
            assertTrue(app.state.connection.memo)
            app.say("Note to self, chase the Leeds invoice.")
            advanceUntilIdle()
            assertEquals(1, app.state.outbox.size)
            assertEquals(4, server.prompts.size)

            // 6. Reconnect: segmented, routed, briefed as dictated offline; memo mode ends.
            app.net.state.value = true
            advanceUntilIdle()
            assertEquals(0, app.state.outbox.size)
            assertFalse(app.state.connection.memo)
            assertEquals(1, app.openRouter.segments.size)
            val leeds = server.prompts.single { "Leeds invoice" in it }
            assertTrue(leeds.contains("Dictated offline"))
            assertEquals(5, server.prompts.size)

            // 7. One more task, killed after its session id is stored and before its prompt leaves.
            app.say("And count the Kestrel aviators.")
            advanceUntilIdle()
            assertTrue(app.actor.killed)
            assertTrue(server.prompts.none { "Kestrel" in it })
        }

        // Meanwhile the stock check finishes on the server.
        val stock = server.sessionFor(server.prompts.first { "RB2140" in it })
        server.complete(stock.id, "Fourteen RB2140 in stock.")

        // 8. Restart: the Resume card shows; the result waits for it; the Kestrel task is sent once.
        App(this, store, server).use { app ->
            app.script()
            app.core.start()
            advanceUntilIdle()
            assertNotNull(app.state.conversation.resume)
            assertEquals("Fourteen RB2140 in stock.", app.state.tasks["t1"]!!.result)
            assertEquals(1, server.prompts.count { "Kestrel" in it })
            assertEquals(TaskState.Cancelled, app.state.tasks["t3"]!!.state)
            assertTrue("not over the Resume card", CascadeEvent.ResultQueued("t1") !in app.events)

            // 9. Resume (Continue): the same conversation, its recent turns, and the result is spoken.
            val conversation = app.state.conversation.id
            app.actor.send(Event.ResumeChosen(ResumeChoice.Continue))
            advanceUntilIdle()
            assertNull(app.state.conversation.resume)
            assertEquals(conversation, app.state.conversation.id)
            assertTrue(app.state.recent.isNotEmpty())
            assertEquals(1, app.events.count { it == CascadeEvent.ResultQueued("t1") })
            assertTrue(app.audio.played.isNotEmpty())

            // Never lost, never doubled, across everything.
            assertEquals(server.prompts.distinct(), server.prompts)
            assertEquals(6, server.prompts.size)
            assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), app.state.tasks.tasks.map { it.id })
            assertTrue(app.state.tasks.tasks.none { it.send == SendState.NotSent })
        }
    }

    /** Spec v2 §14 item 6: the off-call service restores without a call; tasks finish and notify, nothing is spoken. */
    @Test fun theOffCallRestoreRecoversAndNotifiesWithoutACall() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        App(this, store, server).use { app ->
            app.script()
            app.core.start()
            runCurrent()
            app.say("And count the Kestrel aviators.")
            advanceUntilIdle()
            assertEquals(1, server.prompts.size)
        }
        App(this, store, server).use { app ->
            app.core.restore()
            app.core.restore() // idempotent: one restore, one recovery
            advanceUntilIdle()
            assertTrue(app.state.restored)
            assertFalse(app.state.started)
            server.complete("s1", "Nine Kestrels left.")
            advanceUntilIdle()
            assertEquals("Nine Kestrels left.", app.state.tasks["t1"]!!.result)
            assertEquals(listOf(Effect.Notify(DurableTaskRegistry.NOTIFY_DONE, "t1")), app.appEffects.filterIsInstance<Effect.Notify>())
            val info = app.core.notifyInfo("t1", DurableTaskRegistry.NOTIFY_DONE)!!
            assertEquals("Kestrel count", info.label)
            assertEquals("Nine Kestrels left.", info.headline)
            assertTrue("no speech off-call", app.audio.played.isEmpty())
            assertEquals(1, server.prompts.size)
            // A call later continues from the same state.
            app.core.start()
            advanceUntilIdle()
            assertTrue(app.state.started)
            assertEquals(1, server.prompts.size)
        }
    }

    /** R-Q-1: building the cascade does nothing: no request, no store write. */
    @Test fun buildingItIsDark() = runTest {
        val store = MemoryStore()
        val server = FakeServer()
        App(this, store, server).use { app ->
            advanceUntilIdle()
            assertTrue(store.keys().isEmpty())
            assertTrue(server.sessions.isEmpty())
            assertFalse(app.state.started || app.state.restored)
        }
    }
}
