package com.folio.hermes.cascade

import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud speech-to-text's text for a fast segment (`Event.Revised`, cloud STT slice): Jev judges
 * the cloud words only when they differ materially and Jev has not decided on the turn yet;
 * otherwise only the caption changes. Pure rules first, then the actor on a virtual clock.
 */
class CloudRevisionTest {
    // ── pure rules ──────────────────────────────────────────────────────────────────────────

    private val settings = CascadeSettings()

    private fun heard(text: String) = TurnRules.final(TurnState(), text).state

    @Test fun aMaterialRevisionBeforeCommitRewritesJevsWords() {
        val t = heard("Check stock on the kestrel in sto")
        val step = TurnRules.revised(t, "Check stock on the kestrel in sto", "Check stock on the Kestrel, in stock.", material = true)
        assertEquals("Check stock on the Kestrel, in stock.", step.state.heard)
        assertTrue(step.state.shownAs.isEmpty())
        assertTrue(step.effects.isEmpty())
    }

    @Test fun aRevisionThatIsNotMaterialOnlyChangesTheCaption() {
        val t = heard("Check stock")
        val step = TurnRules.revised(t, "Check stock", "Check stock.", material = false)
        assertEquals("Jev's words stay", "Check stock", step.state.heard)
        assertEquals("Check stock.", step.state.shown(step.state.heard))
    }

    @Test fun onceTheCommitCallIsOutOnlyTheCaptionChanges() {
        var t = heard("Cancel the newsletter")
        t = TurnRules.speechEnded(t, 0, settings).state
        val ask: JevAsk = { h, p, _ -> JevRequest(kotlinx.serialization.json.JsonObject(emptyMap()), emptyMap(), CascadeBrain.key(h, p)) }
        t = TurnRules.timer(t, Timer(TurnRules.EARLY, t.pause!!.seq), 500, ask).state
        // The early answer never came: at the commit point a second call goes out (Committing).
        t = TurnRules.timer(t, Timer(TurnRules.COMMIT, t.pause!!.seq), 1_000, ask).state
        assertEquals(PauseStage.Committing, t.pause!!.stage)
        val step = TurnRules.revised(t, "Cancel the newsletter", "Cancel the news letter.", material = true)
        assertEquals("Cancel the newsletter", step.state.heard)
        assertEquals("Cancel the news letter.", step.state.shown(step.state.heard))
    }

    @Test fun parkedWordsKeepTheirTextForTheHoldSilenceRule() {
        val t = TurnState(pending = listOf("we have in sto"), parked = true)
        val step = TurnRules.revised(t, "we have in sto", "we have in stock", material = true)
        assertEquals(listOf("we have in sto"), step.state.pending)
        assertEquals("we have in stock", step.state.shown(step.state.pending.single()))
    }

    @Test fun heldWordsWaitingForTheRecheckTakeTheCloudWords() {
        val t = TurnState(pending = listOf("Check what we have in sto"), pause = PauseState(1, 0, settings, PauseStage.Holding, turnId = "u1"))
        val step = TurnRules.revised(t, "we have in sto", "we have in stock", material = true)
        assertEquals(listOf("Check what we have in stock"), step.state.pending)
    }

    @Test fun wordsAlreadyCommittedAreLeftAlone() {
        val t = heard("And the next thing")
        val step = TurnRules.revised(t, "Check stock", "Check the stock", material = true)
        assertSame(t, step.state)
    }

    @Test fun theCaptionMapIsForgottenWithTheTurn() {
        val t = TurnRules.revised(heard("Check stock"), "Check stock", "Check stock.", material = false).state
        assertTrue(TurnRules.reset(t).state.shownAs.isEmpty())
    }

    // ── the actor: Jev asked again at the commit point only when it matters ──────────────────

    private fun ok(vararg pairs: Pair<String, JevAnswer>) = OpenRouterResult.Ok(mapOf(*pairs))
    private val reply = ok("turn" to answer("done"), "action" to answer("reply"), "line" to answer("ack.understood"), "enough" to answer("line_then_writer"))

    private inner class Rig(val scope: TestScope) : AutoCloseable {
        val job = SupervisorJob()
        val runScope = CoroutineScope(scope.coroutineContext + job)
        val clock = TestClock(scope)
        val openRouter = FakeOpenRouter(clock)
        val events = mutableListOf<Pair<Long, CascadeEvent>>()
        val actor = CascadeActor(
            scope = runScope,
            env = CascadeEnv(PhraseBank.load()),
            settings = { CascadeSettings() },
            openRouter = openRouter,
            hermes = FakeHermes(),
            audio = FakeAudioOut(clock),
            clock = clock,
            decisions = DecisionLog(MemoryDecisionStore(), now = clock::wallMs),
            telemetry = TelemetrySink.None,
            onEndCall = { },
            seed = 7,
        )

        init {
            runScope.launch { actor.events.collect { events += scope.testScheduler.currentTime to it } }
            scope.runCurrent()
            actor.start()
            scope.runCurrent()
        }

        fun say(text: String) {
            actor.speechStarted()
            actor.final(text)
            actor.speechEnded()
        }

        fun utterances(): List<String> = openRouter.jevCalls.map { it.state["utterance"]!!.jsonPrimitive.content }

        override fun close() = job.cancel()
    }

    private fun rigTest(block: suspend TestScope.(Rig) -> Unit) = runTest { Rig(this).use { block(it) } }

    @Test fun aMaterialRevisionAfterTheEarlyCallIsJudgedAtTheCommitPoint() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("turn" to answer("keep_listening")))
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.say("check what we have in sto")
        advanceTimeBy(800) // the early call (500) has answered; the cloud text lands before commit (1000)
        runCurrent()
        rig.actor.send(Event.Revised("check what we have in sto", "Check what we have in stock.", material = true))
        advanceUntilIdle()
        assertEquals(listOf("check what we have in sto", "Check what we have in stock."), rig.utterances())
        assertEquals("the re-ask goes out at the commit point", 1_000L, rig.openRouter.jevCalls[1].at)
        assertEquals("Check what we have in stock.", rig.actor.state.value.recent.single().user)
        assertTrue(rig.events.any { it.second == CascadeEvent.TurnCommitted("u1") })
    }

    @Test fun aRevisionThatIsNotMaterialKeepsTheEarlyAnswerAndUpdatesTheCaption() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.say("check stock")
        advanceTimeBy(800)
        runCurrent()
        rig.actor.send(Event.Revised("check stock", "Check stock.", material = false))
        runCurrent()
        assertEquals("the caption shows the cloud text", "Check stock.", rig.actor.ui.value.turn.partial)
        advanceUntilIdle()
        assertEquals("one Jev call: the early answer stands", 1, rig.openRouter.jevCalls.size)
        assertEquals(1_000L, rig.events.first { it.second == CascadeEvent.TurnCommitted("u1") }.first)
    }

    @Test fun aRevisionBeforeTheEarlyCallNeedsNoSecondCall() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.say("check what we have in sto")
        advanceTimeBy(300)
        runCurrent()
        rig.actor.send(Event.Revised("check what we have in sto", "Check what we have in stock.", material = true))
        advanceUntilIdle()
        assertEquals(listOf("Check what we have in stock."), rig.utterances())
    }

    @Test fun aRevisionAfterCommitAsksNothing() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, reply)
        rig.say("check stock")
        advanceTimeBy(1_200)
        runCurrent()
        rig.actor.send(Event.Revised("check stock", "Check the stock.", material = true))
        advanceUntilIdle()
        assertEquals(1, rig.openRouter.jevCalls.size)
        assertEquals("check stock", rig.actor.state.value.recent.single().user)
    }
}
