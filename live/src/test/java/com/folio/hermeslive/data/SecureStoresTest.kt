package com.folio.hermeslive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.HermesCredentials
import com.folio.hermes.HermesTokens
import com.folio.hermeslive.call.FallbackRoute
import com.folio.hermeslive.call.SessionMemory
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Keystore box itself needs a phone; here a software AES key stands in, with the same framing. */
class SoftwareSecretBox : SecretBox {
    var key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var destroyed = false
    override fun seal(plain: ByteArray) = GcmFrame.seal(key, plain)
    override fun open(sealed: ByteArray) = GcmFrame.open(key, sealed)
    override fun destroy() {
        destroyed = true
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SecureStoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tokens = HermesTokens("access-abc", "refresh-xyz", 1_700_000_000_000, "basic", "matt")

    private fun store(box: SecretBox = SoftwareSecretBox(), name: String = "secure-test") =
        SecureTokenStore(context.getSharedPreferences(name, Context.MODE_PRIVATE), box) to context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun tokensAndPasswordRoundTripSealed() = runTest {
        val (s, prefs) = store()
        assertFalse(s.hasSession())
        s.saveTokens(tokens)
        s.saveCredentials(HermesCredentials("matt", "pa\"ss word"))
        assertTrue(s.hasSession())
        assertEquals(tokens, s.loadTokens())
        assertEquals(HermesCredentials("matt", "pa\"ss word"), s.loadCredentials())
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("access-abc"))
        assertFalse(raw.contains("pa\"ss"))
        s.saveTokens(null)
        assertNull(s.loadTokens())
        assertFalse(s.hasSession())
    }

    @Test fun anythingThatWillNotOpenReadsAsSignedOut() = runTest {
        val box = SoftwareSecretBox()
        val (s, prefs) = store(box, "secure-tamper")
        s.saveTokens(tokens)
        box.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() // key lost (e.g. restored backup)
        assertNull(s.loadTokens())
        assertFalse(prefs.contains("tokens"))
        prefs.edit().putString("credentials", "not base64 !!").commit()
        assertNull(s.loadCredentials())
    }

    @Test fun wipeForgetsEverythingAndTheKey() = runTest {
        val box = SoftwareSecretBox()
        val (s, prefs) = store(box, "secure-wipe")
        s.saveTokens(tokens)
        s.saveCredentials(HermesCredentials("u", "p"))
        s.wipe()
        assertTrue(prefs.all.isEmpty())
        assertTrue(box.destroyed)
        assertNull(s.loadTokens())
    }

    @Test fun settingsDefaultsPersistAndAccountClears() {
        val prefs = context.getSharedPreferences("settings-test", Context.MODE_PRIVATE)
        val store = SettingsStore(prefs)
        assertEquals(LiveSettings(), store.settings.value)
        store.update { it.copy(spokenApprovals = true, idleTimeoutMinutes = 5, fallbackRoute = FallbackRoute.Earpiece) }
        val again = SettingsStore(prefs)
        assertTrue(again.settings.value.spokenApprovals)
        assertEquals(5, again.settings.value.idleTimeoutMinutes)
        assertEquals(FallbackRoute.Earpiece, again.settings.value.fallbackRoute)

        again.setAccount("https://hermes.example.com", "matt")
        again.save(SessionMemory("stored-1", 42))
        assertEquals(SessionMemory("stored-1", 42), SettingsStore(prefs).load())
        again.clearAccount()
        val cleared = SettingsStore(prefs)
        assertNull(cleared.serverUrl.value)
        assertNull(cleared.username)
        assertEquals(SessionMemory(null, 0), cleared.load())
        assertTrue(cleared.settings.value.spokenApprovals) // call preferences survive sign-out
    }
}
