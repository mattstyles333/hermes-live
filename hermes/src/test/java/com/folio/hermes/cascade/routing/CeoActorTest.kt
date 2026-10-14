package com.folio.hermes.cascade.routing

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.CascadeActor
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.FakeAudioOut
import com.folio.hermes.cascade.FakeHermes
import com.folio.hermes.cascade.FakeOpenRouter
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TestClock
import com.folio.hermes.cascade.answer
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenario tests: the actor with the routing slice on, a virtual clock and fake ports (R-ARC-3). No network, no keys. */
class CeoActorTest {
    private val bank = PhraseBank.load()

    private fun ok(vararg pairs: Pair<String, JevAnswer>) = OpenRouterResult.Ok(mapOf("turn" to answer("done")) + mapOf(*pairs))

    private inner class Rig(val scope: TestScope, settings: CascadeSettings = CascadeSettings()) : AutoCloseable {
        val job = SupervisorJob()
        val runScope = CoroutineScope(scope.coroutineContext + job)
        val clock = TestClock(scope)
        val openRouter = FakeOpenRouter(clock)
        val hermes = FakeHermes()
        val audio = FakeAudioOut(clock)
        val events = mutableListOf<Pair<Long, CascadeEvent>>()
        val actor = CascadeActor(
            scope = runScope,
            env = CeoRouting.env(bank, MemoRouting(ZoneOffset.UTC)),
            settings = { settings },
            openRouter = openRouter,
            hermes = hermes,
            audio = audio,
            clock = clock,
            telemetry = TelemetrySink.None,
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

        override fun close() = job.cancel()
    }

    private fun rigTest(settings: CascadeSettings = CascadeSettings(), block: suspend TestScope.(Rig) -> Unit) =
        runTest { Rig(this, settings).use { block(it) } }

    @Test fun aSplitIntoSixStartsFourAndQueuesTwo() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("new_task"), "split" to answer("several"), "line" to answer("start.parallel")))
        rig.openRouter.plan = { OpenRouterResult.Ok(briefs(*"ABCDEF".map { brief("Job $it.", "job $it", "doing job $it") }.toTypedArray())) }
        rig.say("six separate jobs")
        advanceUntilIdle()
        assertEquals(listOf("t1", "t2", "t3", "t4"), rig.hermes.submits.map { it.taskId })
        val cards = rig.actor.ui.value.tasks
        assertEquals(listOf(TaskUiStatus.Queued, TaskUiStatus.Queued), cards.filter { it.id in setOf("t5", "t6") }.map { it.status })
        // Accepted at once (the fake): the four summaries were spoken, after the start line.
        val clips = rig.openRouter.clips
        val start = clips.indexOfFirst { it in bank["start.parallel"]!!.lines }
        assertTrue(start >= 0)
        assertEquals(listOf("Doing job A.", "Doing job B.", "Doing job C.", "Doing job D."), clips.filter { it.startsWith("Doing job") })
        assertTrue(clips.indexOf("Doing job A.") > start)
        // One finishes: the first queued task starts.
        rig.hermes.complete("t3", "done")
        advanceUntilIdle()
        assertEquals("t5", rig.hermes.submits.last().taskId)
        assertEquals(5, rig.hermes.submits.size)
    }

    @Test fun theSummaryPlaysOnlyAfterAccepted() = rigTest { rig ->
        rig.hermes.autoStart = false
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("new_task"), "line" to answer("start.one")))
        rig.openRouter.plan = { OpenRouterResult.Ok(briefs(brief("Check stock.", "stock check", "checking the stock"))) }
        rig.say("check the stock")
        advanceUntilIdle()
        assertEquals(1, rig.hermes.submits.size)
        assertTrue("start.* plays at once", rig.openRouter.clips.any { it in bank["start.one"]!!.lines })
        assertFalse(rig.openRouter.clips.contains("Checking the stock."))
        assertEquals(com.folio.hermes.cascade.SendState.Sending, rig.actor.ui.value.tasks.single().send)
        advanceTimeBy(5_000)
        assertFalse(rig.openRouter.clips.contains("Checking the stock."))
        rig.hermes.submits.single().updates.trySend(TurnUpdate.Accepted("rt-t1", SubmitStatus.Streaming))
        advanceUntilIdle()
        assertTrue(rig.openRouter.clips.contains("Checking the stock."))
        assertTrue(rig.events.any { it.second is CascadeEvent.TaskStarted })
    }

    @Test fun aCorrectionWithin20sRedirectsAfter20sJevDecides() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("new_task"), "line" to answer("start.one")))
        rig.openRouter.plan = { OpenRouterResult.Ok(briefs(brief("Email Sapphire.", "supplier email", "emailing Sapphire"))) }
        rig.say("email Sapphire")
        advanceUntilIdle()
        val startedAt = testScheduler.currentTime
        // 10 s later: "no, Safilo" with Jev unsure about redirect → still a redirect (the newest task, under 20 s).
        advanceTimeBy(10_000 - (testScheduler.currentTime - startedAt))
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect", 0.1)))
        rig.say("no, I said Safilo")
        advanceUntilIdle()
        val redirect = rig.hermes.submits.last()
        assertEquals("no, I said Safilo", redirect.text)
        assertFalse(redirect.queued)
        // 30 s after the start: the same unsure answer is queued.
        advanceTimeBy(20_000)
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect", 0.1)))
        rig.say("and copy in Tom")
        advanceUntilIdle()
        assertTrue(rig.hermes.submits.last().queued)
        assertEquals("and copy in Tom", rig.hermes.submits.last().text)
    }

    @Test fun keyDoubtStartsTheTaskThenAsks() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(
            100,
            ok("action" to answer("new_task"), "line" to answer("start.one"), "clarity" to answer("key_doubt", 0.8)),
        )
        rig.openRouter.plan = {
            OpenRouterResult.Ok(
                """{"briefs":[${brief("Email Sapphire.", "supplier email", "emailing Sapphire")}],"doubt":{"word":"Sapphire","question":"Safilo or Sapphire?"}}""",
            )
        }
        rig.say("email Sapphire")
        advanceTimeBy(3_000)
        runCurrent()
        val submit = rig.hermes.submits.single()
        assertTrue("the task starts without waiting for an answer", "Clarity: key_doubt (0.80)" in submit.text)
        assertTrue(rig.openRouter.clips.contains("Safilo or Sapphire?"))
        assertTrue(rig.events.any { it.second == CascadeEvent.ClarityDoubt("u1", "key_doubt") })
        advanceUntilIdle()
        assertEquals("nobody answers: nothing more is sent", 1, rig.hermes.submits.size)
        assertTrue(rig.openRouter.planPrompts.single().contains("Transcript: \"email Sapphire\""))
    }

    @Test fun aResultIsBriefedThenReadInFullOnRequest() = rigTest { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("new_task"), "line" to answer("start.one")))
        rig.openRouter.plan = { OpenRouterResult.Ok(briefs(brief("Check stock.", "stock check", "checking the stock"))) }
        rig.say("check the stock")
        advanceUntilIdle()
        rig.openRouter.writes = { _, prompt -> if ("at most 2 sentences" in prompt) listOf("RB2140 has 14.", "Full answer's on screen.") else listOf("Sure.") }
        rig.hermes.complete("t1", "RB2140: 14 in stock.\nRB3025: 2 left.\nRB4171: none.")
        advanceUntilIdle()
        assertTrue(rig.events.any { it.second == CascadeEvent.ResultSpoken("t1") })
        assertTrue(rig.openRouter.clips.contains("RB2140 has 14."))
        rig.actor.send(com.folio.hermes.cascade.Event.TaskTapped("t1", com.folio.hermes.cascade.TaskTap.ReadAll))
        advanceUntilIdle()
        assertEquals(listOf("RB2140: 14 in stock.", "RB3025: 2 left.", "RB4171: none."), rig.openRouter.clips.takeLast(3))
        assertEquals(1, rig.hermes.submits.size)
    }

    @Test fun aFailedTaskTurnDoesNotHoldItsSlot() = rigTest(CascadeSettings(maxTasks = 1)) { rig ->
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("new_task"), "line" to answer("start.one")))
        rig.openRouter.plan = { OpenRouterResult.Ok(briefs(brief("First.", "first job", "first"))) }
        rig.say("first job")
        advanceUntilIdle()
        rig.openRouter.jevScripts += FakeOpenRouter.JevScript(100, ok("action" to answer("new_task"), "line" to answer("start.one")))
        rig.openRouter.plan = { OpenRouterResult.Ok(briefs(brief("Second.", "second job", "second"))) }
        rig.say("second job")
        advanceUntilIdle()
        assertEquals(1, rig.hermes.submits.size)
        rig.hermes.complete("t1", "", TurnStatus.Error)
        advanceUntilIdle()
        assertEquals(listOf("t1", "t2"), rig.hermes.submits.map { it.taskId })
    }
}
