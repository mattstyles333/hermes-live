package com.folio.hermes.cascade.routing

import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.Timer
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.budget.CappedJevStateBudget.Companion.size
import com.folio.hermes.cascade.conversation.ConversationPolicy
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-STT-5/6 clarity and repair, R-CTX-1/3 budget, R-OFF-4 memo batches, through the pure `reduce`. */
class CeoTurnTest {
    private fun newTask(clarity: String = "clear", conf: Double = 0.9) =
        arrayOf("action" to answer("new_task"), "line" to answer("start.one"), "split" to answer("one"), "clarity" to answer(clarity, conf))

    /** The writer's answer for the doubted turn: one brief, and the word it doubts with Bill's question. */
    private fun doubted(word: String = "Sapphire", question: String = "Sapphire, right?") =
        """{"briefs":[${brief("Email Sapphire about forty units.", "supplier email", "emailing Sapphire about forty units")}],""" +
            """"repaired":"","notes":"","doubt":{"word":"$word","question":"$question"}}"""

    /** A key-doubted new task started, accepted, and everything said so far played. */
    private fun startDoubted(h: CeoHarness, conf: Double = 0.9): Effect.SubmitTask {
        h.turn("email Sapphire about forty units", 0, *newTask("key_doubt", conf))
        h.write(doubted())
        val submit = h.submits().single()
        h.accept(submit)
        h.drain()
        return submit
    }

    /** Hub decision 2026-10-14: a key doubt never blocks. The task starts at once; then Bill asks. */
    @Test fun keyDoubtStartsTheTaskAtOnceThenAsksOneQuestion() {
        val h = CeoHarness()
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final("email Sapphire about forty units", listOf(Word("email"), Word("Sapphire", 0.2, listOf("Safilo")), Word("about"), Word("forty"), Word("units"))))
        h.at(0, Event.SpeechEnded)
        val call = h.at(500, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(600, Event.JevAnswered(call.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done")) + newTask("key_doubt")), 600))
        val commit = h.at(1_000, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq)))
        assertTrue(CascadeEvent.ClarityDoubt("u1", "key_doubt") in commit.filterIsInstance<Effect.Emit>().map { it.event })
        val write = commit.filterIsInstance<Effect.Write>().single()
        assertTrue("the brief writer names the doubted word", Briefs.DOUBT in write.prompt)
        assertTrue("no question before the task", commit.none { it is Effect.StreamWriter })
        assertTrue("no wait", h.effects.filterIsInstance<Effect.StartTimer>().none { it.timer.name == KeyDoubt.MARK })

        h.write(doubted())
        val text = h.submits().single().text
        assertTrue("As heard: email [Sapphire|Safilo] about forty units" in text)
        assertTrue("Clarity: key_doubt (0.90)" in text)
        assertTrue(text, "The voice assistant is checking \"Sapphire\" with the user" in text)
        h.drain()
        assertEquals("the start line, then the question", "start.one", h.slotsSaid().first())
        assertEquals("Sapphire, right?", h.said()[1])
        assertTrue(h.s.marks[KeyDoubt.MARK].orEmpty().isNotEmpty())
    }

    @Test fun aCorrectionRedirectsTheTask() {
        val h = CeoHarness()
        startDoubted(h)
        val fx = h.turn("No, Safilo.", 3_000, "action" to answer("reply"), "line" to answer("ack.no"))
        val busy = fx.filterIsInstance<Effect.ReadBusyMode>().single()
        h.on(Event.BusyModeRead(busy.seq, null))
        assertEquals(2, h.submits().size)
        val update = h.submits().last()
        assertEquals("t1", update.taskId)
        assertFalse("a redirect, not a queued note", update.queued)
        assertTrue(update.text, "Correction: I asked the user \"Sapphire, right?\" about \"Sapphire\"" in update.text && "\"No, Safilo.\"" in update.text)
        assertEquals(listOf("t1"), h.s.tasks.tasks.map { it.id })
        assertTrue(h.s.marks[KeyDoubt.MARK].isNullOrEmpty())
    }

