package com.folio.hermeslive.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.ports.SttEvent
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device model bench (speech-model picker slice). Opt-in: `-e modelbench true
 * -e combos <live>+<accurate>,… [-e corpus <dir>] [-e threads 4] [-e dictation true] [-e export true]`.
 *
 * Per combo, the real pipeline as fast as it goes (pace 0) with the corpus's hotwords: fast and
 * final WER, names right in each pass, the live stage's RTF (capture thread, VAD included) and
 * the accurate stage's decode time, model sizes on disk, and whether each model takes hotwords.
 * With `dictation=true`, also 3 joined 10-clip dictations through the live stage (an offline model
 * run as streaming costs more as a segment grows). With `export=true`, one JSON line per clip to
 * `stt-bench/transcripts-<corpus>-<combo>.jsonl` (synthetic eval phrases; never in the repo).
 * Results go to `stt-bench/models-<corpus>-t<threads>.json` after each combo (a native exit
 * keeps the earlier ones).
 */
@RunWith(AndroidJUnit4::class)
class ModelBenchDeviceTest {
    @Test fun bench() {
        assumeTrue("modelbench=true", Device.args.getString("modelbench") == "true")
        val corpus = Device.requireCorpus()
        val names = Device.hotwords()
        val threads = Device.args.getString("threads")?.toIntOrNull() ?: 4
        val combos = Device.args.getString("combos")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val corpusName = Device.corpusDir().name
        val conditions = File(Device.corpusDir(), "manifest.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
            .associate { it.getString("file") to it.optString("condition", "clean") }
        val resultFile = Device.results("models-$corpusName-t$threads.json")
        val out = if (resultFile.isFile) JSONObject(resultFile.readText()) else JSONObject()
        out.put("files", corpus.size).put("hotword_count", names.size).put("threads", threads)
            .put("device", android.os.Build.MODEL + " / " + android.os.Build.SUPPORTED_ABIS.first())
        val runs = out.optJSONObject("runs") ?: JSONObject()
        for (combo in combos) {
            val (liveId, accId) = combo.split('+').let { it[0] to it.getOrNull(1) }
            val live = SttCatalog.byId(liveId) ?: error("unknown $liveId")
            val acc = accId?.let { SttCatalog.byId(it) ?: error("unknown $it") }
            val needed = listOfNotNull(live, acc)
            if (!needed.all(Device.models::isInstalled)) {
                runs.put(combo, JSONObject().put("skipped", "not installed: ${needed.filterNot(Device.models::isInstalled).map { it.id }}"))
                continue
            }
            val config = SttEngineConfig(streaming = live, accurate = acc, streamingThreads = threads, accurateThreads = threads)
            val t0 = System.nanoTime()
            val engines = Device.engines(config)
            val loadMs = (System.nanoTime() - t0) / 1e6
            val runner = CorpusRunner(engines, names, pace = 0.0)
            val export = if (Device.args.getString("export") == "true") Device.results("transcripts-$corpusName-$combo.jsonl").apply { writeText("") } else null
            val started = System.nanoTime()
            val utts = corpus.map { item ->
                val c0 = System.nanoTime()
                runner.run(item).also { u -> export?.appendText(row(u, corpusName, conditions, combo, c0).toString() + "\n") }
            }
            val wallS = (System.nanoTime() - started) / 1e9
            val captureRtf = runner.stt.captureRtf
            runner.close()
            val audioS = utts.sumOf { u -> u.timing?.audioMs ?: 0L } / 1000.0
            val decode = utts.mapNotNull { it.timing?.decodeMs?.toDouble() }
            val r = JSONObject()
                .put("live", live.id).put("accurate", acc?.id).put("files", utts.size)
                .put("live_hotwords", live.hotwords && Device.models.hasVocab(live))
                .put("accurate_hotwords", acc?.let { it.hotwords && Device.models.hasVocab(it) } ?: false)
                .put("live_mb_on_disk", Device.models.bytesOnDisk(live) / 1e6)
                .put("accurate_mb_on_disk", acc?.let { Device.models.bytesOnDisk(it) / 1e6 })
                .put("load_ms", loadMs).put("wall_s", wallS)
                .put("live_rtf", captureRtf)
                .put("fast_wer", Scores.wer(utts) { it.fastText }.json())
                .put("final_wer", Scores.wer(utts) { it.transcript?.text ?: it.fastText }.json())
                .put("accurate_share", utts.count { it.transcript?.pass == TranscriptPass.Accurate }.toDouble() / utts.size)
                .put("accurate_decode_ms", Scores.dist(decode))
                .put("accurate_rtf", if (audioS > 0) decode.sum() / 1000.0 / audioS else null)
                .put("names_fast", Scores.names(utts, names) { it.fastText }.let { "${it.first}/${it.second}" })
                .put("names_final", Scores.names(utts, names) { it.transcript?.text ?: it.fastText }.let { "${it.first}/${it.second}" })
                .put("no_speech_found", utts.count { u -> u.events.none { it.event is SttEvent.SpeechStarted } })
            val byCondition = JSONObject()
            utts.groupBy { conditions[it.item.file.name] ?: "clean" }.forEach { (c, us) ->
                byCondition.put(c, JSONObject().put("fast", Scores.wer(us) { it.fastText }.wer).put("final", Scores.wer(us) { it.transcript?.text ?: it.fastText }.wer))
            }
            r.put("by_condition", byCondition)
            if (Device.args.getString("dictation") == "true") r.put("dictation", dictation(corpus, engines))
            engines.release()
            runs.put(combo, r)
            resultFile.writeText(out.put("runs", runs).toString(2))
        }
    }

    private fun row(u: Utt, corpus: String, conditions: Map<String, String>, combo: String, startedNanos: Long): JSONObject {
        val t = u.transcript
        val words = JSONArray()
        t?.words?.forEach { w ->
            words.put(JSONObject().put("w", w.text).put("conf", w.confidence ?: JSONObject.NULL).put("uncertain", w.uncertain).put("alt", JSONArray(w.alternatives)))
        }
        val lastFinal = u.events.lastOrNull { it.event is SttEvent.Final }?.nanos
        return JSONObject()
            .put("file", u.item.file.name).put("corpus", corpus).put("condition", conditions[u.item.file.name] ?: "clean")
            .put("model", combo).put("ref", u.item.text).put("fast", u.fastText)
            .put("accurate", if (t?.pass == TranscriptPass.Accurate) t.text else JSONObject.NULL)
            .put("words", words)
            .put("fast_ms", lastFinal?.let { (it - startedNanos) / 1e6 } ?: JSONObject.NULL)
            .put("accurate_ms", u.timing?.decodeMs ?: JSONObject.NULL)
    }

    /** 3 dictations of 10 joined clips (400 ms gaps), as fast as the pipeline goes: the live stage's RTF on long segments. */
    private fun dictation(corpus: List<Device.Item>, engines: SttEngines): JSONObject {
        val runner = CorpusRunner(engines, emptyList(), pace = 0.0)
        val gap = ShortArray(16_000 * 400 / 1000)
        val utts = (0 until 3).map { t ->
            val picked = corpus.filterIndexed { i, _ -> i % 3 == t }.take(10)
            val pcm = picked.fold(ShortArray(0)) { acc, item -> acc + CorpusAudioSource.read(item.file) + gap }
            runner.runPcm(pcm, Device.Item(picked.first().file, picked.joinToString(" ") { it.text }, "mixed", "dictation"))
        }
        val rtf = runner.stt.captureRtf
        runner.close()
        return JSONObject()
            .put("live_rtf", rtf)
            .put("audio_s", utts.sumOf { u -> u.timing?.audioMs ?: 0L } / 1000.0)
            .put("fast_wer", Scores.wer(utts) { it.fastText }.json())
            .put("final_wer", Scores.wer(utts) { it.transcript?.text ?: it.fastText }.json())
    }
}
