package com.folio.hermes.live

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeTokens
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An unreachable Hermes fails each line at once for a minute, so the fallback voice never waits per line. */
class HermesVoiceBreakerTest {
    @Test fun anUnreachableRelayIsSkippedForAMinute() = runBlocking {
        val hermes = FakeHermesServer()
        val fake = FakeHermesVoice(hermes)
        var now = 0L
        val voice = HermesVoice(HermesVoiceApi(hermes.config(), FakeTokens(), nowMs = { now }), DirectVoiceWires(), AudioDecoder.None, TelemetrySink.None, nowMs = { now })
        voice.config()
        hermes.server.close()
        assertEquals(FailureKind.Offline, (voice.speak("a", null) as OpenRouterResult.Failed).kind)
        assertEquals(OpenRouterResult.Failed(FailureKind.Offline), voice.speak("b", null))
        assertEquals(OpenRouterResult.Failed(FailureKind.Offline), voice.transcribe(byteArrayOf(1), emptyList(), 1_000).let { if (it is OpenRouterResult.Failed) OpenRouterResult.Failed(it.kind) else it })
        now += HermesVoice.DOWN_MS + 1
        assertTrue(voice.speak("c", null) is OpenRouterResult.Failed)
        assertEquals(1, fake.configCalls.get())
    }

    /** Offline-memo slice: the network back, the app resets the breaker so a held memo's chunk is tried at once. */
    @Test fun resetBreakersTriesAgainAtOnce() = runBlocking {
        val hermes = FakeHermesServer()
        FakeHermesVoice(hermes)
        val stt = java.util.concurrent.atomic.AtomicInteger()
        val sink = TelemetrySink { name, _, _ -> if (name == HermesVoice.EVENT_STT) stt.incrementAndGet() }
        val voice = HermesVoice(HermesVoiceApi(hermes.config(), FakeTokens()), DirectVoiceWires(), AudioDecoder.None, sink)
        voice.config()
        hermes.server.close()
        voice.transcribe(byteArrayOf(1), emptyList(), 1_000)
        assertEquals(1, stt.get())
        voice.transcribe(byteArrayOf(1), emptyList(), 1_000)
        assertEquals("the breaker skips it", 1, stt.get())
        voice.resetBreakers()
        voice.transcribe(byteArrayOf(1), emptyList(), 1_000)
        assertEquals("tried again", 2, stt.get())
    }
}
