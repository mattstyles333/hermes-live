package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.registry.TaskRecovery
import com.folio.hermes.cascade.store.CoreDto
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys
import com.folio.hermes.cascade.store.durableEnv
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kill-and-restart harness (R-DUR-5, R-ARC-3, R-Q-4) on the real persistence
 * ([com.folio.hermes.cascade.store.DurablePersistence], the task registry and conversations):
 * run a scenario, kill the actor before its k-th effect (for every k, durable writes included),
 * start a new actor and a [TaskRecovery] on the same [Store] and the same [FakeServer], and check
 * that across every kill point
 * - **no double submit:** the server never gets a turn's text twice;
 * - **no lost turn:** every turn the store had seen is submitted, still held, or a `NotSent` task
 *   with its brief ("tap to retry"; R-DUR-4);
 * - **persist before network (R-DUR-1):** whatever the server got at the kill, the store had a task for;
 * - a turn held with no task at the kill is submitted exactly once after the restart.
 *
 * The one honest grey zone: a kill after the prompt left but before Hermes's session id was
 * stored. Nobody can tell whether it arrived, so the task is `NotSent` (never resent on its own).
 * Those kills are counted and must stay inside that window.
 */
class CascadeKillRestartTest {
    private val bank = PhraseBank.load()

    private val newTask = OpenRouterResult.Ok(
        mapOf("turn" to answer("done"), "action" to answer("new_task"), "line" to answer("start.lookup"), "enough" to answer("line_then_writer")),
    )
    private val texts = listOf("check stock of RB2140", "email Safilo about the order")

    private class Run(val actor: CascadeActor, val openRouter: FakeOpenRouter, val hermes: ClientHermes, val scope: CoroutineScope) {
        fun close() = scope.coroutineContext[Job]!!.cancel()
    }

    private fun TestScope.actor(store: Store, server: FakeServer, gate: EffectGate): Run {
        val clock = TestClock(this)
        val openRouter = FakeOpenRouter(clock).apply {
            jevDefault = FakeOpenRouter.JevScript(10, newTask)
            // The brief is the request itself, so each turn's submit is recognisable.
            plan = { prompt -> OpenRouterResult.Ok("LABEL: job\nPROMPT: ${prompt.substringAfter("The request: \"").trimEnd('"')}\nSAY: On it.") }
        }
        val hermes = ClientHermes(server)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val actor = CascadeActor(
            scope = scope, env = durableEnv(bank), settings = { CascadeSettings() },
            openRouter = openRouter, hermes = hermes, audio = FakeAudioOut(clock), clock = clock, store = store,
            telemetry = TelemetrySink.None, gate = gate, seed = 3,
        )
        return Run(actor, openRouter, hermes, scope)
    }

    /** Two turns dictated offline (Jev unreachable), then the network comes back and they are sent. */
    private fun TestScope.offline(run: Run) {
        run.actor.start()
        runCurrent()
        run.openRouter.jevDefault = FakeOpenRouter.JevScript(10, OpenRouterResult.Failed(FailureKind.Offline))
        for (text in texts) say(run, text)
        run.openRouter.jevDefault = FakeOpenRouter.JevScript(10, newTask)
        run.actor.network(true)
        advanceUntilIdle()
    }

    /** Two new tasks asked for while online: commit, plan, submit. */
    private fun TestScope.online(run: Run) {
        run.actor.start()
        runCurrent()
        for (text in texts) say(run, text)
        advanceUntilIdle()
    }

    private fun TestScope.say(run: Run, text: String) {
        run.actor.speechStarted()
        run.actor.final(text)
        run.actor.speechEnded()
        advanceTimeBy(2_000)
        runCurrent()
    }

    private fun countEffects(scenario: TestScope.(Run) -> Unit): Int {
        var count = 0
        runTest {
            val run = actor(MemoryStore(), FakeServer(), EffectGate { count++; true })
            scenario(run)
            run.close()
        }
        return count
    }

