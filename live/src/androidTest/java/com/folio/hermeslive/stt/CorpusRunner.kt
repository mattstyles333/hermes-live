package com.folio.hermeslive.stt

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.ports.SttEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** One corpus file through the real pipeline. Numbers and words for scoring; never written out as text. */
class Utt(
    val item: Device.Item,
    val events: List<EventLog.Timed>,
    val fast: List<Word>,
    val transcript: Transcript?,
    val timing: SecondPass.Timing?,
    /** Wall ms from the true end of speech to SpeechEnded (paced runs only). */
    val endLagMs: Double? = null,
    /** Wall ms from speech onset to the first Partial (paced runs only). */
    val firstPartialMs: Double? = null,
    /** At commit (SpeechEnded + pause), how long accurate() still took (paced runs only). */
    val commitWaitMs: Double? = null,
    /** System.nanoTime() of the true end of speech (paced runs only). */
    val clipEndNanos: Long? = null,
) {
    val fastText: String get() = events.mapNotNull { (it.event as? SttEvent.Final)?.text }.joinToString(" ")

    /** The cloud's texts for this clip's segments, in order (cloud mode). */
    val cloudText: String get() = events.mapNotNull { (it.event as? SttEvent.Revised)?.text }.joinToString(" ")
    val orderOk: Boolean get() {
        val names = events.map { it.event }.filterNot { it is SttEvent.Revised } // cloud texts arrive whenever they do
        val lastEnd = names.indexOfLast { it.isSpeechEnd }
        // Nothing textual after the last SpeechEnded, and each Final directly precedes a SpeechEnded.
        if (lastEnd < 0) return names.isEmpty()
        if (names.drop(lastEnd + 1).any { it is SttEvent.Partial || it is SttEvent.Final }) return false
        return names.withIndex().filter { it.value is SttEvent.Final }.all { (i, _) -> names.getOrNull(i + 1)?.isSpeechEnd == true }
    }
}

/**
 * Drives [SherpaSpeechToText] with a [CorpusAudioSource]. [pace] 0 runs as fast as the
 * pipeline goes (WER, RTF); 1.0 is real time (latencies, with a commit at `SpeechEnded + pauseMs`).
 */
class CorpusRunner(
    private val engines: SttEngines,
    private val hotwords: List<String>,
    private val pace: Double,
    private val settings: CascadeSettings = CascadeSettings(),
    /** The cloud pass (used when [settings] has a `cloudSttModel`). */
    cloud: CloudStt? = null,
) {
    val source = CorpusAudioSource(pace, if (pace > 0) CorpusAudioSource.Idle.SILENCE else CorpusAudioSource.Idle.WAIT)
    val stt = SherpaSpeechToText(engines, source, settings = { settings.copy(vocabulary = hotwords) }, cloud = cloud)
    private val log = EventLog(stt)

    init {
        stt.start()
    }

    fun run(item: Device.Item, silenceAfterMs: Long = if (pace > 0) 2_000L else 1_200L): Utt =
        runPcm(CorpusAudioSource.read(item.file), item, silenceAfterMs)

    /** [pcm] (16 kHz) scored against [item]'s text; e.g. several clips joined into one dictation. */
    fun runPcm(pcm: ShortArray, item: Device.Item, silenceAfterMs: Long = if (pace > 0) 2_000L else 1_200L): Utt {
        val span = speechSpan(pcm)
        val id = item.file.name + "#" + System.nanoTime()
        source.enqueue(pcm, id, silenceAfterMs)
        return if (pace > 0) paced(item, id, span) else fast(item)
    }

    private fun fast(item: Device.Item): Utt {
        check(source.awaitDrained(120_000)) { "corpus source did not drain" }
        log.awaitSettled()
        val events = log.take()
        val t = runBlocking { stt.accurate("bench") }
        return Utt(item, events, finals(events), t, stt.secondPass.lastTiming)
    }

    private fun paced(item: Device.Item, id: String, span: Pair<Int, Int>?): Utt = runBlocking {
        // Commit like the actor: at SpeechEnded (the last one in this clip) + pauseMs.
        var endedAt = 0L
        val deadline = System.nanoTime() + 60_000_000_000L
        var start: Long? = null
        while (System.nanoTime() < deadline) {
            start = start ?: source.clipStarts[id]
            val s = log.snapshot()
            val ended = s.lastOrNull { it.event.isSpeechEnd }
            val started = s.count { it.event is SttEvent.SpeechStarted }
            val endedCount = s.count { it.event.isSpeechEnd }
            // Never before the clip's last speech sample has played (joined clips have gaps).
            val spoken = start != null && source.samplesOut >= start + (span?.second ?: 0)
            if (ended != null && started == endedCount && spoken && ended.nanos >= source.nanosAt(start!! + (span?.second ?: 0))) {
                // Wait out the pause: speech may resume (a second segment) before the commit.
                val commitAt = ended.nanos + settings.pause * 1_000_000
                if (System.nanoTime() >= commitAt) {
                    endedAt = ended.nanos
                    break
                }
            }
            delay(5)
        }
        val acc = async(Dispatchers.Default) {
            val t0 = System.nanoTime()
            stt.accurate("bench") to (System.nanoTime() - t0) / 1e6
        }
        val (t, wait) = acc.await()
        check(source.awaitDrained(30_000))
        log.awaitSettled()
        val events = log.take()
        val clip = start ?: 0L
        val endLag = span?.let { (endedAt - source.nanosAt(clip + it.second)) / 1e6 }
        val firstPartial = events.firstOrNull { it.event is SttEvent.Partial }?.let { p -> span?.let { (p.nanos - source.nanosAt(clip + it.first)) / 1e6 } }
        Utt(item, events, finals(events), t, stt.secondPass.lastTiming, endLag, firstPartial, wait, clipEndNanos = span?.let { source.nanosAt(clip + it.second) })
    }

    private fun finals(events: List<EventLog.Timed>) = events.flatMap { (it.event as? SttEvent.Final)?.words.orEmpty() }

    fun close() {
        stt.stop()
        log.close()
        stt.secondPass.shutdown()
    }
}

