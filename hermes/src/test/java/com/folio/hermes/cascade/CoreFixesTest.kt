package com.folio.hermes.cascade

import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.conversation.DurableConversations
import com.folio.hermes.cascade.conversation.ReconnectRouter
import com.folio.hermes.cascade.ports.LiveOpenRouter
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.registry.DurableTaskRegistry
import com.folio.hermes.cascade.registry.TaskRecovery
import com.folio.hermes.cascade.routing.CeoHarness
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.cascade.routing.MemoRouting
import com.folio.hermes.cascade.routing.MemoSegmentHandler
import com.folio.hermes.cascade.store.ConversationDto
import com.folio.hermes.cascade.store.DurablePersistence
import com.folio.hermes.cascade.store.ExchangeDto
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.jsonBody
import com.folio.hermes.openrouter.jsonResponse
import com.folio.hermes.openrouter.openRouter
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec v2 §14 follow-ups owned by the core (items 4, 5, 12, 17, 18, 19), on the pure reducer and the store. */
class CoreFixesTest {
    private val newTask = arrayOf("action" to answer("new_task"), "split" to answer("one"), "line" to answer("start.lookup"))

    private fun durableHarness(memo: MemoRouting = MemoRouting(ZoneOffset.UTC)) = CeoHarness(
        conversation = DurableConversations(ReconnectRouter { tx, _ -> memo.begin(tx) }),
        memo = memo,
        registry = DurableTaskRegistry,
    )

    // ── item 4: Stop mid-action ─────────────────────────────────────────────────────────────