    @Test fun aConfirmationChangesNothing() {
        val h = CeoHarness()
        startDoubted(h)
        val fx = h.turn("Yes, that's right.", 3_000, "action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect"))
        h.drain()
        assertEquals("nothing more reaches Hermes", 1, h.submits().size)
        assertTrue(fx.none { it is Effect.ReadBusyMode || it is Effect.Write || it is Effect.StreamWriter })
        assertEquals(KeyDoubt.CONFIRMED_SLOT, h.slotsSaid().last())
        assertTrue(h.s.marks[KeyDoubt.MARK].isNullOrEmpty())
        // The word itself is a yes too.
        val h2 = CeoHarness()
        startDoubted(h2)
        h2.turn("Sapphire, yeah.", 3_000, "action" to answer("reply"))
        assertEquals(1, h2.submits().size)
    }

    /** A fast task is often done before the user answers: the answer still belongs to it. */
    @Test fun anAnswerAfterTheTaskFinishedStillBelongsToIt() {
        val yes = CeoHarness()
        val first = startDoubted(yes)
        yes.on(Event.TaskUpdated("t1", first.runId, TurnUpdate.Completed("Emailed Sapphire.", TurnStatus.Complete, null)))
        yes.on(Event.TaskRunEnded("t1", first.runId))
        assertEquals(TaskState.Done, yes.s.tasks["t1"]!!.state)
        yes.turn("Yes, that's right.", 3_000, "action" to answer("update_task"), "task" to answer("t1"))
        yes.drain()
        assertEquals("a yes to a finished task sends nothing", 1, yes.submits().size)
        assertEquals(KeyDoubt.CONFIRMED_SLOT, yes.slotsSaid().last())

        val no = CeoHarness()
        val second = startDoubted(no)
        no.on(Event.TaskUpdated("t1", second.runId, TurnUpdate.Completed("Emailed Sapphire.", TurnStatus.Complete, null)))
        no.on(Event.TaskRunEnded("t1", second.runId))
        no.turn("No, Safilo.", 3_000, "action" to answer("reply"))
        assertEquals(2, no.submits().size)
        val follow = no.submits().last()
        assertEquals("t1", follow.taskId)
        assertTrue(follow.text, follow.text.startsWith("Correction: I asked the user \"Sapphire, right?\""))
    }

    @Test fun noAnswerLeavesTheTaskRunningUnchanged() {
        val h = CeoHarness()
        startDoubted(h)
        // Nothing is waiting: no timer, and the task runs on.
        assertTrue(h.effects.filterIsInstance<Effect.StartTimer>().none { it.timer.name == KeyDoubt.MARK })
        // A later turn, past the answer window, is its own request.
        h.turn("check how many RB2140 frames we have", 20_000, *newTask())
        h.write(briefs(brief("Check RB2140 frames.", "stock check", "checking RB2140 frames")))
        assertEquals(listOf("t1", "t2"), h.submits().map { it.taskId })
        assertEquals(listOf("supplier email", "stock check"), h.s.tasks.tasks.map { it.label })
        assertTrue(h.s.marks[KeyDoubt.MARK].isNullOrEmpty())
    }

    @Test fun aNewRequestRightAfterTheQuestionIsNotAnAnswer() {
        val h = CeoHarness()
        startDoubted(h)
        h.turn("also check how many RB2140 frames we have in the Leeds shop", 3_000, *newTask())
        h.write(briefs(brief("Check RB2140 frames.", "stock check", "checking RB2140 frames")))
        assertEquals(listOf("t1", "t2"), h.submits().map { it.taskId })
        assertTrue(h.submits().none { it.text.startsWith("Correction") })
    }

    @Test fun noQuestionBelowTheAskFloorOrForAKnownWord() {
        // Jev's key_doubt at 0.58: notes only.
        val low = CeoHarness()
        low.turn("email Sapphire about forty units", 0, *newTask("key_doubt", 0.58))
        assertFalse(Briefs.DOUBT in low.effects.filterIsInstance<Effect.Write>().single().prompt)
        low.write(doubted())
        low.drain()
        assertTrue("Clarity: key_doubt (0.58)" in low.submits().single().text)
        assertFalse("Sapphire, right?" in low.said())
        // In the user's vocabulary: notes only.
        val vocab = CeoHarness(CascadeSettings(vocabulary = listOf("Sapphire")))
        startDoubted(vocab)
        assertFalse("Sapphire, right?" in vocab.said())
        assertTrue(vocab.s.marks[KeyDoubt.MARK].isNullOrEmpty())
        // The label of an earlier task: notes only. (This turn's own new label does not count.)
        val labelled = CeoHarness()
        labelled.turn("check the Sapphire order", 0, *newTask())
        labelled.write(briefs(brief("Check the Sapphire order.", "Sapphire order check", "checking it")))
        labelled.turn("email Sapphire about forty units", 5_000, *newTask("key_doubt"))
        labelled.write(doubted())
        labelled.drain()
        assertEquals(2, labelled.submits().size)
        assertFalse("Sapphire, right?" in labelled.said())
    }

    @Test fun scrapThatAfterTheQuestionCancelsTheNewTask() {
        val h = CeoHarness()
        startDoubted(h)
        val fx = h.turn("scrap that", 3_000, "action" to answer("cancel_task"), "task" to answer("none"))
        assertTrue(Effect.InterruptTask("t1") in fx)
        assertEquals(1, h.submits().size)
    }

    @Test fun writerFixesRepairOnlyDoubtedWords() {
        val h = CeoHarness()
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final("email Sapphire about forty units", listOf(Word("email"), Word("Sapphire", 0.2, listOf("Safilo")), Word("about"), Word("forty"), Word("units"))))
        h.at(0, Event.SpeechEnded)
        val call = h.at(500, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        h.at(600, Event.JevAnswered(call.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done")) + newTask("minor_doubt", 0.7)), 600))
        h.at(1_000, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq)))
        h.write("{\"briefs\":[" + brief("Email Safilo about forty units.", "supplier email", "emailing Safilo") + "],\"fixes\":[{\"from\":\"Sapphire\",\"to\":\"Safilo\"},{\"from\":\"forty\",\"to\":\"fourteen\"}],\"notes\":\"\"}")
        val text = h.submits().single().text
        // "forty" was heard with confidence, so the whole repair is refused and the verbatim text stands.
        assertTrue("Transcript: email Sapphire about forty units" in text)
        assertTrue("repair not applied" in text)
    }

    @Test fun garbledSaysClarifyAgainAndMinorDoubtProceedsWithNotes() {
        val h = CeoHarness()
        h.turn("blah frames the", 0, *newTask("garbled", 0.8))
        h.drain()
        assertEquals(AppLines.SAY_AGAIN, h.slotsSaid().last())
        assertTrue(h.effects.none { it is Effect.Write })
        h.turn("ask Bright Well for frames", 5_000, *newTask("minor_doubt", 0.7))
        h.write(briefs(brief("Ask Brightwell for frames.", "frames ask", "asking Brightwell"), repaired = "ask Brightwell for frames", notes = "Bright Well is likely Brightwell"))
        val text = h.submits().single().text
        // No vocabulary and no uncertain words: the repair is refused, the verbatim text stands, and the note says so.
        assertTrue("Transcript: ask Bright Well for frames" in text)
        assertTrue("repair not applied" in text)
    }

    @Test fun vocabularyRepairIsAccepted() {
        val h = CeoHarness(CascadeSettings(vocabulary = listOf("Brightwell")))
        h.turn("ask Bright Well for frames", 0, *newTask("minor_doubt", 0.7))
        h.write(briefs(brief("Ask Brightwell for frames.", "frames ask", "asking Brightwell"), repaired = "ask Brightwell for frames", notes = "Bright Well -> Brightwell"))
        val text = h.submits().single().text
        assertTrue("Transcript: ask Brightwell for frames" in text)
        assertTrue("As heard: ask Bright Well for frames" in text)
        assertTrue("Notes: Bright Well -> Brightwell" in text)
    }

    @Test fun theCapHoldsForAdversarialInputWhileTheFullTextReachesTheSubmit() {
        val h = CeoHarness()
        val nasty = "say \"quote\" \\ \u0001 and 🙂 "
        val dictation = (1..3_000).joinToString(" ") { "w$it" } + " " + nasty.repeat(400)
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final(dictation))
        h.at(0, Event.SpeechEnded)
        val call = h.at(500, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.EARLY, h.s.turn.pauseSeq))).filterIsInstance<Effect.CallJev>().single()
        assertTrue("Jev state ${call.request.state.size()}", call.request.state.size() <= h.s.settings.jevStateCapChars)
        h.at(600, Event.JevAnswered(call.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done")) + newTask()), 600))
        h.at(1_000, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq)))
        // Over 24,000 characters the writer works in chunks of 20,000.
        val chunks = BriefPlanner.chunks(dictation.trim(), 24_000, 20_000)
        assertTrue(chunks.size > 1)
        repeat(chunks.size) { i ->
            assertTrue(h.effects.filterIsInstance<Effect.Write>().last().prompt.contains("This is part ${i + 1} of ${chunks.size}"))
            h.write(briefs(brief("Part ${'A' + i}.", "part ${'A' + i}", "part ${'A' + i}")))
        }
        val submits = h.submits()
        assertEquals(chunks.size, submits.size)
        submits.forEach { assertTrue("the full verbatim dictation reaches Hermes", "As heard: ${dictation.trim()}\n" in it.text) }
    }

    @Test fun theMemoBatchKeepsOrder() {
        lateinit var memo: MemoRouting
        val policy = object : ConversationPolicy {
            override fun online(tx: Tx): Boolean = memo.begin(tx)
        }
        val h = CeoHarness(conversation = policy)
        memo = h.memo
        // Three turns held offline, a minute apart (one session).
        h.on(Event.Network(false))
        listOf("Chase the Safilo order.", "Also draft the newsletter. And find out why returns went up.", "For the newsletter, lead with autumn frames.")
            .forEachIndexed { i, text ->
                val t = i * 60_000L
                h.at(t, Event.SpeechStarted)
                h.at(t, Event.Final(text))
                h.at(t, Event.SpeechEnded)
                h.at(t + 1_500, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq)))
            }
        assertEquals(listOf("u1", "u2", "u3"), h.s.outbox.ids())
        val online = h.at(200_000, Event.Network(true))
        val segment = online.filterIsInstance<Effect.Write>().single()
        assertEquals(MemoSegmentHandler.SYSTEM, segment.system)
        assertEquals("[1] Chase the Safilo order.\n[2] Also draft the newsletter.\n[3] And find out why returns went up.\n[4] For the newsletter, lead with autumn frames.", segment.prompt)
        // The writer groups 2 and 4 (the newsletter) and leaves 3 out: 3 still becomes its own instruction.
        val routed = h.write("{\"groups\":[[1],[2,4]]}")
        assertEquals(listOf("mu1.1", "mu1.2", "mu1.3"), h.s.outbox.ids())
        assertEquals(listOf("Chase the Safilo order.", "Also draft the newsletter. For the newsletter, lead with autumn frames.", "And find out why returns went up."), h.s.outbox.held.map { it.turn.text })
        assertTrue("held turns are never sent twice", h.s.outbox.sent.containsAll(listOf("u1", "u2", "u3")))
        // Jev routes each instruction in order against the current tasks.
        var jev = routed.filterIsInstance<Effect.CallJev>().single()
        for ((i, label) in listOf("safilo chase", "newsletter", "returns check").withIndex()) {
            val out = h.on(Event.JevAnswered(jev.seq, OpenRouterResult.Ok(mapOf("turn" to answer("done")) + newTask()), h.now))
            assertTrue("no stock line per instruction", out.none { it is Effect.FetchClip && it.piece.slot == "start.one" })
            val next = h.write(briefs(brief("Instruction ${'A' + i}.", label, "doing $label")))
            assertEquals("t${i + 1}", h.submits().last().taskId)
            assertTrue("Dictated offline 08:53–08:55." in h.submits().last().text)
            next.filterIsInstance<Effect.CallJev>().singleOrNull()?.let { jev = it }
        }
        assertEquals(listOf("safilo chase", "newsletter", "returns check"), h.s.tasks.tasks.map { it.label })
        assertEquals(0, h.s.outbox.size)
        h.submits().forEach { h.accept(it) }
        h.drain()
        assertFalse("no per-task summaries for offline dictation", h.said().any { it.startsWith("Doing") })
        assertEquals(listOf("Back online.", "From your offline notes, I've started 3 tasks."), h.said().takeLast(2))
        assertTrue(h.s.tasks.tasks.all { it.offline })
    }

    @Test fun reconnectingMidBatchNeverSegmentsTwice() {
        lateinit var memo: MemoRouting
        val policy = object : ConversationPolicy {
            override fun online(tx: Tx): Boolean = memo.begin(tx)
        }
        val h = CeoHarness(conversation = policy)
        memo = h.memo
        h.on(Event.Network(false))
        h.at(0, Event.SpeechStarted)
        h.at(0, Event.Final("Chase the order."))
        h.at(0, Event.SpeechEnded)
        h.at(1_500, Event.TimerFired(Timer(com.folio.hermes.cascade.TurnRules.COMMIT, h.s.turn.pauseSeq)))
        h.at(10_000, Event.Network(true))
        h.at(10_001, Event.Network(false))
        assertTrue(h.at(10_002, Event.Network(true)).none { it is Effect.Write })
        // The writer fails: each held turn is one instruction.
        val w = h.effects.filterIsInstance<Effect.Write>().single()
        val out = h.on(Event.WriterWrote(w.seq, OpenRouterResult.Failed(FailureKind.Timeout)))
        assertEquals(listOf("mu1.1"), h.s.outbox.ids())
        assertEquals(1, out.filterIsInstance<Effect.CallJev>().size)
        // Begin again while the flush runs: nothing new.
        val tx = Tx(h.s, h.env)
        assertTrue(memo.begin(tx))
        assertTrue(tx.effects.isEmpty())
    }
}
