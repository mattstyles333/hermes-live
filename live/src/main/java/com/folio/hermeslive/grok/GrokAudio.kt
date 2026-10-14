package com.folio.hermeslive.grok

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.folio.hermes.realtime.RealtimePlayback
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.audio.CaptureSource
import com.folio.hermeslive.audio.Dbfs
import com.folio.hermeslive.audio.MicAudioSource
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 16 kHz → 24 kHz mono s16le (the mic runs at the speech pipeline's 16 kHz; Grok Voice takes
 * `audio/pcm` at 24 kHz). Linear interpolation, 2 input samples to 3 output samples, with the
 * last sample carried between chunks so the seams are smooth. One per capture stream.
 */
class Resampler16To24 {
    private var carry: Short? = null

    /** Where the next output sample sits, in thirds of an input sample, from the carried sample. */
    private var phase3 = 0

    /** [count] samples of [input] as little-endian 24 kHz PCM. */
    fun convert(input: ShortArray, count: Int = input.size): ByteArray {
        if (count <= 0) return ByteArray(0)
        val prev = carry
        val n = count + if (prev != null) 1 else 0
        fun at(i: Int): Int = if (prev != null) (if (i == 0) prev.toInt() else input[i - 1].toInt()) else input[i].toInt()
        val last3 = (n - 1) * 3
        val out = ByteArray(((last3 - phase3) / 2 + 1).coerceAtLeast(0) * 2)
        var o = 0
        var pos3 = phase3
        while (pos3 <= last3) {
            val i = pos3 / 3
            val r = pos3 % 3
            val a = at(i)
            val v = if (r == 0) a else a + (at(i + 1) - a) * r / 3
            out[o++] = (v and 0xff).toByte()
            out[o++] = ((v shr 8) and 0xff).toByte()
            pos3 += 2
        }
        phase3 = pos3 - last3
        carry = at(n - 1).toShort()
        return if (o == out.size) out else out.copyOf(o)
    }
}

/** Grok's voice out with its level and release. */
interface GrokPlayback : RealtimePlayback {
    /** Output level 0..1 at the playback head (the orb). */
    fun level(): Float
    fun release()
}

/** One Grok call's audio: the mic (16 kHz [AudioSource]) and the speaker. */
class GrokAudioIo(val source: AudioSource, val out: GrokPlayback)

/** Builds a Grok call's audio. The app's is [Device]. */
fun interface GrokAudioFactory {
    fun create(echoCancel: Boolean): GrokAudioIo

    companion object {
        /** The mic (`VOICE_COMMUNICATION` + AEC/NS when echo cancel is on) and a 24 kHz [StreamOut]. */
        val Device = GrokAudioFactory { echoCancel ->
            GrokAudioIo(MicAudioSource(if (echoCancel) CaptureSource.VOICE_COMMUNICATION else CaptureSource.VOICE_RECOGNITION), StreamOut())
        }
    }
}

/**
 * Grok's streamed voice: an [AudioTrack] with `USAGE_VOICE_COMMUNICATION` (so the echo canceller
 * has the reference), PCM s16le mono at [rate], fed by one writer thread. [stop] is instant
 * (pause + flush, the queue dropped); [idle] turns true once the head has played everything
 * written, which is how the session knows Grok has finished speaking (audio outlasts `response.done`).
 */
class StreamOut(private val rate: Int = 24_000) : GrokPlayback {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val lock = Any()
    private val _idle = MutableStateFlow(true)
    override val idle: StateFlow<Boolean> = _idle.asStateFlow()

    @Volatile private var generation = 0

    @Volatile private var written = 0L

    @Volatile private var base = 0L

    @Volatile private var running = true

    @Volatile private var lastLevel = 0f
    private val levels = ArrayDeque<Pair<Long, Float>>()
    private var track: AudioTrack? = null
    private val thread = Thread(::loop, "grok-out").apply { isDaemon = true; start() }

    override fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        _idle.value = false
        queue.put(pcm)
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            queue.clear()
            track?.let { t -> runCatching { t.pause(); t.flush() } }
            written = 0
            base = head()
            levels.clear()
            lastLevel = 0f
        }
        _idle.value = true
    }

    override fun playedMs(): Long = ((head() - base).coerceAtLeast(0) * 1000) / rate

    override fun level(): Float = lastLevel

    override fun release() {
        running = false
        stop()
        thread.interrupt()
        synchronized(lock) {
            track?.release()
            track = null
        }
    }

    private fun head(): Long = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL

    private fun loop() {
        while (running) {
            val chunk = try {
                queue.poll(TICK_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            if (chunk != null) play(chunk)
            tick()
        }
    }

    private fun play(chunk: ByteArray) {
        val gen = generation
        val t = synchronized(lock) { track ?: build().also { track = it } }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
        var off = 0
        val usable = chunk.size - chunk.size % 2
        while (off < usable && running && gen == generation) {
            val n = t.write(chunk, off, usable - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) break
            off += n
            if (n == 0) {
                tick()
                Thread.sleep(5)
            }
        }
        synchronized(lock) {
            if (gen != generation) return
            written += off / 2
            levels.addLast(written to Dbfs.toUnit(Dbfs.ofPcm16(chunk, 0, usable)))
            while (levels.size > MAX_LEVELS) levels.removeFirst()
        }
    }

    /** Level at the head, and idle once everything written has played. */
    private fun tick() {
        val (done, level) = synchronized(lock) {
            val played = head() - base
            while (levels.isNotEmpty() && levels.first().first < played) levels.removeFirst()
            (queue.isEmpty() && played >= written) to (levels.firstOrNull()?.second ?: 0f)
        }
        lastLevel = if (done) 0f else level
        if (done && !_idle.value) _idle.value = true
    }

    private fun build(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(min, rate * 2 / 5))
            .build()
            .also { base = 0L }
    }

    private companion object {
        const val TICK_MS = 20L
        const val MAX_LEVELS = 512
    }
}
