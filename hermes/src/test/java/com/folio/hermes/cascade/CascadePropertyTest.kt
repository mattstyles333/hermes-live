package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.cascade.routing.MemoRouting
import com.folio.hermes.cascade.routing.MemoSegmentHandler
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.cascade.writer.Summaries
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterException
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail
import org.junit.Test

/**
 * The reducer property test (test-shape plan, "add" item 3). Random calls through the real
 * reducer, the real persistence and recovery ([CascadeCore] on the CEO env) and a fake Hermes that
 * outlives app processes, on a virtual clock: the user speaks (sometimes over Bill), pauses, Jev
 * answers any route or times out or fails, the network flaps, tasks finish, and the app is killed
 * before an arbitrary effect and restarted on the same store. After every step and at the end:
 *
 * 1. **never a double submit:** Hermes never receives the same prompt twice;
 * 2. **never a lost committed turn:** every turn committed as a new task reaches Hermes (or its
 *    task was cancelled before it left) once the network is back and running tasks finish, at the
 *    latest in the next call (a turn whose flush Jev failed stays held, durably, until then);
 * 3. **never playing while the user speaks:** no clip starts while the user is speaking, and a
 *    confirmed cut-in stops Bill at once;
 * 4. **stale results are never applied:** a result the actor already consumed (Jev, writer,
 *    timer, clip, playback, busy mode), delivered again, changes nothing and does nothing;
 * 5. **the UI is consistent:** the screen state is the projection of the state, and its phase,
 *    offline pill and task cards agree with it.
 *
 * Seeded and reproducible: [SEQUENCES] sequences from [BASE_SEED]. A failure prints the seed and a
 * minimal sequence (shrunk by removing operations while it still fails). To replay one sequence,
 * set `CASCADE_PROPERTY_SEED`.
 */
class CascadePropertyTest {
    private val bank = PhraseBank.load()
    private val settings = CascadeSettings()

    enum class Route { NewTask, Status, Cancel, Update, Reply, KeepListening, Ignore, Timeout, Offline }

    /** One step of a call. Every value the run depends on is here, so a list of these replays exactly. */
    sealed interface Op {
        /** The user says utterance [n]; Jev routes it as [route] (aimed at task t[target]) after [jevMs]. */
        data class Say(val n: Int, val route: Route, val target: Int, val jevMs: Long, val speechMs: Long) : Op

        data class Wait(val ms: Long) : Op

        data class Net(val online: Boolean) : Op

        /** Hermes finishes the [pick]-th unfinished prompted session (modulo how many there are). */
        data class Finish(val pick: Int) : Op

        /** The app process dies before its [inEffects]-th next effect (or after 3 s, if sooner) and starts again. */
        data class Kill(val inEffects: Int) : Op
    }

    /** An invariant broken: [kind] names it (the shrinker keeps a sequence that breaks the same one). */
    private class Violation(val kind: String, detail: String) : AssertionError("$kind: $detail")

    // ── generation ──────────────────────────────────────────────────────────────────────────

    private fun generate(random: Random): List<Op> {
        var n = 0
        return List(random.nextInt(4, 16)) {
            when (random.nextInt(100)) {
                in 0 until 45 -> Op.Say(
                    n = ++n,
                    route = pickRoute(random),
                    target = random.nextInt(1, 5),
                    jevMs = random.nextLong(20, 600),
                    speechMs = random.nextLong(200, 1_500),
                )
                in 45 until 63 -> Op.Wait(random.nextLong(100, 6_000))
                in 63 until 73 -> Op.Net(random.nextInt(3) != 0)
                in 73 until 88 -> Op.Finish(random.nextInt(8))
                else -> Op.Kill(random.nextInt(0, 40))
            }
        }
    }

