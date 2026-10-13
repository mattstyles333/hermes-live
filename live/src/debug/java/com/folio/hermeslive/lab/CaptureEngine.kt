package com.folio.hermeslive.lab

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Called on the capture thread for every chunk; the echo test uses it. */
interface CaptureProbe {
    fun onChunk(startSample: Long, samples: FloatArray, speech: Boolean)

    fun onFinalWords(words: List<String>) {}
}

data class Utterance(
    val id: Int,
    val streaming: List<TokenConfidence.WordConfidence>,
    val streamingText: String,
    val audioS: Double,
    val hotwords: Boolean,
    val secondText: String? = null,
    val diff: WordDiff.Result? = null,
    val secondMs: Double? = null,
    val secondRtf: Double? = null,
)

data class LiveState(
    val running: Boolean = false,
    val status: String = "Stopped",
    val source: CaptureSource? = null,
    val aec: AecReport? = null,
    val dbfs: Double = Dbfs.FLOOR,
    val speech: Boolean = false,
    val partial: List<TokenConfidence.WordConfidence> = emptyList(),
    val utterances: List<Utterance> = emptyList(),
    val streamRtf: Double? = null,
    val slowChunks: Int = 0,
    val readErrors: Int = 0,
    val hotwordsActive: Boolean = false,
)

/**
 * Mic -> Silero VAD + streaming recogniser at 16 kHz; at each endpoint the utterance PCM
 * (memory only, dropped after the second pass) goes to the offline recogniser. Audio is never
 * written anywhere. One instance per run: build, [start], [stop].
 */
