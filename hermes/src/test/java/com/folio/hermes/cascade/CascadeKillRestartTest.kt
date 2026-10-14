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
import com.folio.hermes.cascade.routing.MemoRouting
import java.time.ZoneOffset
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
 * that across every kill point of every scenario (offline flush, online, an update with the
 * writer, an update with the line only)
 * - **no double submit:** the server never gets a turn's text twice;
 * - **no lost turn:** every committed turn (stored at any point, or past its commit step's durable
 *   writes) is submitted, still held, or a `NotSent` task with its brief ("tap to retry"; R-DUR-4);
 * - **persist before network (R-DUR-1):** whatever task or update the server got at the kill, the store had.
 *
 * Exact idempotency (R-DUR-2): the task's session is created and its id stored before the prompt
 * leaves, and an update's run (turn id, text) is stored before it leaves, so recovery can ask
 * Hermes which prompts arrived. Nothing is left to the user: `NotSent` is 0. A port that cannot
 * create ahead keeps the old grey zone (the last test), bounded and never doubled.
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

    private var createAhead = true

    /** Run on the env `:live` ships ([CascadeCore.env]: CEO routing on the durable seams) instead of the C1 handlers. */
    private var ceo = false

    /** What the server got for [text]: equal on C1; on CEO a brief built around it (Goal line plus the transcript block). */
    private fun sent(prompts: List<String>, text: String): Int = prompts.count { if (ceo) it.contains(text) else it == text }

    private fun TestScope.actor(store: Store, server: FakeServer, gate: EffectGate): Run {
        val clock = TestClock(this)
        val openRouter = FakeOpenRouter(clock).apply {
            jevDefault = FakeOpenRouter.JevScript(10, newTask)
            // The brief is the request itself, so each turn's submit is recognisable.
            plan = { prompt ->
                if (ceo) {
                    val request = prompt.substringAfter("Transcript: \"").substringBefore("\"\n")
                    OpenRouterResult.Ok("""{"briefs":[{"brief":"Goal: $request\nDone when: it is done","label":"job","summary":"On it."}],"notes":""}""")
                } else {
                    OpenRouterResult.Ok("LABEL: job\nPROMPT: ${prompt.substringAfter("The request: \"").trimEnd('"')}\nSAY: On it.")
                }
            }
        }
        val hermes = ClientHermes(server).apply { createAhead = this@CascadeKillRestartTest.createAhead }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val actor = CascadeActor(
            scope = scope, env = if (ceo) CascadeCore.env(bank, MemoRouting(ZoneOffset.UTC)) else durableEnv(bank), settings = { CascadeSettings() },
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

    /** The scenario's effect count with no kill; every one of [texts] reaches Hermes exactly once. */
    private fun countEffects(texts: List<String>, scenario: TestScope.(Run) -> Unit): Int {
        var count = 0
        runTest {
            val server = FakeServer()
            val run = actor(MemoryStore(), server, EffectGate { count++; true })
            scenario(run)
            texts.forEach { assertEquals("'$it' sent once with no kill", 1, sent(server.prompts, it)) }
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
     * Updates to a running task (spec v2 §14 item 1). Before: 2 doubles with the writer on, 8 lost
     * with the line only. Now the run carries its turn id and text until `Accepted`, the outbox
     * marks `update:<task>` in the same step, and recovery resends only what Hermes never got.
     */
    @Test fun killingAnUpdateWithTheWriterLosesNothingAndNeverSubmitsTwice() {
        updateAnswer = update("line_then_writer")
        killEverywhere("update+writer", listOf(texts[0], UPDATE)) { updating(it) }
    }

    @Test fun killingAnUpdateWithTheLineOnlyLosesNothingAndNeverSubmitsTwice() {
        updateAnswer = update("line_only")
        killEverywhere("update+line", listOf(texts[0], UPDATE)) { updating(it) }
    }

    /*
     * The same kills on the env `:live` ships (CEO routing: JSON briefs, TaskQueue, Confirmations
     * marks, CeoUpdateTaskHandler with R-RT-7 redirect and the busy-mode read).
     */
    @Test fun ceoKillingTheOnlineScenarioAnywhereLosesNothingAndNeverSubmitsTwice() {
        ceo = true
        killEverywhere("ceo online") { online(it) }
    }

    @Test fun ceoKillingAnUpdateWithTheWriterLosesNothingAndNeverSubmitsTwice() {
        ceo = true
        updateAnswer = update("line_then_writer")
        killEverywhere("ceo update+writer", listOf(texts[0], UPDATE)) { updating(it) }
    }

    @Test fun ceoKillingAnUpdateWithTheLineOnlyLosesNothingAndNeverSubmitsTwice() {
        ceo = true
        updateAnswer = update("line_only")
        killEverywhere("ceo update+line", listOf(texts[0], UPDATE)) { updating(it) }
    }

    /** A port that cannot create a session ahead (the old path): no double and no loss, but some kills end `NotSent`. */
    @Test fun withoutSessionFirstTheGreyZoneIsBoundedAndNeverDoubled() {
        createAhead = false
        killEverywhere("online, no session-first", allowNotSent = true) { online(it) }
    }

    /** Kill before every effect k and restart. [allowNotSent]: the old grey zone is expected (no session-first). */
    private fun killEverywhere(name: String, texts: List<String> = this.texts, allowNotSent: Boolean = false, scenario: TestScope.(Run) -> Unit) {
        val total = countEffects(texts, scenario)
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
                // R-DUR-1: a new task never reaches Hermes before it is stored, nor an update before its run or stage.
                server.prompts.filter { sent(listOf(it), UPDATE) == 0 }.forEach { p -> assertTrue("k=$k: '$p' reached Hermes before its task was stored", atKill.tasks.any { it.brief == p }) }
                if (sent(server.prompts, UPDATE) > 0) assertTrue("k=$k: the update reached Hermes before it was stored", "u2" in atKill.updates)

                val second = actor(store, server, EffectGate.Open)
                second.actor.start()
                runCurrent()
                TaskRecovery(second.hermes, second.hermes).launch(second.scope, second.actor)
                runCurrent()
                second.actor.network(true)
                advanceUntilIdle()
                val after = second.actor.state.value

                texts.forEach { t ->
                    if (sent(server.prompts, t) > 1) {
                        doubles++
                        println("$name k=$k: '$t' submitted twice (at kill: $atKill)")
                    }
                }
                val turns = everDurable.filter { id -> id.drop(1).toIntOrNull()?.let { it in 1..texts.size } == true }
                if (turns.isNotEmpty()) durableKills++
                for (turnId in turns) {
                    val text = texts[turnId.drop(1).toInt() - 1]
                    val submitted = sent(server.prompts, text) > 0
                    val held = after.outbox.held.any { it.turnId == turnId }
                    val task = after.tasks.tasks.firstOrNull { it.turnId == turnId }
                    val retryable = task != null && task.send == SendState.NotSent && task.brief?.let { if (ceo) it.contains(text) else it == text } == true
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
        assertEquals("$name: lost turns", 0, lost)
        assertEquals("$name: double submits", 0, doubles)
        assertTrue("most kills happen with durable turns ($durableKills)", durableKills > total / 3)
        if (allowNotSent) {
            assertTrue("NotSent only in the narrow window between persist and Hermes's session id ($notSent)", notSent <= total / 5)
        } else {
            assertEquals("$name: session-first leaves nothing for the user to retry", 0, notSent)
        }
    }

    /** What the store holds, read with the real codec. */
    private class Durable(val tasks: List<TaskRecord>, val held: Set<String>, val turnIds: Set<String>, val updates: Set<String>) {
        override fun toString() = "tasks=${tasks.map { "${it.id}/${it.turnId}/${it.send}/${it.storedSessionId}" }} held=$held"

        companion object {
            fun read(store: Store): Durable {
                val core = StoreCodec.decode(CoreDto.serializer(), store.get(StoreKeys.CORE)) ?: CoreDto()
                val tasks = store.keys(StoreKeys.TASK_PREFIX).mapNotNull { StoreCodec.decodeTask(store.get(it)) }
                val held = core.outbox.held.map { it.turnId }.toSet()
                val updates = tasks.flatMap { t -> t.runs.values.mapNotNull { it.turnId } }.toSet() +
                    core.outbox.stages.filterValues { v -> v.any { it.startsWith("update:") } }.keys
                return Durable(tasks, held, held + core.outbox.awaiting + core.outbox.sent + tasks.mapNotNull { it.turnId } + updates, updates)
            }
        }
    }
}