    @Test fun theOfflineScenarioSendsBothHeldTurnsOnce() = runTest {
        val server = FakeServer()
        val store = MemoryStore()
        val run = actor(store, server, EffectGate.Open)
        offline(run)
        assertEquals(texts, server.prompts)
        assertEquals(2, run.actor.state.value.outbox.sent.size)
        val core = StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE))!!
        assertTrue("nothing left held or awaiting", core.outbox.held.isEmpty() && core.outbox.awaiting.isEmpty())
        assertEquals(listOf("u1", "u2"), core.outbox.sent)
        run.close()
    }

    @Test fun theTaskIsDurableBeforeItsSubmitAndTheTurnClearsOnlyOnAccepted() = runTest {
        val store = MemoryStore()
        val writes = mutableListOf<Pair<Effect, CoreDto?>>()
        val run = actor(store, FakeServer(), EffectGate { e ->
            writes += e to StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE))
            true
        })
        offline(run)
        val effects = writes.map { it.first }
        val submit = effects.indexOfFirst { it is Effect.SubmitTask }
        val taskWrite = effects.subList(0, submit).indexOfLast { it is Effect.Persist && it.key == StoreKeys.task("t1") }
        assertTrue("the task is stored before its submit", taskWrite in 0 until submit)
        // In the submit's step the turn is still in the durable outbox (awaiting Accepted), never cleared with it.
        val atSubmit = writes[submit].second!!
        assertTrue("u1 awaits Accepted at the submit", "u1" in atSubmit.outbox.awaiting || atSubmit.outbox.held.any { it.turnId == "u1" })
        assertTrue("u1 is not sent yet at the submit", "u1" !in atSubmit.outbox.sent)
        val core = StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE))!!
        assertTrue("after Accepted it is sent", "u1" in core.outbox.sent && core.outbox.awaiting.isEmpty())
        run.close()
    }

    @Test fun killingTheOfflineScenarioAnywhereLosesNothingAndNeverSubmitsTwice() = killEverywhere("offline") { offline(it) }

    @Test fun killingTheOnlineScenarioAnywhereLosesNothingAndNeverSubmitsTwice() = killEverywhere("online") { online(it) }

    private fun killEverywhere(name: String, scenario: TestScope.(Run) -> Unit) {
        val total = countEffects(scenario)
        assertTrue("the scenario runs a real number of effects ($total)", total > 40)
        var lost = 0
        var doubles = 0
        var durableKills = 0
        var notSent = 0
        var greyZone = 0
        for (k in 0..total) {
            runTest {
                val store = MemoryStore()
                val server = FakeServer()
                var seen = 0
                val first = actor(store, server, EffectGate { seen++ < k })
                scenario(first)
                first.close()
                val atKill = Durable.read(store)
                val promptsAtKill = server.prompts.toList()
                // R-DUR-1: the server never has a prompt the store has no task for.
                promptsAtKill.forEach { p -> assertTrue("k=$k: '$p' reached Hermes before its task was stored", atKill.tasks.any { it.brief == p }) }

                val second = actor(store, server, EffectGate.Open)
                second.actor.start()
                runCurrent()
                TaskRecovery(second.hermes, second.hermes).launch(second.scope, second.actor)
                runCurrent()
                second.actor.network(true)
                advanceUntilIdle()
                val after = second.actor.state.value

                texts.forEach { t -> if (server.prompts.count { it == t } > 1) doubles++ }
                val turns = atKill.turnIds.filter { it == "u1" || it == "u2" }
                if (turns.isNotEmpty()) durableKills++
                for (turnId in turns) {
                    val text = texts[turnId.drop(1).toInt() - 1]
                    val submitted = server.prompts.count { it == text } == 1
                    val held = after.outbox.held.any { it.turnId == turnId }
                    val task = after.tasks.tasks.firstOrNull { it.turnId == turnId }
                    val retryable = task != null && task.send == SendState.NotSent && task.brief == text
                    if (!submitted && !held && !retryable) {
                        lost++
                        println("$name k=$k: $turnId lost (at kill: $atKill)")
                    }
                    if (retryable) {
                        notSent++
                        val taskAtKill = atKill.tasks.firstOrNull { it.turnId == turnId }
                        assertTrue("k=$k: $turnId NotSent only from a Sending task with no stored session", taskAtKill != null && taskAtKill.send == SendState.Sending && taskAtKill.storedSessionId == null)
                        if (submitted) greyZone++
                    }
                    // A turn the store held with no task yet is carried out once after the restart.
                    if (atKill.held.contains(turnId) && atKill.tasks.none { it.turnId == turnId }) {
                        assertEquals("$name k=$k: $turnId held at the kill is submitted once", 1, server.prompts.count { it == text })
                    }
                }
                second.close()
            }
        }
        println("kill-restart $name: $total effects, $durableKills kills with durable turns, lost=$lost doubles=$doubles notSent=$notSent (grey zone $greyZone)")
        assertEquals("lost turns", 0, lost)
        assertEquals("double submits", 0, doubles)
        assertTrue("most kills happen with durable turns ($durableKills)", durableKills > total / 3)
        assertTrue("NotSent only in the narrow window between persist and Hermes's session id ($notSent)", notSent <= total / 5)
    }

    /** What the store holds, read with the real codec. */
    private class Durable(val tasks: List<TaskRecord>, val held: Set<String>, val turnIds: Set<String>) {
        override fun toString() = "tasks=${tasks.map { "${it.id}/${it.turnId}/${it.send}/${it.storedSessionId}" }} held=$held"

        companion object {
            fun read(store: Store): Durable {
                val core = StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE)) ?: CoreDto()
                val tasks = store.keys(StoreKeys.TASK_PREFIX).mapNotNull { StoreCodec.decodeTask(store.get(it)) }
                val held = core.outbox.held.map { it.turnId }.toSet()
                return Durable(tasks, held, held + core.outbox.awaiting + core.outbox.sent + tasks.mapNotNull { it.turnId })
            }
        }
    }
}