/** Corpus-level scores of a run. Aggregates only. */
object Scores {
    data class Wer(val errors: Int, val refWords: Int, val sub: Int, val ins: Int, val del: Int) {
        val wer: Double get() = if (refWords == 0) 0.0 else errors.toDouble() / refWords
        operator fun plus(o: Wer) = Wer(errors + o.errors, refWords + o.refWords, sub + o.sub, ins + o.ins, del + o.del)
        fun json() = JSONObject().put("wer", wer).put("errors", errors).put("ref_words", refWords).put("sub", sub).put("ins", ins).put("del", del)

        companion object {
            val ZERO = Wer(0, 0, 0, 0, 0)
            fun of(ref: String, hyp: String) = Align.errors(ref, hyp).let { Wer(it.total, it.refWords, it.sub, it.ins, it.del) }
        }
    }

    fun wer(utts: List<Utt>, text: (Utt) -> String): Wer = utts.fold(Wer.ZERO) { acc, u -> acc + Wer.of(u.item.text, text(u)) }

    /** Hotword occurrences in the references that the hypothesis also has (by normalised token). */
    fun names(utts: List<Utt>, names: List<String>, text: (Utt) -> String): Pair<Int, Int> {
        val keys = names.map { it.lowercase() }.toSet()
        var total = 0
        var hit = 0
        for (u in utts) {
            val ref = TextNorm.normalize(u.item.text).filter { it in keys }
            if (ref.isEmpty()) continue
            val hyp = TextNorm.normalize(text(u)).groupingBy { it }.eachCount().toMutableMap()
            for (w in ref) {
                total++
                val n = hyp[w] ?: 0
                if (n > 0) {
                    hit++
                    hyp[w] = n - 1
                }
            }
        }
        return hit to total
    }

    /** One word for the R-STT-4 calibration: its streaming confidence, whether the passes disagreed, whether it is wrong. */
    data class Flag(val confidence: Double?, val disagree: Boolean, val wrong: Boolean)

    fun mergedFlags(utts: List<Utt>): List<Flag> = utts.flatMap { u ->
        val t = u.transcript ?: return@flatMap emptyList()
        if (t.pass != TranscriptPass.Accurate) return@flatMap emptyList()
        val wrong = Align.wrongWords(u.item.text, t.words.map { it.text })
        t.words.mapIndexed { i, w -> Flag(w.confidence, w.confidence == null && TextNorm.key(w.text).isNotEmpty(), wrong[i]) }
    }

