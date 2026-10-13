package com.folio.hermes.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubTest {
    @Test fun bearerTokensAndJwtsAreRedacted() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.c2lnbmF0dXJl"
        val out = Scrub.message("401 for Authorization: Bearer abc.def-123 and $jwt")
        assertFalse(out, "abc.def-123" in out)
        assertFalse(out, jwt in out)
        assertTrue(out, "<redacted>" in out)
    }

    @Test fun keyValueSecretsAreRedacted() {
        val out = Scrub.message("""token=s3cr3t password: hunter2 {"access_token":"zzz"} cookie=abc; api_key=k1""")
        listOf("s3cr3t", "hunter2", "zzz", "k1").forEach { assertFalse("$it in $out", it in out) }
        assertTrue(out, "token=<redacted>" in out)
    }

    @Test fun urlQueriesFragmentsAndUserInfoGo() {
        val out = Scrub.message("GET https://user:pw@hermes.example.com/api/x?q=what+did+I+say#frag failed")
        assertEquals("GET https://<user>@hermes.example.com/api/x?<redacted> failed", out)
    }

    @Test fun emailsLongHexAndBase64Go() {
        val out = Scrub.message("user matt@example.com id ${"a1".repeat(20)} blob ${"QUJD1234abcd".repeat(4)}")
        assertEquals("user <email> id <hex> blob <redacted>", out)
    }

    @Test fun kotlinxJsonInputIsCut() {
        val out = Scrub.message("Unexpected JSON token at offset 3: Expected quote.\nJSON input: {\"text\":\"my private words\"}")
        assertFalse(out, "private" in out)
        assertTrue(out, out.endsWith("JSON input: <redacted>"))
    }

    @Test fun messagesAreTruncated() {
        val out = Scrub.message("word ".repeat(400))
        assertEquals(Scrub.MESSAGE_MAX, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun ordinaryDiagnosticsSurvive() {
        val text = "java.lang.IllegalStateException: createOffer: failed (code 503) at 0x7f12ab34cd56ef01"
        assertEquals(text, Scrub.message(text))
        val frame = "\tat com.folio.hermes.live.DelegationBridge\$onServerEvent\$1.invokeSuspend(DelegationBridge.kt:164)"
        assertEquals(frame, Scrub.text(frame))
    }

    @Test fun stackTracesScrubEveryMessageInTheChain() {
        val cause = IllegalArgumentException("JSON input: {\"prompt\":\"secret plan\"}")
        val top = RuntimeException("call failed with Bearer tok123", cause)
        val trace = Records.stackTrace(top)
        assertFalse(trace, "secret plan" in trace)
        assertFalse(trace, "tok123" in trace)
        assertTrue(trace, trace.startsWith("java.lang.RuntimeException: call failed with Bearer <redacted>\n\tat "))
        assertTrue(trace, "Caused by: java.lang.IllegalArgumentException: JSON input: <redacted>" in trace)
    }

    @Test fun attributesAreTypedOrScrubbed() {
        val r = Records.record(1L, Severity.Info, "call.end", mapOf("duration_s" to 12, "ok" to true, "ratio" to 0.5, "reason" to Thread.State.NEW, "url" to "https://x.example.com/a?b=c", "bad key!" to "v", "gone" to null))
        assertEquals("12", r.attributes["duration_s"]!!.content)
        assertFalse(r.attributes["duration_s"]!!.isString)
        assertEquals("true", r.attributes["ok"]!!.content)
        assertEquals("NEW", r.attributes["reason"]!!.content)
        assertEquals("https://x.example.com/a?<redacted>", r.attributes["url"]!!.content)
        assertEquals("v", r.attributes["bad_key_"]!!.content)
        assertFalse("gone" in r.attributes)
        assertEquals("call.end", r.attributes["event.name"]!!.content)
    }
}
