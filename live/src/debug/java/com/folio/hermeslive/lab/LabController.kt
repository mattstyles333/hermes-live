package com.folio.hermeslive.lab

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.folio.hermeslive.BuildConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

data class HotwordView(
    val phrases: Int = 0,
    val errors: List<String> = emptyList(),
    val streaming: String = "",
    val offline: String = "",
    val streamingSupported: Boolean = false,
    val offlineSupported: Boolean = false,
    val streamingEncodable: Int? = null,
    val offlineEncodable: Int? = null,
    val selfTestStreaming: Pair<String, String>? = null,
    val selfTestOffline: Pair<String, String>? = null,
)

/**
 * Owns the lab's models, settings and jobs. Heavy work runs on one worker thread, one job at
 * a time; downloads run on their own pool. Nothing here talks to the network except
 * [ModelStore.download].
 */
class LabController(private val context: Context) : EchoTest.Host {
    val store = ModelStore(File(context.noBackupFilesDir, "stt-lab"))
    val settings = MutableStateFlow(LabSettings())
    val live = MutableStateFlow(LiveState())
    val job = MutableStateFlow<String?>(null)
    val talkNow = MutableStateFlow(false)
    val echoTrials = MutableStateFlow<List<EchoTrial>>(emptyList())
    val bench = MutableStateFlow<List<BenchRow>>(emptyList())
    val hotwords = MutableStateFlow(HotwordView())
    val loadMs = MutableStateFlow<Map<String, Double>>(emptyMap())
    val stats = LiveStats()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lab-worker") }
    private val downloads = Executors.newFixedThreadPool(2) { Thread(it, "lab-download") }
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var cancelRequested = false
    private var loaded: Loaded? = null
    private var engine: CaptureEngine? = null
    private var mirror: Job? = null
    private var savedMode: Int? = null
    private var tts: Playback.Tts? = null
    private var passage: WavPcm.Pcm? = null

    /** Loaded models plus how they were built, which is what decides if hotwords are safe. */
    private class Loaded(
        val key: String,
        val vad: Vad,
        val online: OnlineRecognizer,
        val streamingModel: LabModel,
        val streamingDecoding: String,
        val streamingVocab: Boolean,
        val offline: OfflineRecognizer?,
        val offlineModel: LabModel?,
        val offlineDecoding: String,
        val offlineVocab: Boolean,
    ) {
        /** The hotwords argument for [model] as built; "" whenever it could crash. */
        fun hotwordArg(model: LabModel, text: String): String {
            val (decoding, vocab) = when (model) {
                streamingModel -> streamingDecoding to streamingVocab
                offlineModel -> offlineDecoding to offlineVocab
                else -> return ""
            }
            val phrases = Hotwords.parse(text, model.upperCaseHotwords).phrases
            return Hotwords.safeArgument(phrases, model.kind, decoding, vocab)
        }

        fun release() {
            vad.release()
            online.release()
            offline?.release()
        }
    }

    init {
        refreshHotwords()
    }

    // --- models -------------------------------------------------------------------------

    fun download(model: LabModel) {
        if (!store.queue(model)) return
        downloads.execute {
            store.download(model)
            refreshHotwords()
        }
    }

    fun cancelDownload(model: LabModel) = store.cancel(model)

    fun delete(model: LabModel) = submit("Deleting ${model.title}") {
        stopEngine()
        releaseLoaded()
        store.delete(model)
        refreshHotwords()
    }

    // --- settings and hotwords ----------------------------------------------------------

    fun update(change: (LabSettings) -> LabSettings) {
        val before = settings.value
        val after = change(before)
        settings.value = after
        if (before.copy(source = after.source, callMode = after.callMode, hotwordsText = after.hotwordsText) != after) {
            // Model, threads or decoding changed: captions restart with the new models.
            submit("Unloading models") {
                stopEngine()
                restoreAudio()
                releaseLoaded()
            }
        } else if ((before.source != after.source || before.callMode != after.callMode) && engine != null) {
            submit("Switching source") {
                stopEngine()
                restoreAudio()
                startEngine(after.source, measure = true)
            }
        }
        refreshHotwords()
    }

