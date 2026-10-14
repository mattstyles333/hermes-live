package com.folio.hermes.live

import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.FakeAudioOut
import com.folio.hermes.cascade.FakeOpenRouter
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TestClock
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPT-Live parallel agents ([AgentDesk]) on the real shared task API: a [CascadeCore] on the env
 * `:live` ships, a fake Hermes ([FakeServer]), a virtual clock. The voice is a recorder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentDeskTest {
    private val bank = PhraseBank.load()

    class FakeVoice : AgentVoice {
        val said = mutableListOf<Pair<String?, String>>()
        val thought = mutableListOf<Pair<String, String>>()
        val instructed = mutableListOf<String>()
        override fun say(delegationId: String?, text: String) { said += delegationId to text }
        override fun think(delegationId: String, text: String) { thought += delegationId to text }
        override fun instruct(text: String) { instructed += text }
    }

    private inner class Rig(
        val scope: TestScope,
        val server: FakeServer = FakeServer(),
        val intents: AgentIntents = HeuristicAgentIntents,
        val store: MemoryStore = MemoryStore(),
    ) : AutoCloseable {
        val job = SupervisorJob()
        val run = CoroutineScope(scope.coroutineContext + job)
        val clock = TestClock(scope)
        val hermes = ClientHermes(server)
        val core = CascadeCore.create(
            scope = run,
            ports = CascadePorts(FakeOpenRouter(clock), hermes, hermes, FakeAudioOut(clock), store, clock),
            bank = bank, settings = { CascadeSettings() }, telemetry = TelemetrySink.None,
            zone = ZoneOffset.UTC, seed = 7,
        )
        val tasks = DelegationTasks.of(core)
        val events = mutableListOf<AgentDeskEvent>()
        val voice = FakeVoice()
        fun desk() = AgentDesk(run, tasks, intents, { AgentText.heuristicLabel(it.prompt) }, { scope.testScheduler.currentTime }) { events += it }
        val desk = desk().also { it.start() }

        fun ask(id: String?, text: String) = desk.onDelegation(id, DelegationPrompt(text, "User: $text"))
        fun sessionOf(prompt: String) = server.sessionFor(prompt)
        fun tool(prompt: String, label: String) = sessionOf(prompt).live.forEach { it.trySend(TurnUpdate.ToolStarted("x", label.replace(' ', '_'), label)) }

        override fun close() {
            desk.stop()
            job.cancel()
        }
    }

    private fun TestScope.quiet(rig: Rig, ms: Long = 2_000) {
        advanceTimeBy(ms)
        rig.desk.signals(userSpeaking = false, voiceSpeaking = false)
        runCurrent()
    }

    @Test fun eachDelegationIsItsOwnTaskAndTheyRunAtOnce() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            rig.ask("del_2", "Email Safilo about the late order")
            rig.ask("del_3", "Find the cheapest flight to Dublin on Friday")
            advanceUntilIdle()
            assertEquals("one Hermes session per delegation", 3, rig.server.sessions.size)
            assertEquals(3, rig.server.prompts.size)
            assertTrue(rig.server.prompts[0].startsWith("Check stock of RB2140 in Leeds"))
            val views = rig.tasks.status()
            assertEquals(listOf("running", "running", "running"), views.map { it.status })
            assertEquals(listOf("Check stock RB2140", "Email Safilo late", "Find cheapest flight"), views.map { it.label })
            // Each start is quiet progress on its own delegation, never commentary.
            assertEquals(listOf("del_1", "del_2", "del_3"), rig.voice.thought.map { it.first })
            assertTrue(rig.voice.thought.all { it.second.startsWith("Started task '") })
            assertTrue(rig.voice.said.isEmpty())
            assertTrue(rig.desk.working)
            assertEquals(3, rig.desk.running.value)
            assertEquals("the session note went first", AgentDesk.SESSION_NOTE, rig.voice.instructed.first())
        }
    }

    @Test fun theSessionAndDelegationIdAreTheKeySoARepeatNeverSubmitsTwice() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice, sessionId = "sess_A")
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            advanceUntilIdle()
            assertEquals(1, rig.server.prompts.size)
            // The same delegation of the same session again (another desk, as after a process restart): the stored key wins.
            val again = rig.desk()
            again.start()
            again.attach(rig.voice, sessionId = "sess_A")
            again.onDelegation("del_1", DelegationPrompt("Check stock of RB2140 in Leeds", "User: Check stock of RB2140 in Leeds"))
            advanceUntilIdle()
            assertEquals("no double submit", 1, rig.server.prompts.size)
            assertTrue(rig.events.any { it is AgentDeskEvent.Started && it.existing })
            assertEquals("one task", 1, rig.tasks.status().size)
            again.stop()
        }
    }

    @Test fun anotherSessionsDelegationWithTheSameIdIsNewWork() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice, sessionId = "sess_A")
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            advanceUntilIdle()
            // The next call (or a reopened session) numbers its delegations afresh.
            rig.desk.detach()
            rig.desk.attach(rig.voice, sessionId = "sess_B")
            rig.ask("del_1", "Email Safilo about the late order")
            advanceUntilIdle()
            assertEquals(2, rig.server.prompts.size)
            assertEquals(listOf("Check stock RB2140", "Email Safilo late"), rig.tasks.status().map { it.label })
            // sess_A's task finishing never speaks on sess_B's del_1.
            rig.server.complete(rig.sessionOf(rig.server.prompts[0]).id, "Four in Leeds.")
            advanceUntilIdle()
            quiet(rig, 4_000)
            assertTrue(rig.voice.said.none { it.first == "del_1" && it.second.contains("Four in Leeds") })
            assertTrue(rig.voice.instructed.any { it.startsWith("Task 'Check stock RB2140' finished: Four in Leeds.") })
        }
    }

    @Test fun toolStepsGoToTheModelAsThinkingOnTheirDelegation() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            rig.ask("del_2", "Email Safilo about the late order")
            advanceUntilIdle()
            rig.tool(rig.server.prompts[1], "send email")
            rig.tool(rig.server.prompts[0], "web search")
            advanceUntilIdle()
            val progress = rig.voice.thought.filter { it.second.contains("is working:") }
            assertEquals(
                setOf(
                    "del_2" to "Task 'Email Safilo late' is working: send email. Not done yet.",
                    "del_1" to "Task 'Check stock RB2140' is working: web search. Not done yet.",
                ),
                progress.toSet(),
            )
            assertEquals(2, progress.size)
            // The same tool again says nothing new.
            rig.tool(rig.server.prompts[0], "web search")
            advanceUntilIdle()
            assertEquals(2, rig.voice.thought.count { it.second.contains("is working:") })
        }
    }

    @Test fun aResultWaitsUntilTheUserIsSilentAndTheVoiceIdleThenIsMarkedHeard() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            advanceUntilIdle()
            rig.desk.signals(userSpeaking = true, voiceSpeaking = false)
            rig.desk.userWords()
            rig.server.complete(rig.sessionOf(rig.server.prompts[0]).id, "There are 4 in Leeds. Two more arrive Monday.")
            runCurrent()
            assertEquals(1, rig.desk.pendingResults.value)
            assertTrue("not while the user speaks", rig.voice.said.isEmpty())
            // The user stops: still the settle time, then the voice's answer to that turn (or the grace).
            rig.desk.userWords()
            rig.desk.signals(userSpeaking = false, voiceSpeaking = false)
            advanceTimeBy(1_000)
            rig.desk.signals(userSpeaking = false, voiceSpeaking = false)
            assertTrue("within the settle time", rig.voice.said.isEmpty())
            advanceTimeBy(500)
            rig.desk.voiceWords()
            rig.desk.signals(userSpeaking = false, voiceSpeaking = true)
            assertTrue("not over the voice", rig.voice.said.isEmpty())
            quiet(rig, 900)
            assertEquals(listOf("del_1" to "Task 'Check stock RB2140' finished: There are 4 in Leeds. Two more arrive Monday."), rig.voice.said)
            assertEquals(0, rig.desk.pendingResults.value)
            assertTrue(rig.events.any { it is AgentDeskEvent.Injected && it.asCommentary == 1 })
            assertTrue(rig.core.actor.state.value.tasks.tasks.single().unread)
            // The voice takes it up: read, so a later call never replays it.
            rig.desk.voiceWords()
            advanceUntilIdle()
            assertFalse(rig.core.actor.state.value.tasks.tasks.single().unread)
        }
    }

    @Test fun aResultFromAnEarlierSessionGoesAsAnInstruction() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            advanceUntilIdle()
            // The session closes (on demand or a dropped line) and a new one opens: del_1 is not in it.
            rig.desk.detach()
            val next = FakeVoice()
            rig.desk.attach(next)
            rig.server.complete(rig.sessionOf(rig.server.prompts[0]).id, "There are 4 in Leeds.")
            advanceUntilIdle()
            quiet(rig, 4_000)
            assertTrue(next.said.isEmpty())
            assertEquals(AgentDesk.SESSION_NOTE, next.instructed[0])
            assertTrue(next.instructed[1].startsWith("Task 'Check stock RB2140' finished: There are 4 in Leeds."))
            assertTrue(next.instructed[1].contains("Tell the user"))
        }
    }

    @Test fun whileNoSessionIsOpenResultsQueueForTheNext() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            advanceUntilIdle()
            rig.desk.detach()
            rig.server.complete(rig.sessionOf(rig.server.prompts[0]).id, "There are 4 in Leeds.")
            advanceUntilIdle()
            quiet(rig, 4_000)
            assertEquals("a reason to reopen", 1, rig.desk.pendingResults.value)
            assertEquals("Tasks running: none open now", null, rig.desk.openTasksNote())
        }
    }

    @Test fun statusAndCancelByVoiceNeverReachHermesAsNewWork() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            rig.ask("del_2", "Email Safilo about the late order")
            advanceUntilIdle()
            rig.tool(rig.server.prompts[0], "web search")
            advanceUntilIdle()
            assertEquals("Tasks running: 'Check stock RB2140' (working: web search), 'Email Safilo late' (working).", rig.desk.openTasksNote())

            rig.ask("del_3", "How's the stock check going?")
            advanceUntilIdle()
            assertEquals(2, rig.server.prompts.size)
            assertEquals("del_3" to "Task 'Check stock RB2140': working: web search.", rig.voice.said.last())

            rig.ask("del_4", "Cancel the stock check")
            advanceUntilIdle()
            assertEquals(2, rig.server.prompts.size)
            assertEquals("del_4" to "Stopped task 'Check stock RB2140'.", rig.voice.said.last())
            assertEquals(TaskState.Cancelled, rig.core.actor.state.value.tasks["t1"]!!.state)
            assertEquals(TaskState.Running, rig.core.actor.state.value.tasks["t2"]!!.state)

            rig.ask("del_5", "Stop everything")
            advanceUntilIdle()
            assertEquals("del_5" to "Stopped task 'Email Safilo late'.", rig.voice.said.last())
            assertEquals(listOf("handled status", "handled cancel", "handled cancel"), rig.events.filterIsInstance<AgentDeskEvent.Handled>().map { "handled ${it.intent}" })
        }
    }

    @Test fun jevClassifiesWhenItCanAndAnAmbiguousCancelAsksWhich() = runTest {
        val answers = ArrayDeque<OpenRouterResult<Map<String, JevAnswer>>>()
        val asked = mutableListOf<Set<String>>()
        val jev = JevAgentIntents({ _, questions, _ -> asked += questions.keys; answers.removeFirst() })
        Rig(this, intents = jev).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            advanceUntilIdle()
            assertTrue("no task to be about: no Jev call", asked.isEmpty())
            // No answer scripted: the call fails and the words decide (new work).
            rig.ask("del_2", "Email Safilo about the late order")
            advanceUntilIdle()
            assertEquals(1, asked.size)
            assertEquals(2, rig.server.prompts.size)

            // "drop the email one": Jev names t2.
            answers += OpenRouterResult.Ok(mapOf("action" to JevAnswer("choice", "cancel_task", confidence = 0.9), "task" to JevAnswer("choice", "t2", confidence = 0.8)))
            rig.ask("del_3", "Drop the email one")
            advanceUntilIdle()
            assertEquals("del_3" to "Stopped task 'Email Safilo late'.", rig.voice.said.last())
            assertEquals(setOf("action", "task", "update_mode"), asked.last())

            // A queued update: passed to the task, not a new one.
            answers += OpenRouterResult.Ok(mapOf("action" to JevAnswer("choice", "update_task", confidence = 0.7), "task" to JevAnswer("choice", "t1", confidence = 0.9)))
            rig.ask("del_4", "Also check Manchester")
            advanceUntilIdle()
            assertEquals("Also check Manchester", rig.sessionOf(rig.server.prompts[0]).prompts.last())
            assertEquals("del_4" to "Passed that to task 'Check stock RB2140'. Not done yet.", rig.voice.thought.last())

            // Jev unreachable: the words decide (new work here).
            answers += OpenRouterResult.Failed(FailureKind.Offline)
            rig.ask("del_5", "Book a table for two tonight")
            advanceUntilIdle()
            assertEquals(4, rig.server.prompts.size)
        }
    }

    @Test fun anAmbiguousCancelAsksWhichTask() = runTest {
        Rig(this).use { rig ->
            rig.desk.attach(rig.voice)
            rig.ask("del_1", "Check stock of RB2140 in Leeds")
            rig.ask("del_2", "Email Safilo about the late order")
            advanceUntilIdle()
            rig.ask("del_3", "Cancel it")
            advanceUntilIdle()
            assertEquals("del_3" to "Which task should I stop: 'Check stock RB2140' or 'Email Safilo late'?", rig.voice.said.last())
            assertEquals(listOf(TaskState.Running, TaskState.Running), rig.core.actor.state.value.tasks.tasks.map { it.state })
        }
    }
}
