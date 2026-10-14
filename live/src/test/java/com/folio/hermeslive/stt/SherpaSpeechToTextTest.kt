package com.folio.hermeslive.stt

import com.folio.hermes.cascade.ports.SttEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /**
     * The capture thread drained [closed]'s source and closed it (its last step), so the loop has
     * ended; [SherpaSpeechToText.stop] then joins the thread.
     */
    private fun SherpaSpeechToText.awaitEndOfInput(closed: CountDownLatch) {
        assertTrue("the source was closed", closed.await(5, TimeUnit.SECONDS))
        stop()
        assertFalse(isRunning)
    }

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
        withTimeout(5_000) { stt.events.first { it.isSpeechEnd } }
        stt.awaitEndOfInput(source.closed)
        assertEquals(1, source.opens)
        assertTrue(stt.samplesProcessed >= 23L * F)
        assertEquals(null, stt.failure)
    }

    @Test fun reCutsOddReadSizesIntoVadFrames() = runBlocking {
        val vad = FakeVad()
        val closed = CountDownLatch(1)
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
            override fun close() = closed.countDown()
        }
        val stt = SherpaSpeechToText(SttEngines(vad, FakeStreaming(), null), odd)
        stt.start()
        stt.awaitEndOfInput(closed)
        assertEquals(10, vad.frames)
    }
}
