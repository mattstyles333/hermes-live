package com.folio.hermeslive.lab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Echo and cut-in test (R-SPK-4, R-SPK-5), per capture source:
 * 1. "Stay quiet": the passage plays on the loudspeaker while captions run. Counts the words
 *    the recogniser makes of the playback (echo leak) and the share of VAD windows that say
 *    speech (false speech).
 * 2. "Talk over it": the passage plays again; at a random moment the screen says TALK NOW.
 *    When the VAD fires, playback stops at once. Cut-in latency is the time from the user's
 *    estimated speech onset (from the input level) to the VAD firing.
 */
class EchoTest(private val host: Host) {
    interface Host {
        val callMode: Boolean

        /** A running capture engine on [source] (models already loaded). */
        fun engineFor(source: CaptureSource): CaptureEngine

        fun newPlayer(): Player

        fun volumeFraction(): Double?

        fun status(text: String, talkNow: Boolean = false)

        fun cancelled(): Boolean
    }

    private class WindowProbe : CaptureProbe {
        val meter = FrameMeter(FRAME)

        @Volatile var chunks = 0

        @Volatile var speechChunks = 0
        val words = java.util.Collections.synchronizedList(ArrayList<String>())

        override fun onChunk(startSample: Long, samples: FloatArray, speech: Boolean) {
            meter.accept(samples)
            chunks++
            if (speech) speechChunks++
        }

        override fun onFinalWords(words: List<String>) {
            this.words += words
        }
    }

    private class CutInProbe(private val onFire: () -> Unit) : CaptureProbe {
        val meter = FrameMeter(FRAME)
        val fired = CountDownLatch(1)

        @Volatile var baseSample = -1L

        @Volatile var promptSample = Long.MAX_VALUE

        @Volatile var fireSample = -1L
        private var previous = false

        override fun onChunk(startSample: Long, samples: FloatArray, speech: Boolean) {
            if (baseSample < 0) baseSample = startSample
            meter.accept(samples)
            if (fireSample < 0 && startSample + samples.size >= promptSample && speech && !previous) {
                fireSample = startSample + samples.size
                onFire()
                fired.countDown()
            }
            previous = speech
        }
    }

    fun run(sources: List<CaptureSource>): List<EchoTrial> {
        val out = ArrayList<EchoTrial>()
        for (source in sources) {
            if (host.cancelled()) break
            out += trial(source)
        }
        return out
    }

    private fun trial(source: CaptureSource): EchoTrial {
        val engine = host.engineFor(source)
        val aecEnabled = engine.state.value.aec?.enabled == true
        val slowBefore = engine.state.value.slowChunks
        host.status("${source.label}: stay quiet. Playing the passage…")
        Thread.sleep(1_000)

        // Phase 1: echo leak.
        val quiet = WindowProbe()
        engine.resetStream()
        engine.setProbe(quiet)
        val first = host.newPlayer()
        first.start()
        first.awaitEnd(PLAY_TIMEOUT_MS)
        Thread.sleep(800)
        engine.setProbe(null)
        val heard = quiet.words.toList() + engine.currentPartialWords()
        first.release()
        val (echoWords, inPassage) = EchoMaths.echoWords(heard, Playback.PASSAGE)
        val quietFrames = quiet.meter.toArray()

        if (host.cancelled()) return result(source, aecEnabled, first, quiet, echoWords, inPassage, quietFrames, null, slowBefore, engine)
        host.status("${source.label}: get ready. Talk over the voice the moment TALK NOW shows.")
        Thread.sleep(1_500)

        // Phase 2: cut-in.
        val second = host.newPlayer()
        var stopMs: Double? = null
        val cut = CutInProbe { stopMs = second.stop() }
        engine.resetStream()
        engine.setProbe(cut)
        val playStart = engine.samplesCaptured
        second.start()
        Thread.sleep(Random.nextLong(3_000, 6_000))
        val prompt = engine.samplesCaptured
        val busy = engine.speechNow
        cut.promptSample = prompt
        host.status("TALK NOW", talkNow = true)
        val detected = cut.fired.await(CUT_IN_WAIT_MS, TimeUnit.MILLISECONDS)
        if (!detected) second.stop()
        second.awaitEnd(1_000)
        engine.setProbe(null)
        second.release()
        host.status("${source.label}: done.")

        val cutIn = if (detected && cut.baseSample >= 0) {
            val frames = cut.meter.toArray()
            fun frameOf(sample: Long) = ((sample - cut.baseSample) / FRAME).toInt().coerceIn(0, maxOf(0, frames.size - 1))
            val promptFrame = frameOf(prompt)
            val baseline = EchoMaths.median(frames, frameOf(playStart) + SETTLE_FRAMES, promptFrame)
                ?: EchoMaths.median(frames, 0, promptFrame) ?: Dbfs.FLOOR
            val onset = EchoMaths.onsetFrame(frames, frameOf(cut.fireSample), maxOf(0, promptFrame - SETTLE_FRAMES), baseline)
            CutIn(
                ms = onset?.let { EchoMaths.latencyMs(it, FRAME, cut.fireSample - cut.baseSample, Sherpa.SAMPLE_RATE) },
                promptToVadMs = (cut.fireSample - prompt) * 1000.0 / Sherpa.SAMPLE_RATE,
                stopMs = stopMs,
                busy = busy,
            )
        } else {
            CutIn(null, null, null, busy)
        }
        return result(source, aecEnabled, first, quiet, echoWords, inPassage, quietFrames, cutIn, slowBefore, engine)
    }

    private data class CutIn(val ms: Double?, val promptToVadMs: Double?, val stopMs: Double?, val busy: Boolean)

    private fun result(
        source: CaptureSource,
        aecEnabled: Boolean,
        player: Player,
        quiet: WindowProbe,
        echoWords: Int,
        inPassage: Int,
        quietFrames: DoubleArray,
        cutIn: CutIn?,
        slowBefore: Int,
        engine: CaptureEngine,
    ) = EchoTrial(
        source = source.name,
        callMode = host.callMode,
        aecEnabled = aecEnabled,
        playbackPath = player.path,
        playbackDbfs = player.dbfs,
        voiceCallVolume = host.volumeFraction(),
        echoInputDbfsMedian = EchoMaths.median(quietFrames, 0, quietFrames.size),
        echoWords = echoWords,
        echoWordsInPassage = inPassage,
        vadFalseSpeechRatio = EchoMaths.ratio(quiet.speechChunks, quiet.chunks),
        vadBusyAtPrompt = cutIn?.busy ?: false,
        cutInDetected = cutIn?.promptToVadMs != null,
        cutInMs = cutIn?.ms,
        promptToVadMs = cutIn?.promptToVadMs,
        stopPlaybackMs = cutIn?.stopMs,
        slowChunks = engine.state.value.slowChunks - slowBefore,
    )

    private companion object {
        const val FRAME = 160 // 10 ms at 16 kHz
        const val SETTLE_FRAMES = 50 // 0.5 s
        const val PLAY_TIMEOUT_MS = 45_000L
        const val CUT_IN_WAIT_MS = 10_000L
    }
}
