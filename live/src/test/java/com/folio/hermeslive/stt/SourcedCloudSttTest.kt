package com.folio.hermeslive.stt

import com.folio.hermes.live.AudioDecoder
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.data.FakeHermesVoiceServer
import com.folio.hermeslive.data.HermesVoices
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SourceChoice
import com.folio.hermeslive.data.SpeechRecognition
import com.folio.hermeslive.data.SttRoute
import com.folio.hermeslive.data.TsNetLoopback
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cloud pass from Hermes or OpenRouter (Hermes voice slice): fallbacks within the same budget, never a longer wait. */
class SourcedCloudSttTest {
    private val fake = FakeHermesVoiceServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var settings = LiveSettings(speechRecognition = SpeechRecognition.Cloud)
    private var signedIn = true
    private val openRouterCalls = AtomicInteger()
    private var now = 0L
    private val hermes = HermesVoices(
        account = { if (signedIn) fake.account() else null },
        client = OkHttpClient.Builder().dns(TsNetLoopback).build(),
        decoder = { AudioDecoder.None },
        telemetry = TelemetrySink.None,
        wanted = { signedIn && settings.sttFrom.prefersHermes },
        scope = scope,
    )

    private fun stt(key: Boolean = true, online: Boolean = true) = SourcedCloudStt(
        settings = { settings },
        hermes = hermes,
        openRouter = CloudStt { _, _, _, _ -> openRouterCalls.incrementAndGet(); "from openrouter" },
        openRouterUsable = { key },
        online = { online },
        clock = { now },
    )

    @After fun tearDown() {
        scope.cancel()
        fake.close()
    }

    @Test fun hermesFirst() = runBlocking {
        val s = stt()
        assertEquals("hello from hermes", s.transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        assertEquals(SttRoute.HermesRelay, s.route())
        assertEquals(0, openRouterCalls.get())
    }

    @Test fun noSpeechFromHermesIsNullAndNoSecondUpload() = runBlocking {
        fake.transcript = ""
        assertNull(stt().transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        assertEquals(0, openRouterCalls.get())
    }

    @Test fun aHermesFailureFallsBackOnlyWithTimeLeft() = runBlocking {
        fake.hermesCode = 500
        hermes.refresh() // the config fetch fails; the relay is tried as soon as it answers again
        fake.hermesCode = 200
        hermes.refresh()
        fake.hermesCode = 500
        assertEquals("from openrouter", stt().transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        val late = SourcedCloudStt({ settings }, hermes, CloudStt { _, _, _, _ -> openRouterCalls.incrementAndGet(); "x" }, { true }, { true }, clock = { now.also { now += 3_500 } })
        assertNull("too little time left: the on-device text stands", late.transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        assertEquals(1, openRouterCalls.get())
    }

    @Test fun openRouterChosenNeverAsksHermes() = runBlocking {
        settings = settings.copy(sttFrom = SourceChoice.OpenRouter)
        assertEquals("from openrouter", stt().transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        assertEquals(0, fake.audioRequests)
    }

    @Test fun signedOutWithoutAKeyStaysOnTheDevice() = runBlocking {
        signedIn = false
        val s = stt(key = false)
        assertEquals(SttRoute.OnDevice, s.route())
        assertNull(s.transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        assertEquals(0, fake.audioRequests)
        assertEquals(0, openRouterCalls.get())
    }

    @Test fun offlineMakesNoRequest() = runBlocking {
        assertNull(stt(online = false).transcribe(byteArrayOf(1), "m/x", emptyList(), 4_000))
        assertEquals(0, fake.audioRequests)
        assertEquals(0, openRouterCalls.get())
    }
}