    fun fastFlags(utts: List<Utt>): List<Flag> = utts.flatMap { u ->
        val wrong = Align.wrongWords(u.item.text, u.fast.map { it.text })
        u.fast.mapIndexed { i, w -> Flag(w.confidence, false, wrong[i]) }
    }

    /**
     * Each accurate word with what the streaming pass said about it: the agreed word's
     * confidence, or (passes disagree) the least confidence of the fast words in that gap
     * (null when the fast pass had nothing there).
     */
    data class Gap(val agreedConfidence: Double?, val disagree: Boolean, val gapConfidence: Double?, val wrong: Boolean)

    fun gaps(utts: List<Utt>): List<Gap> = utts.flatMap { u ->
        val t = u.transcript?.takeIf { it.pass == TranscriptPass.Accurate } ?: return@flatMap emptyList()
        val acc = t.words.map { it.text }
        val wrong = Align.wrongWords(u.item.text, acc)
        val pairs = Align.lcsPairs(acc.map(TextNorm::key), u.fast.map { TextNorm.key(it.text) })
        val out = ArrayList<Gap>()
        var pa = -1
        var pf = -1
        for ((ai, fi) in pairs + (acc.size to u.fast.size)) {
            val gapConf = u.fast.subList(pf + 1, fi).mapNotNull { it.confidence }.minOrNull()
            for (k in pa + 1 until ai) if (TextNorm.key(acc[k]).isNotEmpty()) out += Gap(null, true, gapConf, wrong[k])
            if (ai < acc.size) out += Gap(u.fast[fi].confidence, false, null, wrong[ai])
            pa = ai
            pf = fi
        }
        out
    }

    /**
     * Best F1 per disagreement rule, sweeping the agreed-word threshold t: disagreements flagged
     * always (spec R-STT-4), never, or only when the fast pass was itself at least d confident
     * in its reading (or had none).
     */
    fun rules(gaps: List<Gap>): JSONArray {
        val out = JSONArray()
        val rules = listOf<Pair<String, (Gap) -> Boolean>>(
            "always" to { _ -> true },
            "never" to { _ -> false },
        ) + listOf(0.3, 0.5, 0.7, 0.8, 0.9).map { d -> "fast_conf>=$d" to { g: Gap -> g.gapConfidence == null || g.gapConfidence >= d } }
        for ((name, flagDisagree) in rules) {
            var best: JSONObject? = null
            var t = 0.0
            while (t <= 0.951) {
                val flagged = gaps.map { if (it.disagree) flagDisagree(it) else (it.agreedConfidence != null && it.agreedConfidence < t) }
                val tp = gaps.indices.count { flagged[it] && gaps[it].wrong }
                val fp = gaps.indices.count { flagged[it] && !gaps[it].wrong }
                val fn = gaps.indices.count { !flagged[it] && gaps[it].wrong }
                val p = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
                val r = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
                val f1 = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
                if (best == null || f1 > best.getDouble("f1")) {
                    best = JSONObject().put("rule", name).put("t", Math.round(t * 100) / 100.0).put("tp", tp).put("fp", fp).put("fn", fn).put("precision", p).put("recall", r).put("f1", f1)
                }
                t += 0.05
            }
            out.put(best)
        }
        return out
    }

    /** Precision, recall and F1 of "flagged uncertain" for wrong words, per threshold. */
    fun sweep(flags: List<Flag>): JSONArray {
        val out = JSONArray()
        var t = 0.0
        while (t <= 0.951) {
            val flagged = flags.map { it.disagree || (it.confidence != null && it.confidence < t) }
            val tp = flags.indices.count { flagged[it] && flags[it].wrong }
            val fp = flags.indices.count { flagged[it] && !flags[it].wrong }
            val fn = flags.indices.count { !flagged[it] && flags[it].wrong }
            val p = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val r = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
            val f1 = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
            out.put(JSONObject().put("t", Math.round(t * 100) / 100.0).put("tp", tp).put("fp", fp).put("fn", fn).put("precision", p).put("recall", r).put("f1", f1).put("flagged", tp + fp))
            t += 0.05
        }
        return out
    }

    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    fun dist(values: List<Double>): JSONObject = JSONObject()
        .put("n", values.size)
        .put("p50", percentile(values, 0.5))
        .put("p90", percentile(values, 0.9))
        .put("max", values.maxOrNull())
        .put("mean", if (values.isEmpty()) null else values.average())
}
