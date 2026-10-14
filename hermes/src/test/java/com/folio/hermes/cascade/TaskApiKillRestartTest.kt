package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.store.CoreDto
import com.folio.hermes.cascade.store.StoreCodec
import com.folio.hermes.cascade.store.StoreKeys
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kill-and-restart harness for tasks started through the task API (grok-voice slice; the
 * Grok engine's path: no Bill call, `restore()` only). Kill the actor before its k-th effect (for
 * every k, durable writes included), start a new core on the same [Store] and fake Hermes with
 * `restore()` (which launches the one `TaskRecovery`), and check:
 * - **no double submit:** Hermes never gets a brief or an update twice;
 * - **no lost command:** every command that got its reply, or whose task or update run was ever
 *   stored, reaches Hermes after the restart;
 * - **nothing left to the user:** no task ends `NotSent` (session-first holds for the API too).
 */
class TaskApiKillRestartTest {
    private val bank = PhraseBank.load()
    private val briefs = listOf("Check stock of RB2140 in Leeds.", "Email Safilo about order 1182.")
    private val update = "Also check the York store."

    private class Run(val core: CascadeCore, val scope: CoroutineScope, val replies: MutableMap<String, TaskReply>) {
        fun close() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    private fun TestScope.core(store: Store, server: FakeServer, gate: EffectGate): Run {
        val clock = TestClock(this)
        val hermes = ClientHermes(server)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val core = CascadeCore.create(
            scope = scope, ports = CascadePorts(FakeOpenRouter(clock), hermes, hermes, FakeAudioOut(clock), store, clock),
            bank = bank, settings = { CascadeSettings() }, telemetry = TelemetrySink.None, zone = ZoneOffset.UTC, gate = gate, seed = 3,
        )
        return Run(core, scope, linkedMapOf())
    }

    /** Two tasks started by key, then an update to the first once it runs. Replies are recorded, never awaited by the test. */
    private fun TestScope.scenario(run: Run) {
        briefs.forEachIndexed { i, b -> run.scope.launch { run.replies["k${i + 1}"] = run.core.tasks.startTask(b, "Job ${i + 1}", key = "k${i + 1}") } }
        advanceUntilIdle()
        run.scope.launch { run.replies["k3"] = run.core.tasks.updateTask("t1", update, UpdateMode.Queue, key = "k3") }
        advanceUntilIdle()
    }

    private fun countEffects(): Int {
        var count = 0
        runTest {
            val server = FakeServer()
            val run = core(MemoryStore(), server, EffectGate { count++; true })
            scenario(run)
            (briefs + update).forEach { assertEquals("'$it' sent once with no kill", 1, server.prompts.count { p -> p == it }) }
            assertEquals(TaskReply.Updated("t1", "queue"), run.replies["k3"])
            run.close()
        }
        return count
    }

    @Test fun killingAnApiStartedTaskAnywhereLosesNothingAndNeverSubmitsTwice() {
        val total = countEffects()
        assertTrue("the scenario runs a real number of effects ($total)", total > 15)
        var lost = 0
        var doubles = 0
        var notSent = 0
        var durableKills = 0
        for (k in 0..total) {
            runTest {
                val store = MemoryStore()
                val server = FakeServer()
                var seen = 0
                val everDurable = linkedSetOf<String>()
                val first = core(store, server, EffectGate { _ ->
                    everDurable += durableKeys(store)
                    seen++ < k
                })
                scenario(first)
                everDurable += durableKeys(store)
                // A reply means the step's durable writes ran.
                everDurable += first.replies.filterValues { it is TaskReply.Started || it is TaskReply.Updated }.keys
                first.close()

                val second = core(store, server, EffectGate.Open)
                second.core.restore()
                advanceUntilIdle()
                val after = second.core.actor.state.value
                if (everDurable.isNotEmpty()) durableKills++

                (briefs + update).forEach { t ->
                    if (server.prompts.count { it == t } > 1) {
                        doubles++
                        println("k=$k: '$t' submitted twice")
                    }
                }
                for (key in everDurable) {
                    val text = when (key) {
                        "k1" -> briefs[0]
                        "k2" -> briefs[1]
                        else -> update
                    }
                    if (text !in server.prompts) {
                        lost++
                        println("k=$k: $key lost")
                    }
                }
                notSent += after.tasks.tasks.count { it.send == SendState.NotSent }
                second.close()
            }
        }
        println("task API kill-restart: $total effects, $durableKills kills with durable commands, lost=$lost doubles=$doubles notSent=$notSent")
        assertEquals("lost commands", 0, lost)
        assertEquals("double submits", 0, doubles)
        assertEquals("nothing left for the user to retry", 0, notSent)
        assertTrue("most kills happen with durable commands ($durableKills)", durableKills > total / 3)
    }

    /** The command keys the store holds: tasks by their turn id, updates by their run's turn id or outbox stage. */
    private fun durableKeys(store: Store): Set<String> {
        val core = StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE)) ?: CoreDto()
        val tasks = store.keys(StoreKeys.TASK_PREFIX).mapNotNull { StoreCodec.decodeTask(store.get(it)) }
        val turns = tasks.mapNotNull { it.turnId } + tasks.flatMap { t -> t.runs.values.mapNotNull { it.turnId } } +
            core.outbox.stages.filterValues { v -> v.any { it.startsWith("update:") } }.keys
        return turns.filter { it.startsWith("api:") }.map { it.removePrefix("api:") }.toSet()
    }
}
