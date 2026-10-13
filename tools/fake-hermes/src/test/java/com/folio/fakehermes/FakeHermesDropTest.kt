package com.folio.fakehermes

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.GatewayConnection
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.GatewaySessionRecovery
import com.folio.hermes.cascade.registry.SessionProbe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Socket drops, stalls and refused handshakes, with the client resuming its sessions. */
class FakeHermesDropTest {
    private val h = Harness().apply { signIn() }

    @After fun tearDown() = h.close()

    private fun upgrades() = h.fake.get("/__fake/received?kind=http").arr("received").count { it.str("path") == "/api/ws" }

    @Test fun `a dropped socket reconnects and the turn carries on after session resume`() = runBlocking<Unit> {
        for (mode in listOf("abort", "close")) {
            val conv = h.conversation()
            val updates = async {
                conv.turn("slow job $mode").onEach {
                    if (it is TurnUpdate.Started) h.fake.post("/__fake/drop", """{"mode": "$mode"}""")
                }.untilDone()
            }
            assertEquals("Slow job finished for: slow job $mode", updates.await().completed().text)
            val resumes = h.fake.get("/__fake/received?kind=resume&session=${conv.storedSessionId}").arr("received")
            assertEquals(mode, 1, resumes.size)
            assertEquals(true, resumes.single().bool("omit_messages"))
        }
        assertTrue(upgrades() >= 3)
    }

    @Test fun `events after a drop reach only sockets that resumed`() = runBlocking<Unit> {
        val conv = h.conversation()
        val sid = conv.ensureSession()
        h.gateway.submitPrompt(sid, "slow job unseen")
        val before = (h.gateway.connection.value as GatewayConnection.Ready).generation
        h.fake.post("/__fake/drop")
        withTimeout(5_000) { h.gateway.connection.first { it is GatewayConnection.Ready && it.generation > before } }
        // Nobody resumed: the session has no viewer and its events are dropped.
        val session = h.session(conv.storedSessionId!!)
        assertTrue(session.arr("viewers").isEmpty())
        val probe = GatewaySessionRecovery(h.gateway, h.config).probe("t", conv.storedSessionId!!) as SessionProbe.Running
        assertEquals("Slow job finished for: slow job unseen", probe.updates.untilDone().completed().text)
    }

    @Test fun `a stall trips the heartbeat, then the client reconnects`() = runBlocking<Unit> {
        val conv = h.conversation()
        val updates = async {
            conv.turn("slow job stalled").onEach {
                if (it is TurnUpdate.Started) h.fake.post("/__fake/stall", """{"ms": 1500}""")
            }.untilDone()
        }
        val last = updates.await().last()
        // Either the turn was followed to its end, or it ended inside the gap and the probe reads it.
        val text = when (last) {
            is TurnUpdate.Completed -> last.text
            else -> (GatewaySessionRecovery(h.gateway, h.config).probe("t", conv.storedSessionId!!) as SessionProbe.Ended).text
        }
        assertEquals("Slow job finished for: slow job stalled", text)
        assertTrue(upgrades() >= 2)
    }

    @Test fun `an open approval is delivered again after a drop and resume`() = runBlocking<Unit> {
        val conv = h.conversation()
        val asked = AtomicInteger()
        val updates = conv.turn("send the email now").onEach { u ->
            if (u is TurnUpdate.ApprovalNeeded) {
                if (asked.incrementAndGet() == 1) {
                    h.fake.post("/__fake/drop")
                } else {
                    launch { h.gateway.answerApproval(u.request, ApprovalChoice.Session) }
                }
            }
        }.untilDone()
        assertEquals(2, asked.get())
        assertEquals("Sent (session).", updates.completed().text)
    }

    @Test fun `refused tickets and upgrades back off, then recover`() = runBlocking<Unit> {
        h.faults("""{"refuse_tickets": 503}""")
        h.gateway.connect()
        withTimeout(5_000) { h.gateway.connection.first { it is GatewayConnection.Waiting } }
        h.fake.admin("DELETE", "/__fake/faults")
        withTimeout(5_000) { h.gateway.connection.first { it is GatewayConnection.Ready } }
        h.gateway.disconnect()
        h.faults("""{"refuse_upgrade": true}""")
        h.gateway.connect()
        val stopped = withTimeout(10_000) { h.gateway.connection.first { it is GatewayConnection.Stopped } } as GatewayConnection.Stopped
        assertTrue(stopped.reason, stopped.reason.contains("403"))
    }
}
