package com.folio.hermeslive.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicReference

/**
 * 16 kHz mono 16-bit input for the speech-to-text pipeline and the offline memo recorder. The
 * mic ([MicAudioSource]) in the app; a WAV corpus (`CorpusAudioSource`, debug only) in the
 * emulator tests. Only an offline memo (`MemoCall`, Settings › Voice › Offline memos) writes it
 * to disk, deleted once Hermes has transcribed it; calls and meetings never do (R-STT-3).
 * Called from the one capture thread.
 */
interface AudioSource {
    val sampleRate: Int get() = SAMPLE_RATE

    /** Open the input; throws if it can't. */
    fun open()

    /** Blocking read of up to [buffer].size samples: the count, 0 if none yet, -1 at the end of the input. */
    fun read(buffer: ShortArray): Int

    fun close()

    /** Set by the pipeline: called (any thread) when the input route changes. */
    var onRouteChanged: (() -> Unit)?

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** Capture source, switchable in Settings (R-SPK-5; spec §11b). */
enum class CaptureSource(val androidValue: Int) {
    /** The default: the platform runs its echo canceller and noise suppressor on this one. */
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC(MediaRecorder.AudioSource.MIC),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),
}

/** Which effects the input got (reported in diagnostics as booleans). */
data class InputEffects(
    val source: CaptureSource,
    val aecAvailable: Boolean,
    val aecOn: Boolean,
    val nsAvailable: Boolean,
    val nsOn: Boolean,
)

/**
 * `AudioIn`: an [AudioRecord] on [CaptureSource.VOICE_COMMUNICATION] (by default) at 16 kHz mono,
 * with [AcousticEchoCanceler] and [NoiseSuppressor] switched on when the device has them.
 * [switchTo] changes the source; it takes effect at the next read. The caller holds
 * RECORD_AUDIO (a call starts only after it is granted).
 */
class MicAudioSource(source: CaptureSource = CaptureSource.VOICE_COMMUNICATION) : AudioSource {
    private val wanted = AtomicReference(source)
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var openSource: CaptureSource? = null
    private val routing = AudioRecord.OnRoutingChangedListener { onRouteChanged?.invoke() }

    @Volatile var effects: InputEffects? = null
        private set

    override var onRouteChanged: (() -> Unit)? = null

    val source: CaptureSource get() = wanted.get()

    /** Use [source] from the next read on. */
    fun switchTo(source: CaptureSource) = wanted.set(source)

    @SuppressLint("MissingPermission")
    override fun open() {
        close()
        val source = wanted.get()
        val min = AudioRecord.getMinBufferSize(AudioSource.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            .setAudioSource(source.androidValue)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioSource.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 4, AudioSource.SAMPLE_RATE))
            .build()
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialised" }
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        val a = if (aecAvailable) runCatching { AcousticEchoCanceler.create(r.audioSessionId) }.getOrNull() else null
        runCatching { a?.enabled = true }
        val nsAvailable = NoiseSuppressor.isAvailable()
        val n = if (nsAvailable) runCatching { NoiseSuppressor.create(r.audioSessionId) }.getOrNull() else null
        runCatching { n?.enabled = true }
        r.addOnRoutingChangedListener(routing, null)
        r.startRecording()
        record = r
        aec = a
        ns = n
        openSource = source
        effects = InputEffects(source, aecAvailable, a?.enabled == true, nsAvailable, n?.enabled == true)
    }

    override fun read(buffer: ShortArray): Int {
        if (wanted.get() != openSource) {
            open()
            onRouteChanged?.invoke()
        }
        val r = record ?: return -1
        return r.read(buffer, 0, buffer.size).let { if (it < 0) 0 else it }
    }

    override fun close() {
        record?.let { r ->
            runCatching { r.removeOnRoutingChangedListener(routing) }
            runCatching { r.stop() }
            r.release()
        }
        aec?.release()
        ns?.release()
        record = null
        aec = null
        ns = null
        openSource = null
    }
}
