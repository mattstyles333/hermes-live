package com.folio.hermeslive.stt

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.hermes.cascade.ports.SttEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Shared helpers for the emulator tests. The corpus and the model archives are pushed by hand
 * (see src/androidTest/README.md) into the app's external files dir; they are never committed.
 * Instrumentation arguments: `bench=true` runs the corpus bench, `limit=N` caps the files,
 * `corpus=/path` overrides the corpus folder.
 */
object Device {
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val args: Bundle get() = InstrumentationRegistry.getArguments()

    fun external(): File = context.getExternalFilesDir(null)!!

    fun corpusDir(): File = File(args.getString("corpus") ?: File(external(), "stt-corpus").path)

    val models: ModelManager by lazy { ModelManager(File(context.noBackupFilesDir, "stt")) }

    data class Item(val file: File, val text: String, val voice: String, val source: String)

    fun corpus(): List<Item> {
        val dir = corpusDir()
        val manifest = File(dir, "manifest.jsonl")
        if (!manifest.isFile) return emptyList()
        val items = manifest.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.map {
            Item(File(dir, it.getString("file")), it.getString("text"), it.getString("voice"), it.getString("source"))
        }.filter { it.file.isFile }
        val limit = args.getString("limit")?.toIntOrNull()
        return if (limit != null) items.take(limit) else items
    }

    /** Supplier-like names for the hotword runs: a sidecar pushed with the corpus (not in the repo). */
    fun hotwords(): List<String> =
        File(corpusDir(), "hotwords.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun requireCorpus(): List<Item> = corpus().also { assumeTrue("no corpus pushed (see README)", it.isNotEmpty()) }

    fun requireModels(vararg models: SttModel) =
        assumeTrue("models not installed: ${models.filterNot(Device.models::isInstalled).map { it.id }}", models.all(Device.models::isInstalled))

    fun engines(config: SttEngineConfig): SttEngines = SherpaEngines.load(context.assets, models, config)

    fun results(name: String): File = File(external(), "stt-bench").apply { mkdirs() }.let { File(it, name) }
}

/** Collects a [SherpaSpeechToText]'s events on a background thread. */
class EventLog(stt: SherpaSpeechToText, scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    data class Timed(val event: SttEvent, val nanos: Long)

    private val list = ArrayList<Timed>()
    val job: Job = scope.launch { stt.events.collect { e -> synchronized(list) { list += Timed(e, System.nanoTime()) } } }

    fun snapshot(): List<Timed> = synchronized(list) { list.toList() }

    fun take(): List<Timed> = synchronized(list) { list.toList().also { list.clear() } }

    /** Waits until every SpeechStarted has its SpeechEnded (or [timeoutMs]). */
    fun awaitSettled(timeoutMs: Long = 3_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val s = snapshot()
            val started = s.count { it.event is SttEvent.SpeechStarted }
            val ended = s.count { it.event.isSpeechEnd }
            if (started == ended) return true
            Thread.sleep(5)
        }
        return false
    }

    fun close() = job.cancel()
}

/** Samples of [pcm] above -40 dBFS in 10 ms frames: the first and last such sample (speech span). */
fun speechSpan(pcm: ShortArray): Pair<Int, Int>? {
    val frame = 160
    var first = -1
    var last = -1
    for (i in 0 until pcm.size / frame) {
        var sum = 0.0
        for (k in i * frame until (i + 1) * frame) sum += (pcm[k] / 32768.0) * (pcm[k] / 32768.0)
        val db = 10 * kotlin.math.log10(sum / frame + 1e-12)
        if (db > -40) {
            if (first < 0) first = i * frame
            last = (i + 1) * frame
        }
    }
    return if (first < 0) null else first to last
}