    private fun pickRoute(random: Random): Route = when (random.nextInt(100)) {
        in 0 until 35 -> Route.NewTask
        in 35 until 43 -> Route.Status
        in 43 until 51 -> Route.Cancel
        in 51 until 61 -> Route.Update
        in 61 until 73 -> Route.Reply
        in 73 until 81 -> Route.KeepListening
        in 81 until 86 -> Route.Ignore
        in 86 until 93 -> Route.Timeout
        else -> Route.Offline
    }

    // ── the world: Hermes, the store, the network and Jev outlive app processes ─────────────

    private inner class World(val ts: TestScope, val seed: Long, val accurate: Boolean) {
        val store = MemoryStore()
        val server = FakeServer()
        val online = MutableStateFlow(true)
        val network = object : Network {
            override val online: Flow<Boolean> = this@World.online
        }
        val says = linkedMapOf<Int, Op.Say>()
        val attempts = HashMap<Int, Int>()

        /** The newest correlation id issued per kind of result (from the effects), to tell a stale result. */
        val newest = HashMap<String, Long>()
        val finishedPieces = HashSet<Piece>()
        val decisions = mutableListOf<DecisionRecord>()

        /** Results the actor has consumed (correlation-id events), for the stale replays. */
        val consumed = mutableListOf<Event>()
        val reducer = CascadeReducer(CascadeCore.env(bank, MemoRouting(ZoneOffset.UTC)))
        val pick = Random(seed xor 0x5eed)
        lateinit var proc: Proc

        /** The accurate pass (R-STT-2) when [accurate]: back after a seeded delay, sometimes past `accuratePassWaitMs`. */
        val passDelays = Random(seed xor 0xacc)
        val stt = object : SpeechToText {
            override val events: Flow<SttEvent> = emptyFlow()
            override suspend fun accurate(turnId: String): Transcript? {
                delay(passDelays.nextLong(20, 2_000))
                val text = proc.actor.state.value.actions.waiting.firstOrNull { it.turnId == turnId }?.text ?: return null
                return Transcript(text, text.split(' ').map { Word(it, confidence = 0.95) }, TranscriptPass.Accurate)
            }
        }

        fun utterance(n: Int) = "item $n: please look after job number $n"

        val router = object : OpenRouterPort {
            override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> {
                val latest = state["latest"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val say = Regex("item (\\d+)").findAll(latest).lastOrNull()?.groupValues?.get(1)?.toInt()?.let { says[it] }
                    ?: return OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95), "action" to answer("reply", 0.95)))
                if (!online.value) {
                    delay(15)
                    return OpenRouterResult.Failed(FailureKind.Offline)
                }
                // A failed or timed-out Jev fails that utterance's first call only; asked again, it routes a new task.
                val first = attempts.merge(say.n, 1, Int::plus) == 1
                if (first && say.route == Route.Offline) {
                    delay(15)
                    return OpenRouterResult.Failed(FailureKind.Offline)
                }
                if (first && say.route == Route.Timeout) {
                    delay(timeoutMs)
                    return OpenRouterResult.Failed(FailureKind.Timeout)
                }
                delay(say.jevMs)
                val target = "t${say.target}"
                val answers = when (say.route) {
                    Route.NewTask -> mapOf("action" to "new_task", "split" to "one", "clarity" to "clear", "line" to "start.one", "enough" to "line_then_writer")
                    Route.Status -> mapOf("action" to "status", "line" to "none", "enough" to "line_then_writer")
                    Route.Cancel -> mapOf("action" to "cancel_task", "task" to target, "line" to "cancel.one", "enough" to "line_only")
                    Route.Update -> mapOf("action" to "update_task", "task" to target, "update_mode" to "queue", "line" to "update.queued", "enough" to "line_only")
                    Route.Reply -> mapOf("action" to "reply", "line" to "none", "enough" to "line_then_writer")
                    Route.Offline, Route.Timeout -> mapOf("action" to "new_task", "split" to "one", "clarity" to "clear", "line" to "start.one", "enough" to "line_then_writer")
                    else -> emptyMap()
                }
                val turn = when (say.route) {
                    Route.KeepListening -> "keep_listening"
                    Route.Ignore -> "ignore"
                    else -> "done"
                }
                return OpenRouterResult.Ok((mapOf("turn" to turn) + answers).mapValues { (_, v) -> answer(v, 0.95) })
            }

