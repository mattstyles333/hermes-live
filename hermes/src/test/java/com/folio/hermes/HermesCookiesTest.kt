package com.folio.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesCookiesTest {
    @Test fun readsHostPrefixedQuotedCookies() {
        val session = HermesCookies.session(
            listOf(
                "__Host-hermes_session_at=\"YWNjZXNzLXRva2Vu==\"; HttpOnly; Max-Age=43200; Path=/; SameSite=lax; Secure",
                "__Host-hermes_session_rt=cmVmcmVzaA; HttpOnly; Max-Age=2592000; Path=/; SameSite=lax; Secure",
                "__Host-hermes_session_provider=basic; HttpOnly; Max-Age=2592000; Path=/; SameSite=lax; Secure",
            ),
        )
        assertEquals("YWNjZXNzLXRva2Vu==", session.accessToken)
        assertEquals("cmVmcmVzaA", session.refreshToken)
        assertEquals(43_200L, session.accessMaxAgeSeconds)
        assertEquals("basic", session.provider)
    }

    @Test fun readsSecurePrefixAndBareNames() {
        assertEquals("a", HermesCookies.session(listOf("__Secure-hermes_session_at=a; Path=/hermes")).accessToken)
        assertEquals("b", HermesCookies.session(listOf("hermes_session_at=b; Max-Age=60")).accessToken)
    }

    @Test fun ignoresDeletions() {
        val session = HermesCookies.session(
            listOf("__Host-hermes_session_at=\"\"; Max-Age=0; Path=/", "hermes_session_at=live; Max-Age=60"),
        )
        assertEquals("live", session.accessToken)
        assertNull(HermesCookies.session(listOf("hermes_session_at=x; Max-Age=0")).accessToken)
    }

    @Test fun unquotesPythonEscapes() {
        assertEquals("a\"b\\c,d", HermesCookies.unquote("\"a\\\"b\\\\c\\054d\""))
        assertEquals("plain", HermesCookies.unquote("plain"))
    }

    @Test fun toStringHidesValues() {
        val text = HermesCookies.parse("hermes_session_at=secret-value; Max-Age=1").toString()
        assert(!text.contains("secret-value"))
    }
}
