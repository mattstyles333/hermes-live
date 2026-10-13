package com.folio.hermes.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsEndpointTest {
    @Test fun offWhenNoDefaultAndNoOverride() {
        assertNull(DiagnosticsEndpoint.effective(enabled = true, override = null, buildDefault = ""))
        assertNull(DiagnosticsEndpoint.effective(enabled = true, override = "  ", buildDefault = null))
    }

    @Test fun offWhenSwitchedOffEvenWithAUrl() {
        assertNull(DiagnosticsEndpoint.effective(enabled = false, override = "https://o.example.com", buildDefault = "https://logs.example.com"))
    }

    @Test fun overrideWinsOverTheBuildDefault() {
        assertEquals("https://o.example.com", DiagnosticsEndpoint.effective(true, " https://o.example.com ", "https://logs.example.com"))
        assertEquals("https://logs.example.com", DiagnosticsEndpoint.effective(true, "", "https://logs.example.com"))
        // A half-typed or plain-http override is ignored, not "off".
        assertEquals("https://logs.example.com", DiagnosticsEndpoint.effective(true, "http://o.example.com", "https://logs.example.com"))
    }

    @Test fun logsPathIsAppendedOnce() {
        assertEquals("https://logs.example.com/v1/logs", DiagnosticsEndpoint.logsUrl("https://logs.example.com").toString())
        assertEquals("https://logs.example.com/v1/logs", DiagnosticsEndpoint.logsUrl("https://logs.example.com/").toString())
        assertEquals("https://logs.example.com/v1/logs", DiagnosticsEndpoint.logsUrl("https://logs.example.com/v1/logs").toString())
        assertEquals("https://example.com/otel/v1/logs", DiagnosticsEndpoint.logsUrl("https://example.com/otel").toString())
    }

    @Test fun onlyHttpsOrTailnetHttp() {
        assertNull(DiagnosticsEndpoint.logsUrl("http://logs.example.com"))
        assertNotNull(DiagnosticsEndpoint.logsUrl("http://box.tailnet.ts.net:4318"))
        assertNull(DiagnosticsEndpoint.logsUrl("ftp://logs.example.com"))
        assertNull(DiagnosticsEndpoint.logsUrl("logs.example.com"))
        assertNull(DiagnosticsEndpoint.logsUrl("https://u:p@logs.example.com"))
        assertNull(DiagnosticsEndpoint.logsUrl("https://logs.example.com/?k=v"))
        assertNull(DiagnosticsEndpoint.problem(""))
        assertNotNull(DiagnosticsEndpoint.problem("http://logs.example.com"))
    }
}