            override fun sentences(system: String, prompt: String): Flow<String> = flow {
                if (!online.value) throw OpenRouterException(OpenRouterResult.Failed(FailureKind.Offline))
                delay(40)
                emit("Right.")
            }

            override suspend fun write(system: String, prompt: String): OpenRouterResult<String> {
                delay(30)
                if (!online.value) return OpenRouterResult.Failed(FailureKind.Offline)
                return when (system) {
                    Briefs.SYSTEM -> {
                        val transcript = prompt.substringAfter("Transcript: \"").substringBefore("\"\n").replace("\"", "'")
                        OpenRouterResult.Ok(briefs(brief(transcript, "job", "On it.")))
                    }
                    Summaries.SYSTEM -> OpenRouterResult.Ok("They run jobs by voice.")
                    // Unusable on purpose: the fallback makes one instruction per held turn.
                    MemoSegmentHandler.SYSTEM -> OpenRouterResult.Failed(FailureKind.BadBody)
                    else -> OpenRouterResult.Ok("{}")
                }
            }

            override suspend fun clip(text: String): OpenRouterResult<AudioClip> = OpenRouterResult.Ok(clipOf(400))

            override fun isCached(text: String): Boolean = true
        }
    }

    /** Plays in virtual time and knows whether a clip is playing now. */
    private class Speaker(private val clock: Clock) : AudioOut {
        private var stopSignal: CompletableDeferred<Unit>? = null
        var playing = false
            private set

        override suspend fun play(clip: AudioClip): Long {
            val start = clock.nowMs()
            val signal = CompletableDeferred<Unit>()
            stopSignal = signal
            playing = true
            try {
                withTimeoutOrNull(clip.durationMs) { signal.await() }
            } finally {
                playing = false
            }
            return minOf(clock.nowMs() - start, clip.durationMs)
        }

        override fun stop() {
            stopSignal?.complete(Unit)
        }
    }

    /** One app process on the world's store, talking to its Hermes. */
    private inner class Proc(val world: World, val generation: Int) {
        val job = SupervisorJob()
        val scope = CoroutineScope(world.ts.coroutineContext + job)
        val clock = TestClock(world.ts)
        val hermes = ClientHermes(world.server)
        val speaker = Speaker(clock)

        /** Effects left before the process dies (null: it lives). */
        var killIn: Int? = null
        val core: CascadeCore = CascadeCore.create(
            scope = scope,
            ports = CascadePorts(world.router, hermes, hermes, speaker, world.store, clock, world.network, world.stt.takeIf { world.accurate }),
            bank = bank,
            settings = { if (world.accurate) settings.copy(accuratePass = true) else settings },
            telemetry = TelemetrySink.None,
            zone = ZoneOffset.UTC,
            gate = EffectGate { e -> allow(e) },
            seed = world.seed + generation,
            trace = { e, before -> traced(e, before) },
        )
        val actor get() = core.actor

        private fun allow(e: Effect): Boolean {
            if (e is Effect.Play && actor.state.value.userSpeaking) throw Violation(PLAYED_OVER_USER, "'${e.piece.text}' started while the user was speaking")
            if (e is Effect.LogDecision) world.decisions += e.record
            correlation(e)?.let { (kind, id) -> world.newest.merge(kind, id, ::maxOf) }
            val left = killIn ?: return true
            killIn = left - 1
            return left > 0
        }

        private fun traced(e: Event, before: State) {
            if (TRACE) println("  [p$generation t=${before.nowMs}] ${e.toString().take(160)}  (online=${before.connection.online} memo=${before.connection.memo} held=${before.outbox.held.map { it.turnId }})")
            if (e is Event.PlaybackFinished) world.finishedPieces += e.piece
            if (result(e) != null || e is Event.ClipReady || e is Event.PlaybackFinished) world.consumed += e
        }

        fun close() = job.cancel()
    }

    /** The kind and id an effect starts (its results carry the same). */
    private fun correlation(e: Effect): Pair<String, Long>? = when (e) {
        is Effect.StartTimer -> "timer:${e.timer.name}" to e.timer.seq
        is Effect.CallJev -> "jev" to e.seq
        is Effect.StreamWriter -> "stream" to e.seq
        is Effect.Write -> "write" to e.seq
        is Effect.ReadBusyMode -> "busy" to e.seq
        else -> null
    }

    /** The kind and id a result event answers. */
    private fun result(e: Event): Pair<String, Long>? = when (e) {
        is Event.TimerFired -> "timer:${e.timer.name}" to e.timer.seq
        is Event.JevAnswered -> "jev" to e.seq
        is Event.WriterSentence -> "stream" to e.seq
        is Event.WriterFinished -> "stream" to e.seq
        is Event.WriterWrote -> "write" to e.seq
        is Event.BusyModeRead -> "busy" to e.seq
        else -> null
    }

    /**
     * Stale: its operation was superseded by a newer one of the same kind (a later timer of that
     * name, a later Jev call, writer or busy-mode read), or its clip already finished playing.
     */
    private fun World.stale(e: Event): Boolean {
        result(e)?.let { (kind, id) -> return id < (newest[kind] ?: id) }
        return when (e) {
            is Event.ClipReady -> e.piece in finishedPieces
            is Event.PlaybackFinished -> e.piece in finishedPieces
            else -> false
        }
    }

    // ── running a sequence ──────────────────────────────────────────────────────────────────

    /** Null if every invariant held, else what broke. */
    private fun run(ops: List<Op>, seed: Long, accurate: Boolean): String? {
        var failure: String? = null
        try {
            runTest {
                val w = World(this, seed, accurate)
                w.proc = Proc(w, 0)
                w.proc.core.start()
                runCurrent()
                for (op in ops) {
                    apply(w, op)
                    check(w, op)
                }
                settle(w)
                check(w, null)
                // A turn still held (Jev failed while flushing) waits durably for the next call: start one.
                if (w.proc.actor.state.value.outbox.size > 0) {
                    restart(w)
                    settle(w)
                    check(w, null)
                }
                final(w)
                w.proc.close()
            }
        } catch (v: AssertionError) {
            failure = v.message ?: v.toString()
        }
        return failure
    }

    private fun TestScope.apply(w: World, op: Op) {
        val actor = w.proc.actor
        when (op) {
            is Op.Say -> {
                w.says[op.n] = op
                if (w.proc.speaker.playing) {
                    // Over Bill: the pipeline confirms the cut-in (R-SPK-6), then the speech starts.
                    actor.userSpeech(settings.bargeInMs + 50)
                    runCurrent()
                    if (w.proc.speaker.playing) throw Violation(PLAYED_OVER_USER, "Bill kept playing after a confirmed cut-in")
                }
                actor.speechStarted()
                actor.final(w.utterance(op.n))
                runCurrent()
                advanceTimeBy(op.speechMs)
                runCurrent()
                actor.speechEnded()
                runCurrent()
            }
            is Op.Wait -> {
                advanceTimeBy(op.ms)
                runCurrent()
            }
            is Op.Net -> {
                w.online.value = op.online
                runCurrent()
            }
            is Op.Finish -> {
                val open = w.server.sessions.values.filter { it.prompts.isNotEmpty() && it.ended == null }
                if (open.isNotEmpty()) {
                    val s = open[op.pick % open.size]
                    w.server.complete(s.id, "Done with ${s.id}.")
                }
                runCurrent()
            }
            is Op.Kill -> {
                w.proc.killIn = op.inEffects
                advanceTimeBy(3_000)
                runCurrent()
                // Dead before that effect, or (if the call was quiet) killed now, between events.
                restart(w)
            }
        }
    }

    /** The process ends (killed, or the call is over) and the app starts again on the same store. */
    private fun TestScope.restart(w: World) {
        w.proc.close()
        runCurrent()
        // A dead process's executors die with it: its results can never reach the next one.
        w.consumed.clear()
        w.newest.clear()
        w.finishedPieces.clear()
        w.proc = Proc(w, w.proc.generation + 1)
        w.proc.core.start()
        runCurrent()
    }

    /** The network back, every prompted task finished, a few quiet minutes: everything owed is sent. */
    private fun TestScope.settle(w: World) {
        w.online.value = true
        runCurrent()
        repeat(6) {
            w.server.sessions.values.filter { it.prompts.isNotEmpty() && it.ended == null }.forEach { w.server.complete(it.id, "Done with ${it.id}.") }
            advanceTimeBy(20_000)
            runCurrent()
        }
    }

    /** Invariants 3 to 5 between steps ([op] null: after settling). */
    private fun check(w: World, op: Op?) {
        val actor = w.proc.actor
        val s = actor.state.value
        val ui = actor.ui.value
        // 5. The UI.
        if (ui != UiProjection.project(s)) throw Violation(UI, "the screen is not the projection of the state after $op")
        if ((ui.turn.phase == TurnPhase.UserSpeaking) != s.userSpeaking) throw Violation(UI, "phase ${ui.turn.phase} but userSpeaking=${s.userSpeaking}")
        if (ui.turn.offline == ui.connection.online) throw Violation(UI, "offline pill ${ui.turn.offline} but online=${ui.connection.online}")
        if (ui.tasks.map { it.id } != s.tasks.tasks.map { it.id } || ui.tasks.map { it.id }.distinct().size != ui.tasks.size) {
            throw Violation(UI, "task cards ${ui.tasks.map { it.id }} vs tasks ${s.tasks.tasks.map { it.id }}")
        }
        ui.tasks.forEach { t ->
            val done = t.status == TaskUiStatus.Done || t.status == TaskUiStatus.Failed || t.status == TaskUiStatus.Cancelled
            if (done && t.currentTool != null) throw Violation(UI, "finished card ${t.id} still shows a tool")
        }
        if (s.userSpeaking && w.proc.speaker.playing && s.speech.current == null) throw Violation(PLAYED_OVER_USER, "audio playing with nothing current while the user speaks")
        // 4. Stale results: a consumed result delivered again changes nothing.
        val sample = w.consumed.filter { w.stale(it) }.shuffled(w.pick).take(4)
        for (e in sample) {
            val (after, effects) = w.reducer.reduce(s, e)
            if (after != s || effects.isNotEmpty()) {
                throw Violation(STALE, "${e::class.simpleName} applied after $op: effects ${effects.map { it::class.simpleName }}")
            }
        }
        // 1. Doubles, as soon as they happen.
        doubles(w)
    }

    private fun doubles(w: World) {
        val prompts = w.server.prompts
        val twice = prompts.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (twice.isNotEmpty()) throw Violation(DOUBLE, "Hermes got a prompt twice: ${twice.map { it.lineSequence().first() }}")
    }

    /** Invariants 1 and 2 after settling. */
    private fun final(w: World) {
        doubles(w)
        val s = w.proc.actor.state.value
        if (s.outbox.size > 0) throw Violation(
            LOST,
            "${s.outbox.size} turns still held after the network came back (held ${s.outbox.held.map { it.turnId }}, flush ${s.flush}, " +
                "action ${s.actions.active}, waiting ${s.actions.waiting.size}, memo ${s.connection.memo}, decisions ${w.decisions.map { "${it.turnId}:${it.outcome}:${it.route}" }})",
        )
        val prompts = w.server.prompts
        for (d in w.decisions) {
            if (d.outcome != "done" || d.route != "new_task") continue
            val heard = d.state?.get("utterance")?.jsonPrimitive?.contentOrNull ?: d.state?.get("latest")?.jsonPrimitive?.contentOrNull ?: continue
            for (n in Regex("item (\\d+)").findAll(heard).map { it.groupValues[1] }) {
                val text = w.utterance(n.toInt())
                val sent = prompts.any { text in it }
                val cancelled = s.tasks.tasks.any { it.state == TaskState.Cancelled && it.brief?.contains(text) == true }
                if (!sent && !cancelled) throw Violation(LOST, "committed turn ${d.turnId} ('item $n', a new task) never reached Hermes")
            }
        }
    }

    // ── shrinking ───────────────────────────────────────────────────────────────────────────

    /** Remove items (halves, then quarters, … then single ones) while [fails] still holds. */
    private fun <T> shrink(items: List<T>, fails: (List<T>) -> Boolean): List<T> {
        var current = items
        var chunk = current.size / 2
        while (chunk >= 1) {
            var i = 0
            var removed = false
            while (i < current.size) {
                val candidate = current.subList(0, i) + current.subList(minOf(current.size, i + chunk), current.size)
                if (candidate.isNotEmpty() && fails(candidate)) {
                    current = candidate
                    removed = true
                } else {
                    i += chunk
                }
            }
            if (!removed) chunk /= 2
        }
        return current
    }

    @Test fun invariantsHoldOverRandomCalls() = invariants(accurate = false)

    /**
     * The same with the accurate pass on, as `:live` runs Bill: the stock line plays at commit while
     * the pass is out, kills land between that line and the gate opening, and some passes time out.
     */
    @Test fun invariantsHoldWithTheAccuratePass() = invariants(accurate = true)

    private fun invariants(accurate: Boolean) {
        val only = System.getenv("CASCADE_PROPERTY_SEED")?.toLongOrNull()
        val seeds = if (only != null) listOf(only) else (0 until SEQUENCES).map { BASE_SEED + it }
        for (seed in seeds) {
            val ops = generate(Random(seed))
            val failure = run(ops, seed, accurate) ?: continue
            val kind = failure.substringBefore(':')
            val minimal = shrink(ops) { run(it, seed, accurate)?.substringBefore(':') == kind }
            fail(
                "seed $seed (accurate pass ${if (accurate) "on" else "off"}): $failure\n" +
                    "minimal sequence (${minimal.size} of ${ops.size} ops; replay with CASCADE_PROPERTY_SEED=$seed):\n" +
                    minimal.joinToString("\n") { "  $it" } + "\nwhich fails with: ${run(minimal, seed, accurate)}",
            )
        }
    }

    /** The shrinker keeps a failing sequence failing and drops what does not matter. */
    @Test fun aFailureShrinksToTheOperationsThatMatter() {
        val ops = listOf(Op.Wait(100), Op.Net(false), Op.Wait(200), Op.Say(1, Route.Reply, 1, 50, 300), Op.Net(true), Op.Wait(300))
        val current = shrink(ops) { list -> list.any { it == Op.Net(false) } && list.any { it is Op.Say } }
        if (current != listOf(Op.Net(false), Op.Say(1, Route.Reply, 1, 50, 300))) fail("shrank to $current")
    }

    private companion object {
        const val DOUBLE = "double submit"
        const val LOST = "lost committed turn"
        const val PLAYED_OVER_USER = "played while the user spoke"
        const val STALE = "stale result applied"
        const val UI = "inconsistent UI"
        const val SEQUENCES = 500
        val TRACE = System.getenv("CASCADE_PROPERTY_TRACE") == "1"
        const val BASE_SEED = 20_261_014L
    }
}