    fun applyHotwords(text: String) {
        settings.update { it.copy(hotwordsText = text) }
        refreshHotwords()
        engine?.hotwordsUpdated()
    }

    private fun refreshHotwords() {
        val s = settings.value
        fun describe(model: LabModel?): Triple<String, Boolean, Int?> {
            if (model == null) return Triple("no second pass", false, null)
            if (!store.isReady(model)) return Triple("${model.title}: not downloaded", false, null)
            val support = Hotwords.support(model.kind, Hotwords.decodingFor(model.kind, s.decoding), store.hasVocab(model))
            val phrases = Hotwords.parse(s.hotwordsText, model.upperCaseHotwords).phrases
            return when (support) {
                is Hotwords.Support.No -> Triple("${model.title}: not used (${support.reason})", false, null)
                Hotwords.Support.Yes -> {
                    val vocab = store.vocab(model)
                    val ok = phrases.count { Hotwords.encodable(it, vocab) }
                    Triple("${model.title}: accepted $ok of ${phrases.size} phrase(s)", true, ok)
                }
            }
        }
        val parsed = Hotwords.parse(s.hotwordsText, upperCase = false)
        val st = describe(s.streaming)
        val off = describe(s.offline)
        hotwords.update {
            it.copy(
                phrases = parsed.phrases.size,
                errors = parsed.errors,
                streaming = st.first,
                streamingSupported = st.second,
                streamingEncodable = st.third,
                offline = off.first,
                offlineSupported = off.second,
                offlineEncodable = off.third,
            )
        }
    }

    // --- jobs ---------------------------------------------------------------------------

    fun startCaptions() = submit("Loading models") {
        ensureLoaded()
        startEngine(settings.value.source, measure = true)
    }

    fun stopCaptions() = submit("Stopping") {
        stopEngine()
        restoreAudio()
    }

    fun runEcho(allSources: Boolean) = submit("Echo test") {
        cancelRequested = false
        ensureLoaded()
        val t = tts ?: Playback.Tts(context).also { tts = it }
        check(t.await()) { "TextToSpeech is not available" }
        if (passage == null) passage = Playback.synthesize(t, Playback.PASSAGE)
        val sources = if (allSources) CaptureSource.entries else listOf(settings.value.source)
        try {
            val trials = EchoTest(this).run(sources)
            echoTrials.update { it + trials }
        } finally {
            talkNow.value = false
            stopEngine()
            restoreAudio()
        }
    }

    fun runBench() = submit("Benchmarks") {
        cancelRequested = false
        stopEngine()
        restoreAudio()
        releaseLoaded()
        val rows = Bench(store, settings.value.decoding).run(ModelCatalog.all, { job.value = "Benchmark: $it" }, { cancelRequested })
        bench.update { it + rows }
    }

    /**
     * Decodes each model's probe WAV without and with its built-in probe hotword. A changed
     * output proves hotwords reach the decoder on this phone (R-STT-7).
     */
    fun runHotwordSelfTest() = submit("Hotword self-test") {
        stopEngine()
        restoreAudio()
        val l = ensureLoaded()
        val s = settings.value
        fun probeArg(model: LabModel): String = if (model.probeHotword.isEmpty()) "" else l.hotwordArg(model, model.probeHotword)
        val streamArg = probeArg(s.streaming)
        val streaming = if (streamArg.isEmpty()) null else {
            val wav = WaveReader.readWave(store.path(s.streaming, s.streaming.probeWav))
            fun decode(arg: String): String {
                val stream = l.online.createStream(arg)
                stream.acceptWaveform(wav.samples, wav.sampleRate)
                stream.acceptWaveform(FloatArray(wav.sampleRate * 3 / 10), wav.sampleRate)
                stream.inputFinished()
                while (l.online.isReady(stream)) l.online.decode(stream)
                return l.online.getResult(stream).text.also { stream.release() }
            }
            decode("") to decode(streamArg)
        }
        val offModel = s.offline
        val offArg = offModel?.let(::probeArg).orEmpty()
        val offline = if (l.offline == null || offModel == null || offArg.isEmpty()) null else {
            val wav = WaveReader.readWave(store.path(offModel, offModel.probeWav))
            fun decode(arg: String): String {
                val stream = if (arg.isEmpty()) l.offline.createStream() else l.offline.createStream(arg)
                stream.acceptWaveform(wav.samples, wav.sampleRate)
                l.offline.decode(stream)
                return l.offline.getResult(stream).text.also { stream.release() }
            }
            decode("") to decode(offArg)
        }
        hotwords.update { it.copy(selfTestStreaming = streaming, selfTestOffline = offline) }
    }

