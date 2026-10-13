package com.folio.hermeslive.hooks

import com.folio.hermes.HermesEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug and staging: the emulator's fake Hermes address works; nothing else changes. */
class TestServerTest {
    @Test fun theEmulatorHostBecomesTheFakeTailnetName() {
        assertEquals("http://fake-hermes.ts.net:18119", BuildHooks.serverUrl("http://10.0.2.2:18119"))
        assertEquals("http://fake-hermes.ts.net/prefix", TestServer.alias(" http://10.0.2.2/prefix "))
        assertTrue(HermesEndpoint.parse(BuildHooks.serverUrl("http://10.0.2.2:18119")) is HermesEndpoint.Parsed.Valid)
        assertEquals("10.0.2.2", BuildHooks.dns.lookup("fake-hermes.ts.net").single().hostAddress)
    }

    @Test fun everythingElseIsAsTyped() {
        assertEquals("https://hermes.example.com", TestServer.alias("https://hermes.example.com"))
        assertEquals("http://10.0.2.20:1", TestServer.alias("http://10.0.2.20:1"))
        assertEquals("https://10.0.2.2", TestServer.alias("https://10.0.2.2"))
        assertTrue("plain 10.0.2.2 is still refused by the endpoint rules", HermesEndpoint.parse("http://10.0.2.2:18119") is HermesEndpoint.Parsed.Invalid)
    }
}
