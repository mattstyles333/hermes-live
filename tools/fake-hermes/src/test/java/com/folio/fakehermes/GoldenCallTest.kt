package com.folio.fakehermes

import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.ports.AudioOut
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.telemetry.TelemetrySink
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden calls (test shape plan, item 1): a whole Bill call in-process through [CascadeCore] and
 * [CascadePorts.gateway] (the app's own wiring: `GatewayHermesPort` on a real `HermesGateway`)
 * against the Python fake Hermes, with a scripted Jev, writer and voice. No emulator, no key.
 *
 * The first one is the Bill smoke call (`BillSmokeTest`) without the phone: a stock check is
 * routed to a new task, the task goes Starting → Running (a tool on the card) → Done, the fake
 * gets the prompt exactly once, and the result is spoken.
 */
class GoldenCallTest {
    private val h = Harness(FakeHermesProcess("scenarios/test.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
        h.close()
    }

    /** Jev routes by what was said; the writer answers briefs and sentences; each clip remembers its text. */
    private class ScriptedOpenRouter : OpenRouterPort {
        val clipText: MutableMap<AudioClip, String> = Collections.synchronizedMap(IdentityHashMap())
        val sentencesAsked = CopyOnWriteArrayList<String>()

        override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> {
            delay(20)
            val latest = state["latest"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val route = if ("stock" in latest.lowercase()) {
                mapOf("action" to "new_task", "split" to "one", "clarity" to "clear")
            } else {
                mapOf("action" to "reply")
            }
            return OpenRouterResult.Ok((mapOf("turn" to "done") + route).mapValues { (_, v) -> JevAnswer("choice", v, mapOf(v to 0.95), 0.95) })
        }

        override fun sentences(system: String, prompt: String): Flow<String> {
            sentencesAsked += prompt
            return flowOf("Sample Frame TEST-0001 has 14 in stock.")
        }

        override suspend fun write(system: String, prompt: String): OpenRouterResult<String> {
            delay(20)
            check(system == Briefs.SYSTEM) { "unexpected writer job" }
            return OpenRouterResult.Ok(
                """{"briefs":[{"brief":"Goal: check stock of the Kestrel aviators.\nDone when: the count is known","label":"Kestrel stock","summary":"Checking the Kestrel stock."}],"repaired":"","notes":""}""",
            )
        }

        override suspend fun clip(text: String): OpenRouterResult<AudioClip> {
            val format = SpeechFormat.Pcm()
            return OpenRouterResult.Ok(AudioClip(ByteArray(format.bytesFor(150).toInt()), format).also { clipText[it] = text })
        }

        override fun isCached(text: String): Boolean = true
    }

    /** Plays instantly and records what Bill said, in order. */
    private class SpokenLog(private val router: ScriptedOpenRouter) : AudioOut {
        val said = CopyOnWriteArrayList<String>()
        override suspend fun play(clip: AudioClip): Long {
            said += router.clipText[clip] ?: "?"
            delay(clip.durationMs)
            return clip.durationMs
        }

        override fun stop() = Unit
    }

    private suspend fun eventually(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!check()) delay(10) } ?: throw AssertionError("timed out: $what")
    }

    @Test fun oneStockCheckIsRunningThenDoneThenSpoken() = runBlocking {
        h.signIn()
        val router = ScriptedOpenRouter()
        val out = SpokenLog(router)
        val core = CascadeCore.create(
            scope = scope,
            ports = CascadePorts.gateway(h.gateway, h.config, router, out, MemoryStore()),
            bank = PhraseBank.load(),
            settings = { CascadeSettings() },
            telemetry = TelemetrySink.None,
            seed = 7,
        )
        val actor = core.actor
        val events = CopyOnWriteArrayList<CascadeEvent>()
        val seen = CopyOnWriteArrayList<TaskUiStatus>()
        val subscribed = CompletableDeferred<Unit>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { subscribed.complete(Unit); actor.events.collect { events += it } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            actor.ui.collect { ui -> ui.tasks.firstOrNull()?.let { t -> if (seen.lastOrNull() != t.status) seen += t.status } }
        }
        subscribed.await()
        h.gateway.connect()
        core.start()

        actor.speechStarted()
        actor.send(Event.Final("Check how many Kestrel aviators we have in stock."))
        actor.speechEnded()

        eventually("a task card") { actor.ui.value.tasks.isNotEmpty() }
        eventually("task running (status ${actor.ui.value.tasks.firstOrNull()?.status}, seen $seen)") {
            actor.ui.value.tasks.any { it.status == TaskUiStatus.Running || it.currentTool != null }
        }
        eventually("task finished") { events.any { it is CascadeEvent.TaskFinished } }
        eventually("result spoken") { events.any { it is CascadeEvent.ResultSpoken } && !actor.speaking }

        val task = actor.ui.value.tasks.single()
        assertEquals(TaskUiStatus.Done, task.status)
        assertEquals("both tool steps reached the card", 2, task.steps)
        assertEquals(listOf(TaskUiStatus.Starting, TaskUiStatus.Running, TaskUiStatus.Done), seen.filter { it != TaskUiStatus.Queued })
        assertTrue("accepted announced", events.any { it is CascadeEvent.TaskAccepted })
        assertTrue("a tool showed", events.any { it is CascadeEvent.ToolStarted })
        // Hermes got the prompt exactly once.
        assertEquals(1, h.prompts()["count"].toString().toInt())
        assertEquals(true, h.doubles().bool("ok"))
        // The result was spoken (after the stock line).
        assertTrue("result spoken: ${out.said}", out.said.any { "14 in stock" in it })
        core.stop()
    }
}
