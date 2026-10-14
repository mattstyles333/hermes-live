package com.folio.hermeslive.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.TranscriptPass
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The S-C corpus bench (spec v2 §11b): WER per model combination with and without hotwords,
 * real-time factors, second-pass latency, and the `ysProbs` calibration for `uncertainBelow`.
 * Runs only with `bench=true` (about 20 minutes on the x86_64 emulator). Results:
 * `<external files>/stt-bench/bench.json` (aggregates only, no transcripts).
 */
@RunWith(AndroidJUnit4::class)
class CorpusBenchTest {
    private data class Combo(val id: String, val config: SttEngineConfig, val hotwords: Boolean)

    private val combos = listOf(
        Combo("zipformer+p110m", SttEngineConfig(), false),
        Combo("zipformer+p110m+hotwords", SttEngineConfig(), true),
        // The tail padding before 2026-10-14 (0.3 s; now 0.66 s), for comparison.
        Combo("zipformer+p110m+pad0.3", SttEngineConfig(tailPadS = 0.3), false),
        Combo("zipformer+p06b", SttEngineConfig(accurate = SttCatalog.parakeet06b), false),
        Combo("zipformer+p06b+hotwords", SttEngineConfig(accurate = SttCatalog.parakeet06b), true),
        Combo("zipformer-greedy", SttEngineConfig(decoding = Hotwords.GREEDY, accurate = null), false),
        Combo("nemotron+p110m", SttEngineConfig(streaming = SttCatalog.nemotron), false),
    )

    @Test fun bench() {
        assumeTrue("bench=true runs the corpus bench", Device.args.getString("bench") == "true")
        val corpus = Device.requireCorpus()
        val names = Device.hotwords()
        val only = Device.args.getString("combos")?.split(',')?.toSet()
        val out = JSONObject()
            .put("files", corpus.size)
            .put("audio_s", corpus.sumOf { CorpusAudioSource.read(it.file).size } / 16_000.0)
            .put("hotword_count", names.size)
            .put("device", android.os.Build.MODEL + " / " + android.os.Build.SUPPORTED_ABIS.first())
            .put("cores", Runtime.getRuntime().availableProcessors())
        val runs = JSONArray()
        var calibration: JSONObject? = null
        for (combo in combos) {
            if (only != null && combo.id !in only) continue
            val models = listOfNotNull(combo.config.streaming, combo.config.accurate)
            if (!models.all(Device.models::isInstalled)) {
                runs.put(JSONObject().put("combo", combo.id).put("skipped", "models not installed"))
                continue
            }
            val t0 = System.nanoTime()
            val engines = Device.engines(combo.config)
            val loadMs = (System.nanoTime() - t0) / 1e6
            val runner = CorpusRunner(engines, if (combo.hotwords) names else emptyList(), pace = 0.0)
            val started = System.nanoTime()
            val utts = corpus.map { runner.run(it) }
            val wallS = (System.nanoTime() - started) / 1e9
            val captureRtf = runner.stt.captureRtf
            runner.close()
            engines.release()
            val audioS = utts.sumOf { u -> u.timing?.audioMs ?: 0L } / 1000.0
            val decode = utts.mapNotNull { it.timing?.decodeMs?.toDouble() }
            val r = JSONObject()
                .put("combo", combo.id)
                .put("streaming", combo.config.streaming.id)
                .put("accurate", combo.config.accurate?.id)
                .put("decoding", Hotwords.decodingFor(combo.config.streaming.kind, combo.config.decoding))
                .put("hotwords", combo.hotwords)
                .put("threads", "${combo.config.streamingThreads}/${combo.config.accurateThreads}")
                .put("load_ms", loadMs)
                .put("wall_s", wallS)
                .put("streaming_rtf", captureRtf)
                .put("fast_wer", Scores.wer(utts) { it.fastText }.json())
                .put("final_wer", Scores.wer(utts) { it.transcript?.text ?: it.fastText }.json())
                .put("accurate_share", utts.count { it.transcript?.pass == TranscriptPass.Accurate }.toDouble() / utts.size)
                .put("event_order_ok", utts.count { it.orderOk })
                .put("no_speech_found", utts.count { u -> u.events.none { it.event is com.folio.hermes.cascade.ports.SttEvent.SpeechStarted } })
                .put("segments_per_file", utts.sumOf { u -> u.events.count { it.event.isSpeechEnd } }.toDouble() / utts.size)
                .put("second_pass_decode_ms", Scores.dist(decode))
                .put("second_pass_rtf", if (audioS > 0) decode.sum() / 1000.0 / audioS else null)
            val (fastHit, fastTotal) = Scores.names(utts, names) { it.fastText }
            val (finalHit, finalTotal) = Scores.names(utts, names) { it.transcript?.text ?: it.fastText }
            r.put("names_fast", "$fastHit/$fastTotal").put("names_final", "$finalHit/$finalTotal")
            val byVoice = JSONObject()
            utts.groupBy { it.item.voice }.forEach { (voice, us) ->
                byVoice.put(voice, JSONObject().put("fast", Scores.wer(us) { it.fastText }.wer).put("final", Scores.wer(us) { it.transcript?.text ?: it.fastText }.wer))
            }
            r.put("by_voice", byVoice)
            if (combo.config.accurate != null) {
                r.put("calibration_merged", Scores.sweep(Scores.mergedFlags(utts)))
                r.put("calibration_fast_only", Scores.sweep(Scores.fastFlags(utts)))
                r.put("calibration_rules", Scores.rules(Scores.gaps(utts)))
                val flags = Scores.mergedFlags(utts)
                r.put("merged_words", flags.size).put("merged_wrong", flags.count { it.wrong }).put("merged_disagree", flags.count { it.disagree })
                if (combo.id == "zipformer+p110m") calibration = r
            }
            runs.put(r)
            Device.results("bench.json").writeText(out.put("runs", runs).toString(2))
        }
        out.put("runs", runs)
        assertTrue(calibration != null || only != null)
        // Real-time turns: commit at SpeechEnded + pause, as the actor does.
        if (only == null || "latency" in only) out.put("latency", latency(corpus, SttEngineConfig()))
        if (only == null || "latency06b" in only) out.put("latency_06b", latency(corpus, SttEngineConfig(accurate = SttCatalog.parakeet06b)))
        if (only == null || "dictation" in only) {
            out.put("dictation", dictation(corpus, SttEngineConfig()))
            out.put("dictation_06b", dictation(corpus, SttEngineConfig(accurate = SttCatalog.parakeet06b)))
        }
        Device.results("bench.json").writeText(out.toString(2))
    }

