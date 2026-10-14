package com.folio.hermeslive.smoke

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.SpeechAudio
import com.folio.hermes.openrouter.SpeechDelivery
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.audio.CascadeAudioFactory
import com.folio.hermeslive.audio.CascadeAudioIo
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.cascade.CascadeCallEngine
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.SignInResult
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.graph
import com.folio.hermeslive.hooks.BuildHooks
import com.folio.hermeslive.hooks.CapturingAudioOut
import com.folio.hermeslive.service.CallService
import com.folio.hermeslive.stt.CorpusAudioSource
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one Bill smoke call (spec v2 §11b, cut down): the debug app, engine `cascade`, the real
 * OpenRouter (Jev, writer, Bill's voice) with a key given at run time, the fake Hermes on the
 * host, speech from a WAV through the real sherpa pipeline, Bill into a capturing output.
 *
 * Expect: Jev routes the stock-check request to `new_task`; a stock line plays; the fake gets
 * exactly one prompt; the tool steps arrive; the result is spoken. Timings and the outcome go to
 * `<external files>/smoke/bill.json` (no transcripts). Run it with `tools/cascade-smoke.sh`
 * (reads `OPENROUTER_API_KEY`); skipped without the `openrouterKey` argument.
 *
 * Arguments: `openrouterKey` (required), `hermesUrl` (default `http://10.0.2.2:18119`),
 * `wav` (a 16 kHz WAV on the device; default: the request spoken by TTS at run time), `say`.
 *
 * `dryRun=true` (no key needed, no OpenRouter request): a placeholder key, `openrouter.ai`
 * mapped to an address that refuses, a corpus WAV. It proves the plumbing (fake Hermes sign-in,
 * the engine switch, the call service, models, the corpus into the pipeline, a turn decided and
 * held for the network) and that the fake gets no prompt.
 */
@RunWith(AndroidJUnit4::class)
class BillSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(what: String, timeoutMs: Long, check: () -> Boolean): Long {
        val t0 = System.nanoTime()
        while (!check()) {
            if (System.nanoTime() - t0 > timeoutMs * 1_000_000) throw AssertionError("timed out: $what")
            Thread.sleep(25)
        }
        return System.nanoTime() / 1_000_000
    }

    @Test fun oneStockCheckCall() {
        val dry = args.getString("dryRun") == "true"
        val key = if (dry) DRY_KEY else args.getString("openrouterKey")
        assumeTrue("openrouterKey not given", !key.isNullOrBlank())
        if (dry) BuildHooks.hosts = mapOf("openrouter.ai" to "127.0.0.1")
        val say = args.getString("say") ?: "Check how many Kestrel aviators we have in stock."
        val g = context.graph
        val shell = VoiceShell.of(context)
        val out = CapturingAudioOut()
        val corpus = CorpusAudioSource(pace = 2.0)
        val events = ArrayList<Pair<Long, CascadeEvent>>()
        val statuses = HashMap<String, LinkedHashMap<TaskUiStatus, Long>>()
        val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = JSONObject()
        try {
            shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            runBlocking { g.ready.await() }
            val signIn = runBlocking { g.account.signIn(args.getString("hermesUrl") ?: "http://10.0.2.2:18119", "fake", "fake-password") }
            assertTrue("signed in to the fake Hermes: $signIn", signIn is SignInResult.Ok)
            shell.shell.release()
            // A fresh Bill: what earlier runs left in his durable store (an offline memo held by the
            // dry run, a task waiting on approval) would otherwise be sent or recovered in this call.
            freshBill(shell)
            shell.keys.save(key!!, KeyCheck.Valid)
            // Not shell.onSettingsChanged(): that would auto-prepare (about 285 TTS requests). Stock
            // lines are therefore not cached here: their latency is an upper bound.
            g.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade, startOnOpen = false) }
            assertTrue("gate open", shell.gate.active)

            val wav = args.getString("wav") ?: if (dry) File(context.getExternalFilesDir(null), "stt-corpus/e14-sarah.wav").path else null
            val pcm = wav?.let { CorpusAudioSource.read(File(it)) } ?: speak(key, say)
            BuildHooks.cascadeAudio = CascadeAudioFactory { _, _ -> CascadeAudioIo(corpus, out, out) }

            val scenario = ActivityScenario.launch(LiveActivity::class.java)
            scenario.onActivity { CallService.start(it, CallRequest(entry = "smoke")) }
            val t0 = System.nanoTime() / 1_000_000
            val live = waitFor("call live", 30_000) { g.calls.state.value.phase == CallPhase.Live }
            result.put("call_live_ms", live - t0)
            val engine = g.calls.cascadeEngine as CascadeCallEngine
            val actor = engine.session!!.core.actor
            watch.launch { actor.events.collect { e -> synchronized(events) { events += System.nanoTime() / 1_000_000 to e } } }
            // First time each task shows each status (the card's Starting → Running → Done).
            watch.launch {
                actor.ui.collect { ui ->
                    val now = System.nanoTime() / 1_000_000
                    synchronized(statuses) { ui.tasks.forEach { t -> statuses.getOrPut(t.id) { LinkedHashMap() }.putIfAbsent(t.status, now) } }
                }
            }
            // The noise floor learns over the first 2 s: give it quiet first (the source idles in silence).
            Thread.sleep(2_500)

            corpus.enqueue(pcm, "request", silenceAfterMs = 4_000)
            val speechMs = pcm.size * 1000L / 16_000 / 2 // fed at twice real time
            val fedAt = waitFor("request fed", 30_000) { corpus.clipStarts.containsKey("request") }
            val speechEnd = fedAt + speechMs
            fun at(e: (CascadeEvent) -> Boolean) = synchronized(events) { events.firstOrNull { e(it.second) }?.first }

            if (dry) {
                // Jev can't be reached: the turn is decided without it and held for the network.
                waitFor("turn held", 30_000) { at { it is CascadeEvent.TurnHeld } != null || actor.ui.value.connection.outbox > 0 }
                result.put("speech_end_to_held_ms", at { it is CascadeEvent.TurnHeld }?.let { it - speechEnd })
                    .put("outbox", actor.ui.value.connection.outbox).put("tasks", actor.ui.value.tasks.size)
                main { g.calls.end() }
                waitFor("call ended", 10_000) { !g.calls.state.value.inCall }
                scenario.close()
                assertEquals("no task without Jev", 0, actor.ui.value.tasks.size)
                result.put("pass", true)
                return
            }
            waitFor("turn committed", 20_000) { at { it is CascadeEvent.TurnCommitted } != null }
            // This call's task: the one the committed turn started (never an older card).
            fun ours() = actor.state.value.tasks.tasks.firstOrNull { t -> synchronized(events) { events.any { (_, e) -> e is CascadeEvent.TurnCommitted && e.turnId == t.turnId } } }
            val taskId = run {
                waitFor("task started", 30_000) { ours() != null }
                ours()!!.id
            }
            // The ui projection follows the state a moment later: the card may not be there yet.
            fun card() = actor.ui.value.tasks.firstOrNull { it.id == taskId }
            fun seen(status: TaskUiStatus) = synchronized(statuses) { statuses[taskId]?.get(status) }
            waitFor("task running", 30_000) { card()?.let { it.status == TaskUiStatus.Running || it.currentTool != null } == true }
            waitFor("a tool on the card", 10_000) { card()?.let { it.currentTool != null || it.steps > 0 } == true }
            // Let the card settle on screen (the satellite fades in over about 0.3 s), then capture
            // while the fake's tools are still running (its stock check takes about 2.5 s).
            Thread.sleep(700)
            result.put("midtask_card", card()?.status?.name)
            shell("screencap -p /sdcard/Download/bill-smoke-midtask.png")
            waitFor("task finished", 60_000) { at { it is CascadeEvent.TaskFinished && it.id == taskId } != null }
            waitFor("result spoken", 60_000) { at { it is CascadeEvent.ResultSpoken && it.id == taskId } != null && !actor.speaking }
            val task = card()!!
            val played = out.played
            val started = at { it is CascadeEvent.TaskStarted && it.id == taskId }!!
            val finished = at { it is CascadeEvent.TaskFinished && it.id == taskId }!!
            result
                .put("route", actor.ui.value.jev.action?.choice)
                .put("tasks", actor.ui.value.tasks.size)
                .put("task_status", task.status.name)
                .put("task_statuses", synchronized(statuses) { statuses[taskId]?.keys?.joinToString(",") { it.name } })
                .put("task_steps", task.steps)
                .put("clips_played", played.size)
                .put("speech_end_to_commit_ms", at { it is CascadeEvent.TurnCommitted }!! - speechEnd)
                .put("speech_end_to_stock_line_ms", played.firstOrNull()?.let { it.startedAtMs - speechEnd })
                .put("jev_latency_ms", actor.ui.value.jev.latencyMs)
                .put("speech_end_to_task_started_ms", started - speechEnd)
                .put("task_started_to_accepted_ms", at { it is CascadeEvent.TaskAccepted && it.id == taskId }?.let { it - started })
                .put("task_started_to_running_ms", seen(TaskUiStatus.Running)?.let { it - started })
                .put("task_started_to_first_tool_ms", at { it is CascadeEvent.ToolStarted && it.id == taskId }?.let { it - started })
                .put("task_finished_to_result_audio_ms", played.firstOrNull { it.startedAtMs >= finished }?.startedAtMs?.minus(finished))
                .put("task_finished_to_result_spoken_ms", at { it is CascadeEvent.ResultSpoken && it.id == taskId }!! - finished)
            main { g.calls.end() }
            waitFor("call ended", 10_000) { !g.calls.state.value.inCall }
            scenario.close()
            assertEquals("new_task", actor.ui.value.jev.action?.choice ?: result.optString("route"))
            assertEquals(1, result.getInt("tasks"))
            assertEquals(TaskUiStatus.Done.name, result.getString("task_status"))
            assertTrue("a stock line and the result were spoken", played.size >= 2)
            result.put("pass", true)
        } catch (e: Throwable) {
            result.put("pass", false).put("error", e.javaClass.simpleName + ": " + e.message?.take(160))
            throw e
        } finally {
            File(context.getExternalFilesDir(null), "smoke").apply { mkdirs() }.resolve("bill.json").writeText(result.toString(2))
            android.util.Log.i("HermesSmoke", "BILL_SMOKE $result")
            watch.cancel()
            runCatching { main { g.calls.end() } }
            BuildHooks.reset()
            shell.keys.remove()
            g.settings.update { it.copy(voiceEngine = VoiceEngine.GptLive, startOnOpen = true) }
            shell.onSettingsChanged()
            runBlocking { g.account.signOut() }
        }
    }

    /** Wipe Bill's durable store, decision log and rotations (keep the TTS cache and the speech models). No runtime may be open. */
    private fun freshBill(shell: VoiceShell) {
        shell.shell.release()
        shell.dirs.store.deleteRecursively()
        shell.dirs.decisions.delete()
        shell.dirs.rotations.delete()
    }

    private companion object {
        /** Shaped like a key, valid nowhere; used only with `openrouter.ai` unreachable. */
        const val DRY_KEY = "sk-or-v1-dryrun000000000000000000000000000000000000000000000000000000"
    }

    /** [text] spoken by OpenRouter TTS (24 kHz), as 16 kHz mono samples for the pipeline. */
    private fun speak(key: String, text: String): ShortArray {
        val bytes = ByteArrayOutputStream()
        var rate = 24_000
        runBlocking {
            OpenRouter(key = { key }).speech.speak(text, SpeechFormat.Pcm(), SpeechDelivery.WholeClip, voice = "george").collect { part ->
                when (part) {
                    is SpeechAudio.Started -> rate = (part.format as? SpeechFormat.Pcm)?.sampleRateHz ?: rate
                    is SpeechAudio.Chunk -> bytes.write(part.bytes)
                    is SpeechAudio.Finished -> Unit
                }
            }
        }
        val b = bytes.toByteArray()
        val src = ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort() }
        return resample(src, rate, 16_000)
    }

    /**
     * Band-limited resampling (windowed sinc, cut-off just under the lower Nyquist). Picking every
     * 1.5th sample instead folds 8-12 kHz into the speech band and smears the consonants the
     * recogniser needs (a phone's mic has an anti-alias filter; the test input should too).
     */
    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return src
        val ratio = from.toDouble() / to
        val cutoff = 0.95 * minOf(1.0, to.toDouble() / from)
        val half = 16
        val n = (src.size / ratio).toInt()
        return ShortArray(n) { i ->
            val centre = i * ratio
            val first = kotlin.math.ceil(centre - half / cutoff).toInt().coerceAtLeast(0)
            val last = kotlin.math.floor(centre + half / cutoff).toInt().coerceAtMost(src.size - 1)
            var acc = 0.0
            for (j in first..last) {
                val x = (j - centre) * cutoff
                val sinc = if (x == 0.0) 1.0 else kotlin.math.sin(Math.PI * x) / (Math.PI * x)
                val window = 0.5 + 0.5 * kotlin.math.cos(Math.PI * x / half)
                acc += src[j] * cutoff * sinc * window
            }
            acc.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
