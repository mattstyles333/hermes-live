package com.folio.hermes.cascade

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.routing.HandlerRegistry
import com.folio.hermes.cascade.routing.Next
import com.folio.hermes.cascade.routing.RouteHandler
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CascadeUiState and CascadeEvent transitions (the call screen's contract, S-D), and the routing seam. */
class CascadeUiTest {
    private val bank = PhraseBank.load()
    private fun ok(vararg pairs: Pair<String, com.folio.hermes.openrouter.JevAnswer>) = OpenRouterResult.Ok(mapOf(*pairs))

    private class Run(val reducer: CascadeReducer) {
        var s = reducer.reduce(State(), Event.Start()).first
        val events = mutableListOf<CascadeEvent>()
        val effects = mutableListOf<Effect>()
        val ui get() = UiProjection.project(s)

        fun at(ms: Long, event: Event): List<Effect> {
            val (next, fx) = reducer.reduce(s.copy(nowMs = ms, wallMs = ms), event)
            s = next
            effects += fx
            events += fx.filterIsInstance<Effect.Emit>().map { it.event }
            return fx
        }
    }

    @Test fun phasesFollowTheTurn() {
        val r = Run(CascadeReducer(CascadeEnv(bank)))
        assertEquals(TurnPhase.Idle, r.ui.turn.phase)
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("find me a flight"))
        assertEquals(TurnPhase.UserSpeaking, r.ui.turn.phase)
        r.at(0, Event.SpeechEnded)
        assertEquals(TurnPhase.Pausing, r.ui.turn.phase)
        r.at(50, Event.TimerFired(Timer(CascadeReducer.TICK, r.s.tickSeq!!)))
        assertEquals(0.05f, r.ui.turn.pauseProgress, 0.001f)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        assertTrue(r.ui.jev.inFlight)
        r.at(1_000, Event.TimerFired(Timer(CascadeReducer.TICK, r.s.tickSeq!!)))
        assertEquals("the ring is full and Jev is still out", TurnPhase.Deciding, r.ui.turn.phase)
        // Commit with the early call still out: a second call races it.
        val second = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(1_100, Event.JevAnswered(second.seq, ok("turn" to answer("done"), "action" to answer("reply"), "enough" to answer("line_then_writer")), 1_100))
        assertTrue("the slow early call is cancelled", Effect.CancelJev(early.seq) in r.effects)
        assertEquals("no stock line: the writer only", TurnPhase.Writing, r.ui.turn.phase)
        assertEquals("done", r.ui.jev.turn!!.choice)
        assertEquals(1_100L - 1_000L, r.ui.jev.latencyMs)
        assertEquals(listOf(CascadeEvent.JevFired(true), CascadeEvent.JevFired(false), CascadeEvent.JevAnswered(100), CascadeEvent.TurnCommitted("u1")), r.events)
    }

    @Test fun tasksAndTheOfflinePill() {
        val r = Run(CascadeReducer(CascadeEnv(bank)))
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("check stock"))
        r.at(0, Event.SpeechEnded)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(510, Event.JevAnswered(early.seq, OpenRouterResult.Failed(FailureKind.Offline), 510))
        val second = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(1_010, Event.JevAnswered(second.seq, OpenRouterResult.Failed(FailureKind.Offline), 1_010))
        val ui = r.ui
        assertFalse(ui.connection.online)
        assertEquals(1, ui.connection.outbox)
        assertEquals(listOf(HeldTurnUi("u1", "check stock", 1_010)), ui.ceo.heldTurns)
        assertTrue(ui.turn.offline)
        assertTrue(CascadeEvent.Offline in r.events)
    }

    @Test fun taskCardsShowSendingThenAcceptedThenDone() {
        val r = Run(CascadeReducer(CascadeEnv(bank)))
        val start = r.s.tasks.start("Check RB2140 stock.", "stock check", "", 4, 0)
        r.s = r.s.copy(tasks = start.table)
        val runId = (start.effects.single() as Effect.SubmitTask).runId
        assertEquals(TaskUiStatus.Starting, r.ui.tasks.single().status)
        assertEquals(SendState.Sending, r.ui.tasks.single().send)
        assertEquals("Check RB2140 stock.", r.ui.tasks.single().brief)
        r.at(10, Event.TaskUpdated("t1", runId, TurnUpdate.Accepted("rt1", SubmitStatus.Streaming)))
        r.at(20, Event.TaskUpdated("t1", runId, TurnUpdate.Started))
        r.at(30, Event.TaskUpdated("t1", runId, TurnUpdate.ToolStarted("c1", "web_search", "web search")))
        assertEquals(TaskUiStatus.Running, r.ui.tasks.single().status)
        assertEquals("web search", r.ui.tasks.single().currentTool)
        assertTrue(CascadeEvent.ToolStarted("t1", "web search") in r.events)
        r.at(40, Event.TaskUpdated("t1", runId, TurnUpdate.Completed("RB2140: 14 in stock", com.folio.hermes.TurnStatus.Complete, null)))
        r.at(50, Event.TaskRunEnded("t1", runId))
        val card = r.ui.tasks.single()
        assertEquals(TaskUiStatus.Done, card.status)
        assertEquals("RB2140: 14 in stock", card.result)
        assertTrue(card.unread)
        assertEquals(listOf("t1"), r.ui.ceo.unreadResults)
        assertTrue(CascadeEvent.ResultQueued("t1") in r.events)
        assertTrue(CascadeEvent.TaskFinished("t1", ok = true) in r.events)
    }

    /** A slice changes a route by registering a handler: the reducer is untouched (spec v2 §12). */
    @Test fun aRegisteredHandlerReplacesARoute() {
        val quiet = object : RouteHandler {
            override fun start(tx: Tx): Next {
                tx.play("social.thanks")
                return Next.Done
            }
        }
        val registry = HandlerRegistry.default().with(Route.Reply.wire, quiet)
        assertEquals(HandlerRegistry.default().wires, registry.wires)
        val r = Run(CascadeReducer(CascadeEnv(bank, handlers = registry)))
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("thanks"))
        r.at(0, Event.SpeechEnded)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(600, Event.JevAnswered(early.seq, ok("turn" to answer("done"), "action" to answer("reply")), 600))
        val commit = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertTrue("no writer", commit.none { it is Effect.StreamWriter })
        assertEquals("social.thanks", commit.filterIsInstance<Effect.FetchClip>().single().piece.slot)
    }

    /** R-STT-2 through the hooks only: a committed turn waits for the accurate pass (a gate), then runs. */
    @Test fun aCommitHookCanHoldATurnUntilTheAccuratePass() {
        val hooks = object : com.folio.hermes.cascade.routing.TurnHooks {
            override fun committed(tx: Tx, action: ActionState, event: TurnEvent.Done): ActionState {
                tx.effects += Effect.AccuratePass(action.turnId)
                return action.copy(gates = setOf("accurate"))
            }

            override fun accurate(tx: Tx, event: Event.AccurateFinal) {
                tx.s = tx.s.copy(transcript = tx.s.transcript.copy(byTurn = tx.s.transcript.byTurn + (event.turnId to event.transcript!!)))
                tx.openGate(event.turnId, "accurate")
            }
        }
        val r = Run(CascadeReducer(CascadeEnv(bank, handlers = HandlerRegistry.default().with(hooks))))
        r.at(0, Event.SpeechStarted)
        r.at(0, Event.Final("email Sapphire"))
        r.at(0, Event.SpeechEnded)
        val early = r.at(500, Event.TimerFired(Timer(TurnRules.EARLY, 1))).filterIsInstance<Effect.CallJev>().single()
        r.at(600, Event.JevAnswered(early.seq, ok("turn" to answer("done"), "action" to answer("reply"), "enough" to answer("line_then_writer")), 600))
        val commit = r.at(1_000, Event.TimerFired(Timer(TurnRules.COMMIT, 1)))
        assertTrue(Effect.AccuratePass("u1") in commit)
        assertTrue("held until the accurate pass", commit.none { it is Effect.StreamWriter })
        val accurate = Transcript("email Safilo", listOf(Word("email", 0.99), Word("Safilo", 0.42, listOf("Sapphire"), uncertain = true)), TranscriptPass.Accurate)
        val run = r.at(1_400, Event.AccurateFinal("u1", accurate))
        assertTrue(run.any { it is Effect.StreamWriter })
        assertEquals(TranscriptPass.Accurate, r.ui.turn.pass)
        assertEquals(listOf("Safilo"), r.ui.turn.words.filter { it.uncertain }.map { it.text })
    }
}
