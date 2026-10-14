package com.folio.hermeslive.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.PlaybackMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The real sherpa pipeline on the emulator, fed corpus WAVs in real time: turn events in the
 * order the actor needs, the accurate pass inside `accuratePassWaitMs`, and the echo gate
 * (R-SPK-6) with Bill's voice mixed back into the "mic". Skipped when the models or the corpus
 * are not on the device; `bench=true` widens the echo sweep.
 */
@RunWith(AndroidJUnit4::class)
class SpeechPipelineDeviceTest {
    @Test fun realTimeTurnsOnTheRealPipeline() {
        val corpus = Device.requireCorpus()
        Device.requireModels(SttCatalog.zipformer, SttCatalog.parakeet110m)
        val engines = Device.engines(SttEngineConfig())
        val runner = CorpusRunner(engines, emptyList(), pace = 1.0)
        val picks = corpus.filter { it.voice == "sarah" }.take(4)
        val utts = picks.map { runner.run(it) }
        runner.close()
        engines.release()
        for (u in utts) {
            assertTrue("event order", u.orderOk)
            assertEquals(SttEvent.SpeechStarted, u.events.first().event)
            assertEquals(TranscriptPass.Accurate, u.transcript?.pass)
        }
        assertTrue("accurate pass within 1.5 s at commit", utts.count { (it.commitWaitMs ?: 1e9) <= 1_500 } >= utts.size - 1)
        assertTrue("final WER", Scores.wer(utts) { it.transcript?.text ?: "" }.wer < 0.35)
    }

    /**
     * Bill's clip plays (a simulated [PlaybackMonitor]) and its echo is mixed into the input at
     * [gainDb] after a 60 ms echo path. Echo alone must never become user speech; a user who
     * talks over Bill must cut in quickly, and the accepted text must be theirs.
     */
    @Test fun echoGateOnTheRealPipeline() {
        val corpus = Device.requireCorpus()
        Device.requireModels(SttCatalog.zipformer, SttCatalog.parakeet110m)
        val bench = Device.args.getString("bench") == "true"
        // Bill's longer lines (still playing when the user starts after 1.2 s), each paired with
        // another voice saying something else.
        val bills = corpus.filter { it.voice == "bill" && CorpusAudioSource.read(it.file).size > 3 * 16_000 }
        val users = corpus.filter { it.voice != "bill" }
        val random = java.util.Random(7)
        val pairs = bills.map { b -> b to users.filter { it.source != b.source }.let { it[random.nextInt(it.size)] } }.take(if (bench) 10 else 3)
        val gains = if (bench) listOf(-20.0, -10.0, -3.0, 0.0) else listOf(-10.0)
        val engines = Device.engines(SttEngineConfig())
        val rows = JSONArray()
        var falseAccepts = 0
        var missed = 0
        val latencies = ArrayList<Double>()
        for (gain in gains) {
            val playback = SimulatedPlayback(gain)
            val source = CorpusAudioSource(pace = 1.0)
            source.mix = playback::mix
            val stt = SherpaSpeechToText(engines, source, playback)
            playback.samplesNow = { source.samplesOut }
            val log = EventLog(stt)
            val stopper = CoroutineScope(Dispatchers.Default).launch { stt.cutIns.collect { playback.stop() } }
            stt.start()
            // Warm-up: Bill's first lines teach the gate its echo gain; the user stays quiet.
            for ((bill, _) in pairs.take(2)) {
                playback.start(CorpusAudioSource.read(bill.file), source.samplesOut + 1_600)
                source.enqueue(ShortArray(0), "quiet", 100L + CorpusAudioSource.read(bill.file).size * 1000L / 16_000 + 600)
                check(source.awaitDrained(60_000))
            }
            log.awaitSettled()
            falseAccepts += log.take().count { it.event is SttEvent.SpeechStarted }
            stt.dropHeld()
            for ((bill, user) in pairs) {
                val billPcm = CorpusAudioSource.read(bill.file)
                val userPcm = CorpusAudioSource.read(user.file)
                val leadMs = 1_200L
                val id = user.file.name + "#" + gain
                playback.start(billPcm, source.samplesOut + 1_600)
                source.enqueue(ShortArray(0), "lead#$id", leadMs)
                source.enqueue(userPcm, id, 1_500)
                check(source.awaitDrained(60_000))
                log.awaitSettled()
                val events = log.take()
                stt.dropHeld()
                val onset = (source.clipStarts[id] ?: 0L) + (speechSpan(userPcm)?.first ?: 0)
                val cut = events.firstOrNull { it.event is SttEvent.UserSpeech }
                val text = events.mapNotNull { (it.event as? SttEvent.Final)?.text }.joinToString(" ")
                val billOnly = TextNorm.normalize(bill.text).toSet() - TextNorm.normalize(user.text).toSet()
                val leaked = TextNorm.normalize(text).count { it in billOnly }
                val startedBeforeUser = events.firstOrNull { it.event is SttEvent.SpeechStarted }?.let { it.nanos < source.nanosAt(onset) } ?: false
                if (startedBeforeUser) falseAccepts++
                val latency = cut?.let { (it.nanos - source.nanosAt(onset)) / 1e6 }
                if (cut == null && playback.wasPlayingAt(onset)) missed++
                latency?.let { latencies += it }
                rows.put(
                    JSONObject()
                        .put("gain_db", gain)
                        .put("echo_gain_learnt_db", stt.gate.echo.gainDb)
                        .put("user_over_bill", playback.wasPlayingAt(onset))
                        .put("cut_in", cut != null)
                        .put("cut_in_latency_ms", latency)
                        .put("played_ms", playback.stoppedAtMs)
                        .put("started_before_user", startedBeforeUser)
                        .put("bill_words_leaked", leaked)
                        .put("user_wer", Align.wer(user.text, text))
                        .put("user_speech_db", speechDb(userPcm))
                        .put("echo_speech_db", speechDb(billPcm) + gain),
                )
            }
            stopper.cancel()
            log.close()
            stt.stop()
            stt.secondPass.shutdown()
        }
        engines.release()
        val summary = JSONObject()
            .put("rows", rows)
            .put("false_accepts", falseAccepts)
            .put("missed_cut_ins", missed)
            .put("cut_in_latency_ms", Scores.dist(latencies))
        Device.results(if (bench) "echo-bench.json" else "echo.json").writeText(summary.toString(2))
        assertEquals("echo became user speech", 0, falseAccepts)
        if (!bench) {
            assertEquals("missed cut-ins", 0, missed)
            assertTrue("cut-in latency $latencies", (Scores.percentile(latencies, 0.5) ?: 0.0) < 600)
        }
    }
}

