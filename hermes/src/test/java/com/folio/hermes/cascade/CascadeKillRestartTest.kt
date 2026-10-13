package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.store.StatePersistence
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
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
 * The kill-and-restart harness (R-DUR-5, R-ARC-3): run a scenario, kill the actor before its
 * k-th effect (for every k), start a new actor on the same [Store], and check what survived.
 *
 * S0 wires it to the in-memory [Store] with [OutboxPersistence], a test-only persistence of the
 * outbox (held turns, sent ids, stages). S-B replaces it with the real `store/` persistence and
 * the task registry, and tightens [inFlight] to zero (a submit that may have gone out before the
 * kill is recovered from the registry, R-DUR-2/3).
 */
class CascadeKillRestartTest {
    private val bank = PhraseBank.load()

    /** Test-only: persists the outbox under one key, written whenever it changes. */
    object OutboxPersistence : StatePersistence {
        private const val KEY = "outbox"
        private const val SEP = "\u0001"

        override fun save(before: State, after: State): List<Effect> =
            if (before.outbox == after.outbox) emptyList() else listOf(Effect.Persist(KEY, encode(after.outbox)))

        override fun restore(store: Store, fresh: State): State = store.get(KEY)?.let { fresh.copy(outbox = decode(it)) } ?: fresh

        fun encode(box: OutboxState): String = buildList {
            box.held.forEach { add(listOf("held", it.turnId, it.turn.text, it.turn.speechEndAt).joinToString(SEP)) }
            box.sent.forEach { add(listOf("sent", it).joinToString(SEP)) }
            box.stages.forEach { (id, stages) -> stages.forEach { add(listOf("stage", id, it).joinToString(SEP)) } }
        }.joinToString("\n")

        fun decode(text: String): OutboxState {
            var box = OutboxState()
            text.lines().filter { it.isNotEmpty() }.map { it.split(SEP) }.forEach { f ->
                box = when (f[0]) {
                    "held" -> box.hold(f[1], HeldTurn(f[2], f[3].toLong())).first
                    "sent" -> box.copy(sent = box.sent + f[1])
                    else -> box.markStage(f[1], f[2])
                }
            }
            return box
        }
    }

    private val newTask = OpenRouterResult.Ok(
        mapOf("turn" to answer("done"), "action" to answer("new_task"), "line" to answer("start.lookup"), "enough" to answer("line_then_writer")),
    )

    private class Run(val actor: CascadeActor, val openRouter: FakeOpenRouter, val scope: CoroutineScope)