    @Test fun hangingUpMidActionHoldsTheTurnAndItIsCarriedOutOnceNotAsOfflineDictation() {
        val h = durableHarness()
        h.turn("check stock of RB2140", 0, *newTask)
        assertTrue("waiting on the briefs", h.s.actions.active?.waitingOn?.startsWith("plan:") == true)
        h.on(Event.Stop)
        val held = h.s.outbox.held.single()
        assertEquals("u1", held.turnId)
        assertTrue("carried, not dictated offline", held.turn.carried)
        assertTrue(h.s.tasks.tasks.isEmpty())

        h.on(Event.Start())
        h.on(Event.Network(true))
        assertTrue("never segmented as a memo", h.effects.filterIsInstance<Effect.Write>().none { it.system == MemoSegmentHandler.SYSTEM })
        val call = h.effects.filterIsInstance<Effect.CallJev>().last()
        h.on(Event.JevAnswered(call.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done")) + newTask), h.now))
        h.write(briefs(brief("Check stock of RB2140.", "stock check", "Checking.")))
        assertEquals(listOf("t1"), h.submits().map { it.taskId })
        assertFalse("no \"dictated offline\" note", h.submits().single().text.contains("Dictated offline"))
        assertEquals(0, h.s.outbox.size)
    }

    @Test fun hangingUpMidReplyDropsItAndAStartedTaskIsNeverHeldAgain() {
        val h = durableHarness()
        h.turn("what time is it", 0, "action" to answer("reply"))
        assertNotNull(h.s.actions.active)
        h.on(Event.Stop)
        assertEquals("a conversational turn is not resent", 0, h.s.outbox.size)

        val g = durableHarness()
        g.turn("check stock of RB2140", 0, *newTask)
        g.write(briefs(brief("Check stock of RB2140.", "stock check", "Checking.")))
        g.on(Event.Stop)
        assertEquals("its task exists: nothing to hold", 0, g.s.outbox.size)
    }

    // ── item 5: memo mode ends with the network ─────────────────────────────────────────────

    @Test fun memoModeClearsWhenTheNetworkReturnsWithNothingHeld() {
        val h = durableHarness()
        h.on(Event.Network(false))
        assertTrue(h.s.connection.memo)
        h.on(Event.Network(true))
        assertFalse(h.s.connection.memo)
        assertTrue(CascadeEvent.Memo(false) in h.emitted())
    }

    // ── item 18 (S-A asks of S-B logic) ─────────────────────────────────────────────────────

    @Test fun twoFailedSubmitsWithTheRegistrySayGaveUpOnce() {
        val h = durableHarness()
        h.turn("check stock of RB2140", 0, *newTask)
        h.write(briefs(brief("Check stock of RB2140.", "stock check", "Checking.")))
        repeat(2) {
            val submit = h.submits().last()
            h.on(Event.TaskUpdated("t1", submit.runId, TurnUpdate.Failed("unreachable")))
            h.on(Event.TaskRunEnded("t1", submit.runId))
        }
        assertEquals("one resend, then it gives up", 2, h.submits().size)
        assertEquals(SendState.NotSent, h.s.tasks["t1"]!!.send)
        h.drain()
        assertEquals(1, h.slotsSaid().count { it == AppLines.GAVE_UP })
    }

    @Test fun aHeldTurnStaysHeldWhenTheResendsJevFailsForAnyReasonButOffline() {
        val plain = CeoHarness() // C1 flush (no memo routing): the resend goes straight to Jev
        plain.on(Event.Network(false))
        plain.at(0, Event.SpeechStarted)
        plain.at(0, Event.Final("chase the Leeds invoice"))
        plain.at(0, Event.SpeechEnded)
        plain.at(1_500, Event.TimerFired(Timer(TurnRules.COMMIT, plain.s.turn.pauseSeq)))
        plain.at(2_000, Event.Network(true))
        val call = plain.effects.filterIsInstance<Effect.CallJev>().last()
        plain.at(2_100, Event.JevAnswered(call.seq, OpenRouterResult.Failed(FailureKind.Timeout), 2_100))
        assertEquals("never marked sent", listOf("u1"), plain.s.outbox.ids())
        assertNull(plain.s.flush)
        plain.drain()
        assertTrue(AppLines.GAVE_UP in plain.slotsSaid())
    }

    @Test fun aTaskQueuedOnThePhoneSurvivesARestartIsNeverResumedAndStartsWhenASlotFrees() {
        val store = MemoryStore()
        val queued = TaskRecord("t2", "second", 1_000, brief = "Do the second thing.", turnId = "u1", queued = true)
        val running = TaskRecord("t1", "first", 1_000, brief = "Do the first thing.", turnId = "u1", storedSessionId = "s1", send = SendState.Accepted, accepted = true, active = 1, runs = mapOf(1L to TaskRun(RunKind.Start, false, accepted = true)), events = listOf(TaskEvent.Started(1_000)))
        val before = State(settings = CascadeSettings(maxTasks = 1), tasks = TaskTable(listOf(running, queued), counter = 2, runCounter = 1))
        val stamped = before.copy(conversation = ConversationState(id = "c1"))
        DurablePersistence.save(before, stamped).filterIsInstance<Effect.Persist>().forEach { store.put(it.key, it.value) }

        val restored = DurablePersistence.restore(store, State(settings = CascadeSettings(maxTasks = 1)))
        val t2 = restored.tasks["t2"]!!
        assertEquals("kept as queued, not NotSent or Failed", TaskState.Running, t2.state)
        assertEquals(SendState.Sending, t2.send)
        assertTrue(t2.queued)
        assertEquals("never resumed", listOf("t1"), TaskRecovery.candidates(restored).map { it.id })

        val env = CascadeCore.env(PhraseBank.load())
        val reducer = CascadeReducer(env)
        val (started, fx) = reducer.reduce(restored, Event.Start(restored))
        assertTrue("no slot yet", fx.none { it is Effect.OpenTaskSession })
        val (afterEnd, fx2) = reducer.reduce(started, Event.TaskRunEnded("t1", 1L))
        assertEquals("t2", fx2.filterIsInstance<Effect.OpenTaskSession>().single().taskId)
        assertFalse(afterEnd.tasks["t2"]!!.queued)
    }

    // ── item 17 (S-A asks) ──────────────────────────────────────────────────────────────────

    @Test fun jevSeesUncertainWordsAndTheConversationSummaryWithinTheCap() {
        val h = CeoHarness()
        h.s = h.s.copy(conversation = h.s.conversation.copy(summary = "They run an optician's shop."))
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final("email Sapphire", listOf(Word("email", 0.9), Word("Sapphire", 0.2, listOf("Safilo")))))
        h.at(0, Event.SpeechEnded)
        val call = h.at(500, Event.TimerFired(Timer(TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        val state = call.request.state
        assertEquals("Sapphire", state["uncertain"]!!.jsonArray.single().jsonObject["heard"]!!.jsonPrimitive.content)
        assertEquals("They run an optician's shop.", state["conversation_summary"]!!.jsonPrimitive.content)
        // The cap still holds: the summary is the first thing dropped.
        val tight = CeoHarness(CascadeSettings(jevStateCapChars = 300))
        tight.s = tight.s.copy(conversation = tight.s.conversation.copy(summary = "x".repeat(500)))
        tight.at(0, Event.SpeechStarted)
        tight.at(0, Event.Final("email Sapphire"))
        tight.at(0, Event.SpeechEnded)
        val capped = tight.at(500, Event.TimerFired(Timer(TurnRules.EARLY, tight.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        assertTrue(capped.request.state.toString().length <= 300)
        assertNull(capped.request.state["conversation_summary"])
    }

    @Test fun marksAndQueuedTasksArePersistedAndComeBack() {
        val store = MemoryStore()
        val before = State()
        val after = before.copy(
            conversation = ConversationState(id = "c1"),
            marks = mapOf("u1" to setOf("summary:t1"), "task:t1" to setOf("ack:3:update.queued")),
            tasks = TaskTable(listOf(TaskRecord("t1", "job", 0, queued = true)), counter = 1),
        )
        DurablePersistence.save(before, after).filterIsInstance<Effect.Persist>().forEach { store.put(it.key, it.value) }
        val back = DurablePersistence.restore(store, State())
        assertEquals(after.marks, back.marks)
        assertTrue(back.tasks["t1"]!!.queued)
        // A change to the marks alone is saved.
        val changed = after.copy(marks = after.marks + ("u2" to setOf("x")))
        assertTrue(DurablePersistence.save(after, changed).any { it is Effect.Persist && it.key == StoreKeys.CORE })
    }

    @Test fun theWholeWriterAnswerMayRunTo4096TokensAndTheWorstCaseFits() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tts = FakeTts()
        try {
            server.enqueue(jsonResponse("""{"choices":[{"message":{"content":"{}"}}]}"""))
            LiveOpenRouter(server.openRouter(), voice = tts.voice()).write(Briefs.SYSTEM, "prompt")
            assertEquals(LiveOpenRouter.WRITE_MAX_TOKENS, server.takeRequest().jsonBody()["max_tokens"]!!.jsonPrimitive.int)
        } finally {
            server.close()
        }
        // Worst case for four briefs: every line long, every field filled (about 4 characters a token).
        val line = "x".repeat(300)
        val four = briefs(*Array(4) { """{"brief":"Goal: $line\nConstraints: $line\nDone when: $line","label":"four word label here","summary":"${"word ".repeat(12)}"}""" }, notes = line)
        assertTrue("4 briefs ≈ ${four.length / 4} tokens", four.length / 4 < LiveOpenRouter.WRITE_MAX_TOKENS / 2)
        // Offline segmentation of a 24,000-character chunk of 20-character sentences: 1,200 one-number groups.
        val groups = (1..1_200).joinToString(",", "{\"groups\":[", "]}") { "[$it]" }
        assertTrue("segmentation ≈ ${groups.length / 2} tokens", groups.length / 2 < LiveOpenRouter.WRITE_MAX_TOKENS)
    }

    // ── item 12: notification text ──────────────────────────────────────────────────────────

    @Test fun notifyInfoGivesTheLabelAndAOneLineHeadline() {
        val long = "## Result\n**Fourteen** RB2140 in stock at Leeds, " + "and more detail ".repeat(20) + ". Second sentence."
        val tasks = TaskTable(
            listOf(
                TaskRecord("t1", "stock check", 0, state = TaskState.Done, result = long),
                TaskRecord("t2", "supplier email", 0, waiting = true),
                TaskRecord("t3", "newsletter", 0, state = TaskState.Failed, lastFailed = true),
                TaskRecord("t4", "Leeds invoice", 0, send = SendState.NotSent, state = TaskState.Failed),
            ),
        )
        val s = State(tasks = tasks)
        val done = notifyInfo(s, "t1", DurableTaskRegistry.NOTIFY_DONE)!!
        assertEquals("stock check", done.label)
        assertTrue(done.headline.startsWith("Result."))
        val first = notifyInfo(State(tasks = TaskTable(listOf(TaskRecord("t1", "x", 0, state = TaskState.Done, result = "Fourteen in stock, " + "and more ".repeat(30))))), "t1")!!
        assertTrue(first.headline.length <= Notices.HEADLINE_CHARS)
        assertTrue(first.headline.endsWith("…"))
        assertEquals("Needs you: open it to answer.", notifyInfo(s, "t2", DurableTaskRegistry.NOTIFY_NEEDS_YOU)!!.headline)
        assertEquals("Didn't finish. Tap to retry.", notifyInfo(s, "t3", DurableTaskRegistry.NOTIFY_FAILED)!!.headline)
        assertEquals("Not sent. Tap to retry.", notifyInfo(s, "t4")!!.headline)
        assertNull(notifyInfo(s, "t9"))
    }

    // ── item 19: open a past conversation ───────────────────────────────────────────────────

    @Test fun openingAPastConversationMakesItCurrentAndFilesTheOldOne() {
        val store = MemoryStore()
        val past = ConversationDto(id = "c100", startedWallMs = 100, endedWallMs = 200, summary = "Stock talk.", recent = listOf(ExchangeDto("check stock", "On it.")))
        store.put(StoreKeys.conversation("c100"), StoreCodec.encode(ConversationDto.serializer(), past))
        val policy = DurableConversations()
        val loaded = policy.load(store, "c100")!!
        assertEquals("Stock talk.", loaded.summary)
        assertNull(policy.load(store, "c404"))

        val h = CeoHarness(conversation = policy)
        h.s = h.s.copy(conversation = ConversationState(id = "c500"))
        val fx = h.on(Event.OpenConversation("c100", loaded))
        assertEquals("c100", h.s.conversation.id)
        assertEquals("Stock talk.", h.s.conversation.summary)
        assertEquals(listOf(Exchange("check stock", "On it.")), Tx(h.s, h.env).recentExchanges())
        assertTrue("the old one is filed in the history", fx.any { it is Effect.Persist && it.key == StoreKeys.conversation("c500") && it.value != null })
        assertTrue("the reopened one leaves the history", Effect.Persist(StoreKeys.conversation("c100"), null) in fx)
    }

    @Test fun talkingWithTheResumeCardUpTakesItsDefaultAndReleasesWaitingResults() {
        val h = CeoHarness(conversation = DurableConversations())
        h.s = h.s.copy(
            conversation = ConversationState(id = "c1", resume = ResumeCandidate("c1", 0, 1, 0, 0, ResumeChoice.Continue)),
            tasks = TaskTable(listOf(TaskRecord("t1", "stock check", 0, state = TaskState.Done, result = "Fourteen in stock.", unread = true, accepted = true, send = SendState.Accepted)), counter = 1),
            offCall = OffCallState(results = listOf(TaskResult("t1", "stock check", "Fourteen in stock.", ok = true))),
        )
        h.turn("what's new", 0, "action" to answer("reply"))
        assertNull(h.s.conversation.resume)
        assertEquals("c1", h.s.conversation.id)
        assertEquals(1, h.emitted().count { it == CascadeEvent.ResultQueued("t1") })
    }

    @Test fun anUpdateWhileTheSessionOpensWaitsAndFollowsTheStartInTheSameSession() {
        val start = TaskTable().start("Check RB2140 stock.", "stock check", "", 4, 0, turnId = "u1")
        assertTrue(start.effects.single() is Effect.OpenTaskSession)
        val update = start.table.launch("t1", "and the Leeds store", "", RunKind.Queue, 10, turnId = "u2")
        assertTrue("waits for the session", update.effects.isEmpty())
        val opened = update.table.sessionOpened("t1", 1L, "s1")
        val submits = opened.effects.filterIsInstance<Effect.SubmitTask>()
        assertEquals(listOf("Check RB2140 stock.", "and the Leeds store"), submits.map { it.text })
        assertTrue(submits.all { it.storedSessionId == "s1" })
        assertTrue(opened.table["t1"]!!.runs.values.none { it.opening })
    }

    @Test fun theUiNamesTheTurnItsWordsBelongTo() {
        val h = CeoHarness()
        h.turn("email Sapphire", 0, "action" to answer("reply"))
        assertEquals("u1", UiProjection.project(h.s).turn.turnId)
        h.at(5_000, Event.SpeechStarted)
        h.at(5_000, Event.Partial("and"))
        assertNull("mid-speech the words are live", UiProjection.project(h.s).turn.turnId)
    }
}