    /**
     * Long dictation: [clips] corpus clips joined with 400 ms gaps (shorter than the pause, so one
     * turn of many VAD segments), committed once at the end. Shows whether the per-segment second
     * pass keeps up.
     */
    private fun dictation(corpus: List<Device.Item>, config: SttEngineConfig, turns: Int = 3, clips: Int = 10): JSONObject {
        if (!listOfNotNull(config.streaming, config.accurate).all(Device.models::isInstalled)) return JSONObject().put("skipped", true)
        val engines = Device.engines(config)
        val runner = CorpusRunner(engines, emptyList(), pace = 1.0)
        val gap = ShortArray(16_000 * 400 / 1000)
        val utts = (0 until turns).map { t ->
            val picked = corpus.filterIndexed { i, _ -> i % turns == t }.take(clips)
            val pcm = picked.fold(ShortArray(0)) { acc, item -> acc + CorpusAudioSource.read(item.file) + gap }
            val item = Device.Item(picked.first().file, picked.joinToString(" ") { it.text }, "mixed", "dictation")
            runner.runPcm(pcm, item)
        }
        runner.close()
        engines.release()
        return JSONObject()
            .put("accurate", config.accurate?.id)
            .put("turns", utts.size)
            .put("audio_s_per_turn", Scores.dist(utts.mapNotNull { u -> u.timing?.audioMs?.div(1000.0) }))
            .put("segments_per_turn", Scores.dist(utts.mapNotNull { it.timing?.segments?.toDouble() }))
            .put("decode_ms_per_turn", Scores.dist(utts.mapNotNull { it.timing?.decodeMs?.toDouble() }))
            .put("accurate_wait_at_commit_ms", Scores.dist(utts.mapNotNull { it.commitWaitMs }))
            .put("event_order_ok", utts.count { it.orderOk })
            .put("accurate_pass", utts.count { it.transcript?.pass == TranscriptPass.Accurate })
            .put("final_wer", Scores.wer(utts) { it.transcript?.text ?: it.fastText }.json())
    }

    private fun latency(corpus: List<Device.Item>, config: SttEngineConfig): JSONObject {
        if (!listOfNotNull(config.streaming, config.accurate).all(Device.models::isInstalled)) return JSONObject().put("skipped", true)
        val engines = Device.engines(config)
        val runner = CorpusRunner(engines, emptyList(), pace = 1.0)
        val pick = corpus.filterIndexed { i, _ -> i % 6 == 0 }.take(Device.args.getString("latencyFiles")?.toIntOrNull() ?: 21)
        val utts = pick.map { runner.run(it) }
        runner.close()
        engines.release()
        val wait = utts.mapNotNull { it.commitWaitMs }
        return JSONObject()
            .put("accurate", config.accurate?.id)
            .put("files", utts.size)
            .put("pace", 1.0)
            .put("pause_ms", 1000)
            .put("speech_end_to_speech_ended_ms", Scores.dist(utts.mapNotNull { it.endLagMs }))
            .put("onset_to_first_partial_ms", Scores.dist(utts.mapNotNull { it.firstPartialMs }))
            .put("accurate_wait_at_commit_ms", Scores.dist(wait))
            .put("accurate_within_1500ms", wait.count { it <= 1500 })
            .put("decode_ms", Scores.dist(utts.mapNotNull { it.timing?.decodeMs?.toDouble() }))
            .put("event_order_ok", utts.count { it.orderOk })
            .put("final_wer", Scores.wer(utts) { it.transcript?.text ?: it.fastText }.json())
    }
}