    fun cancel() {
        cancelRequested = true
    }

    private fun submit(name: String, block: () -> Unit) {
        worker.execute {
            job.value = name
            try {
                block()
            } catch (e: Exception) {
                live.update { it.copy(status = "$name failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}") }
            } finally {
                job.value = null
            }
        }
    }

    private fun ensureLoaded(): Loaded {
        val s = settings.value
        val offlineModel = s.offline
        val key = "${s.streamingId}|${s.offlineId}|${s.threads}|${s.decoding}"
        loaded?.let { if (it.key == key) return it }
        stopEngine()
        releaseLoaded()
        val missing = listOfNotNull(ModelCatalog.sileroVad, s.streaming, offlineModel).filterNot(store::isReady)
        check(missing.isEmpty()) { "download first: ${missing.joinToString { it.title }}" }
        listOfNotNull(s.streaming, offlineModel).forEach(store::ensureVocab)
        val times = LinkedHashMap<String, Double>()
        fun <T> timed(name: String, f: () -> T): T {
            job.value = "Loading $name"
            val t0 = System.nanoTime()
            return f().also { times[name] = (System.nanoTime() - t0) / 1e6 }
        }
        val vad = timed("VAD") { Sherpa.vad(store) }
        val online = timed(s.streaming.id) { Sherpa.online(store, s.streaming, s.threads, s.decoding) }
        val offline = offlineModel?.let { m -> timed(m.id) { Sherpa.offline(store, m, s.threads, s.decoding) } }
        loadMs.value = times
        val streamingDecoding = Hotwords.decodingFor(s.streaming.kind, s.decoding)
        val offlineDecoding = offlineModel?.let { Hotwords.decodingFor(it.kind, s.decoding) } ?: Hotwords.GREEDY
        return Loaded(
            key = key,
            vad = vad,
            online = online,
            streamingModel = s.streaming,
            streamingDecoding = streamingDecoding,
            streamingVocab = Hotwords.vocabConfigured(streamingDecoding, store.hasVocab(s.streaming)),
            offline = offline,
            offlineModel = offlineModel,
            offlineDecoding = offlineDecoding,
            offlineVocab = offlineModel != null && Hotwords.vocabConfigured(offlineDecoding, store.hasVocab(offlineModel)),
        ).also { loaded = it }
    }

    private fun releaseLoaded() {
        loaded?.release()
        loaded = null
    }

    /** The echo test gets its own engine that records nothing into [stats] and has no second pass. */
    override fun engineFor(source: CaptureSource): CaptureEngine = startEngine(source, measure = false)

    override fun ttsOfflineVoice(): Boolean = tts?.offlineVoice == true

    private fun startEngine(source: CaptureSource, measure: Boolean): CaptureEngine {
        stopEngine()
        val l = ensureLoaded()
        enterCallMode()
        val e = CaptureEngine(
            source = source,
            vad = l.vad,
            online = l.online,
            streamingModel = l.streamingModel,
            offline = l.offline,
            offlineModel = l.offlineModel,
            stats = stats,
            hotwordArgs = { m -> l.hotwordArg(m, settings.value.hotwordsText) },
            measure = measure,
        )
        l.vad.reset()
        mirror?.cancel()
        mirror = scope.launch { e.state.collect { live.value = it } }
        engine = e
        e.start()
        return e
    }

