package com.folio.hermeslive.provider

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.HermesTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConnectionProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun provider() = Robolectric.setupContentProvider(ConnectionProvider::class.java, ConnectionProvider.AUTHORITY)

    @Test(expected = SecurityException::class)
    fun callersWithoutThePermissionAreRefused() {
        shadowOf(app).denyPermissions(ConnectionProvider.PERMISSION)
        provider().call(ConnectionProvider.METHOD_CONNECTION, null, null)
    }

    @Test fun stateNeedsThePermissionToo() {
        shadowOf(app).denyPermissions(ConnectionProvider.PERMISSION)
        val refused = runCatching { provider().call(ConnectionProvider.METHOD_STATE, null, null) }
        assertTrue(refused.exceptionOrNull() is SecurityException)
    }

    @Test fun signedOutAppSaysSoAndHandsOutNothing() {
        shadowOf(app).grantPermissions(ConnectionProvider.PERMISSION)
        // The real wiring: no server stored, so nothing is read or sent.
        val bundle = provider().call(ConnectionProvider.METHOD_CONNECTION, null, null)!!
        assertFalse(bundle.getBoolean(ConnectionProvider.KEY_SIGNED_IN, true))
        assertNull(bundle.getString(ConnectionProvider.KEY_ACCESS_TOKEN))
        assertEquals(setOf(ConnectionProvider.KEY_SIGNED_IN), bundle.keySet())
    }

    @Test fun signedInBundleShape() {
        shadowOf(app).grantPermissions(ConnectionProvider.PERMISSION)
        val p = provider()
        p.connection = { _, _ -> ConnectionProvider.Connection.SignedIn("https://hermes.example.com", HermesTokens("tok", "ref", 1_800_000_000_000, "basic", "u")) }
        val bundle = p.call(ConnectionProvider.METHOD_CONNECTION, null, null)!!
        assertTrue(bundle.getBoolean(ConnectionProvider.KEY_SIGNED_IN))
        assertEquals("https://hermes.example.com", bundle.getString(ConnectionProvider.KEY_BASE_URL))
        assertEquals("tok", bundle.getString(ConnectionProvider.KEY_ACCESS_TOKEN))
        assertEquals(1_800_000_000_000, bundle.getLong(ConnectionProvider.KEY_EXPIRES_AT_MS))
        assertNull(bundle.getString("refresh_token"))
        assertEquals(setOf("signed_in", "base_url", "access_token", "expires_at_ms"), bundle.keySet())
    }

    @Test fun forceRefreshIsPassedThrough() {
        shadowOf(app).grantPermissions(ConnectionProvider.PERMISSION)
        val p = provider()
        val forced = mutableListOf<Boolean>()
        p.connection = { _, force -> forced += force; ConnectionProvider.Connection.SignedOut }
        p.call(ConnectionProvider.METHOD_CONNECTION, null, null)
        p.call(ConnectionProvider.METHOD_CONNECTION, null, android.os.Bundle().apply { putBoolean("force_refresh", true) })
        assertEquals(listOf(false, true), forced)
        assertEquals("content://com.folio.hermeslive.connection", ConnectionProvider.URI.toString())
    }

    @Test fun unreachableKeepsTheTokenBack() {
        shadowOf(app).grantPermissions(ConnectionProvider.PERMISSION)
        val p = provider()
        p.connection = { _, _ -> ConnectionProvider.Connection.Unavailable("Hermes is unreachable") }
        val bundle = p.call(ConnectionProvider.METHOD_CONNECTION, null, null)!!
        assertTrue(bundle.getBoolean(ConnectionProvider.KEY_SIGNED_IN))
        assertNull(bundle.getString(ConnectionProvider.KEY_ACCESS_TOKEN))
    }

    @Test fun stateReportsTheCall() {
        shadowOf(app).grantPermissions(ConnectionProvider.PERMISSION)
        val p = provider()
        assertFalse(p.call(ConnectionProvider.METHOD_STATE, null, null)!!.getBoolean(ConnectionProvider.KEY_IN_CALL, true))
        p.inCall = { true }
        assertTrue(p.call(ConnectionProvider.METHOD_STATE, null, null)!!.getBoolean(ConnectionProvider.KEY_IN_CALL))
        assertNull(p.call("unknown", null, null))
    }
}
