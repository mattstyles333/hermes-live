package com.folio.fakehermes

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.AuthState
import com.folio.hermes.BusyMode
import com.folio.hermes.GatewayConnection
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesRpcException
import com.folio.hermes.PromptSurface
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.live.VoiceLiveApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Login, a full prompt with tool steps, approval and clarify round trips, failures. */
class FakeHermesTurnTest {
    private val h = Harness()

    @After fun tearDown() = h.close()

    @Test fun `login, ready and voice status`() = runBlocking<Unit> {
        val tokens = h.auth.signIn(FakeHermesProcess.USER, FakeHermesProcess.PASSWORD)
        assertTrue(h.auth.state.value is AuthState.SignedIn)
        assertTrue(tokens.refreshToken.isNotEmpty())
        val ready = h.gateway.awaitReady()
        assertTrue(ready.replayEpoch.isNotEmpty())
        val status = VoiceLiveApi(h.config, h.auth, h.fake.client).status()!!
        assertTrue(status.ok)
        assertFalse(status.available)
        assertEquals("gpt-live", status.mode)
        val noAuth = h.fake.client.newCall(Request.Builder().url("${h.fake.baseUrl}/api/audio/voice-live/status").build())
            .execute().use { it.code }
        assertEquals(401, noAuth)
    }

    @Test fun `refused login and refresh`() = runBlocking<Unit> {
        h.faults("""{"refuse_logins": true}""")
        try {
            h.auth.signIn(FakeHermesProcess.USER, FakeHermesProcess.PASSWORD)
            fail("login should be refused")
        } catch (e: HermesAuthException.InvalidCredentials) {
            // expected
        }
        h.fake.admin("DELETE", "/__fake/faults")
        // A 60 s access token is inside the client's 5 min margin: the next use refreshes it.
        h.fake.post("/__fake/config", """{"access_ttl_s": 60}""")
        val first = h.auth.signIn(FakeHermesProcess.USER, FakeHermesProcess.PASSWORD)
        val renewed = h.auth.accessToken()
        assertNotEquals(first.accessToken, renewed)
        val refreshes = h.fake.get("/__fake/received?kind=http").arr("received").count { it.str("path") == "/auth/native/refresh" }
        assertEquals(1, refreshes)
        h.gateway.awaitReady() // the refreshed token gets a ticket
    }

    @Test fun `a prompt streams tool steps, interim text and the reply`() = runBlocking<Unit> {
        h.signIn()
        val conv = h.conversation()
        val updates = conv.turn("check stock of the sample frame", PromptSurface.VoiceLive, "User: check stock\n").untilDone()
        assertEquals(SubmitStatus.Streaming, (updates.first() as TurnUpdate.Accepted).status)
        assertTrue(updates.contains(TurnUpdate.Started))
        assertEquals(listOf("s4l_ops_get_sku", "s4l_ops_get_latest_stock_comparison"), updates.filterIsInstance<TurnUpdate.ToolStarted>().map { it.name })
        assertEquals(2, updates.filterIsInstance<TurnUpdate.ToolCompleted>().size)
        assertEquals("Checking the shelves.", updates.filterIsInstance<TurnUpdate.Interim>().single().text)
        assertTrue(updates.filterIsInstance<TurnUpdate.Text>().size > 1)
        val done = updates.completed()
        assertEquals("Sample Frame TEST-0001 has 14 in stock.", done.text)
        assertEquals(TurnStatus.Complete, done.status)
        val prompt = h.prompts(conv.storedSessionId!!).single()
        assertEquals("check stock of the sample frame", prompt.str("text"))
        assertEquals("voice-live", prompt.str("surface"))
        assertEquals("streaming", prompt.str("outcome"))
        assertTrue(prompt.int("voice_context_chars")!! > 0)
    }

    @Test fun `approval round trip, both answers`() = runBlocking<Unit> {
        h.signIn()
        for ((choice, reply) in listOf(ApprovalChoice.Once to "Sent (once).", ApprovalChoice.Deny to "Kept it as a draft.")) {
            val conv = h.conversation()
            val updates = conv.turn("please send the email").onEach { u ->
                if (u is TurnUpdate.ApprovalNeeded) {
                    assertTrue(u.request.id.startsWith("srq-"))
                    assertEquals(conv.storedSessionId, h.sessions().first { it.str("session_id") == u.request.sessionId }.str("stored_session_id"))
                    assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always, ApprovalChoice.Deny), u.request.choices)
                    launch { h.gateway.answerApproval(u.request, choice) }
                }
            }.untilDone()
            assertEquals(reply, updates.completed().text)
        }
    }

    @Test fun `clarify round trip`() = runBlocking<Unit> {
        h.signIn()
        val conv = h.conversation()
        val updates = conv.turn("chase the supplier about the order").onEach { u ->
            if (u is TurnUpdate.ClarifyNeeded) {
                assertEquals("channel", u.request.questions.single().qid)
                launch { h.gateway.answerClarify(u.request, mapOf("channel" to "email")) }
            }
        }.untilDone()
        assertEquals("Chased by email.", updates.completed().text)
    }

    @Test fun `a failing turn ends with status error`() = runBlocking<Unit> {
        h.signIn()
        val done = h.conversation().turn("fail on purpose").untilDone().completed()
        assertEquals(TurnStatus.Error, done.status)
        assertEquals("The tool crashed (fake failure).", done.error)
    }

    @Test fun `unknown params are refused like the real server (4000)`() = runBlocking<Unit> {
        h.signIn()
        val conv = h.conversation()
        val sid = conv.ensureSession()
        try {
            h.gateway.call("prompt.submit", buildJsonObject { put("session_id", sid); put("text", "x"); put("bogus", 1) })
            fail("expected 4000")
        } catch (e: HermesRpcException) {
            assertEquals(4000, e.code)
        }
        try {
            h.gateway.call("prompt.submit", buildJsonObject { put("session_id", "nope"); put("text", "x") })
            fail("expected 4001")
        } catch (e: HermesRpcException) {
            assertTrue(e.isSessionNotFound)
        }
        assertTrue(h.gateway.connection.value is GatewayConnection.Ready)
    }

    @Test fun `config get busy is read, never set`() = runBlocking<Unit> {
        h.signIn()
        assertEquals(BusyMode.Interrupt, h.gateway.busyMode())
        h.fake.post("/__fake/config", """{"busy_mode": "queue"}""")
        h.gateway.disconnect()
        assertEquals(BusyMode.Queue, h.gateway.busyMode())
        val sets = h.fake.get("/__fake/received?kind=rpc").arr("received").count { it.str("method")?.startsWith("config.set") == true }
        assertEquals(0, sets)
        Unit
    }
}
