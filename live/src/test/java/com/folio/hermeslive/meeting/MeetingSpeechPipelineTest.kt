package com.folio.hermeslive.meeting

import com.folio.hermes.cascade.ports.Clock
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.stt.FakeOffline
import com.folio.hermeslive.stt.FakeStreaming
import com.folio.hermeslive.stt.FakeVad
import com.folio.hermeslive.stt.SherpaSpeechToText
import com.folio.hermeslive.stt.SttEngines
import com.folio.hermeslive.stt.frames
import com.folio.hermeslive.stt.silence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Meeting mode on the call's real speech pipeline ([SherpaSpeechToText] with fake engines): Pause
 * takes the accurate pass before the mic stops (stopping drops held audio), Resume starts the same
 * loaded pipeline again, and segment numbers carry on with nothing dropped.
 */
class MeetingSpeechPipelineTest {
    /** Frames fed by the test; the capture thread waits for them (never ends on its own). */
    private class QueueSource : AudioSource {
        private val queue = LinkedBlockingQueue<FloatArray>()
        var opens = 0
        val closes = Semaphore(0)
        override var onRouteChanged: (() -> Unit)? = null
        override fun open() {
            opens++
        }
        fun feed(f: List<FloatArray>) = queue.addAll(f)
        override fun read(buffer: ShortArray): Int {
            val f = queue.poll(20, TimeUnit.MILLISECONDS) ?: return 0
            for (k in buffer.indices) buffer[k] = (f[k] * 32768f).toInt().toShort()
            return buffer.size
        }
        override fun close() = closes.release()
    }

    private fun utterance() = List(5) { silence() } + frames(12) + List(6) { silence() }

    @Test fun pauseFlushesThenResumeCarriesOnInTheSameTranscript() = runBlocking {
        val decoded = arrayOf(CountDownLatch(1), CountDownLatch(2))
        var n = 0
        val offline = FakeOffline {
            n++
            decoded.forEach { it.countDown() }
            "accurate words $n"
        }
        val source = QueueSource()
        val stt = SherpaSpeechToText(SttEngines(FakeVad(), FakeStreaming(), offline), source)
        val speech = SherpaMeetingSpeech(stt)
        val log = memoryLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = MeetingSession(log, speech, Clock.System, scope, MeetingMeta("m1", startedAt = System.currentTimeMillis()))
            session.begin("Pipeline")
            source.feed(utterance())
            withTimeout(10_000) { session.live.first { it.segments == 1 } }
            assertTrue("decoded", decoded[0].await(10, TimeUnit.SECONDS))
            session.pause()
            assertTrue("the mic closed", source.closes.tryAcquire(10, TimeUnit.SECONDS))
            val first = log.transcript("m1").segments.single()
            assertEquals("accurate", first.pass)
            assertEquals("Accurate words 1", first.text.replaceFirstChar { it.uppercaseChar() })

            session.record()
            assertEquals(2, source.opens)
            source.feed(utterance())
            withTimeout(10_000) { session.live.first { it.segments == 2 } }
            assertTrue("decoded", decoded[1].await(10, TimeUnit.SECONDS))
            session.end()
            val t = log.transcript("m1")
            assertEquals(listOf(1, 2), t.segments.map { it.n })
            assertEquals(listOf("accurate", "accurate"), t.segments.map { it.pass })
            assertEquals(listOf("pause", "resume"), t.gaps.map { it.why })
        } finally {
            speech.release()
            scope.cancel()
        }
    }
}