    private fun stopEngine() {
        val e = engine ?: return
        engine = null
        e.setProbe(null)
        if (!e.stop()) {
            // A decode is still running natively: never free the models under it. Leak them;
            // the next start loads fresh ones.
            loaded = null
            live.update { it.copy(status = "Stopped; a decode was still running, so the models were left alone and will reload") }
        }
    }

    private fun enterCallMode() {
        if (!settings.value.callMode || savedMode != null) return
        savedMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { audio.setCommunicationDevice(it) }
    }

    private fun restoreAudio() {
        val mode = savedMode ?: return
        audio.clearCommunicationDevice()
        audio.mode = mode
        savedMode = null
    }

    // --- EchoTest.Host --------------------------------------------------------------------

    override val callMode: Boolean get() = settings.value.callMode

    override fun newPlayer(): Player = passage?.let { Playback.TrackPlayer(it) } ?: Playback.SpeakPlayer(tts!!, Playback.PASSAGE)

    override fun volumeFraction(): Double? {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        return if (max <= 0) null else audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL).toDouble() / max
    }

    override fun status(text: String, talkNow: Boolean) {
        this.talkNow.value = talkNow
        job.value = "Echo test: $text"
    }

    override fun cancelled(): Boolean = cancelRequested

    // --- report and lifecycle -------------------------------------------------------------

    fun clearResults() {
        stats.reset()
        echoTrials.value = emptyList()
        bench.value = emptyList()
        live.update { it.copy(utterances = emptyList(), partial = emptyList()) }
    }

    fun report(): LabReport {
        val s = settings.value
        val (sherpa, ort) = Sherpa.version()
        val h = hotwords.value
        return LabReport(
            createdUtc = Instant.now().toString(),
            device = DeviceReport(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                socModel = Build.SOC_MODEL,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                cores = Runtime.getRuntime().availableProcessors(),
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            ),
            app = AppReport(BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE, sherpa, ort),
            settings = SettingsReport(
                captureSource = s.source.name,
                streamingModel = s.streamingId,
                offlineModel = s.offlineId,
                threads = s.threads,
                decodingMethod = s.decoding,
                callMode = s.callMode,
                endpointTrailingSilenceS = Sherpa.ENDPOINT_TRAILING_SILENCE_S,
                vadThreshold = Sherpa.VAD_THRESHOLD,
                vadMinSpeechS = Sherpa.VAD_MIN_SPEECH_S,
                vadMinSilenceS = Sherpa.VAD_MIN_SILENCE_S,
                vadMaxSpeechS = Sherpa.VAD_MAX_SPEECH_S,
                voiceCallVolume = volumeFraction(),
            ),
            models = ModelCatalog.all.map { m ->
                val t = store.timing(m)
                val ready = store.isReady(m)
                ModelReport(
                    model = m.id,
                    ready = ready,
                    sha256Ok = t?.shaOk ?: if (ready) true else null,
                    downloadS = t?.downloadS,
                    extractS = t?.extractS,
                    bytesOnDisk = if (ready) store.bytesOnDisk(m) else 0,
                    vocabPieces = if (ready && store.hasVocab(m)) store.vocab(m).size else null,
                )
            },
            aec = stats.aec.values.sortedBy { it.source },
            live = stats.report(),
            confidence = stats.confidenceReport(),
            hotwords = HotwordReport(
                phrases = h.phrases,
                parseErrors = h.errors.size,
                streamingSupported = h.streamingSupported,
                offlineSupported = h.offlineSupported,
                streamingEncodable = h.streamingEncodable,
                offlineEncodable = h.offlineEncodable,
                selfTestStreamingChanged = h.selfTestStreaming?.let { it.first != it.second },
                selfTestOfflineChanged = h.selfTestOffline?.let { it.first != it.second },
            ),
            echo = echoTrials.value,
            bench = bench.value,
        )
    }

    fun close() {
        cancelRequested = true
        worker.execute {
            stopEngine()
            restoreAudio()
            releaseLoaded()
            tts?.shutdown()
            tts = null
            passage = null
        }
        worker.shutdown()
        ModelCatalog.all.forEach(store::cancel)
        downloads.shutdownNow()
        scope.cancel()
    }
}
