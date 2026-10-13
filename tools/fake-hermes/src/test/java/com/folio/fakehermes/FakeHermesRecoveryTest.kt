package com.folio.fakehermes

import com.folio.hermes.HermesRpcException
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.GatewaySessionRecovery
import com.folio.hermes.cascade.registry.SessionProbe
import com.folio.hermes.isUnknownSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The session probe (`session.resume` with messages), 4007, lost submits, restarts, double submits. */
class FakeHermesRecoveryTest {
    private val h = Harness().apply { signIn() }
    private val recovery = GatewaySessionRecovery(h.gateway, h.config)

    @After fun tearDown() = h.close()

    @Test fun `probe reads never prompted, ended and running sessions`() = runBlocking<Unit> {
        val idle = h.conversation().apply { ensureSession() }
        assertEquals(SessionProbe.NeverPrompted, recovery.probe("t1", idle.storedSessionId!!))

        val done = h.conversation()
        done.turn("check stock of the sample frame").untilDone()
        val ended = recovery.probe("t2", done.storedSessionId!!) as SessionProbe.Ended
        assertEquals("Sample Frame TEST-0001 has 14 in stock.", ended.text)
        assertEquals(TurnStatus.Complete, ended.status)
        assertEquals(listOf("check stock of the sample frame"), ended.prompts)

        val busy = h.conversation()
        val sid = busy.ensureSession()
        h.gateway.submitPrompt(sid, "slow job probe")
        val running = recovery.probe("t3", busy.storedSessionId!!) as SessionProbe.Running
        // The stored user row and inflight.user both carry the live turn's text (as on the real server).
        assertEquals(listOf("slow job probe"), running.prompts!!.distinct())
        val updates = running.updates.untilDone()
        assertEquals("Slow job finished for: slow job probe", updates.completed().text)

        // The probe asked with messages, never omitted them.
        val resumes = h.fake.get("/__fake/received?kind=resume").arr("received")
        assertTrue(resumes.isNotEmpty() && resumes.all { it.bool("omit_messages") == false })
    }

    @Test fun `unknown session is 4007 and probes as never prompted`() = runBlocking<Unit> {
        try {
            h.gateway.resumeSession("20260101_000000_abcdef")
            fail("expected 4007")
        } catch (e: HermesRpcException) {
            assertTrue(e.isUnknownSession)
        }
        assertEquals(SessionProbe.NeverPrompted, recovery.probe("t", "20260101_000000_abcdef"))
    }

    @Test fun `a submit lost after the server stored it must not be resent`() = runBlocking<Unit> {
        h.faults("""{"lose_submits": {"count": 1, "mode": "after_store"}}""")
        val conv = h.conversation()
        val updates = conv.turn("check stock lost reply").untilDone()
        assertTrue(updates.last() is TurnUpdate.Failed)
        val probe = recovery.probe("t", conv.storedSessionId!!)
        assertTrue(probe.toString(), probe is SessionProbe.Running || probe is SessionProbe.Ended)
        assertEquals(listOf("check stock lost reply"), probe.prompts!!.distinct())
        val prompt = h.prompts(conv.storedSessionId!!).single()
        assertEquals("lost_after_store:streaming", prompt.str("outcome"))
        assertEquals(true, h.doubles().bool("ok"))
    }

    @Test fun `a submit lost before the server stored it is safe to resend`() = runBlocking<Unit> {
        h.faults("""{"lose_submits": {"count": 1, "mode": "before_store"}}""")
        val conv = h.conversation()
        assertTrue(conv.turn("check stock never arrived").untilDone().last() is TurnUpdate.Failed)
        assertEquals(SessionProbe.NeverPrompted, recovery.probe("t", conv.storedSessionId!!))
        assertEquals("Sample Frame TEST-0001 has 14 in stock.", conv.turn("check stock never arrived").untilDone().completed().text)
        val outcomes = h.prompts(conv.storedSessionId!!).map { it.str("outcome") }
        assertEquals(listOf("lost_before_store", "streaming"), outcomes)
        assertEquals(true, h.doubles().bool("ok"))
    }

    @Test fun `double submits are reported, in one session and across sessions`() = runBlocking<Unit> {
        val a = h.conversation()
        a.turn("order more cases").untilDone()
        a.turn("order more cases").untilDone()
        h.conversation().turn("order more cases").untilDone()
        val doubles = h.doubles()
        assertEquals(false, doubles.bool("ok"))
        assertEquals(2, doubles.arr("same_session").single().int("count"))
        assertEquals(2, doubles.arr("cross_session").single().let { (it["sessions"] as kotlinx.serialization.json.JsonArray).size })
    }

    @Test fun `restart keeps prompted sessions, forgets unprompted ones, mints new runtime ids`() = runBlocking<Unit> {
        val done = h.conversation()
        done.turn("check stock before restart").untilDone()
        val oldRuntime = h.session(done.storedSessionId!!).str("session_id")
        val idle = h.conversation().apply { ensureSession() }
        h.fake.post("/__fake/restart")

        assertEquals(SessionProbe.NeverPrompted, recovery.probe("t1", idle.storedSessionId!!))
        val ended = recovery.probe("t2", done.storedSessionId!!) as SessionProbe.Ended
        assertEquals("Sample Frame TEST-0001 has 14 in stock.", ended.text)
        assertNotEquals(oldRuntime, h.session(done.storedSessionId!!).str("session_id"))
        // The same conversation carries on: its old runtime id is gone, so it resumes the stored one.
        assertEquals("Done: and after the restart", done.turn("and after the restart").untilDone().completed().text)
        assertEquals(2, h.prompts(done.storedSessionId!!).size)
    }

    @Test fun `kill -9 and a new process on the same data dir`() = runBlocking<Unit> {
        val done = h.conversation()
        done.turn("check stock before the crash").untilDone()
        val running = h.conversation()
        h.gateway.submitPrompt(running.ensureSession(), "hang forever")
        h.fake.kill()
        h.fake.start()

        val ended = recovery.probe("t1", done.storedSessionId!!) as SessionProbe.Ended
        assertEquals("Sample Frame TEST-0001 has 14 in stock.", ended.text)
        // The hung turn died with the process: its prompt is stored, its reply is not.
        val dead = recovery.probe("t2", running.storedSessionId!!) as SessionProbe.Ended
        assertEquals(TurnStatus.Error, dead.status)
        assertEquals(listOf("hang forever"), dead.prompts)
        // The received log survived the crash.
        assertEquals(1, h.prompts(running.storedSessionId!!).size)
        assertFalse(h.fake.get("/__fake/health").str("boot").isNullOrEmpty())
    }
}
