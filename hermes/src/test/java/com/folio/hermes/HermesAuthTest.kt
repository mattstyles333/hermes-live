package com.folio.hermes

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HermesAuthTest {
    private val server = MockWebServer()
    private var now = 1_000_000_000L
    private lateinit var store: InMemoryTokenStore
    private lateinit var auth: HermesAuth

    @Before fun setUp() {
        server.start()
        store = InMemoryTokenStore()
        auth = HermesAuth(server.config(), store, clock = { now })
    }

    @After fun tearDown() = server.close()

    private fun loginOk(access: String = "at-1", rt: String = "rt-1", maxAge: Long = 43_200) = json(
        200, """{"ok":true,"next":"/"}""",
        "Set-Cookie" to "__Host-hermes_session_at=\"$access==\"; HttpOnly; Max-Age=$maxAge; Path=/; SameSite=lax; Secure",
        "Set-Cookie" to "__Host-hermes_session_rt=$rt; HttpOnly; Max-Age=2592000; Path=/; SameSite=lax; Secure",
        "Set-Cookie" to "__Host-hermes_session_provider=basic; HttpOnly; Max-Age=2592000; Path=/; SameSite=lax; Secure",
    )

    private fun refreshOk(access: String, rt: String, expiresAtSeconds: Long) = json(
        200,
        """{"access_token":"$access","refresh_token":"$rt","token_type":"Bearer","expires_at":$expiresAtSeconds,"provider":"basic","user_id":"owner"}""",
    )

    private suspend fun signedIn(expiresInMs: Long = 12 * 3_600_000L) {
        store.saveTokens(HermesTokens("at-old", "rt-old", now + expiresInMs, "basic", "owner"))
        store.saveCredentials(HermesCredentials("owner", "example-password"))
    }

    @Test fun offStateSendsNothing() = runBlocking {
        try {
            auth.accessToken()
            fail("expected SignInRequired")
        } catch (e: HermesAuthException.SignInRequired) {
            // expected
        }
        assertEquals(0, server.requestCount)
        assertEquals(AuthState.SignedOut, auth.state.value)
    }

    @Test fun signInReadsTokensFromCookies() = runBlocking {
        server.enqueue(loginOk())
        val tokens = auth.signIn("owner", "example-password")
        val request = server.takeRequest()
        assertEquals("/auth/password-login", request.url.encodedPath)
        val body = request.jsonBody()
        assertEquals("basic", body.string("provider"))
        assertEquals("owner", body.string("username"))
        assertEquals("example-password", body.string("password"))
        assertEquals("", body.string("next"))
        assertEquals("at-1==", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(now + 43_200_000L, tokens.expiresAtMs)
        assertEquals(tokens, store.loadTokens())
        assertEquals(HermesCredentials("owner", "example-password"), store.loadCredentials())
        assertEquals(AuthState.SignedIn("owner", now + 43_200_000L), auth.state.value)
        assertFalse(tokens.toString().contains("at-1"))
    }

    @Test fun badPasswordStoresNothing() = runBlocking {
        server.enqueue(json(401, """{"detail":"Invalid credentials"}"""))
        try {
            auth.signIn("owner", "wrong")
            fail()
        } catch (e: HermesAuthException.InvalidCredentials) {
            // expected
        }
        assertNull(store.loadTokens())
        assertNull(store.loadCredentials())
        assertTrue(auth.state.value is AuthState.NeedsSignIn)
    }

    @Test fun loginWithoutCookiesIsAProtocolError() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        try {
            auth.signIn("owner", "pw")
            fail()
        } catch (e: HermesAuthException.Protocol) {
            // expected
        }
    }

    @Test fun rateLimitedLoginIsNotRetried() = runBlocking {
        server.enqueue(json(429, """{"detail":"Too many login attempts."}"""))
        try {
            auth.signIn("owner", "pw")
            fail()
        } catch (e: HermesAuthException.RateLimited) {
            // expected
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun freshTokenNeedsNoNetwork() = runBlocking {
        signedIn()
        assertEquals("at-old", auth.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test fun nearExpiryRefreshesAndConvertsSeconds() = runBlocking {
        signedIn(expiresInMs = 60_000)
        server.enqueue(refreshOk("at-new", "rt-new", expiresAtSeconds = 2_000_000L))
        assertEquals("at-new", auth.accessToken())
        val request = server.takeRequest()
        assertEquals("/auth/native/refresh", request.url.encodedPath)
        assertEquals("rt-old", request.jsonBody().string("refresh_token"))
        assertEquals("basic", request.jsonBody().string("provider"))
        val stored = store.loadTokens()!!
        assertEquals(2_000_000_000L, stored.expiresAtMs)
        assertEquals("rt-new", stored.refreshToken)
    }

    @Test fun deadRefreshTokenSignsInAgainSilently() = runBlocking {
        signedIn(expiresInMs = 60_000)
        server.enqueue(json(401, """{"error":"session_expired","detail":"Refresh token expired or invalid; start a new sign-in."}"""))
        server.enqueue(loginOk(access = "at-relog"))
        assertEquals("at-relog==", auth.accessToken())
        assertEquals("/auth/native/refresh", server.takeRequest().url.encodedPath)
        val relogin = server.takeRequest()
        assertEquals("/auth/password-login", relogin.url.encodedPath)
        assertEquals("example-password", relogin.jsonBody().string("password"))
    }

    @Test fun changedPasswordAsksTheUser() = runBlocking {
        signedIn(expiresInMs = 60_000)
        server.enqueue(json(401, """{"error":"session_expired"}"""))
        server.enqueue(json(401, """{"detail":"Invalid credentials"}"""))
        try {
            auth.accessToken()
            fail()
        } catch (e: HermesAuthException.InvalidCredentials) {
            // expected
        }
        assertNull(store.loadTokens())
        assertNull(store.loadCredentials())
        assertTrue(auth.state.value is AuthState.NeedsSignIn)
        assertEquals(2, server.requestCount)
    }

    @Test fun unavailableRefreshKeepsAnUnexpiredSession() = runBlocking {
        signedIn(expiresInMs = 60_000)
        server.enqueue(json(503, """{"detail":"Auth provider 'basic' unreachable"}"""))
        assertEquals("at-old", auth.accessToken())
        assertEquals(1, server.requestCount)
        assertEquals("at-old", store.loadTokens()!!.accessToken)
    }

    @Test fun unavailableRefreshOnExpiredSessionThrows() = runBlocking {
        signedIn(expiresInMs = -1)
        server.enqueue(json(503, """{"detail":"down"}"""))
        try {
            auth.accessToken()
            fail()
        } catch (e: HermesAuthException.Unavailable) {
            assertEquals(503, e.httpCode)
        }
    }

    @Test fun unauthorizedRefreshesOnceAndSkipsWhenAlreadyRotated() = runBlocking {
        signedIn()
        server.enqueue(refreshOk("at-2", "rt-2", 2_000_000L))
        assertEquals("at-2", auth.refreshAfterUnauthorized("at-old"))
        // A second caller that still holds the old token gets the new one without another refresh.
        assertEquals("at-2", auth.refreshAfterUnauthorized("at-old"))
        assertEquals(1, server.requestCount)
    }

    @Test fun signOutClearsEverything() = runBlocking {
        signedIn()
        auth.signOut()
        assertNull(store.loadTokens())
        assertNull(store.loadCredentials())
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun tokensRoundTripAndRedact() {
        val tokens = HermesTokens("a-secret", "r-secret", 5L, "basic", "owner")
        assertEquals(tokens, HermesTokens.decode(tokens.encode()))
        assertNull(HermesTokens.decode("not json"))
        assertFalse(tokens.toString().contains("secret"))
        assertFalse(HermesCredentials("u", "p-secret").toString().contains("p-secret"))
    }
}
