package com.folio.hermes.cascade

import com.folio.hermes.TurnUpdate
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `reduce` with plain inputs and outputs (R-ARC-3): no coroutines, no clock, no I/O. */
class CascadeReduceTest {
    private val bank = PhraseBank.load()
    private val reducer = CascadeReducer(CascadeEnv(bank))
    private val epoch = 1_760_000_000_000L
    private var s = reducer.reduce(State(), Event.Start()).first

    /** Feed [event] at virtual time [ms]; returns the effects. */
    private fun at(ms: Long, event: Event): List<Effect> {
        val (next, effects) = reducer.reduce(s.copy(nowMs = ms, wallMs = epoch + ms), event)
        s = next
        return effects
    }

    private inline fun <reified T : Effect> List<Effect>.one(): T = filterIsInstance<T>().single()
    private fun List<Effect>.emitted(): List<CascadeEvent> = filterIsInstance<Effect.Emit>().map { it.event }
    private fun ok(vararg pairs: Pair<String, JevAnswer>) = OpenRouterResult.Ok(mapOf(*pairs))
    private val reply = ok("turn" to answer("done"), "action" to answer("reply"), "line" to answer("ack.understood"), "enough" to answer("line_then_writer"))

    /** Say [text], then fire the early call: returns its seq. */
    private fun sayAndPause(text: String, from: Long = 0): Long {
        at(from, Event.SpeechStarted)
        at(from, Event.Final(text))
        at(from, Event.SpeechEnded)
        return at(from + 500, Event.TimerFired(Timer(TurnRules.EARLY, s.turn.pauseSeq))).one<Effect.CallJev>().seq
    }

    @Test fun nothingRunsBeforeTheCallStarts() {
        var off = State()
        for (event in listOf(Event.SpeechStarted, Event.Final("hello"), Event.SpeechEnded, Event.Say(AppLines.HI), Event.Network(true))) {
            val (next, effects) = reducer.reduce(off, event)
            assertTrue("$event", effects.isEmpty())
            off = next
        }
        assertEquals(State(), off)
    }