/** RMS level (dBFS) of the speech part of [pcm]: 10 ms frames above -40 dBFS. */
fun speechDb(pcm: ShortArray): Double {
    var sum = 0.0
    var n = 0
    for (i in 0 until pcm.size / 160) {
        var e = 0.0
        for (k in i * 160 until (i + 1) * 160) e += (pcm[k] / 32768.0).pow(2)
        if (10 * log10(e / 160 + 1e-12) > -40) {
            sum += e
            n += 160
        }
    }
    return if (n == 0) Dbfs.FLOOR else 10 * log10(sum / n)
}

/**
 * Bill playing a 16 kHz clip from source sample [start]: the [PlaybackMonitor] the gate reads,
 * and the echo it leaves in the mic ([mix], [gainDb] down, 60 ms later). Called on the capture
 * thread only.
 */
class SimulatedPlayback(private val gainDb: Double) : PlaybackMonitor {
    @Volatile private var clip = ShortArray(0)
    @Volatile private var start = Long.MAX_VALUE
    @Volatile private var stoppedAt = Long.MAX_VALUE
    private val gain = 10.0.pow(gainDb / 20)
    var samplesNow: () -> Long = { 0L }

    @Volatile var stoppedAtMs: Long? = null
        private set

    fun start(pcm: ShortArray, atSample: Long) {
        clip = pcm
        start = atSample
        stoppedAt = Long.MAX_VALUE
        stoppedAtMs = null
    }

    fun stop() {
        val now = samplesNow()
        if (now in start until start + clip.size && stoppedAt == Long.MAX_VALUE) {
            stoppedAt = now
            stoppedAtMs = (now - start) * 1000 / 16_000
        }
    }

    fun wasPlayingAt(sample: Long): Boolean = sample in start until minOf(start + clip.size, stoppedAt)

    private fun pos(sample: Long): Int = (sample - start).toInt()

    override val playing: Boolean get() = wasPlayingAt(samplesNow())

    override fun levelDb(): Double {
        val p = pos(samplesNow())
        if (!playing || p < 0) return Dbfs.FLOOR
        val from = maxOf(0, p - 320)
        val to = minOf(clip.size, p)
        if (to <= from) return Dbfs.FLOOR
        var sum = 0.0
        for (i in from until to) sum += (clip[i] / 32768.0).pow(2)
        return if (sum <= 0) Dbfs.FLOOR else 20 * log10(sqrt(sum / (to - from)))
    }

    override fun playedMs(): Long = maxOf(0L, (samplesNow() - start) * 1000 / 16_000)

    fun mix(first: Long, chunk: ShortArray, n: Int) {
        for (k in 0 until n) {
            val s = first + k - ECHO_DELAY
            if (s < start || s >= minOf(start + clip.size, stoppedAt)) continue
            val v = chunk[k] + clip[(s - start).toInt()] * gain
            chunk[k] = v.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private companion object {
        const val ECHO_DELAY = 960L
    }
}
