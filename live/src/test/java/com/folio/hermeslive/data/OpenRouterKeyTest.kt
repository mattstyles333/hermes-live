package com.folio.hermeslive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.telemetry.TelemetrySink
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A made-up key: never a real one in a test. */
const val FAKE_KEY = "sk-or-v1-0000000000000000000000000000000000000000000000000000000000fake"

/** OpenRouter answering every request with [status], recording what was asked. */
class FakeOpenRouterHttp(var status: Int = 200) : Interceptor {
    val requests = mutableListOf<okhttp3.Request>()
    override fun intercept(chain: Interceptor.Chain): Response {
        requests += chain.request()
        return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body("{\"data\":{}}".toResponseBody("application/json".toMediaType())).build()
    }
    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
}

class RecordingSink : TelemetrySink {
    val events = mutableListOf<Triple<String, Map<String, Any?>, Boolean>>()
    @Synchronized override fun event(name: String, attrs: Map<String, Any?>, warn: Boolean) {
        events += Triple(name, attrs, warn)
    }
    @Synchronized fun text(): String = events.joinToString("\n") { (n, a, w) -> "$n $a $w" }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OpenRouterKeyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun theKeyIsSealedAndReadBack() {
        val prefs = prefs("or-seal")
        val store = OpenRouterKeyStore(prefs, SoftwareSecretBox(), TelemetrySink.None)
        assertFalse(store.hasKey())
        assertNull(store.key())
        store.save(FAKE_KEY, KeyCheck.Valid)
        assertTrue(store.hasKey())
        assertEquals(FAKE_KEY, store.key())
        assertEquals(KeyStatus(true, KeyCheck.Valid, "fake"), store.status.value)
        val raw = prefs.all.values.joinToString()
        assertFalse("the key is never stored in the clear", raw.contains(FAKE_KEY.drop(10).take(20)))
        // Status survives a restart without decrypting.
        val box = object : SecretBox by SoftwareSecretBox() {
            override fun open(sealed: ByteArray): ByteArray = error("status must not decrypt")
        }
        assertTrue(OpenRouterKeyStore(prefs, box, TelemetrySink.None).hasKey())
    }

    @Test fun removeForgetsTheKeyAndItsKeystoreKey() {
        val box = SoftwareSecretBox()
        val store = OpenRouterKeyStore(prefs("or-remove"), box, TelemetrySink.None)
        store.save(FAKE_KEY, KeyCheck.Unchecked)
        store.remove()
        assertFalse(store.hasKey())
        assertNull(store.key())
        assertTrue(box.destroyed)
        assertTrue(prefs("or-remove").all.isEmpty())
    }

    @Test fun aRefusedKeyIsNotUsable() {
        val store = OpenRouterKeyStore(prefs("or-refused"), SoftwareSecretBox(), TelemetrySink.None)
        store.save(FAKE_KEY, KeyCheck.Valid)
        store.setCheck(KeyCheck.Refused)
        assertFalse(store.hasKey())
        assertTrue(store.status.value.saved)
    }

    @Test fun aKeyThatWillNotOpenIsDroppedAndReportedWithoutContent() {
        val box = SoftwareSecretBox()
        val sink = RecordingSink()
        val store = OpenRouterKeyStore(prefs("or-lost"), box, sink)
        store.save(FAKE_KEY, KeyCheck.Valid)
        box.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(store.key())
        assertFalse(store.hasKey())
        assertEquals("keystore.open_failed", sink.events.single().first)
        assertFalse(sink.text().contains("fake"))
    }

    @Test fun signOutWipeDoesNotTouchTheOpenRouterKey() = runTest {
        val tokens = SecureTokenStore(prefs("or-hermes-secure"), SoftwareSecretBox())
        val keyBox = SoftwareSecretBox()
        val keys = OpenRouterKeyStore(prefs("or-own"), keyBox, TelemetrySink.None)
        keys.save(FAKE_KEY, KeyCheck.Valid)
        tokens.wipe()
        assertFalse(keyBox.destroyed)
        assertEquals(FAKE_KEY, keys.key())
        assertTrue(OpenRouterKeyStore.PREFS != "hermes_live_secure")
        assertTrue(OpenRouterKeyStore.KEYSTORE_ALIAS != "hermes_live_session")
    }

    @Test fun pasteAcceptsAKeyAndExplainsEverythingElse() {
        assertEquals(KeyPaste.Parsed.Key(FAKE_KEY), KeyPaste.parse("  $FAKE_KEY\n"))
        assertEquals(KeyPaste.Parsed.Key(FAKE_KEY), KeyPaste.parse("Bearer $FAKE_KEY"))
        assertTrue(KeyPaste.parse(null) is KeyPaste.Parsed.Problem)
        assertTrue(KeyPaste.parse("") is KeyPaste.Parsed.Problem)
        assertTrue(KeyPaste.parse("short") is KeyPaste.Parsed.Problem)
        assertTrue(KeyPaste.parse("this is a sentence someone copied by mistake") is KeyPaste.Parsed.Problem)
        val problem = KeyPaste.parse("not-a-key-but-long-enough-éé") as KeyPaste.Parsed.Problem
        assertFalse("the problem never echoes the clipboard", problem.message.contains("long-enough"))
    }

    @Test fun theCheckAsksOpenRouterAndMapsTheAnswer() = runTest {
        val http = FakeOpenRouterHttp(200)
        val checker = KeyChecker.openRouter(http.client(), OpenRouterConfig(baseUrl = "https://openrouter.example/api/v1"), TelemetrySink.None)
        assertEquals(KeyCheckResult.Valid, checker.check(FAKE_KEY))
        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/key", request.url.encodedPath)
        assertEquals("Bearer $FAKE_KEY", request.header("Authorization"))
        http.status = 401
        assertEquals(KeyCheckResult.Refused, checker.check(FAKE_KEY))
        http.status = 503
        assertEquals(KeyCheckResult.Unreachable("http", 503), checker.check(FAKE_KEY))
    }
}
