package com.folio.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesEndpointTest {
    private fun problem(raw: String) = (HermesEndpoint.parse(raw) as HermesEndpoint.Parsed.Invalid).problem

    @Test fun httpsAnywhereIsAccepted() {
        val e = HermesEndpoint.of("https://hermes.example.com/")
        assertEquals("https://hermes.example.com", e.toString())
        assertFalse(e.isCleartext)
        assertEquals("https://hermes.example.com/api/auth/ws-ticket", e.resolve("/api/auth/ws-ticket").toString())
    }

    @Test fun bareHostDefaultsToHttps() {
        assertEquals("https://hermes.example.com", HermesEndpoint.of("  hermes.example.com ").toString())
    }

    @Test fun pathPrefixIsKept() {
        val e = HermesEndpoint.of("https://example.com/hermes/")
        assertEquals("https://example.com/hermes/api/ws?ticket=t%2B1", e.gatewayUrl("t+1").toString())
    }

    @Test fun cleartextOnlyForTailnet() {
        assertTrue(HermesEndpoint.of("http://box.example.ts.net:9119").isCleartext)
        assertTrue(HermesEndpoint.of("http://100.64.0.1:9119").isCleartext)
        assertTrue(HermesEndpoint.of("http://100.127.255.255").isCleartext)
        assertEquals(HermesEndpoint.Problem.CleartextNotAllowed, problem("http://hermes.example.com"))
        assertEquals(HermesEndpoint.Problem.CleartextNotAllowed, problem("http://100.128.0.1"))
        assertEquals(HermesEndpoint.Problem.CleartextNotAllowed, problem("http://100.63.255.255"))
        assertEquals(HermesEndpoint.Problem.CleartextNotAllowed, problem("http://192.168.1.10:9119"))
        assertEquals(HermesEndpoint.Problem.CleartextNotAllowed, problem("http://ts.net.example.com"))
        assertEquals(HermesEndpoint.Problem.CleartextNotAllowed, problem("http://evil-ts.net"))
    }

    @Test fun refusesOddUrls() {
        assertEquals(HermesEndpoint.Problem.Blank, problem("  "))
        assertEquals(HermesEndpoint.Problem.UnsupportedScheme, problem("ftp://example.com"))
        assertEquals(HermesEndpoint.Problem.UnsupportedScheme, problem("wss://example.com"))
        assertEquals(HermesEndpoint.Problem.HasCredentials, problem("https://user:pw@example.com"))
        assertEquals(HermesEndpoint.Problem.HasQueryOrFragment, problem("https://example.com/?a=1"))
        assertEquals(HermesEndpoint.Problem.HasQueryOrFragment, problem("https://example.com/#x"))
        assertEquals(HermesEndpoint.Problem.NotAUrl, problem("https://"))
    }

    @Test fun tailnetHostRules() {
        assertTrue(HermesEndpoint.isTailnetHost("Machine.Tailnet-Name.TS.NET"))
        assertFalse(HermesEndpoint.isTailnetHost("100.64.0"))
        assertFalse(HermesEndpoint.isTailnetHost("100.64.0.256"))
        assertFalse(HermesEndpoint.isTailnetHost("100.064.0.1x"))
    }
}
