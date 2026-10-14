package com.folio.fakehermes

import com.folio.hermes.HermesGateway
import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Effect
import com.folio.hermes.cascade.EffectGate
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.cascade.routing.MemoSegmentHandler
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.cascade.writer.Summaries
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.telemetry.TelemetrySink
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden calls (test-shape plan, item 1): whole Bill calls in-process through [CascadeCore] and
 * [CascadePorts.gateway] (the app's own wiring: `GatewayHermesPort` on a real `HermesGateway`)
 * against the Python fake Hermes, with a scripted Jev, writer and voice. No emulator, no key.
 * They replace the emulator scenario matrix.
 *
 * Each call asserts what the user hears (as phrase-bank slots, or the writer's and Hermes's own
 * words), what Hermes receives (each prompt exactly once, read from the fake's assertion API) and
 * the final screen state. Waits are on the call's own state (every screen, event and clip wakes
 * them); nothing sleeps. The scripted Jev, writer and voice answer at once; only the wire to the
 * fake (a real socket) and the fake's scripted tools take real time, so these cannot run on a
 * virtual clock. The pause is 400 ms (the setting's minimum) to keep each call short.
 *
 * 1. one task (1b: its stock line does not wait for the accurate pass); 2. one request split into three; 3. a correction to a running task; 4. status;
 * 5. cancel; 6. an offline memo, then reconnect; 7. killed mid-submit, then a restart; 8. resume
 * with Play after the app was closed with a task running; 9. a key doubt starts the task at once
 * and asks after (no answer, a correction redirects, a yes sends nothing); 10. "stop everything"
 * with three tasks.
 */
class GoldenCallTest {
    private val h = Harness(FakeHermesProcess("scenarios/test.json"))
    private val bank = PhraseBank.load()
    private val calls = CopyOnWriteArrayList<Call>()

    @After fun tearDown() {
        calls.forEach { it.close() }
        h.close()
    }

    /** Jev routes by keyword in what was said; the writer answers by job; each clip remembers its text. */
    private class ScriptedOpenRouter(
        val routes: Map<String, Map<String, String>>,
        val briefs: Map<String, List<String>>,
        /** The writer's doubted word per keyword, given only when the brief prompt asks for it (R-STT-5). */
        val doubts: Map<String, String> = emptyMap(),
    ) : OpenRouterPort {
        val clipText: MutableMap<AudioClip, String> = Collections.synchronizedMap(IdentityHashMap())

        /** Every brief request's prompt, in order. */
        val briefPrompts = CopyOnWriteArrayList<String>()

        /** The words of every Jev call, in order (as routed on). */
        val jevAsked = MutableStateFlow<List<String>>(emptyList())

        override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> {
            // The keep_listening recheck asks about the held words with nothing new (`latest` empty): route on all of them.
            val latest = state["latest"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { state["utterance"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            jevAsked.value = jevAsked.value + (state["utterance"]?.jsonPrimitive?.contentOrNull ?: latest)
            val route = routes.entries.firstOrNull { latest.contains(it.key, ignoreCase = true) }?.value ?: mapOf("action" to "reply")
            // A value may carry its confidence ("done@0.51"); 0.95 otherwise.
            return OpenRouterResult.Ok(
                (mapOf("turn" to "done") + route).mapValues { (_, v) ->
                    val choice = v.substringBefore('@')
                    val conf = v.substringAfter('@', "0.95").toDouble()
                    JevAnswer("choice", choice, mapOf(choice to conf), conf)
                },
            )
        }

        /** The spoken writer: a result is read back in Hermes's own words (its first sentence); anything else gets one line. */
        override fun sentences(system: String, prompt: String): Flow<String> = flowOf(
            RESULTS.firstOrNull { it in prompt } ?: "Here is where things are.",
        )

        override suspend fun write(system: String, prompt: String): OpenRouterResult<String> = when (system) {
            // Background summaries are on in the CEO env (R-CTX-2).
            Summaries.SYSTEM -> OpenRouterResult.Ok("They run the shop's jobs by voice.")
            MemoSegmentHandler.SYSTEM -> OpenRouterResult.Ok("""{"groups":[[1]]}""")
            Briefs.SYSTEM -> {
                briefPrompts += prompt
                val transcript = prompt.substringAfter("Transcript: \"").substringBefore("\"\n")
                val jobs = briefs.entries.first { transcript.contains(it.key, ignoreCase = true) }.value
                val doubt = doubts.entries.firstOrNull { Briefs.DOUBT in prompt && transcript.contains(it.key, ignoreCase = true) }?.value
                OpenRouterResult.Ok(
                    """{"briefs":[""" + jobs.joinToString(",") { job ->
                        """{"brief":"Goal: $job\nDone when: it is done","label":"${job.split(' ').take(3).joinToString(" ")}","summary":"On it."}"""
                    } + """],"repaired":"","notes":""""" + (doubt?.let { ""","doubt":{"word":"$it","question":"$it, right?"}""" } ?: "") + "}",
                )
            }
            else -> error("unexpected writer job")
        }

        override suspend fun clip(text: String): OpenRouterResult<AudioClip> {
            val format = SpeechFormat.Pcm()
            return OpenRouterResult.Ok(AudioClip(ByteArray(format.bytesFor(150).toInt()), format).also { clipText[it] = text })
        }

        override fun isCached(text: String): Boolean = true
    }

    /** Plays at once and records what Bill said, in order. */
    private class SpokenLog(private val router: ScriptedOpenRouter, private val changed: () -> Unit) : AudioOut {
        val said = CopyOnWriteArrayList<String>()
        override suspend fun play(clip: AudioClip): Long {
            said += router.clipText[clip] ?: "?"
            changed()
            return clip.durationMs
        }

        override fun stop() = Unit
    }

    /** One app process: a [CascadeCore] on [store] and [gateway], watched. */
    private inner class Call(
        routes: Map<String, Map<String, String>>,
        briefs: Map<String, List<String>> = emptyMap(),
        doubts: Map<String, String> = emptyMap(),
        val store: Store = MemoryStore(),
        val gateway: HermesGateway = h.gateway,
        val online: MutableStateFlow<Boolean> = MutableStateFlow(true),
        private val gate: EffectGate = EffectGate.Open,
        stt: SpeechToText? = null,
        settings: CascadeSettings = SETTINGS,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val version = MutableStateFlow(0L)
        private val ticks = AtomicLong()
        private fun changed() {
            version.value = ticks.incrementAndGet()
        }

        val router = ScriptedOpenRouter(routes, briefs, doubts)
        val out = SpokenLog(router, ::changed)
        val events = CopyOnWriteArrayList<CascadeEvent>()
        val appEffects = CopyOnWriteArrayList<Effect>()

        /** The effect the gate refused: the actor dies there, before running it. */
        val killedAt = CompletableDeferred<Effect>()
        val core = CascadeCore.create(
            scope = scope,
            ports = CascadePorts.gateway(gateway, h.config, router, out, store, network = object : Network {
                override val online = this@Call.online
            }, stt = stt),
            bank = bank,
            settings = { settings },
            telemetry = TelemetrySink.None,
            onAppEffect = { appEffects += it },
            // A kill wakes the waits too (nothing else changes after it). The wake follows the
            // record: the actor sets `killed` only after the gate returns, so a wait on that
            // could check too early and never be woken again.
            gate = EffectGate { e ->
                gate.allow(e).also {
                    if (!it) {
                        killedAt.complete(e)
                        changed()
                    }
                }
            },
            seed = 7,
        )
        val actor get() = core.actor

        init {
            calls += this
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                actor.events.collect {
                    events += it
                    changed()
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { actor.ui.collect { changed() } }
        }

        fun start() = runBlocking {
            gateway.connect()
            core.start()
        }

        fun say(text: String, words: List<Word> = emptyList()) {
            actor.speechStarted()
            actor.send(Event.Final(text, words))
            actor.speechEnded()
        }

        /** Wait until [check] holds, re-checked whenever the call changes (screen, events, clips). */
        fun until(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) = runBlocking {
            withTimeoutOrNull(timeoutMs) { version.first { check() } }
                ?: throw AssertionError("timed out: $what (tasks ${actor.ui.value.tasks.map { "${it.id}:${it.status}" }}, heard ${heard()})")
        }

        fun task(id: String) = actor.ui.value.tasks.first { it.id == id }

        /**
         * At least [count] results spoken, Bill quiet, and each spoken task already Done on its
         * card. The result is spoken from the turn's Completed update; the card turns Done when
         * the run ends, a separate actor event. With instant speech either can land first, so a
         * wait on the speech alone could see the card still Running.
         */
        fun resultsSpoken(count: Int = 1): Boolean {
            val spoken = events.filterIsInstance<CascadeEvent.ResultSpoken>()
            return spoken.size >= count && spoken.all { r -> actor.ui.value.tasks.any { it.id == r.id && it.status == TaskUiStatus.Done } } && !actor.speaking
        }

        /** What the user heard: a stock line as its slot id, anything else (writer, results) as quoted text. */
        fun heard(): List<String> = out.said.map { text -> bank.slots.firstOrNull { text in it.lines }?.id ?: "\"$text\"" }

        override fun close() {
            runCatching { core.stop() }
            scope.cancel()
        }
    }

    private fun newTask(split: String = "one", line: String = "start.one") =
        mapOf("action" to "new_task", "split" to split, "clarity" to "clear", "line" to line, "enough" to "line_then_writer")

    private fun promptCount(): Int = h.prompts()["count"].toString().toInt()

    private fun noDoubles() = assertEquals("Hermes never got a prompt twice", true, h.doubles().bool("ok"))

    /** Every prompt Hermes got, in session order. */
    private fun prompts(): List<String> = h.sessions().flatMap { s -> h.prompts(s.str("stored_session_id")!!).mapNotNull { it.str("text") } }

    // ── 1. one task ───────────────────────────────────────────────────────────────────────

    @Test fun oneTaskIsRunningThenDoneThenSpoken() {
        h.signIn()
        val call = Call(routes = mapOf("stock" to newTask(line = "start.lookup")), briefs = mapOf("stock" to listOf("Check stock of the Kestrel aviators.")))
        val seen = CopyOnWriteArrayList<TaskUiStatus>()
        call.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            call.actor.ui.collect { ui -> ui.tasks.firstOrNull()?.let { t -> if (seen.lastOrNull() != t.status) seen += t.status } }
        }
        call.start()
        call.say("Check how many Kestrel aviators we have in stock.")

        call.until("the result spoken") { call.resultsSpoken() }
        val task = call.actor.ui.value.tasks.single()
        assertEquals(TaskUiStatus.Done, task.status)
        assertEquals("both tool steps reached the card: $task events ${call.events.filter { it is CascadeEvent.ToolStarted || it is CascadeEvent.ToolFinished }}", 2, task.steps)
        assertEquals(listOf(TaskUiStatus.Starting, TaskUiStatus.Running, TaskUiStatus.Done), seen.filter { it != TaskUiStatus.Queued })
        assertTrue("accepted announced", call.events.any { it is CascadeEvent.TaskAccepted })
        assertTrue("a tool showed", call.events.any { it is CascadeEvent.ToolStarted })
        // Heard: the stock line first, then the result in Hermes's words (headline first).
        assertEquals("start.lookup", call.heard().first())
        assertTrue(call.heard().toString(), call.heard().any { "14 in stock" in it })
        assertEquals(1, promptCount())
        noDoubles()
    }

    // ── 1b. the stock line does not wait for the accurate pass ───────────────────────────

    /** The accurate pass (R-STT-2) answers only when [release] is completed. */
    private class HeldAccurate(private val text: String) : SpeechToText {
        val release = CompletableDeferred<Unit>()
        val asked = MutableStateFlow(0)
        override val events: Flow<SttEvent> = emptyFlow()
        override suspend fun accurate(turnId: String): Transcript? {
            asked.value += 1
            release.await()
            return Transcript(text, text.split(' ').map { Word(it, confidence = 0.95) }, TranscriptPass.Accurate)
        }
    }

    /**
     * R-Q-3 / R-SPK-2: the `start.*` line plays at commit, from the cache, while the accurate pass
     * is still running; the brief, the repair and the submit wait for it and use its words.
     */
    @Test fun theStockLinePlaysBeforeTheAccuratePassAndTheSubmitUsesTheAccurateText() {
        h.signIn()
        val heardFast = "Check how many Castrel aviators we have in stock."
        val accurate = HeldAccurate("Check how many Kestrel aviators we have in stock.")
        val call = Call(
            routes = mapOf("stock" to newTask(line = "start.lookup")),
            briefs = mapOf("stock" to listOf("Check stock of the Kestrel aviators.")),
            stt = accurate,
            // The pass never times out here: only `release` lets the turn go on.
            settings = SETTINGS.copy(accuratePass = true, accuratePassWaitMs = 60_000),
        )
        call.start()
        call.say(heardFast)

        call.until("the stock line, with the accurate pass still out", timeoutMs = 5_000) { call.heard().contains("start.lookup") }
        // The pass runs on its own coroutine: it may be asked a moment after the line started.
        runBlocking { withTimeoutOrNull(5_000) { accurate.asked.first { it > 0 } } }
        assertEquals(1, accurate.asked.value)
        assertFalse(accurate.release.isCompleted)
        assertEquals("no brief before the accurate words", 0, call.router.briefPrompts.size)
        assertEquals("nothing submitted before the accurate words", 0, promptCount())
        assertTrue(call.actor.ui.value.tasks.isEmpty())

        accurate.release.complete(Unit)
        call.until("the result spoken") { call.resultsSpoken() }
        val brief = call.router.briefPrompts.single()
        assertTrue(brief, brief.contains("Kestrel aviators") && !brief.contains("Castrel"))
        val prompt = prompts().single()
        assertTrue("the transcript block carries the accurate words: $prompt", prompt.contains("Check how many Kestrel aviators we have in stock") && !prompt.contains("Castrel"))
        assertEquals("the line played once, first", listOf("start.lookup"), call.heard().filter { it == "start.lookup" })
        assertEquals("start.lookup", call.heard().first())
        assertEquals(TaskUiStatus.Done, call.actor.ui.value.tasks.single().status)
        assertEquals(1, promptCount())
        noDoubles()
    }

    // ── 1c. the hold-silence rule: no held turn waits forever ─────────────────────────────

    /**
     * Found on the emulator smoke (2026-10-14): the fast pass heard "… we have in sto", Jev said
     * `turn = done` at 0.51 (under the 0.60 floor) at the pause and at the recheck, and the turn
     * stayed held with Bill silent. Now, after `holdSilenceMs` of silence, words Jev reads as a
     * request (`action` ≥ the floor) are committed: the line, the task, the result.
     */
    @Test fun aClippedRequestJevIsUnsureOfIsCommittedAfterSilence() {
        h.signIn()
        val call = Call(
            routes = mapOf("in sto" to newTask(line = "start.lookup") + ("turn" to "done@0.51") + ("action" to "new_task@0.98")),
            briefs = mapOf("in sto" to listOf("Check stock of the Kestrel aviators.")),
            settings = HOLD,
        )
        call.start()
        call.say("Check how many Kestrel aviators we have in sto")

        call.until("the result spoken") { call.resultsSpoken() }
        assertEquals("the stock line, never a prompt: ${call.heard()}", "start.lookup", call.heard().first())
        assertTrue(call.heard().none { it == AppLines.HOLD_GO_ON || it == AppLines.HOLD_CONFIRM })
        assertEquals(TaskUiStatus.Done, call.actor.ui.value.tasks.single().status)
        assertTrue(prompts().single().contains("we have in sto"))
        assertEquals(1, promptCount())
        noDoubles()
    }

    /** Held words that are not a request: "Go on" once, then "go ahead?"; the answer joins them and nothing was sent before. */
    @Test fun heldWordsThatAreNotARequestGetGoOnThenGoAheadThenTheAnswerJoinsThem() {
        h.signIn()
        val call = Call(
            routes = mapOf(
                "kestrel stock" to newTask(line = "start.lookup"),
                "the thing about" to mapOf("turn" to "keep_listening@0.9", "action" to "reply@0.2"),
            ),
            briefs = mapOf("kestrel stock" to listOf("Check stock of the Kestrel aviators.")),
            settings = HOLD,
        )
        call.start()
        call.say("So the thing about the")
        call.until("go on, then go ahead") { call.heard() == listOf(AppLines.HOLD_GO_ON, AppLines.HOLD_CONFIRM) && !call.actor.speaking }
        assertEquals("nothing sent while held", 0, promptCount())
        assertEquals(listOf("So the thing about the"), call.actor.state.value.turn.pending)

        call.say("Yes, check the Kestrel stock")
        call.until("the result spoken") { call.resultsSpoken() }
        assertTrue(prompts().single().contains("So the thing about the Yes, check the Kestrel stock"))
        assertEquals(listOf(AppLines.HOLD_GO_ON, AppLines.HOLD_CONFIRM, "start.lookup"), call.heard().take(3))
        noDoubles()
    }

    // ── 1d. cloud speech-to-text fixes a clipped fast text (cloud STT slice) ────────────────

    /**
     * Cloud mode as the app's STT sends it: the fast pass's `Final`, the end of speech, then the
     * cloud's text for the same segment (`Revised`); the accurate pass is the cloud text.
     */
    private class ScriptedCloudStt(private val cloud: String) : SpeechToText {
        private val channel = kotlinx.coroutines.channels.Channel<SttEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val events: Flow<SttEvent> = channel.receiveAsFlow()
        fun send(e: SttEvent) = channel.trySend(e)
        override suspend fun accurate(turnId: String): Transcript? =
            Transcript(cloud, cloud.split(' ').map { Word(it, confidence = null) }, TranscriptPass.Accurate)
    }

    /**
     * The smoke's clipped "… we have in sto" (Jev unsure, `done` 0.51) with the cloud's "… in stock."
     * arriving after the early Jev call: Jev is asked again about the cloud words, the turn commits
     * on Jev's answer (not by the hold-silence rule), and the brief and Hermes's prompt carry the
     * cloud words.
     */
    @Test fun theCloudTextFixesAClippedFastTextAndTheTurnCommits() {
        h.signIn()
        val fast = "Check how many Kestrel aviators we have in sto"
        val cloud = "Check how many Kestrel aviators we have in stock."
        val stt = ScriptedCloudStt(cloud)
        val call = Call(
            // Map order: the cloud words first ("in stock" also contains "in sto").
            routes = linkedMapOf(
                "in stock" to newTask(line = "start.lookup"),
                "in sto" to newTask(line = "start.lookup") + ("turn" to "done@0.51") + ("action" to "new_task@0.98"),
            ),
            briefs = mapOf("aviators" to listOf("Check stock of the Kestrel aviators.")),
            stt = stt,
            // The hold-silence rule far away: only Jev's answer about the cloud words can commit the turn here.
            settings = HOLD.copy(accuratePass = true, holdSilenceMs = 60_000),
        )
        call.start()
        stt.send(SttEvent.SpeechStarted)
        stt.send(SttEvent.Final(fast))
        stt.send(SttEvent.SpeechEndedAt(System.nanoTime() / 1_000_000))
        runBlocking { withTimeoutOrNull(5_000) { call.router.jevAsked.first { it.isNotEmpty() } } }
        assertEquals("the early call judged the fast words", fast, call.router.jevAsked.value.first())
        stt.send(SttEvent.Revised(fast, cloud, material = true))

        call.until("the result spoken") { call.resultsSpoken() }
        assertTrue("Jev judged the cloud words: ${call.router.jevAsked.value}", call.router.jevAsked.value.drop(1).any { it.contains("in stock") })
        assertEquals("start.lookup", call.heard().first())
        val brief = call.router.briefPrompts.single()
        assertTrue(brief, brief.contains("we have in stock"))
        val prompt = prompts().single()
        assertTrue("Hermes got the cloud words: $prompt", prompt.contains("we have in stock"))
        assertEquals(1, promptCount())
        noDoubles()
    }

    // ── 2. split into 3 ──────────────────────────────────────────────────────────────────

    @Test fun oneRequestSplitIntoThreeTasksEachSentOnce() {
        h.signIn()
        val jobs = listOf("Check stock of the Kestrel aviators.", "Email the supplier about the late order.", "Draft the Friday newsletter.")
        val call = Call(routes = mapOf("three things" to newTask(split = "several", line = "start.parallel")), briefs = mapOf("three things" to jobs))
        call.start()
        call.say("Three things: check stock of the Kestrels, email the supplier about the late order, and draft the Friday newsletter.")

        call.until("three results spoken") { call.resultsSpoken(3) }
        val ui = call.actor.ui.value
        assertEquals(listOf("t1", "t2", "t3"), ui.tasks.map { it.id })
        assertTrue(ui.tasks.all { it.status == TaskUiStatus.Done })
        assertEquals("start.parallel", call.heard().first())
        // Each result read once, each after its own result line.
        assertEquals(setOf("t1", "t2", "t3"), call.events.filterIsInstance<CascadeEvent.ResultSpoken>().map { it.id }.toSet())
        assertEquals(call.heard().toString(), 3, call.heard().count { it.startsWith("result.") })
        assertTrue(call.heard().toString(), call.heard().any { "14 in stock" in it })
        // Three sessions, one prompt each, each carrying its own job.
        assertEquals(3, h.sessions().count { h.prompts(it.str("stored_session_id")!!).size == 1 })
        jobs.forEach { job -> assertEquals(job, 1, prompts().count { job in it }) }
        assertEquals(3, promptCount())
        noDoubles()
    }

    // ── 3. a correction ──────────────────────────────────────────────────────────────────

    @Test fun aCorrectedWordGoesToItsRunningTaskAsAQueuedUpdate() {
        h.signIn()
        val call = Call(routes = mapOf("slow job" to newTask()), briefs = mapOf("slow job" to listOf("Run the slow job for the Sapphire account.")))
        call.start()
        val text = "Run the slow job for the Sapphire account."
        call.say(text, text.split(' ').map { w -> if (w == "Sapphire") Word(w, confidence = 0.3, alternatives = listOf("Safilo"), uncertain = true) else Word(w, confidence = 0.95) })
        call.until("the task running") { call.actor.ui.value.tasks.singleOrNull()?.status == TaskUiStatus.Running }

        val turn = call.actor.ui.value.turn
        call.actor.send(Event.WordChosen(turn.turnId!!, turn.words.indexOfFirst { it.text == "Sapphire" }, "Safilo"))
        call.until("both results spoken") { call.events.count { it is CascadeEvent.ResultSpoken } >= 1 && call.task("t1").status == TaskUiStatus.Done && !call.actor.speaking }

        assertTrue(Effect.LearnWord("Sapphire", "Safilo") in call.appEffects)
        val session = h.sessions().single()
        val got = h.prompts(session.str("stored_session_id")!!).mapNotNull { it.str("text") }
        assertEquals("the task's session got the job and the correction", 2, got.size)
        assertTrue(got[1], got[1].contains("\"Sapphire\" should be \"Safilo\""))
        assertEquals("one task, no second one", listOf("t1"), call.actor.ui.value.tasks.map { it.id })
        noDoubles()
    }

    // ── 4. status ────────────────────────────────────────────────────────────────────────

    @Test fun statusIsAnsweredFromTheBoardAndNeverSubmits() {
        h.signIn()
        val call = Call(
            routes = mapOf("slow job" to newTask(), "how's it going" to mapOf("action" to "status", "line" to "status.working", "enough" to "line_only")),
            briefs = mapOf("slow job" to listOf("Run the slow job for the Leeds report.")),
        )
        call.start()
        call.say("Run the slow job for the Leeds report.")
        call.until("the task running") { call.actor.ui.value.tasks.singleOrNull()?.status == TaskUiStatus.Running }
        val heardBefore = call.heard().size
        call.say("How's it going?")
        call.until("the status line") { call.heard().drop(heardBefore).contains("status.working") }

        assertEquals("status never submits", 1, promptCount())
        assertEquals(TaskUiStatus.Running, call.task("t1").status)
        call.until("the job's result") { call.resultsSpoken() }
        assertEquals(1, promptCount())
        noDoubles()
    }

    // ── 5. cancel ────────────────────────────────────────────────────────────────────────

    @Test fun cancelInterruptsTheTaskAndNothingIsSpokenForIt() {
        h.signIn()
        val call = Call(
            routes = mapOf("slow job" to newTask(), "scrap" to mapOf("action" to "cancel_task", "task" to "t1", "line" to "cancel.one", "enough" to "line_only")),
            briefs = mapOf("slow job" to listOf("Run the slow job for the Leeds report.")),
        )
        call.start()
        call.say("Run the slow job for the Leeds report.")
        call.until("the task running") { call.actor.ui.value.tasks.singleOrNull()?.status == TaskUiStatus.Running }
        call.say("Scrap that job.")
        call.until("the task cancelled") { call.task("t1").status == TaskUiStatus.Cancelled && call.heard().contains("cancel.one") && !call.actor.speaking }

        val session = h.session(h.sessions().single().str("stored_session_id")!!)
        assertEquals("the turn stopped on the server", false, session.bool("running"))
        assertTrue(call.events.contains(CascadeEvent.TaskCancelled("t1")))
        assertFalse("no result for a cancelled task", call.events.any { it is CascadeEvent.ResultSpoken })
        assertEquals(1, promptCount())
        noDoubles()
    }

    // ── 6. offline memo, then reconnect ──────────────────────────────────────────────────

    @Test fun anOfflineMemoIsHeldThenSentOnceWhenTheNetworkReturns() {
        h.signIn()
        val call = Call(routes = mapOf("Leeds" to newTask()), briefs = mapOf("Leeds" to listOf("Chase the Leeds invoice.")))
        call.start()
        call.online.value = false
        call.until("memo mode") { call.actor.ui.value.connection.memo }
        call.say("Note to self, chase the Leeds invoice.")
        call.until("the memo held") { call.actor.ui.value.connection.outbox == 1 }
        assertEquals("nothing leaves while offline", 0, promptCount())
        assertTrue(call.heard().toString(), call.heard().all { it.startsWith("error.offline") || it.startsWith("\"") || it.startsWith("social") })

        call.online.value = true
        call.until("the memo's task done and spoken") { call.resultsSpoken() }
        val ui = call.actor.ui.value
        assertEquals(0, ui.connection.outbox)
        assertFalse(ui.connection.memo)
        assertEquals(TaskUiStatus.Done, ui.tasks.single().status)
        val prompt = prompts().single()
        assertTrue(prompt, prompt.contains("Leeds invoice") && prompt.contains("Dictated offline"))
        noDoubles()
    }

    // ── 7. killed mid-submit, then a restart ─────────────────────────────────────────────

    @Test fun killedBeforeThePromptLeftItIsSentOnceAfterTheRestart() {
        h.signIn()
        val store = MemoryStore()
        val routes = mapOf("stock" to newTask())
        val briefs = mapOf("stock" to listOf("Check stock of the Kestrel aviators."))
        // First process: dies the moment its submit would leave (the session already exists and is stored).
        val first = Call(routes, briefs, store = store, gate = EffectGate { e -> e !is Effect.SubmitTask })
        first.start()
        first.say("Check how many Kestrel aviators we have in stock.")
        first.until("killed at the submit") { first.killedAt.isCompleted }
        assertTrue(runBlocking { first.killedAt.await() } is Effect.SubmitTask)
        first.close()
        h.gateway.disconnect()
        assertEquals("the prompt never left", 0, promptCount())
        assertEquals("its session was created ahead", 1, h.sessions().size)

        // Second process on the same store, its own gateway: recovery finds the session never prompted and sends it once.
        val gateway = HermesGateway(h.config, h.auth, h.fake.client, h.scope)
        val second = Call(routes, briefs, store = store, gateway = gateway)
        second.start()
        second.until("the task done after the restart") { second.actor.ui.value.tasks.singleOrNull()?.status == TaskUiStatus.Done }
        // The app came back to an unfinished conversation: the Resume card; Continue reads the result.
        assertNotNull(second.actor.ui.value.resume)
        second.actor.send(Event.ResumeChosen(ResumeChoice.Continue))
        second.until("the result spoken") { second.events.any { it == CascadeEvent.ResultSpoken("t1") } && second.resultsSpoken() }
        assertEquals(TaskUiStatus.Done, second.task("t1").status)
        assertTrue(second.heard().toString(), second.heard().any { "14 in stock" in it })
        assertEquals(1, promptCount())
        // Recovery resends a never-prompted start on a fresh session (DurableTaskRegistry.notReceived):
        // the empty one stays on the server, but only one session ever holds the prompt.
        assertEquals(1, h.sessions().count { h.prompts(it.str("stored_session_id")!!).isNotEmpty() })
        noDoubles()
        gateway.disconnect()
    }

    // ── 8. resume with Play ──────────────────────────────────────────────────────────────

    @Test fun resumeWithPlayReadsTheResultThatFinishedWhileTheAppWasClosed() {
        h.signIn()
        val store = MemoryStore()
        val routes = mapOf("slow job" to newTask())
        val briefs = mapOf("slow job" to listOf("Run the slow job for the Leeds report."))
        val first = Call(routes, briefs, store = store)
        first.start()
        first.say("Run the slow job for the Leeds report.")
        first.until("the task running") { first.actor.ui.value.tasks.singleOrNull()?.status == TaskUiStatus.Running && !first.actor.speaking }
        // Hang up and the app goes away while the job runs.
        first.close()
        h.gateway.disconnect()

        val gateway = HermesGateway(h.config, h.auth, h.fake.client, h.scope)
        val second = Call(routes, briefs, store = store, gateway = gateway)
        second.start()
        second.until("the Resume card") { second.actor.ui.value.resume != null }
        second.until("the result in, unread") { second.task("t1").status == TaskUiStatus.Done && second.task("t1").unread }
        assertFalse("never read over the Resume card", second.events.any { it is CascadeEvent.ResultSpoken || it is CascadeEvent.ResultQueued })
        assertNotNull(second.actor.ui.value.resume)

        second.actor.send(Event.ResumeChosen(ResumeChoice.Play))
        second.until("the result spoken") { second.events.any { it == CascadeEvent.ResultSpoken("t1") } && second.resultsSpoken() }
        assertNull(second.actor.ui.value.resume)
        assertFalse(second.task("t1").unread)
        assertTrue(second.heard().toString(), second.heard().any { "Slow job finished" in it })
        assertEquals(1, promptCount())
        noDoubles()
        gateway.disconnect()
    }

    // ── 9. a key doubt never blocks (hub decision 2026-10-14) ──────────────────────────────

    private fun doubtedTask() = newTask() + ("clarity" to "key_doubt")

    /** A key-doubted slow job, started; returns once its question was heard and Hermes has the prompt. */
    private fun startDoubted(): Call {
        val call = Call(
            routes = mapOf("slow job" to doubtedTask(), "Castrol" to mapOf("action" to "reply"), "right" to mapOf("action" to "reply", "line" to "ack.yes")),
            briefs = mapOf("slow job" to listOf("Run the slow job for the Kestrel account.")),
            doubts = mapOf("slow job" to "Kestrel"),
        )
        call.start()
        call.say("Run the slow job for the Kestrel account.")
        call.until("the question asked, the task already running") {
            "\"Kestrel, right?\"" in call.heard() && call.actor.ui.value.tasks.singleOrNull()?.status == TaskUiStatus.Running
        }
        return call
    }

    /** No answer: the task started before the question and runs on unchanged. */
    @Test fun aKeyDoubtStartsTheTaskAtOnceAndAsksInParallel() {
        h.signIn()
        val call = startDoubted()
        val heard = call.heard()
        assertEquals("the start line first, then the question", listOf("start.one", "\"Kestrel, right?\""), heard.take(2))
        call.until("Hermes has the job") { promptCount() == 1 }
        val prompt = prompts().single()
        assertTrue(prompt, prompt.contains("Clarity: key_doubt (0.95)") && prompt.contains("The voice assistant is checking \"Kestrel\""))
        // Nobody answers: the job finishes as it was sent.
        call.until("the result spoken") { call.resultsSpoken() }
        assertEquals(TaskUiStatus.Done, call.task("t1").status)
        assertEquals("nothing more was sent", 1, promptCount())
        noDoubles()
    }

    @Test fun aCorrectionToTheKeyDoubtRedirectsTheTask() {
        h.signIn()
        val call = startDoubted()
        call.until("Hermes has the job") { promptCount() == 1 }
        call.say("No, Castrol.")
        call.until("the correction reached the task") { promptCount() == 2 }
        val session = h.sessions().single().str("stored_session_id")!!
        val got = h.prompts(session)
        assertEquals(2, got.size)
        assertTrue(got[1].str("text")!!, got[1].str("text")!!.contains("Correction: I asked the user \"Kestrel, right?\"") && got[1].str("text")!!.contains("No, Castrol."))
        assertEquals("a redirect, not a queued note", false, got[1].bool("queued"))
        assertEquals("redirected", got[1].str("outcome"))
        call.until("one result for the redirected job") { call.resultsSpoken() }
        assertEquals(listOf("t1"), call.actor.ui.value.tasks.map { it.id })
        assertEquals(1, call.events.count { it is CascadeEvent.ResultSpoken })
        assertTrue(call.heard().toString(), "update.redirect" in call.heard())
        noDoubles()
    }

    @Test fun aConfirmationOfTheKeyDoubtSendsNothing() {
        h.signIn()
        val call = startDoubted()
        call.say("Yes, that's right.")
        call.until("the yes acknowledged") { "ack.understood" in call.heard() }
        call.until("the result spoken") { call.resultsSpoken() }
        assertEquals("no redirect, no note", 1, promptCount())
        assertEquals(TaskUiStatus.Done, call.task("t1").status)
        noDoubles()
    }

    // ── 10. stop everything ──────────────────────────────────────────────────────────────

    @Test fun stopEverythingCancelsAllThreeWithoutAskingWhich() {
        h.signIn()
        val jobs = listOf("Run the slow job for Leeds.", "Run the slow job for Bristol.", "Run the slow job for Cardiff.")
        val call = Call(
            routes = mapOf(
                "three slow jobs" to newTask(split = "several", line = "start.parallel"),
                "stop everything" to mapOf("action" to "cancel_task", "task" to "none", "line" to "cancel.all"),
            ),
            briefs = mapOf("three slow jobs" to jobs),
        )
        call.start()
        call.say("Three slow jobs: Leeds, Bristol and Cardiff.")
        call.until("three running") { call.actor.ui.value.tasks.count { it.status == TaskUiStatus.Running } == 3 && promptCount() == 3 }
        call.say("Stop everything.")
        call.until("all three cancelled and the count said") {
            call.actor.ui.value.tasks.all { it.status == TaskUiStatus.Cancelled } && "\"That's all three.\"" in call.heard() && !call.actor.speaking
        }
        assertTrue(call.heard().toString(), "cancel.all" in call.heard() && "cancel.which" !in call.heard())
        // Each session got its interrupt (its turn then stops on the fake's own thread, a moment later).
        val sessions = h.sessions()
        assertEquals(3, sessions.size)
        assertTrue("every session interrupted: $sessions", sessions.all { s -> s.arr("received").any { it.str("kind") == "interrupt" } })
        assertEquals(setOf("t1", "t2", "t3"), call.events.filterIsInstance<CascadeEvent.TaskCancelled>().map { it.id }.toSet())
        assertFalse("no result for a cancelled task", call.events.any { it is CascadeEvent.ResultSpoken })
        assertEquals(3, promptCount())
        noDoubles()
    }

    private companion object {
        /** The fake's replies (scenarios/test.json) the writer reads back. */
        val RESULTS = listOf("Sample Frame TEST-0001 has 14 in stock.", "Slow job finished", "Done:")

        /** The shortest pause the setting allows, so each call is quick. */
        val SETTINGS = CascadeSettings(pauseMs = 400)

        /** The hold-silence golden calls: the recheck and the silence shortened (real time on the wire). */
        val HOLD = SETTINGS.copy(holdRecheckMs = 300, holdSilenceMs = 400)
    }
}
