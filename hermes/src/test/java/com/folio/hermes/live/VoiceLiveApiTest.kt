package com.folio.hermes.live

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.config
import com.folio.hermes.json
import com.folio.hermes.jsonBody
import com.folio.hermes.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class VoiceLiveApiTest {
    private val server = MockWebServer()
    private val refreshed = mutableListOf<String>()
    private val tokens = object : AccessTokenProvider {
        var token = "at-1"
        override suspend fun accessToken() = token
        override suspend fun refreshAfterUnauthorized(rejected: String): String {
            refreshed += rejected
            token = "at-2"
            return token
        }
    }
    private lateinit var api: VoiceLiveApi

    @Before fun setUp() {
        server.start()
        api = VoiceLiveApi(server.config(), tokens)
    }

    @After fun tearDown() = server.close()

    @Test fun statusIsReadWithTheBearer() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"mode":"gpt-live","available":true,"reason":null,"model":"gpt-live-1","voice":"marin"}"""))
        val status = api.status()!!
        assertEquals(true, status.available)
        assertEquals("gpt-live-1", status.model)
        val request = server.takeRequest()
        assertEquals("/api/audio/voice-live/status", request.url.encodedPath)
        assertEquals("Bearer at-1", request.headers["Authorization"])
    }

    @Test fun statusOnOldServerIsNull() = runBlocking {
        server.enqueue(json(404, """{"detail":"Not Found"}"""))
        assertNull(api.status())
    }

    @Test fun sessionSendsTheOfferByteExactAndReturnsTheAnswer() = runBlocking {
        val offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\na=group:BUNDLE 0 1\r\n"
        server.enqueue(json(200, """{"ok":true,"session":{"id":"sess_1"},"transport":{"type":"webrtc","sdp":"v=0\r\nanswer\r\n"}}"""))
        val history = VoiceLiveApi.history(listOf(LiveTurn(Speaker.User, "hello")))
        val answer = api.createSession(offer, history)
        assertEquals(VoiceLiveAnswer("sess_1", "v=0\r\nanswer\r\n"), answer)
        val body = server.takeRequest().jsonBody()
        assertEquals(offer, body.string("sdp"))
        val item = body["history"]!!.jsonArray.single().jsonObject
        assertEquals("message", item.string("type"))
        assertEquals("user", item.string("role"))
        assertEquals("""[{"type":"input_text","text":"hello"}]""", item["content"].toString())
    }

    @Test fun errorsAreTyped() = runBlocking {
        for ((code, kind) in listOf(400 to VoiceLiveException.Kind.EmptyOffer, 503 to VoiceLiveException.Kind.NotConfigured, 502 to VoiceLiveException.Kind.VendorRejected)) {
            server.enqueue(json(code, """{"detail":"GPT-Live needs an OpenAI API key"}"""))
            try {
                api.createSession("v=0\r\n")
                fail()
            } catch (e: VoiceLiveException) {
                assertEquals(kind, e.kind)
                assertEquals(code, e.httpCode)
            }
        }
    }

    @Test fun unauthorizedRefreshesOnceThenRetries() = runBlocking {
        server.enqueue(json(401, """{"error":"session_expired","detail":"Unauthorized"}"""))
        server.enqueue(json(200, """{"ok":true,"session":{"id":"s"},"transport":{"type":"webrtc","sdp":"ans"}}"""))
        assertEquals("ans", api.createSession("v=0\r\n").sdpAnswer)
        assertEquals(listOf("at-1"), refreshed)
        assertEquals("Bearer at-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer at-2", server.takeRequest().headers["Authorization"])
    }
}
