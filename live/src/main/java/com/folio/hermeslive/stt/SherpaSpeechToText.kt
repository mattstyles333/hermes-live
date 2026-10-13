package com.folio.hermeslive.stt

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.ports.SpeechToText
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.BargeIn
import com.folio.hermeslive.audio.BargeInConfig
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.NoiseFloor
import com.folio.hermeslive.audio.PlaybackMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The cascade's [SpeechToText] on sherpa-onnx (spec v2 R-STT-1..4/7/8, R-SPK-4/6):
 * mic ([AudioSource]) -> Silero VAD -> streaming Zipformer (partials, finals with `ysProbs`
 * confidence) -> per-segment Parakeet second pass -> [accurate] merges the passes and flags
 * uncertain words. Everything runs on the device; audio stays in memory (R-STT-3).
 *
 * Construction (S-F): load the engines off the main thread once the models are ready, e.g.
 * ```
 * val engines = SherpaEngines.load(context.assets, modelManager, SttEngineConfig())
 * val out = AudioTrackOut(onLevel = actor::outputLevel)
 * val stt = SherpaSpeechToText(engines, MicAudioSource(), out, settings = { cascadeSettings })
 * stt.start()   // opens the mic (RECORD_AUDIO granted), starts the capture thread
 * // CascadeActor(..., audio = out, stt = stt); hotwords default to settings.vocabulary;
 * // pass `hotwords = { vocabulary + taskLabels + corrections }` for R-STT-7.
 * stt.stop(); stt.release()
 * ```
 * [events] has one collector (the actor). [accurate] covers the segments since the previous
 * [accurate] or [dropHeld]; call [dropHeld] for a turn that is ignored.
 */
class SherpaSpeechToText(
    private val engines: SttEngines,
    private val source: AudioSource,
    playback: PlaybackMonitor = PlaybackMonitor.None,
    private val settings: () -> CascadeSettings = { CascadeSettings() },
    private val hotwords: () -> List<String> = { settings().vocabulary },
    private val pipelineConfig: (CascadeSettings) -> PipelineConfig = { s ->
        PipelineConfig(uncertainBelow = s.uncertainBelow, bargeInMs = s.bargeInMs)
    },
    bargeIn: BargeInConfig = BargeInConfig(bargeInMs = settings().bargeInMs),
) : SpeechToText {
    private val channel = Channel<SttEvent>(Channel.UNLIMITED)
    private val running = AtomicBoolean(false)
    private val _level = MutableStateFlow(Dbfs.FLOOR)
    private val _cutIns = MutableSharedFlow<CutInReport>(extraBufferCapacity = 16)
    private var thread: Thread? = null

    @Volatile var failure: Throwable? = null
        private set

    /** The barge-in gate (its echo estimate and config), for diagnostics and tests. */
    val gate = BargeIn(bargeIn)

    val secondPass = SecondPass(
        engine = engines.offline,
        hotwords = { engines.offlineHotwords(hotwords()) },
        uncertainBelow = { settings().uncertainBelow },
        agreedBelow = { pipelineConfig(settings()).agreedUncertainBelow },
    )

    private val pipeline = SttPipeline(
        engines = engines,
        secondPass = secondPass,
        gate = gate,
        floor = NoiseFloor(),
        playback = playback,
        config = { pipelineConfig(settings()) },
        hotwords = hotwords,
        emit = { channel.trySend(it) },
        onLevel = { _level.value = it },
        onCutIn = { _cutIns.tryEmit(it) },
    )

    override val events: Flow<SttEvent> = channel.receiveAsFlow()

    /** Input level, dBFS (the orb and diagnostics). */
    val inputLevelDb: StateFlow<Double> = _level.asStateFlow()

    /** Confirmed cut-ins with Bill's `played_ms` (R-SPK-4). */
    val cutIns: SharedFlow<CutInReport> = _cutIns.asSharedFlow()

    /** Samples processed since [start] (tests: the corpus source is drained when this stops growing). */
    @Volatile var samplesProcessed: Long = 0L
        private set

    /** Processing time over audio time since [start] (real-time factor of the capture thread). */
    @Volatile var captureRtf: Double = 0.0
        private set

    val isRunning: Boolean get() = running.get()

    /** Opens the source and starts the capture thread. Throws if the source can't open. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            source.onRouteChanged = { pipeline.routeChanged() }
            source.open()
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
        thread = Thread({ loop() }, "stt-capture").also { it.start() }
    }

    /** Stops capture and forgets held audio. No events after this. */
    fun stop() {
        running.set(false)
        thread?.join(5_000)
        thread = null
    }

    /** Stop, then release the engines once the second pass is idle. */
    fun release() {
        stop()
        if (secondPass.shutdown()) engines.release()
    }

    /** Hold-to-talk (R-STT-8): the headset button or holding the orb. */
    fun holdToTalk(pressed: Boolean) = pipeline.holdToTalk(pressed)

    /** The input route changed (also detected from the mic itself). */
    fun routeChanged() = pipeline.routeChanged()

    /** Forget audio held for the second pass (an ignored turn). */
    fun dropHeld() = secondPass.drop()

    override suspend fun accurate(turnId: String): Transcript? = secondPass.accurate()

    private fun loop() {
        val buffer = ShortArray(SttPipeline.FRAME)
        val frame = FloatArray(SttPipeline.FRAME)
        var filled = 0
        var idle = 0
        var procNanos = 0L
        try {
            while (running.get()) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) {
                    if (++idle > MAX_IDLE_READS) error("audio input gave no samples")
                    Thread.sleep(5)
                    continue
                }
                idle = 0
                // Re-cut into exact VAD frames, whatever the source's read size.
                var i = 0
                while (i < n) {
                    val take = minOf(n - i, SttPipeline.FRAME - filled)
                    for (k in 0 until take) frame[filled + k] = buffer[i + k] / 32768f
                    filled += take
                    i += take
                    if (filled == SttPipeline.FRAME) {
                        val t0 = System.nanoTime()
                        pipeline.frame(frame.copyOf())
                        procNanos += System.nanoTime() - t0
                        samplesProcessed += SttPipeline.FRAME
                        captureRtf = procNanos / 1e9 / (samplesProcessed.toDouble() / AudioRate.HZ)
                        filled = 0
                    }
                }
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            pipeline.close()
            runCatching { source.close() }
            running.set(false)
        }
    }

    private companion object {
        const val MAX_IDLE_READS = 2_000
    }
}
