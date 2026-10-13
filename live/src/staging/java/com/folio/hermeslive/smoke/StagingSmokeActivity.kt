package com.folio.hermeslive.smoke

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.folio.hermes.BusyMode
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.cascade.CascadeCore
import com.folio.hermes.cascade.CascadePorts
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.OpenRouterPort
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.cascade.registry.SessionProbe
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.openrouter.JevQuestion
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.data.FileStore
import com.folio.hermeslive.graph
import com.folio.hermeslive.hooks.CapturingAudioOut
import com.folio.hermeslive.stt.Align
import com.folio.hermeslive.stt.CorpusAudioSource
import com.folio.hermeslive.stt.ModelSet
import com.folio.hermeslive.stt.SherpaEngines
import com.folio.hermeslive.stt.SherpaSpeechToText
import com.folio.hermeslive.stt.SttEngineConfig
import com.folio.hermeslive.stt.isSpeechEnd
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Staging only: proves R8 + sherpa-onnx's JNI + the cascade core in a minified build, on the
 * emulator. No network, no key, no Hermes.
 *
 * 1. The phrase bank (kotlinx.serialization under R8) and a [CascadeCore] on a [FileStore] take a turn.
 * 2. The speech models: copied in from `<external files>/stt-models/<id>/` (a copy of the debug
 *    app's installed folders, `.ready` marker included) if not installed yet.
 * 3. An STT round-trip: one corpus WAV (`<external files>/stt-corpus/`, extra `wav`) through
 *    Silero, Zipformer and Parakeet via [CorpusAudioSource], faster than real time.
 *
 * Result: logcat `STAGING_SMOKE …` and `<external files>/smoke/result.json` (aggregates only).
 */
class StagingSmokeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { text = "Staging smoke: running…"; textSize = 18f; setPadding(48, 96, 48, 48) }
        setContentView(view)
        val wav = intent.getStringExtra("wav")
        scope.launch {
            val result = runCatching { run(wav) }.getOrElse { e -> JSONObject().put("pass", false).put("error", e.javaClass.simpleName + ": " + e.message?.take(200)) }
            val dir = File(getExternalFilesDir(null), "smoke").apply { mkdirs() }
            File(dir, "result.json").writeText(result.toString(2))
            Log.i(TAG, "STAGING_SMOKE ${if (result.optBoolean("pass")) "PASS" else "FAIL"} $result")
            runOnUiThread { view.text = "Staging smoke: " + (if (result.optBoolean("pass")) "PASS" else "FAIL") + "\n\n" + result.toString(2) }
        }
    }

    private fun run(wav: String?): JSONObject {
        val out = JSONObject()
        // 1. The cascade core under R8.
        val bank = PhraseBank.load()
        out.put("phrase_slots", bank.slots.size)
        val store = File(cacheDir, "smoke-store").apply { deleteRecursively() }
        val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val core = CascadeCore.create(
            scope = coreScope,
            ports = CascadePorts(NoOpenRouter, NoHermes, { _, _ -> SessionProbe.NeverPrompted }, CapturingAudioOut(), FileStore(store)),
            bank = bank,
            settings = { CascadeSettings() },
            telemetry = TelemetrySink.None,
        )
        core.start()
        core.actor.send(Event.SpeechStarted)
        core.actor.send(Event.Partial("check the stock"))
        val partial = runBlocking { withTimeout(5_000) { core.actor.ui.first { it.turn.partial == "check the stock" } } }
        out.put("core_turn", partial.turn.partial.isNotEmpty())
        core.stop()
        coreScope.cancel()

        // 2. Models.
        val models = graph.modelManager
        val copied = copyModels(File(getExternalFilesDir(null), "stt-models"), File(noBackupFilesDir, "stt/models"))
        out.put("models_copied", copied).put("models_ready", models.isReady(ModelSet.STANDARD))
        if (!models.isReady(ModelSet.STANDARD)) return out.put("pass", false).put("error", "models not installed")

        // 3. STT round-trip.
        val corpus = File(getExternalFilesDir(null), "stt-corpus")
        val manifest = File(corpus, "manifest.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
        val item = manifest.firstOrNull { wav == null || it.getString("file") == wav } ?: error("no corpus item")
        val t0 = System.nanoTime()
        val engines = SherpaEngines.load(assets, models, SttEngineConfig())
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val source = CorpusAudioSource(pace = 0.0, idle = CorpusAudioSource.Idle.SILENCE)
        val stt = SherpaSpeechToText(engines, source)
        val events = ArrayList<SttEvent>()
        val collector = scope.launch { stt.events.collect { synchronized(events) { events += it } } }
        source.enqueue(File(corpus, item.getString("file")), silenceAfterMs = 1_500)
        val t1 = System.nanoTime()
        stt.start()
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline && synchronized(events) { events.none { it.isSpeechEnd } }) Thread.sleep(20)
        val transcript = runBlocking { withTimeout(10_000) { stt.accurate("smoke") } }
        val sttMs = (System.nanoTime() - t1) / 1_000_000
        stt.release()
        collector.cancel()
        val fast = synchronized(events) { events.filterIsInstance<SttEvent.Final>().joinToString(" ") { it.text } }
        val reference = item.getString("text")
        val wer = transcript?.let { Align.wer(reference, it.text) }
        out.put("load_ms", loadMs).put("stt_ms", sttMs).put("pass_kind", transcript?.pass?.name)
            .put("fast_wer", Align.wer(reference, fast)).put("final_wer", wer)
            .put("words", transcript?.words?.size).put("uncertain", transcript?.words?.count { it.uncertain })
        return out.put("pass", bank.slots.isNotEmpty() && partial.turn.partial.isNotEmpty() && transcript != null && (wer ?: 1.0) < 0.5)
    }

    /** Copy model folders (with their `.ready` marker) that are not installed yet. */
    private fun copyModels(from: File, to: File): Int {
        if (!from.isDirectory) return 0
        var n = 0
        from.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val target = File(to, dir.name)
            if (File(target, ".ready").isFile) return@forEach
            target.deleteRecursively()
            dir.copyRecursively(target, overwrite = true)
            n++
        }
        return n
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private object NoOpenRouter : OpenRouterPort {
        override suspend fun jev(state: JsonObject, questions: Map<String, JevQuestion>, timeoutMs: Long): OpenRouterResult<Map<String, JevAnswer>> =
            OpenRouterResult.Failed(FailureKind.NoKey)
        override fun sentences(system: String, prompt: String): Flow<String> = emptyFlow()
        override suspend fun write(system: String, prompt: String): OpenRouterResult<String> = OpenRouterResult.Failed(FailureKind.NoKey)
        override suspend fun clip(text: String): OpenRouterResult<AudioClip> = OpenRouterResult.Failed(FailureKind.NoKey)
        override fun isCached(text: String): Boolean = false
    }

    private object NoHermes : HermesPort {
        override fun open(taskId: String, label: String, storedSessionId: String?) = Unit
        override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> = emptyFlow()
        override suspend fun interrupt(taskId: String): Boolean = false
        override suspend fun busyMode(): BusyMode? = null
        override val sessions: Flow<Pair<String, String>> = emptyFlow()
        override val connected: Flow<Boolean> = flowOf(false)
    }

    private companion object {
        const val TAG = "HermesStaging"
    }
}
