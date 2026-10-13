package com.folio.hermeslive.stt

import com.folio.hermes.cascade.ports.SttEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSpeechToTextTest {
    private fun engines() = SttEngines(FakeVad(), FakeStreaming(), FakeOffline())

    @Test fun offUntilStarted() {
        val source = FramesSource(frames(10))
        val stt = SherpaSpeechToText(engines(), source)
        assertEquals(0, source.opens)
        assertFalse(stt.isRunning)
        stt.stop()
        assertEquals(0, source.opens)
    }

    @Test fun capturesThroughThePipelineAndStopsAtTheEndOfInput() = runBlocking {
        val source = FramesSource(List(5) { silence() } + frames(12) + List(6) { silence() })
        val stt = SherpaSpeechToText(engines(), source)
        stt.start()
        val events = withTimeout(5_000) { stt.events.take(3).toList() }
        assertEquals(SttEvent.SpeechStarted, events.first())
        withTimeout(5_000) { stt.events.first { it is SttEvent.SpeechEnded } }
        withTimeout(5_000) { while (stt.isRunning) kotlinx.coroutines.delay(5) }
        assertEquals(1, source.opens)
        assertTrue(stt.samplesProcessed >= 23L * F)
        assertEquals(null, stt.failure)
    }

    @Test fun reCutsOddReadSizesIntoVadFrames() = runBlocking {
        val vad = FakeVad()
        val odd = object : com.folio.hermeslive.audio.AudioSource {
            var left = 10 * F
            override var onRouteChanged: (() -> Unit)? = null
            override fun open() = Unit
            override fun read(buffer: ShortArray): Int {
                if (left <= 0) return -1
                val n = minOf(300, left)
                left -= n
                return n
            }
            override fun close() = Unit
        }
        val stt = SherpaSpeechToText(SttEngines(vad, FakeStreaming(), null), odd)
        stt.start()
        withTimeout(5_000) { while (stt.isRunning) kotlinx.coroutines.delay(5) }
        assertEquals(10, vad.frames)
    }
}
