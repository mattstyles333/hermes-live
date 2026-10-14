package com.folio.hermeslive.smoke

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.routing.KeyDoubt
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
import com.folio.hermeslive.hooks.NetTimings
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
 * `<external files>/smoke/bill.json` (no transcripts). `speech_end_*` count from the VAD's
 * `SpeechEndedAt` (the anchor the app's pause counts from); `clip_end_*` from the clip's length.
 * Run it with `tools/cascade-smoke.sh` (reads `OPENROUTER_API_KEY`); skipped without the
 * `openrouterKey` argument.
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
        val net = NetTimings().also { BuildHooks.netEvents = it }
        val say = args.getString("say") ?: "Check how many Kestrel aviators we have in stock."
        val g = context.graph
        val shell = VoiceShell.of(context)
        val out = CapturingAudioOut()
        val corpus = CorpusAudioSource(pace = 2.0)
        val events = ArrayList<Pair<Long, CascadeEvent>>()
        val statuses = HashMap<String, LinkedHashMap<TaskUiStatus, Long>>()
        val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val timeline = ArrayList<Pair<Long, String>>()
        var anchor = 0L
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
            // `-e speech cloud`: Speech recognition "Cloud when online" (the default model); else on device.
            val cloud = args.getString("speech") == "cloud"
            result.put("speech", if (cloud) "cloud" else "device")
            g.settings.update {
                it.copy(
                    voiceEngine = VoiceEngine.Cascade, startOnOpen = false,
                    speechRecognition = if (cloud) com.folio.hermeslive.data.SpeechRecognition.Cloud else com.folio.hermeslive.data.SpeechRecognition.OnDevice,
                    // What a new install gets (`-e live` / `-e accurate` override), not what this emulator had first.
                    liveModel = args.getString("live") ?: com.folio.hermeslive.data.SpeechModelChoice.DEFAULT_LIVE.id,
                    accurateModel = args.getString("accurate") ?: com.folio.hermeslive.data.SpeechModelChoice.DEFAULT_ACCURATE.id,
                )
            }
            result.put("live_model", com.folio.hermeslive.data.SpeechModelChoice.live(g.settings.settings.value).id)
                .put("accurate_model", com.folio.hermeslive.data.SpeechModelChoice.accurate(g.settings.settings.value).id)
            assertTrue("gate open", shell.gate.active)

            val wav = args.getString("wav") ?: if (dry) File(context.getExternalFilesDir(null), "stt-corpus/e14-sarah.wav").path else null
            val pcm = wav?.let { CorpusAudioSource.read(File(it)) } ?: speak(key, say)
            // The request as fed (TTS, made-up words): kept so a misheard run can be decoded again offline (`TailPadDeviceTest`).
            File(context.getExternalFilesDir(null), "smoke").apply { mkdirs() }.resolve("request.wav").writeBytes(wav16(pcm))
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
            // The VAD's end of speech (`SpeechEndedAt`, on the actor's clock: nanoTime ms, as here): the
            // committed action's `speechEndAt` is the reducer's own anchor; the pause's `endAt` before it.
            val vadEnds = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val lastPauseEnd = java.util.concurrent.atomic.AtomicReference<Long?>(null)
            watch.launch {
                actor.state.collect { st ->
                    st.turn.pause?.endAt?.let { lastPauseEnd.set(it) }
                    (listOfNotNull(st.actions.active) + st.actions.waiting).forEach { a -> vadEnds.putIfAbsent(a.turnId, a.speechEndAt) }
                }
            }
            // Diagnostics: every change of the turn-taking's shape (no words, only counts), on the actor's clock.
            watch.launch {
                var last = ""
                actor.state.collect { st ->
                    val t = st.turn
                    val line = "speaking=${st.userSpeaking} stage=${t.pause?.stage} heard=${words(t.heard)}w pending=${t.pending.size} " +
                        "parked=${t.parked} bill=${st.speech.current != null} actions=${listOfNotNull(st.actions.active).size + st.actions.waiting.size}"
                    if (line != last) {
                        last = line
                        synchronized(timeline) { timeline += System.nanoTime() / 1_000_000 to line }
                    }
                }
            }
            // The noise floor learns over the first 2 s: give it quiet first (the source idles in silence).
            Thread.sleep(2_500)

            corpus.enqueue(pcm, "request", silenceAfterMs = 4_000)
            val speechMs = pcm.size * 1000L / 16_000 / 2 // fed at twice real time
            val fedAt = waitFor("request fed", 30_000) { corpus.clipStarts.containsKey("request") }
            anchor = fedAt
            // The clip's end (fed at twice real time): the old anchor, kept as `clip_end_*`.
            val clipEnd = fedAt + speechMs
            fun at(e: (CascadeEvent) -> Boolean) = synchronized(events) { events.firstOrNull { e(it.second) }?.first }
            fun committedTurn() = synchronized(events) { events.firstNotNullOfOrNull { (it.second as? CascadeEvent.TurnCommitted)?.turnId } }
            /** The VAD's end of speech for the committed turn (else the last pause seen), falling back to the clip's end. */
            fun vadEnd(): Long? = committedTurn()?.let { vadEnds[it] } ?: lastPauseEnd.get()

            if (dry) {
                // Jev can't be reached: the turn is decided without it and held for the network.
                waitFor("turn held", 30_000) { at { it is CascadeEvent.TurnHeld } != null || actor.ui.value.connection.outbox > 0 }
                val speechEnd = vadEnd() ?: clipEnd
                result.put("speech_end_source", if (vadEnd() != null) "vad" else "clip")
                    .put("vad_end_minus_clip_end_ms", vadEnd()?.let { it - clipEnd })
                    .put("speech_end_to_held_ms", at { it is CascadeEvent.TurnHeld }?.let { it - speechEnd })
                    .put("clip_end_to_held_ms", at { it is CascadeEvent.TurnHeld }?.let { it - clipEnd })
                    .put("outbox", actor.ui.value.connection.outbox).put("tasks", actor.ui.value.tasks.size)
                main { g.calls.end() }
                waitFor("call ended", 10_000) { !g.calls.state.value.inCall }
                scenario.close()
                assertEquals("no task without Jev", 0, actor.ui.value.tasks.size)
                result.put("pass", true)
                return
            }
            waitFor("turn committed", 20_000) { at { it is CascadeEvent.TurnCommitted } != null }
            waitFor("the committed turn's speech end", 5_000) { vadEnd() != null }
            val speechEnd = vadEnd()!!
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
            // The first stock line after speech end (the start line; a key doubt's question follows the task start).
            val lineAt = synchronized(events) { events.firstOrNull { (t, e) -> e is CascadeEvent.LinePlayed && t >= speechEnd }?.first }
            val finished = at { it is CascadeEvent.TaskFinished && it.id == taskId }!!
            result
                .put("route", actor.ui.value.jev.action?.choice)
                .put("tasks", actor.ui.value.tasks.size)
                .put("task_status", task.status.name)
                .put("task_statuses", synchronized(statuses) { statuses[taskId]?.keys?.joinToString(",") { it.name } })
                .put("task_steps", task.steps)
                .put("clips_played", played.size)
                // From the VAD's `SpeechEndedAt` (what the app's pause counts from); `clip_end_*` from the clip's length.
                .put("speech_end_source", "vad")
                .put("vad_end_minus_clip_end_ms", speechEnd - clipEnd)
                .put("speech_end_to_commit_ms", at { it is CascadeEvent.TurnCommitted }!! - speechEnd)
                .put("speech_end_to_stock_line_ms", lineAt?.let { it - speechEnd })
                .put("speech_end_to_first_audio_ms", played.firstOrNull()?.let { it.startedAtMs - speechEnd })
                // Jev's `clarity` when it doubted the words (a choice id, not content). Since 2026-10-14 a
                // `key_doubt` never blocks: the task starts at once and Bill asks his question after it.
                .put("clarity_doubt", synchronized(events) { events.firstNotNullOfOrNull { (it.second as? CascadeEvent.ClarityDoubt)?.clarity } })
                // Bill's key-doubt question is waiting for an answer (nobody answers in the smoke).
                .put("key_doubt_asked", actor.state.value.marks[KeyDoubt.MARK].orEmpty().isNotEmpty())
                .put("jev_latency_ms", actor.ui.value.jev.latencyMs)
                .put("speech_end_to_task_started_ms", started - speechEnd)
                .put("clip_end_to_commit_ms", at { it is CascadeEvent.TurnCommitted }!! - clipEnd)
                .put("clip_end_to_stock_line_ms", lineAt?.let { it - clipEnd })
                .put("clip_end_to_task_started_ms", started - clipEnd)
                .put("task_started_to_accepted_ms", at { it is CascadeEvent.TaskAccepted && it.id == taskId }?.let { it - started })
                .put("task_started_to_running_ms", seen(TaskUiStatus.Running)?.let { it - started })
                .put("task_started_to_first_tool_ms", at { it is CascadeEvent.ToolStarted && it.id == taskId }?.let { it - started })
                .put("task_finished_to_result_audio_ms", played.firstOrNull { it.startedAtMs >= finished }?.startedAtMs?.minus(finished))
                .put("task_finished_to_result_spoken_ms", at { it is CascadeEvent.ResultSpoken && it.id == taskId }!! - finished)
            // Where OpenRouter's time went (host and path only): Jev calls around the turn, and the first speech fetch after it.
            val jev = net.calls(OPENROUTER, "/systemone")
            result.put("jev_calls", org.json.JSONArray(jev.map { row(it, speechEnd) }))
            net.calls(OPENROUTER, "/audio/speech").firstOrNull { it.startMs >= speechEnd }?.let { result.put("first_tts_after_speech_end", row(it, speechEnd)) }
            result.put("tts_fetches_during_call", net.calls(OPENROUTER, "/audio/speech").count { it.startMs >= t0 })
            // The first stock line: its slot id (not content), and whether it had to be fetched after speech end (a cache miss).
            result.put("first_line_slot", synchronized(events) { events.firstNotNullOfOrNull { (t, e) -> (e as? CascadeEvent.LinePlayed)?.slot?.takeIf { t >= speechEnd } } })
            lineAt?.let { line -> result.put("tts_fetched_before_first_line", net.calls(OPENROUTER, "/audio/speech").count { it.startMs in speechEnd..line }) }
            // The writer's calls (the briefs before the task starts, then the spoken result).
            result.put("writer_calls", org.json.JSONArray(net.calls(OPENROUTER, "/chat/completions").filter { it.startMs >= speechEnd - 30_000 }.map { row(it, speechEnd) }))
            // Cloud speech-to-text: each segment's upload (none on device).
            result.put("stt_calls", org.json.JSONArray(net.calls(OPENROUTER, "/audio/transcriptions").map { row(it, speechEnd) }))
            net.calls(OPENROUTER, "/key").lastOrNull()?.let { result.put("warm", row(it, speechEnd)) }
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
            runCatching { diagnose(result, shell, timeline, events, anchor) }
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

    private fun words(text: String) = text.split(' ').count { it.isNotBlank() }

    /**
     * What happened to the turn, for a smoke that failed: the turn-taking's shape over time (counts,
     * no words), the event types, and each decision-log record's outcome and Jev's `turn`/`action`
     * picks with their confidence. Times are ms from the request clip's start ([anchor]).
     */
    private fun diagnose(result: JSONObject, shell: VoiceShell, timeline: List<Pair<Long, String>>, events: List<Pair<Long, CascadeEvent>>, anchor: Long) {
        val d = JSONObject()
        d.put("timeline", org.json.JSONArray(synchronized(timeline) { timeline.map { (t, l) -> "${t - anchor} $l" } }))
        d.put("events", org.json.JSONArray(synchronized(events) { events.map { (t, e) -> "${t - anchor} ${e.javaClass.simpleName}" } }))
        val decisions = org.json.JSONArray()
        if (shell.dirs.decisions.isFile) {
            shell.dirs.decisions.readLines().filter { it.isNotBlank() }.forEach { line ->
                val r = JSONObject(line)
                val a = r.optJSONObject("answers")
                fun pick(q: String) = a?.optJSONObject(q)?.let { "${it.optString("choice")}@${"%.2f".format(it.optDouble("confidence"))}" }
                // How the turn was committed: Jev's `done` at the pause, or the hold-silence rule (`hold_silence`).
                if (r.optString("outcome") == "done" && !result.has("commit_via")) result.put("commit_via", (r.opt("via") as? String) ?: "pause")
                decisions.put(
                    JSONObject().put("turn", r.optString("turnId")).put("outcome", r.optString("outcome")).put("route", r.opt("route"))
                        .put("via", r.opt("via")).put("slot", r.opt("slot")).put("failure", r.opt("failure")).put("jev_ms", r.opt("jevMs"))
                        .put("jev_turn", pick("turn")).put("jev_action", pick("action")).put("jev_clarity", pick("clarity"))
                        .put("jev_calls", r.optInt("jevCalls")).put("utterance_words", r.optJSONObject("state")?.optString("utterance")?.let(::words)),
                )
                // The smoke's own scripted sentence (test input, not user data): to logcat only, never bill.json.
                r.optJSONObject("state")?.optString("utterance")?.let { android.util.Log.i("HermesSmoke", "BILL_SMOKE_HEARD ${r.optString("turnId")} ${r.optString("outcome")}: $it") }
            }
        }
        d.put("decisions", decisions)
        result.put("diag", d)
    }

    /** Wipe Bill's durable store, decision log and rotations (keep the TTS cache and the speech models). No runtime may be open. */
    private fun freshBill(shell: VoiceShell) {
        shell.shell.release()
        shell.dirs.store.deleteRecursively()
        shell.dirs.decisions.delete()
        shell.dirs.rotations.delete()
    }

    private fun row(r: NetTimings.Row, speechEnd: Long) = JSONObject()
        .put("at_ms_from_speech_end", r.startMs - speechEnd).put("total_ms", r.totalMs).put("ttfb_ms", r.ttfbMs)
        .put("dns_ms", r.dnsMs).put("connect_ms", r.connectMs).put("tls_ms", r.tlsMs).put("protocol", r.protocol).put("reused", r.reused)

    private companion object {
        const val OPENROUTER = "openrouter.ai"

        /** Shaped like a key, valid nowhere; used only with `openrouter.ai` unreachable. */
        const val DRY_KEY = "sk-or-v1-dryrun000000000000000000000000000000000000000000000000000000"
    }

    /** 16 kHz mono 16-bit samples as a WAV file. */
    private fun wav16(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
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