    private fun TestScope.actor(store: Store, hermes: FakeHermes, gate: EffectGate): Run {
        val clock = TestClock(this)
        val openRouter = FakeOpenRouter(clock).apply {
            jevDefault = FakeOpenRouter.JevScript(10, newTask)
            // The brief is the request itself, so each turn's submit is recognisable.
            plan = { prompt -> OpenRouterResult.Ok("LABEL: job\nPROMPT: ${prompt.substringAfter("The request: \"").trimEnd('"')}\nSAY: On it.") }
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val actor = CascadeActor(
            scope = scope, env = CascadeEnv(bank, persistence = OutboxPersistence), settings = { CascadeSettings() },
            openRouter = openRouter, hermes = hermes, audio = FakeAudioOut(clock), clock = clock, store = store,
            telemetry = TelemetrySink.None, gate = gate, seed = 3,
        )
        return Run(actor, openRouter, scope)
    }

    /** Two turns dictated offline (Jev unreachable), then the network comes back and they are sent. */
    private fun TestScope.scenario(run: Run) {
        run.actor.start()
        runCurrent()
        run.openRouter.jevDefault = FakeOpenRouter.JevScript(10, OpenRouterResult.Failed(FailureKind.Offline))
        for (text in listOf("check stock of RB2140", "email Safilo about the order")) {
            run.actor.speechStarted()
            run.actor.final(text)
            run.actor.speechEnded()
            advanceTimeBy(2_000)
            runCurrent()
        }
        run.openRouter.jevDefault = FakeOpenRouter.JevScript(10, newTask)
        run.actor.network(true)
        advanceUntilIdle()
    }

    private fun countEffects(): Int {
        var count = 0
        runTest {
            val run = actor(MemoryStore(), FakeHermes(), EffectGate { count++; true })
            scenario(run)
            run.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
        return count
    }

    @Test fun theScenarioSendsBothHeldTurnsOnce() = runTest {
        val hermes = FakeHermes()
        val store = MemoryStore()
        val run = actor(store, hermes, EffectGate.Open)
        scenario(run)
        assertEquals(listOf("check stock of RB2140", "email Safilo about the order"), hermes.submits.map { it.text })
        assertEquals(2, run.actor.state.value.outbox.sent.size)
        assertEquals("", OutboxPersistence.decode(store.get("outbox")!!).ids().joinToString())
        run.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test fun persistRunsBeforeAnyNetworkEffectOfTheSameStep() = runTest {
        val order = mutableListOf<Effect>()
        val run = actor(MemoryStore(), FakeHermes(), EffectGate { order += it; true })
        scenario(run)
        val submit = order.indexOfFirst { it is Effect.SubmitTask }
        val persisted = order.subList(0, submit).filterIsInstance<Effect.Persist>().last()
        assertTrue("the task stage is durable before the submit", OutboxPersistence.decode(persisted.value!!).stageDone("u1", "task"))
        run.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test fun killingBeforeAnyEffectLosesNoHeldTurnAndNeverSubmitsTwice() {
        val total = countEffects()
        assertTrue("the scenario runs a real number of effects ($total)", total > 50)
        var inFlightKills = 0
        var killsWithHeldTurns = 0
        for (k in 0..total) {
            runTest {
                val store = MemoryStore()
                val hermes = FakeHermes()
                var seen = 0
                val first = actor(store, hermes, EffectGate { seen++ < k })
                scenario(first)
                val heldAtKill = store.get("outbox")?.let(OutboxPersistence::decode) ?: OutboxState()
                first.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()

                val second = actor(store, hermes, EffectGate.Open)
                second.actor.start()
                runCurrent()
                second.actor.network(true)
                advanceUntilIdle()
                val after = second.actor.state.value.outbox

                // Never twice: at most one submit per turn.
                hermes.submits.groupingBy { it.text }.eachCount().forEach { (text, n) -> assertEquals("k=$k: submits of '$text'", 1, n) }
                if (heldAtKill.size > 0) killsWithHeldTurns++
                // Nothing the store saw (held, or sent) is lost: Hermes got it, it is still held, or it was in flight.
                val texts = mapOf("u1" to "check stock of RB2140", "u2" to "email Safilo about the order")
                for (turnId in heldAtKill.ids() + heldAtKill.sent) {
                    val submitted = hermes.submits.any { it.text == texts[turnId] }
                    val stillHeld = turnId in after.ids()
                    val inFlight = !submitted && inFlight(heldAtKill, turnId)
                    if (inFlight) inFlightKills++
                    assertTrue("k=$k: $turnId is submitted, held or in flight", submitted || stillHeld || inFlight)
                }
                second.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
        }
        println("kill-restart: $total effects, $killsWithHeldTurns kills with held turns, $inFlightKills left a turn in flight")
        assertTrue("most kills happen with turns held ($killsWithHeldTurns)", killsWithHeldTurns > total / 2)
        // Known gap, S-B's to close: a kill after a turn's durable "task" stage but before its submit
        // reaches Hermes (the submit coroutine is launched, not yet run) leaves the turn marked sent
        // with no task. The registry must recover it (persist the intent, confirm on `Accepted`,
        // resubmit idempotently; R-DUR-2/3). S-B changes this to assertEquals(0, inFlightKills).
        assertTrue("in-flight kills stay inside the persist→submit window ($inFlightKills)", inFlightKills in 1..killsWithHeldTurns / 4)
    }

    /** The turn's task stage was durable, so a resend will not start it again (S-B: recover it from the registry). */
    private fun inFlight(box: OutboxState, turnId: String) = box.stageDone(turnId, "task")
}
