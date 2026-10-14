package com.folio.hermes.cascade.ports

import com.folio.hermes.FakeHermesServer
import com.folio.hermes.FakeSessions
import com.folio.hermes.FakeTokens
import com.folio.hermes.HermesGateway
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.eventually
import com.folio.hermes.cascade.registry.SessionProbe
import com.folio.hermes.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v2 §14 items 2 and 3 on a real gateway against [FakeSessions] (the deployed server's
 * session shapes): the session is created with no prompt, and the probe reads the transcript
 * with `session.resume` (messages on) to tell never-prompted, running and ended apart. Read only:
 * the probe never submits or creates.
 */
class GatewaySessionRecoveryTest {
    private val fake = FakeHermesServer()
    private val sessions = FakeSessions(fake)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = fake.config()
    private val gateway = HermesGateway(config, FakeTokens(), scope = scope)
    private val port = GatewayHermesPort(gateway, config)
    private val recovery = GatewaySessionRecovery(gateway, config)

    @After fun tearDown() {
        gateway.disconnect()
        scope.cancel()
        fake.close()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(15_000) { block() } }

    private fun calls(method: String): List<JsonObject> = sessions.socket?.frames.orEmpty().filter { it.string("method") == method }

    @Test fun createMakesTheSessionWithNoPromptAndTheSubmitUsesIt() = test {
        val stored = port.create("t1", "stock check")
        assertEquals("stored-1", stored)
        assertEquals("Voice: stock check", (calls("session.create").single()["params"] as JsonObject).string("title"))
        assertTrue("no prompt yet", calls("prompt.submit").isEmpty())
        val first = port.submit("t1", "check RB2140", "", queued = false, interruptFirst = false).first()
        assertEquals(TurnUpdate.Accepted("rt1", SubmitStatus.Streaming), first)
        assertEquals("one session, prompted once", 1, calls("session.create").size)
        assertEquals(listOf("user" to "check RB2140"), sessions.session("stored-1").messages.toList())
    }

    @Test fun createAgainReplacesTheTasksSession() = test {
        port.open("t1", "stock check", "stored-old")
        assertEquals("stored-1", port.create("t1", "stock check"))
        val first = port.submit("t1", "check RB2140", "", queued = false, interruptFirst = false).first()
        assertEquals(TurnUpdate.Accepted("rt1", SubmitStatus.Streaming), first)
        assertTrue("never resumes the stale id", sessions.resumes.none { it.first == "stored-old" })
    }

    @Test fun aCreatedSessionWithNoPromptIsNeverPrompted() = test {
        val stored = port.create("t1", "job")!!
        assertEquals(SessionProbe.NeverPrompted, recovery.probe("t1", stored))
        assertEquals("messages asked for", listOf(stored to false), sessions.resumes.toList())
    }

    @Test fun anUnknownStoredIdIsNeverPromptedBecausePromptSubmitStoresTheRow() = test {
        val stored = port.create("t1", "job")!!
        sessions.session(stored).live = false // reaped after the app died; create stored no row
        assertEquals(SessionProbe.NeverPrompted, recovery.probe("t1", stored))
    }

    @Test fun anEndedTurnGivesItsReplyAndThePrompts() = test {
        val stored = port.create("t1", "job")!!
        val flow = scope.async { port.submit("t1", "check RB2140", "", queued = false, interruptFirst = false).toList() }
        eventually(fake.activity, what = "running") { sessions.session(stored).running }
        sessions.complete(stored, "Fourteen in stock.")
        flow.await()
        val probe = recovery.probe("t1", stored)
        assertEquals(SessionProbe.Ended("Fourteen in stock.", TurnStatus.Complete, listOf("check RB2140")), probe)
        assertTrue("read only", calls("prompt.submit").size == 1 && calls("session.create").size == 1)
    }

    @Test fun aTurnThatEndedWithNoReplyIsAnError() = test {
        val stored = port.create("t1", "job")!!
        sessions.session(stored).apply {
            persisted = true
            messages += "user" to "check RB2140"
        }
        val probe = recovery.probe("t1", stored) as SessionProbe.Ended
        assertEquals(TurnStatus.Error, probe.status)
        assertEquals("", probe.text)
    }

    @Test fun aRunningTurnIsFollowedToItsEndAndAQueuedPromptCounts() = test {
        val stored = port.create("t1", "job")!!
        scope.async { port.submit("t1", "check RB2140", "", queued = false, interruptFirst = false).toList() }
        eventually(fake.activity, what = "running") { sessions.session(stored).running }
        sessions.session(stored).queued = "and the Leeds store"
        val probe = recovery.probe("t1", stored) as SessionProbe.Running
        assertTrue("check RB2140" in probe.prompts!!)
        assertTrue("held, not yet a message", "and the Leeds store" in probe.prompts!!)
        val followed = async { probe.updates.toList() }
        eventually(fake.activity, what = "follow subscribed") { sessions.resumes.size >= 2 }
        sessions.complete(stored, "Fourteen in stock.")
        assertEquals(TurnUpdate.Completed("Fourteen in stock.", TurnStatus.Complete, null), followed.await().last())
    }

    @Test fun aTurnThatEndsBetweenProbeAndFollowIsReadFromTheTranscript() = test {
        val stored = port.create("t1", "job")!!
        scope.async { port.submit("t1", "check RB2140", "", queued = false, interruptFirst = false).toList() }
        eventually(fake.activity, what = "running") { sessions.session(stored).running }
        val probe = recovery.probe("t1", stored) as SessionProbe.Running
        // It ends before anyone follows: no event is waited for, the transcript has the reply.
        sessions.session(stored).apply {
            messages += "assistant" to "Done already."
            running = false
        }
        assertEquals(listOf(TurnUpdate.Completed("Done already.", TurnStatus.Complete, null)), probe.updates.toList())
    }

    @Test fun anyOtherErrorIsUnknown() = test {
        fake.responders["session.resume"] = { s, id, _ -> s.error(id, 5000, "db unavailable") }
        assertEquals(SessionProbe.Unknown, recovery.probe("t1", "stored-9"))
        assertNull(SessionProbe.Unknown.prompts)
    }

    @Test fun plainResumeStillOmitsMessages() = test {
        val stored = port.create("t1", "job")!!
        sessions.session(stored).persisted = true
        val resumed = gateway.resumeSession(stored)
        assertEquals(listOf(stored to true), sessions.resumes.toList())
        assertTrue(resumed.messages.isEmpty())
    }
}
