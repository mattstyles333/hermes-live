package com.folio.hermeslive.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermes.openrouter.CloudSttModels
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.telemetry.TelemetrySink
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cloud speech-to-text on the real pipeline (cloud STT slice). Opt-in, one at a time:
 *
 * - `-e transcripts true [-e corpus <dir>]`: the on-device pipeline (Zipformer + Parakeet-110M,
 *   hotwords from the corpus's `hotwords.txt`, default settings) over the corpus as fast as it
 *   goes; one JSON line per clip to `stt-bench/transcripts-<dir name>.jsonl` (the eval corpus is
 *   synthetic phrases, so its text may be written there; never in the repo).
 * - `-e cloudbench true -e openrouterKey … [-e model <id>] [-e mode cloud|device]`: real time
 *   (pace 1.0), commit at speech end + pause like the actor; final WER, the cloud text's own WER,
 *   how often the cloud text was the accurate pass, how often it differed materially, and the
 *   true end of speech → accurate text latencies. Aggregates only, to `stt-bench/cloud-<mode>.json`.
 *   The key is an argument at run time, never written.
 */
@RunWith(AndroidJUnit4::class)
class CloudCorpusDeviceTest {
    private fun conditions(): Map<String, String> {
        val manifest = File(Device.corpusDir(), "manifest.jsonl")
        return manifest.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.associate { it.getString("file") to it.optString("condition", "clean") }
    }

    @Test fun transcripts() {
        assumeTrue("transcripts=true", Device.args.getString("transcripts") == "true")
        val corpus = Device.requireCorpus()
        val names = Device.hotwords()
        val cond = conditions()
        val config = SttEngineConfig()
        Device.requireModels(config.streaming, config.accurate!!)
        val engines = Device.engines(config)
        val runner = CorpusRunner(engines, names, pace = 0.0)
        val name = Device.corpusDir().name
        val out = Device.results("transcripts-$name.jsonl")
        out.writeText("")
        for (item in corpus) {
            val t0 = System.nanoTime()
            val u = runner.run(item)
            val lastFinal = u.events.lastOrNull { it.event is SttEvent.Final }?.nanos
            val t = u.transcript
            val words = JSONArray()
            t?.words?.forEach { w ->
                words.put(JSONObject().put("w", w.text).put("conf", w.confidence ?: JSONObject.NULL).put("uncertain", w.uncertain).put("alt", JSONArray(w.alternatives)))
            }
            val row = JSONObject()
                .put("file", item.file.name).put("corpus", name).put("condition", cond[item.file.name] ?: "clean")
                .put("ref", item.text).put("fast", u.fastText)
                .put("accurate", if (t?.pass == TranscriptPass.Accurate) t.text else JSONObject.NULL)
                .put("words", words)
                // Run as fast as the pipeline goes: the streaming pass's wall time for the clip, and the second pass's decode time.
                .put("fast_ms", lastFinal?.let { (it - t0) / 1e6 } ?: JSONObject.NULL)
                .put("accurate_ms", u.timing?.decodeMs ?: JSONObject.NULL)
            out.appendText(row.toString() + "\n")
        }
        runner.close()
        engines.release()
    }

    @Test fun cloudBench() {
        assumeTrue("cloudbench=true", Device.args.getString("cloudbench") == "true")
        val mode = Device.args.getString("mode") ?: "cloud"
        val key = Device.args.getString("openrouterKey")
        assumeTrue("an OpenRouter key for cloud mode", mode != "cloud" || !key.isNullOrBlank())
        val model = Device.args.getString("model") ?: CloudSttModels.DEFAULT.id
        val corpus = Device.requireCorpus()
        val names = Device.hotwords()
        val config = SttEngineConfig()
        Device.requireModels(config.streaming, config.accurate!!)
        val engines = Device.engines(config)
        val settings = CascadeSettings(accuratePass = true, cloudSttModel = if (mode == "cloud") model else null)
        val router = OpenRouter(OpenRouterConfig(), key = { key }, telemetry = TelemetrySink.None)
        val cloud = OpenRouterCloudStt(router.transcription, online = { true })
        val runner = CorpusRunner(engines, names, pace = 1.0, settings = settings, cloud = cloud.takeIf { mode == "cloud" })
        val utts = corpus.map { runner.run(it) }
        runner.close()
        engines.release()

        val revisedMs = ArrayList<Double>()
        val deviceMs = ArrayList<Double>()
        var material = 0
        var revisedClips = 0
        for (u in utts) {
            val end = u.clipEndNanos ?: continue
            val revs = u.events.filter { it.event is SttEvent.Revised }
            if (revs.isNotEmpty()) {
                revisedClips++
                revisedMs += (revs.last().nanos - end) / 1e6
                if (revs.any { (it.event as SttEvent.Revised).material }) material++
            }
            // The device text is ready about when the VAD ended the segment plus its decode.
            val ended = u.events.lastOrNull { it.event.isSpeechEnd }?.nanos
            val decode = u.timing?.decodeMs
            if (ended != null && decode != null) deviceMs += (ended - end) / 1e6 + decode
        }
        val timings = utts.mapNotNull { it.timing }
        val result = JSONObject()
            .put("mode", mode).put("model", if (mode == "cloud") model else null)
            .put("files", utts.size).put("hotword_count", names.size)
            .put("final_wer", Scores.wer(utts) { it.transcript?.text ?: it.fastText }.json())
            .put("fast_wer", Scores.wer(utts) { it.fastText }.json())
            .put("accurate_share", utts.count { it.transcript?.pass == TranscriptPass.Accurate }.toDouble() / utts.size)
            .put("names_final", Scores.names(utts, names) { it.transcript?.text ?: it.fastText }.let { "${it.first}/${it.second}" })
            .put("end_to_speech_ended_ms", Scores.dist(utts.mapNotNull { it.endLagMs }))
            .put("commit_wait_ms", Scores.dist(utts.mapNotNull { it.commitWaitMs }))
            .put("end_to_device_text_ms", Scores.dist(deviceMs))
        if (mode == "cloud") {
            val withCloud = utts.filter { it.cloudText.isNotBlank() }
            result
                .put("cloud_text_wer", Scores.wer(withCloud) { it.cloudText }.json())
                .put("cloud_clips", withCloud.size)
                .put("cloud_segments_sent", timings.sumOf { it.cloudSent })
                .put("cloud_segments_used", timings.sumOf { it.cloudUsed })
                .put("material_clips", material).put("revised_clips", revisedClips)
                .put("end_to_cloud_text_ms", Scores.dist(revisedMs))
                .put("cloud_request_ms", Scores.dist(timings.filter { it.cloudSent > 0 }.map { it.cloudMs.toDouble() }))
                .put("names_cloud", Scores.names(withCloud, names) { it.cloudText }.let { "${it.first}/${it.second}" })
        }
        Device.results("cloud-$mode.json").writeText(result.toString(2))
    }
}
