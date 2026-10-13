package com.folio.hermes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayQueuedTest {
    private val fake = FakeHermesServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gateway = HermesGateway(fake.config(), FakeTokens(), scope = scope)

    @After fun tearDown() {
        gateway.disconnect()
        scope.cancel()
        fake.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test fun queuedIsSentOnlyWhenAsked() = test {
        fake.responders["prompt.submit"] = { s, id, _ -> s.reply(id, """{"status":"queued"}""") }
        gateway.submitPrompt("s1", "a")
        assertEquals(SubmitStatus.Queued, gateway.submitPrompt("s1", "b", queued = true).status)
        val socket = fake.nextSocket()
        val first = socket.nextCall("prompt.submit").obj("params")!!
        val second = socket.nextCall("prompt.submit").obj("params")!!
        assertNull(first["queued"])
        assertEquals(true, second.bool("queued"))
    }

    @Test fun busyModeIsReadOnlyAndCachedPerConnection() = test {
        fake.responders["config.get"] = { s, id, p ->
            assertEquals("busy", p.string("key"))
            s.reply(id, """{"key":"busy","value":"steer"}""")
        }
        assertEquals(BusyMode.Steer, gateway.busyMode())
        assertEquals(BusyMode.Steer, gateway.busyMode())
        val socket = fake.nextSocket()
        assertEquals(1, socket.frames.count { it.string("method") == "config.get" })
        assertFalse(socket.frames.any { it.string("method")?.startsWith("config.set") == true })
    }

    @Test fun busyModeUnknownIsNull() = test {
        fake.responders["config.get"] = { s, id, _ -> s.error(id, -32601, "unknown method: config.get") }
        assertNull(gateway.busyMode())
    }

    @Test fun busyModeReplyShapes() {
        assertEquals(BusyMode.Queue, GatewayFrames.busyMode(JsonPrimitive("queue")))
        assertEquals(BusyMode.Interrupt, GatewayFrames.busyMode(buildJsonObject { put("busy", "Interrupt") }))
        assertEquals(BusyMode.Steer, GatewayFrames.busyMode(buildJsonObject { putJsonObject("display") { put("busy_input_mode", "steer") } }))
        assertNull(GatewayFrames.busyMode(buildJsonObject { put("value", "bogus") }))
        assertNull(GatewayFrames.busyMode(JsonNull))
    }
}