class CaptureEngine(
    private val source: CaptureSource,
    private val vad: Vad,
    private val online: OnlineRecognizer,
    private val streamingModel: LabModel,
    private val offline: OfflineRecognizer?,
    private val offlineModel: LabModel?,
    private val stats: LiveStats,
    private val hotwordArgs: (LabModel) -> String,
    /**
     * False for the echo test: no second pass and nothing recorded in [stats], so playback
     * echo never feeds the confidence threshold or the live numbers.
     */
    private val measure: Boolean = true,
) {
    private val _state = MutableStateFlow(LiveState(source = source))
    val state: StateFlow<LiveState> = _state

    private val running = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)
    private val hotwordsChanged = AtomicBoolean(true)
    private val probe = AtomicReference<CaptureProbe?>(null)
    private val secondPass = Executors.newSingleThreadExecutor { Thread(it, "lab-second-pass") }
    private var thread: Thread? = null

    @Volatile var samplesCaptured: Long = 0L
        private set

    @Volatile var speechNow: Boolean = false
        private set

    @Volatile private var partialWords: List<String> = emptyList()

    fun setProbe(p: CaptureProbe?) = probe.set(p)

    /** Words of the current (not yet final) partial. */
    fun currentPartialWords(): List<String> = partialWords

    /** Drop the current partial and start a fresh stream at the next chunk. */
    fun resetStream() = resetRequested.set(true)

    /** New hotwords take effect when the next utterance's stream is created. */
    fun hotwordsUpdated() = hotwordsChanged.set(true)

    @SuppressLint("MissingPermission") // The activity checks RECORD_AUDIO before building an engine.
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuffer = AudioRecord.getMinBufferSize(Sherpa.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioSource(source.androidValue)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Sherpa.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 4, Sherpa.SAMPLE_RATE))
            .build()
        val available = AcousticEchoCanceler.isAvailable()
        val aec = if (available) AcousticEchoCanceler.create(record.audioSessionId) else null
        // Whether the platform already runs AEC on this source, before we ask for it (R-SPK-5).
        val byDefault = aec?.enabled == true
        aec?.enabled = true
        val aecReport = AecReport(source.name, available, aec != null, byDefault, aec?.enabled == true)
        stats.aec[source] = aecReport
        _state.update { it.copy(running = true, status = "Listening", aec = aecReport) }
        thread = Thread({ loop(record, aec) }, "lab-capture").also { it.start() }
    }

    /**
     * Stops capture and waits for a running second pass. Returns false if either is still
     * running after the timeout: the caller must then not release the recognisers (a native
     * use-after-free), and leaks them instead.
     */
    fun stop(): Boolean {
        running.set(false)
        val t = thread
        t?.join(5_000)
        thread = null
        secondPass.shutdown()
        val drained = secondPass.awaitTermination(30, TimeUnit.SECONDS)
        _state.update { it.copy(running = false, status = "Stopped", speech = false) }
        return drained && t?.isAlive != true
    }

    private fun loop(record: AudioRecord, aec: AcousticEchoCanceler?) {
        val chunk = ShortArray(Sherpa.VAD_WINDOW)
        val chunkNanos = Sherpa.VAD_WINDOW * 1_000_000_000L / Sherpa.SAMPLE_RATE
        val utterance = ArrayList<FloatArray>()
        var utteranceSamples = 0
        // Quiet samples at the start of the buffer, before the VAD first heard speech.
        var heardSpeech = false
        var quietLead = 0
        var stream: OnlineStream? = null
        var streamHotwords = false
        var lastUi = 0L
        var procNanos = 0L
        var audioNanos = 0L
        var slow = 0
        var errors = 0
        var errorRun = 0
        var localIds = 0
        try {
            record.startRecording()
            while (running.get()) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    errors++
                    if (measure) stats.readErrors++
                    if (++errorRun >= MAX_ERROR_RUN) error("AudioRecord.read failed $errorRun times in a row ($n)")
                    _state.update { it.copy(readErrors = errors) }
                    Thread.sleep(10)
                    continue
                }
                errorRun = 0
                val t0 = System.nanoTime()
                val samples = Dbfs.pcm16ToFloat(chunk, n)
                val start = samplesCaptured
                samplesCaptured += n

                if (stream == null || resetRequested.getAndSet(false) || (hotwordsChanged.get() && utteranceSamples == 0)) {
                    stream?.release()
                    val args = hotwordArgs(streamingModel)
                    stream = online.createStream(args)
                    streamHotwords = args.isNotEmpty()
                    hotwordsChanged.set(false)
                    utterance.clear()
                    utteranceSamples = 0
                    heardSpeech = false
                    quietLead = 0
                }

                vad.acceptWaveform(samples)
                val speech = vad.isSpeechDetected()
                while (!vad.empty()) vad.pop()
                speechNow = speech

                utterance += samples
                utteranceSamples += n
                if (!heardSpeech) {
                    if (speech) heardSpeech = true else quietLead += n
                }
                while (utteranceSamples > MAX_UTTERANCE_SAMPLES && utterance.isNotEmpty()) {
                    val dropped = utterance.removeAt(0).size
                    utteranceSamples -= dropped
                    quietLead = maxOf(0, quietLead - dropped)
                }

                stream.acceptWaveform(samples, Sherpa.SAMPLE_RATE)
                while (online.isReady(stream)) online.decode(stream)
                val result = online.getResult(stream)
                val words = TokenConfidence.words(result.tokens.toList(), result.ysProbs)
                partialWords = words.map { it.word }
                val level = Dbfs.of(samples)
                if (speech && measure) stats.addSpeechLevel(level)

                probe.get()?.onChunk(start, samples, speech)

                if (online.isEndpoint(stream)) {
                    if (result.text.isNotBlank()) {
                        // Second pass on the speech only (plus pre-roll for the VAD's detection lag).
                        val skip = if (heardSpeech) maxOf(0, quietLead - PRE_ROLL_SAMPLES) else 0
                        val pcm = concat(utterance, utteranceSamples, skip).takeIf { it.size >= Sherpa.SAMPLE_RATE / 5 }
                            ?: concat(utterance, utteranceSamples, 0)
                        val id = if (measure) stats.nextUtteranceId() else ++localIds
                        val u = Utterance(id, words, result.text.trim(), pcm.size.toDouble() / Sherpa.SAMPLE_RATE, streamHotwords)
                        _state.update { it.copy(utterances = (listOf(u) + it.utterances).take(MAX_SHOWN), partial = emptyList()) }
                        probe.get()?.onFinalWords(words.map { it.word })
                        submitSecondPass(u, pcm)
                    }
                    if (hotwordsChanged.get()) {
                        stream.release()
                        val args = hotwordArgs(streamingModel)
                        stream = online.createStream(args)
                        streamHotwords = args.isNotEmpty()
                        hotwordsChanged.set(false)
                    } else {
                        online.reset(stream)
                    }
                    utterance.clear()
                    utteranceSamples = 0
                    heardSpeech = false
                    quietLead = 0
                    partialWords = emptyList()
                }

                val elapsed = System.nanoTime() - t0
                procNanos += elapsed
                audioNanos += chunkNanos * n / chunk.size
                if (elapsed > chunkNanos) {
                    slow++
                    if (measure) stats.slowChunks++
                }
                if (measure) {
                    stats.streamProcNanos = procNanos
                    stats.streamAudioNanos = audioNanos
                }
                if (t0 - lastUi > UI_NANOS) {
                    lastUi = t0
                    _state.update {
                        it.copy(
                            dbfs = level,
                            speech = speech,
                            partial = words,
                            streamRtf = procNanos.toDouble() / audioNanos,
                            slowChunks = slow,
                            hotwordsActive = streamHotwords,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(status = "Capture failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}") }
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
            stream?.release()
            running.set(false)
            _state.update { it.copy(running = false, speech = false) }
        }
    }

    private fun submitSecondPass(u: Utterance, pcm: FloatArray) {
        if (!measure) return
        val recognizer = offline ?: return
        val model = offlineModel ?: return
        secondPass.execute {
            val args = hotwordArgs(model)
            val t0 = System.nanoTime()
            val s = if (args.isEmpty()) recognizer.createStream() else recognizer.createStream(args)
            s.acceptWaveform(pcm, Sherpa.SAMPLE_RATE)
            recognizer.decode(s)
            val text = recognizer.getResult(s).text.trim()
            s.release()
            val ms = (System.nanoTime() - t0) / 1e6
            val rtf = ms / 1000.0 / u.audioS
            val diff = WordDiff.diff(u.streamingText, text)
            stats.recordSecondPass(u, diff, ms, rtf)
            _state.update { st ->
                st.copy(
                    utterances = st.utterances.map {
                        if (it.id == u.id) it.copy(secondText = text, diff = diff, secondMs = ms, secondRtf = rtf) else it
                    },
                )
            }
        }
    }

    /** The buffered chunks as one array, without the first [skip] samples. */
    private fun concat(parts: List<FloatArray>, total: Int, skip: Int): FloatArray {
        val out = FloatArray(maxOf(0, total - skip))
        var toSkip = skip
        var at = 0
        for (p in parts) {
            val from = minOf(toSkip, p.size)
            toSkip -= from
            val n = minOf(p.size - from, out.size - at)
            if (n > 0) System.arraycopy(p, from, out, at, n)
            at += maxOf(0, n)
        }
        return out
    }

    private companion object {
        const val MAX_UTTERANCE_SAMPLES = 30 * Sherpa.SAMPLE_RATE
        const val PRE_ROLL_SAMPLES = 8 * Sherpa.SAMPLE_RATE / 10
        const val MAX_ERROR_RUN = 50
        const val MAX_SHOWN = 12
        const val UI_NANOS = 80_000_000L
    }
}
