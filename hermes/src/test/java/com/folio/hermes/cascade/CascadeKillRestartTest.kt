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
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kill-and-restart harness (R-DUR-5, R-ARC-3, R-Q-4) on the real persistence
 * ([com.folio.hermes.cascade.store.DurablePersistence], the task registry and conversations):
 * run a scenario, kill the actor before its k-th effect (for every k, durable writes included),
 * start a new actor and a [TaskRecovery] on the same [Store] and the same [FakeServer], and check
 * that across every kill point of the new-task scenarios (offline flush and online)
 * - **no double submit:** the server never gets a turn's text twice;
 * - **no lost turn:** every committed turn (stored at any point, or past its commit step's durable
 *   writes) is submitted, still held, or a `NotSent` task with its brief ("tap to retry"; R-DUR-4);
 * - **persist before network (R-DUR-1):** whatever new task the server got at the kill, the store had.
 *
 * Updates to a running task have a measured gap outside S-B (see the update tests).
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

    private companion object {
        const val UPDATE = "also check the Leeds store"
    }

    /** Jev answers by what was said: an update for the Leeds turn, else the scripted default. */
    private class ByText(val inner: FakeOpenRouter, val update: OpenRouterResult<Map<String, JevAnswer>>) : OpenRouterPort by inner {
        override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> =
            if ("Leeds" in state.toString() && inner.jevDefault.result is OpenRouterResult.Ok) {
                delay(10)
                update
            } else {
                inner.jev(state, questions, timeoutMs)
            }
    }

    private class Run(val actor: CascadeActor, val openRouter: FakeOpenRouter, val hermes: ClientHermes, val scope: CoroutineScope) {
        fun close() = scope.coroutineContext[Job]!!.cancel()
    }

    private var updateAnswer: OpenRouterResult<Map<String, JevAnswer>>? = null

    private fun update(enough: String) = OpenRouterResult.Ok(
        mapOf("turn" to answer("done"), "action" to answer("update_task"), "task" to answer("t1"), "line" to answer("update.queued"), "enough" to answer(enough)),
    )

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
            openRouter = updateAnswer?.let { ByText(openRouter, it) } ?: openRouter, hermes = hermes, audio = FakeAudioOut(clock), clock = clock, store = store,
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

    /** A task, then an update to it (queued), once it is running. */
    private fun TestScope.updating(run: Run) {
        run.actor.start()
        runCurrent()
        say(run, texts[0])
        advanceUntilIdle()
        say(run, UPDATE)
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

    /*
     * Updates to a running task: a measured gap outside S-B, held to its current size so a fix shows.
     * Nothing links an update's run to its turn: `TaskRun` has no turn id and `UpdateTaskHandler`
     * marks no outbox stage. So the outbox cannot keep an update turn until its run is accepted, and
     * a resend cannot tell that the update already went out.
     * - Writer on: the turn stays held while Bill speaks after the submit; a kill there resends it (2 doubles).
     * - Line only: the turn commits, submits and finishes in one step, so it is never stored; a kill
     *   before the submit runs loses it (8 lost).
     * Fix (hub/S-A): mark an outbox stage when an update launches and skip it on resend, and add
     * the turn id to `TaskRun` (additive) so the store keeps the turn until that run is accepted.
     */
    @Test fun killingAnUpdateWithTheWriterStaysWithinItsKnownGap() {
        updateAnswer = update("line_then_writer")
        killEverywhere("update+writer", listOf(texts[0], UPDATE), knownGap = 2) { updating(it) }
    }

    @Test fun killingAnUpdateWithTheLineOnlyStaysWithinItsKnownGap() {
        updateAnswer = update("line_only")
        killEverywhere("update+line", listOf(texts[0], UPDATE), knownGap = 8) { updating(it) }
    }

    /**
     * Kill before every effect k and restart. [knownGap]: lost turns plus double submits this
     * scenario may still show (0 unless a gap outside S-B is documented at the call).
     */
    private fun killEverywhere(name: String, texts: List<String> = this.texts, knownGap: Int = 0, scenario: TestScope.(Run) -> Unit) {
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
                // Committed turns that must survive: every turn the store ever held before the kill, and
                // every turn whose commit step got past its durable writes (they run first, so a gate
                // call for the step's `TurnCommitted` means they all ran).
                val everDurable = linkedSetOf<String>()
                val first = actor(store, server, EffectGate { e ->
                    everDurable += Durable.read(store).turnIds
                    ((e as? Effect.Emit)?.event as? CascadeEvent.TurnCommitted)?.let { everDurable += it.turnId }
                    seen++ < k
                })
                scenario(first)
                first.close()
                val atKill = Durable.read(store)
                everDurable += atKill.turnIds
                // R-DUR-1: a new task never reaches Hermes before it is stored.
                server.prompts.filter { it != UPDATE }.forEach { p -> assertTrue("k=$k: '$p' reached Hermes before its task was stored", atKill.tasks.any { it.brief == p }) }

                val second = actor(store, server, EffectGate.Open)
                second.actor.start()
                runCurrent()
                TaskRecovery(second.hermes, second.hermes).launch(second.scope, second.actor)
                runCurrent()
                second.actor.network(true)
                advanceUntilIdle()
                val after = second.actor.state.value

                texts.forEach { t ->
                    if (server.prompts.count { it == t } > 1) {
                        doubles++
                        println("$name k=$k: '$t' submitted twice (at kill: $atKill)")
                    }
                }
                val turns = everDurable.filter { id -> id.drop(1).toIntOrNull()?.let { it in 1..texts.size } == true }
                if (turns.isNotEmpty()) durableKills++
                for (turnId in turns) {
                    val text = texts[turnId.drop(1).toInt() - 1]
                    val submitted = server.prompts.any { it == text }
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
                }
                second.close()
            }
        }
        println("kill-restart $name: $total effects, $durableKills kills with durable turns, lost=$lost doubles=$doubles notSent=$notSent (grey zone $greyZone)")
        if (knownGap == 0) {
            assertEquals("lost turns", 0, lost)
            assertEquals("double submits", 0, doubles)
        } else {
            assertTrue("$name: lost + doubles stay within the documented gap ($lost + $doubles <= $knownGap)", lost + doubles <= knownGap)
        }
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