    @Test fun oneFullTurnSpeechPauseEarlyJevCommitLineWriterSpeechDone() {
        at(0, Event.SpeechStarted)
        assertEquals(TurnPhase.UserSpeaking, UiProjection.project(s).turn.phase)
        at(0, Event.Partial("what's the"))
        assertEquals("what's the", UiProjection.project(s).turn.partial)
        at(0, Event.Final("what's the weather"))
        val ended = at(0, Event.SpeechEnded)
        assertTrue(Effect.StartTimer(Timer(TurnRules.EARLY, 1), 500) in ended)
        assertTrue("the pause ring ticks", ended.any { it is Effect.StartTimer && it.timer.name == CascadeReducer.TICK })
        assertEquals(TurnPhase.Pausing, UiProjection.project(s).turn.phase)

        // Early Jev call at 500 ms: the utterance/latest state (R-RT-4); the commit timer for 1000 ms.
        val early = at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1)))
        val call = early.one<Effect.CallJev>()
        assertTrue(call.early)
        assertEquals("what's the weather", call.request.state["utterance"]!!.jsonPrimitive.content)
        assertEquals(listOf(CascadeEvent.JevFired(true)), early.emitted())
        assertTrue(Effect.StartTimer(Timer(TurnRules.COMMIT, 1), 500) in early)
        assertTrue(UiProjection.project(s).jev.inFlight)

        // Jev answers at 600 ms: nothing acts before pause_ms.
        assertTrue(at(600, Event.JevAnswered(call.seq, reply, 600)).isEmpty())
        assertNull(s.actions.active)

        // Commit at 1000 ms: the stock line plays at once, the writer streams after it.
        val commit = at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertEquals(listOf(CascadeEvent.JevAnswered(100), CascadeEvent.TurnCommitted("u1")), commit.emitted().take(2))
        val writer = commit.one<Effect.StreamWriter>()
        assertTrue("carries on after the stock line", "Carry straight on" in writer.system)
        val fetch = commit.one<Effect.FetchClip>()
        assertEquals("ack.understood", fetch.piece.slot)
        assertTrue(commit.any { it is Effect.SaveRotation && it.slotId == "ack.understood" })
        assertEquals("writer:${writer.seq}", s.actions.active!!.waitingOn)
        assertEquals("done", commit.filterIsInstance<Effect.Report>().single { it.name == "cascade.turn" }.attrs["outcome"])
        assertEquals(TurnPhase.BillSpeaking, UiProjection.project(s).turn.phase)
        assertEquals(listOf(RecentTurn("what's the weather", s.actions.active!!.utteranceId)), s.recent)

        // The writer's sentence joins the reply; the action ends when the writer does.
        at(1_050, Event.WriterSentence(writer.seq, "It's sunny in Leeds."))
        assertEquals(listOf("It's sunny in Leeds."), s.writer.sentences)
        val finished = at(1_100, Event.WriterFinished(writer.seq, null))
        assertEquals(mapOf("route" to "reply", "slot" to "ack.understood", "writer" to true, "resend" to false), finished.filterIsInstance<Effect.Report>().single().attrs)
        assertNull(s.actions.active)
        assertTrue("logged on first audio", finished.none { it is Effect.LogDecision })

        // Speech: fetch → play (prefetching the next) → started → finished, then the next piece.
        val ready = at(1_100, Event.ClipReady(fetch.piece, ClipOutcome.Ok))
        assertEquals(Effect.Play(fetch.piece), ready.one<Effect.Play>())
        val next = ready.one<Effect.FetchClip>()
        assertTrue(next.prefetch)
        assertEquals("It's sunny in Leeds.", next.piece.text)
        val started = at(1_110, Event.PlaybackStarted(fetch.piece, 1_110))
        assertEquals(listOf(CascadeEvent.LinePlayed("ack.understood")), started.emitted())
        val record = started.one<Effect.LogDecision>().record
        assertEquals(1_110L, record.speechEndToAudioMs)
        assertEquals(600L, record.speechEndToJevMs)
        assertEquals("reply", record.route)
        assertTrue(record.writerRan)
        assertEquals(fetch.piece.text, UiProjection.project(s).bill.text)

        val second = at(1_610, Event.PlaybackFinished(fetch.piece, 500, 500)).one<Effect.FetchClip>()
        assertFalse(second.prefetch)
        at(1_610, Event.ClipReady(second.piece, ClipOutcome.Ok))
        at(1_620, Event.PlaybackStarted(second.piece, 1_620))
        at(2_120, Event.PlaybackFinished(second.piece, 500, 500))

        assertFalse(s.speaking)
        val ui = UiProjection.project(s)
        assertEquals(TurnPhase.Idle, ui.turn.phase)
        assertFalse(ui.bill.speaking)
        assertEquals("${fetch.piece.text} It's sunny in Leeds.", s.speech.heard(fetch.piece.utteranceId))
    }

    @Test fun speechResumingMakesTheEarlyAnswerStale() {
        val seq = sayAndPause("book me a")
        val resumed = at(700, Event.SpeechStarted)
        assertTrue(Effect.CancelJev(seq) in resumed)
        val before = s
        assertTrue(at(800, Event.JevAnswered(seq, reply, 800)).isEmpty())
        assertEquals("a stale answer changes nothing", before.copy(nowMs = 800, wallMs = epoch + 800), s)
        // And the old pause's commit timer is stale too.
        at(800, Event.SpeechEnded)
        assertTrue(at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1))).isEmpty())
        assertNull(s.actions.active)
    }

    @Test fun staleResultsAreDropped() {
        val before = s.copy(nowMs = 5, wallMs = epoch + 5)
        val stale = listOf(
            Event.WriterSentence(99, "nobody asked"),
            Event.WriterFinished(99, null),
            Event.WriterWrote(99, OpenRouterResult.Ok("LABEL: x")),
            Event.BusyModeRead(99, null),
            Event.TaskUpdated("t9", 1, TurnUpdate.Started),
            Event.TaskRunEnded("t9", 1),
            Event.ClipReady(Piece(1, 0, "Hi.", null), ClipOutcome.Ok),
            Event.PlaybackStarted(Piece(1, 0, "Hi.", null), 5),
            Event.PlaybackFinished(Piece(1, 0, "Hi.", null), 5, 5),
            Event.TimerFired(Timer(TurnRules.RECHECK, 7)),
            Event.TimerFired(Timer(CascadeReducer.TICK, 7)),
            Event.JevAnswered(42, reply, 5),
        )
        for (event in stale) {
            assertTrue("$event", at(5, event).isEmpty())
            assertEquals("$event", before, s)
        }
    }

    @Test fun aWriterCutOffByTheUserEndsWithoutFailing() {
        val seq = sayAndPause("tell me a story")
        at(600, Event.JevAnswered(seq, reply, 600))
        val commit = at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        val writer = commit.one<Effect.StreamWriter>()
        val line = commit.one<Effect.FetchClip>().piece
        at(1_000, Event.ClipReady(line, ClipOutcome.Ok))
        at(1_010, Event.PlaybackStarted(line, 1_010))
        val cut = at(1_200, Event.UserSpeech(300))
        assertTrue(Effect.StopPlayback in cut)
        assertEquals(listOf(CascadeEvent.UserCutIn), cut.emitted())
        val after = at(1_250, Event.WriterSentence(writer.seq, "Once upon a time."))
        assertTrue("stop reading the writer", Effect.CancelWriter(writer.seq) in after)
        assertTrue("no fail line", after.none { it is Effect.SaveRotation && it.slotId == AppLines.GAVE_UP })
        assertNull("the action finished", s.actions.active)
        // The playing piece's clip still ends; the queue moves on.
        at(1_260, Event.PlaybackFinished(line, 250, 500))
        assertNull(s.speech.current)
        assertFalse(s.speaking)
    }

    @Test fun aSpeechCutBelowBargeInIsIgnored() {
        at(0, Event.Say(AppLines.HI))
        val piece = s.playback!!.piece
        at(0, Event.ClipReady(piece, ClipOutcome.Ok))
        assertTrue(at(10, Event.UserSpeech(249)).isEmpty())
        assertNotNull(s.speech.current)
    }

    /** Jev offline on both calls: the turn is held; reconnecting resends it once, never twice. */
    @Test fun aSecondReconnectDoesNotStartASecondFlush() {
        val early = sayAndPause("email the supplier")
        at(510, Event.JevAnswered(early, OpenRouterResult.Failed(FailureKind.Offline), 510))
        val second = at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1))).one<Effect.CallJev>()
        val held = at(1_010, Event.JevAnswered(second.seq, OpenRouterResult.Failed(FailureKind.Offline), 1_010))
        assertEquals(listOf("u1"), s.outbox.ids())
        assertTrue(CascadeEvent.Offline in held.emitted())
        assertEquals(1, UiProjection.project(s).connection.outbox)
        assertEquals("held", held.one<Effect.LogDecision>().record.outcome)

        val online = at(5_000, Event.Network(true))
        assertTrue(CascadeEvent.Online in online.emitted())
        val resend = online.one<Effect.CallJev>()
        assertEquals("email the supplier", resend.request.state["utterance"]!!.jsonPrimitive.content)
        at(5_010, Event.Network(false))
        assertTrue("one flush at a time", at(5_020, Event.Network(true)).none { it is Effect.CallJev })

        // The resend's answer runs the turn once; the outbox empties and the turn is never held again.
        val acted = at(5_100, Event.JevAnswered(resend.seq, reply, 5_100))
        val writer = acted.one<Effect.StreamWriter>()
        at(5_200, Event.WriterSentence(writer.seq, "Done."))
        val done = at(5_300, Event.WriterFinished(writer.seq, null))
        assertEquals(true, done.filterIsInstance<Effect.Report>().first { it.name == "cascade.action" }.attrs["resend"])
        assertEquals(0, s.outbox.size)
        assertTrue("u1" in s.outbox.sent)
        assertEquals(mapOf("event" to "sent", "count" to 1, "size" to 0), done.filterIsInstance<Effect.Report>().single { it.name == "cascade.outbox" }.attrs)
        assertFalse(s.outbox.hold("u1", HeldTurn("again", 0)).second)
    }

    @Test fun keepListeningHoldsThenTheRecheckCommits() {
        val keep = ok("turn" to answer("keep_listening"))
        val seq = sayAndPause("right so three things")
        at(550, Event.JevAnswered(seq, keep, 550))
        val holding = at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertEquals("keep_listening", holding.one<Effect.LogDecision>().record.outcome)
        assertEquals(listOf("right so three things"), s.turn.pending)
        assertEquals("right so three things", UiProjection.project(s).turn.held)
        assertTrue("results wait while words are held", s.speech.userHolding)
        val recheck = at(3_500, Event.TimerFired(Timer(TurnRules.RECHECK, 1))).one<Effect.CallJev>()
        assertEquals(3_500L, recheck.request.state["silence_ms"]!!.jsonPrimitive.content.toLong())
        val parked = at(3_550, Event.JevAnswered(recheck.seq, keep, 3_550))
        assertEquals("parked", parked.one<Effect.LogDecision>().record.outcome)
        assertFalse("parked words no longer hold results back", s.speech.userHolding)
        assertEquals(listOf("right so three things"), s.turn.pending)
    }

    @Test fun newTaskPlansStartsAndConfirms() {
        val newTask = ok("turn" to answer("done"), "action" to answer("new_task"), "line" to answer("start.lookup"), "enough" to answer("line_then_writer"))
        val seq = sayAndPause("check stock of RB2140")
        at(600, Event.JevAnswered(seq, newTask, 600))
        val commit = at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        val plan = commit.one<Effect.Write>()
        assertTrue("check stock of RB2140" in plan.prompt)
        val started = at(1_200, Event.WriterWrote(plan.seq, OpenRouterResult.Ok("LABEL: stock check\nPROMPT: Check RB2140 stock.\nSAY: Checking the stock now.")))
        // Session first (R-DUR-2): the session is created, its id stored, and only then the prompt goes.
        val open = started.one<Effect.OpenTaskSession>()
        assertTrue(started.none { it is Effect.SubmitTask })
        val opened = at(1_250, Event.TaskSessionOpened(open.taskId, open.runId, "stored-1"))
        assertEquals("stored-1", s.tasks["t1"]!!.storedSessionId)
        val submit = opened.one<Effect.SubmitTask>()
        assertEquals("stored-1", submit.storedSessionId)
        assertEquals("t1", submit.taskId)
        assertEquals("Check RB2140 stock.", submit.text)
        assertFalse(submit.queued)
        assertTrue(CascadeEvent.TaskStarted("t1") in started.emitted())
        assertEquals("u1", s.tasks["t1"]!!.turnId)
        assertTrue(s.outbox.stageDone("u1", "task"))
        assertEquals(listOf("stock check"), UiProjection.project(s).tasks.map { it.label })
        assertEquals(SendState.Sending, UiProjection.project(s).tasks.single().send)

        at(1_300, Event.TaskUpdated("t1", submit.runId, TurnUpdate.Accepted("rt1", com.folio.hermes.SubmitStatus.Streaming)))
        assertEquals(SendState.Accepted, UiProjection.project(s).tasks.single().send)
        // Status never submits (R-TASK-3).
        val statusAnswer = ok("turn" to answer("done"), "action" to answer("status"), "task" to answer("t1"), "enough" to answer("line_then_writer"))
        val seq2 = sayAndPause("how's it going", from = 10_000)
        at(10_600, Event.JevAnswered(seq2, statusAnswer, 10_600))
        val status = at(11_000, Event.TimerFired(Timer(TurnRules.COMMIT, s.turn.pauseSeq)))
        assertTrue(status.none { it is Effect.SubmitTask || it is Effect.InterruptTask })
        assertTrue(status.one<Effect.StreamWriter>().prompt.startsWith("Say how the user's tasks are getting on"))
    }
}
