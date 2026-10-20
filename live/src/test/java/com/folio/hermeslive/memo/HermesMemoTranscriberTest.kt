package com.folio.hermeslive.memo

import com.folio.hermes.live.DirectVoiceWires
import com.folio.hermes.live.HermesVoice
import com.folio.hermes.live.HermesVoiceApi
import com.folio.hermeslive.data.FakeDecoder
import com.folio.hermeslive.data.FakeHermesVoiceServer
import com.folio.hermeslive.data.TsNetLoopback
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A memo chunk through Hermes's relay against the fake server: text, and each failure as its hold reason. */
class HermesMemoTranscriberTest {
    private val fake = FakeHermesVoiceServer()
    private val client = OkHttpClient.Builder().dns(TsNetLoopback).build()

    @After fun tearDown() = fake.close()

    private fun voice(): HermesVoice {
        val (config, tokens) = fake.account()
        return HermesVoice(HermesVoiceApi(config, tokens, client, reachable = { true }), DirectVoiceWires(client), FakeDecoder)
    }

    private fun transcribe(v: HermesVoice? = voice()) = runBlocking { HermesMemoTranscriber { v }.transcribe(ByteArray(3_244), 5_000) }

    @Test fun theRelayTranscribes() {
        fake.transcript = "call the bank"
        assertEquals(MemoText.Text("call the bank"), transcribe())
        assertEquals(1, fake.transcribeCalls.get())
    }

    @Test fun signedOutAsksNothing() {
        assertEquals(MemoText.Hold("signed_out"), transcribe(null))
        assertEquals(0, fake.audioRequests)
    }

    @Test fun anOlderServerIsServerOld() {
        fake.transcribeCode = 404
        assertEquals(MemoText.Hold("server_old"), transcribe())
        fake.transcribeCode = 200
        fake.configCode = 404
        assertEquals("no voice-config route either", MemoText.Hold("server_old"), transcribe())
    }

    @Test fun tooLargeIsFinal() {
        fake.transcribeCode = 413
        assertEquals(MemoText.TooLarge, transcribe())
    }

    @Test fun aServerErrorIsHeldWithItsKind() {
        fake.transcribeCode = 502
        assertEquals(MemoText.Hold("http"), transcribe())
    }

    @Test fun unreachableIsOffline() {
        val v = voice()
        fake.close()
        assertEquals(MemoText.Hold("offline"), transcribe(v))
    }
}
