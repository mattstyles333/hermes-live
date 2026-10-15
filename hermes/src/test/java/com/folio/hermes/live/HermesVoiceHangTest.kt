package com.folio.hermes.live

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeTokens
import com.folio.hermes.json
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Hermes that hangs (a tailnet host that is down answers nothing): no line waits on its config
 * fetch, whether one is in flight or was cut short by the call start's bound.
 */
class HermesVoiceHangTest {
    private val hermes = FakeHermesServer()
    private val asked = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private var requests = 0

    init {
        hermes.routes[HermesVoiceApi.PATH_CONFIG] = {
            synchronized(this) { requests++ }
            asked.countDown()
            release.await(30, TimeUnit.SECONDS)
            json(200, FakeHermesVoice.relayConfig())
        }
    }

    private val voice = HermesVoice(
        HermesVoiceApi(hermes.config(), FakeTokens()), DirectVoiceWires(), AudioDecoder.None, TelemetrySink.None,
        configTimeoutMs = 20_000,
    )

    @After fun tearDown() {
        release.countDown()
        hermes.close()
    }

    @Test fun aLineNeverWaitsOnAFetchInFlightOrCutShort() = runBlocking {
        // The app start's fetch is in flight and hangs.
        val appStart = async(Dispatchers.IO) { voice.config(force = true) }
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        assertEquals(HermesVoiceState.Fetching, voice.state.value)
        val t0 = System.nanoTime()
        assertTrue(voice.speak("Got it.", null) is OpenRouterResult.Failed)
        assertTrue(voice.transcribe(byteArrayOf(1), emptyList(), 4_000) is OpenRouterResult.Failed)
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("a line waited $waited ms", waited < 1_000)

        // The call start's bounded refresh gives up; the app start's is cut short too (as by its scope).
        assertNull(withTimeoutOrNull(300) { voice.config(force = true) })
        appStart.cancel()
        appStart.join()
        assertEquals(HermesVoiceState.Unavailable("timeout"), voice.state.value)
        // A cut-short attempt counts: unforced asks within a minute make no request and do not wait.
        val before = synchronized(this@HermesVoiceHangTest) { requests }
        assertNull(voice.config())
        assertEquals(before, synchronized(this@HermesVoiceHangTest) { requests })
    }
}
