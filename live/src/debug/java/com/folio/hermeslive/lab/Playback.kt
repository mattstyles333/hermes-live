package com.folio.hermeslive.lab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Something that plays the test passage and can be stopped at once (R-SPK-4). */
interface Player {
    val path: String
    val dbfs: Double?

    fun start()

    /** Stops playback; returns how long the stop call took, in ms. */
    fun stop(): Double

    /** Waits until playback ends or [timeoutMs] passes. */
    fun awaitEnd(timeoutMs: Long)

    fun release()
}

/**
 * The echo test's playback. Android TextToSpeech (on-device voice, no network, no key) is
 * synthesised into memory (an anonymous memfd, never a file on disk) and played through our
 * own AudioTrack with USAGE_VOICE_COMMUNICATION, which is how Bill's voice will be played.
 * If the engine can't synthesise to a descriptor, TextToSpeech plays it itself.
 */
object Playback {
    val VOICE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** About 20 s of plain speech, written for the lab (no real names or data). */
    const val PASSAGE =
        "Here is a short update on the garden project. The new benches arrived on Tuesday, and the " +
            "team finished fitting them by Thursday afternoon. Next week we will move the spring plants " +
            "across, check every label, and count the trays twice. If anything looks wrong, please write " +
            "it down and tell the team before Friday. Thank you all for the careful work so far."

    class Tts(context: Context) {
        private val ready = CountDownLatch(1)
        private var ok = false
        val engine = TextToSpeech(context.applicationContext) { status ->
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }

        /** True when an English voice that needs no network was selected. */
        var offlineVoice: Boolean = false
            private set

        fun await(): Boolean {
            ready.await(10, TimeUnit.SECONDS)
            if (!ok) return false
            engine.setAudioAttributes(VOICE_ATTRIBUTES)
            val voice = runCatching { engine.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired }
                .maxByOrNull { it.quality }
            if (voice != null) engine.voice = voice
            offlineVoice = voice != null
            return true
        }

        fun shutdown() = engine.shutdown()
    }

    /** Synthesises [text] into memory; null if the engine can't. */
    fun synthesize(tts: Tts, text: String): WavPcm.Pcm? {
        val fd = Os.memfd_create("lab-tts", 0)
        val pfd = ParcelFileDescriptor.dup(fd)
        val done = CountDownLatch(1)
        var success = false
        tts.engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                success = true
                done.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done.countDown()

            override fun onError(utteranceId: String?, errorCode: Int) = done.countDown()
        })
        try {
            if (tts.engine.synthesizeToFile(text, Bundle(), pfd, "lab-synth") != TextToSpeech.SUCCESS) return null
            if (!done.await(30, TimeUnit.SECONDS) || !success) return null
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            val bytes = FileInputStream(fd).readBytes()
            return runCatching { WavPcm.parse(bytes) }.getOrNull()?.let { it.copy(channels = 1, samples = WavPcm.mono(it)) }
        } finally {
            pfd.close()
            Os.close(fd)
        }
    }

    class TrackPlayer(private val pcm: WavPcm.Pcm) : Player {
        override val path = "audiotrack_voice_communication"
        override val dbfs: Double = Dbfs.of(Dbfs.pcm16ToFloat(pcm.samples))
        private val track = AudioTrack.Builder()
            .setAudioAttributes(VOICE_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(pcm.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                    pcm.sampleRate / 5 * 2,
                ),
            )
            .build()
        private val ended = CountDownLatch(1)

        @Volatile private var stopped = false
        private var writer: Thread? = null

        override fun start() {
            track.play()
            writer = Thread({
                var at = 0
                val step = pcm.sampleRate / 50
                while (!stopped && at < pcm.samples.size) {
                    val n = track.write(pcm.samples, at, minOf(step, pcm.samples.size - at))
                    if (n <= 0) break
                    at += n
                }
                // Let the tail drain (the buffer holds about 200 ms).
                if (!stopped) Thread.sleep(250)
                ended.countDown()
            }, "lab-playback").also { it.start() }
        }

        override fun stop(): Double {
            val t0 = System.nanoTime()
            stopped = true
            track.pause()
            track.flush()
            val ms = (System.nanoTime() - t0) / 1e6
            ended.countDown()
            return ms
        }

        override fun awaitEnd(timeoutMs: Long) {
            ended.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        override fun release() {
            stopped = true
            writer?.join(1_000)
            runCatching { track.stop() }
            track.release()
        }
    }

    class SpeakPlayer(private val tts: Tts, private val text: String) : Player {
        override val path = "tts_speak"
        override val dbfs: Double? = null
        private val ended = CountDownLatch(1)

        override fun start() {
            tts.engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = ended.countDown()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = ended.countDown()

                override fun onStop(utteranceId: String?, interrupted: Boolean) = ended.countDown()
            })
            tts.engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "lab-speak")
        }

        override fun stop(): Double {
            val t0 = System.nanoTime()
            tts.engine.stop()
            return (System.nanoTime() - t0) / 1e6
        }

        override fun awaitEnd(timeoutMs: Long) {
            ended.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        override fun release() = Unit
    }
}
