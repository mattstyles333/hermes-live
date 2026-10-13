package com.folio.hermeslive.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.folio.hermes.HermesCredentials
import com.folio.hermes.HermesTokens
import com.folio.hermes.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Seals small secrets. [KeystoreSecretBox] on the phone; a software key in tests. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    /** Forget the key (sign-out). */
    fun destroy()
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. Each seal gets a fresh random
 * IV from the Keystore; the output is `ivLength ‖ iv ‖ ciphertext+tag`.
 */
class KeystoreSecretBox(private val alias: String = "hermes_live_session") : SecretBox {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray = GcmFrame.seal(key(), plain)

    override fun open(sealed: ByteArray): ByteArray = GcmFrame.open(key(), sealed)

    override fun destroy() {
        runCatching { keyStore.deleteEntry(alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** The `ivLength ‖ iv ‖ ciphertext` framing shared by the Keystore box and the tests' software box. */
object GcmFrame {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty()) { "empty" }
        val ivLength = sealed[0].toInt()
        require(ivLength in 12..16 && sealed.size > 1 + ivLength) { "bad frame" }
        val iv = sealed.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed, 1 + ivLength, sealed.size - 1 - ivLength)
    }
}

/**
 * [TokenStore] for Hermes Live: the dashboard tokens and the password (for silent re-login) are
 * sealed with [box] and kept as Base64 in [prefs]. Anything that will not open (key lost after a
 * restore, tampering) reads as absent and is removed: the user signs in again.
 */
class SecureTokenStore(private val prefs: SharedPreferences, private val box: SecretBox) : TokenStore {
    override suspend fun loadTokens(): HermesTokens? = openText(KEY_TOKENS)?.let(HermesTokens::decode)

    override suspend fun saveTokens(tokens: HermesTokens?) = sealText(KEY_TOKENS, tokens?.encode())

    override suspend fun loadCredentials(): HermesCredentials? = openText(KEY_CREDENTIALS)?.let { text ->
        runCatching {
            val obj = Json.parseToJsonElement(text).jsonObject
            HermesCredentials(obj["u"]!!.jsonPrimitive.content, obj["p"]!!.jsonPrimitive.content)
        }.getOrNull()
    }

    override suspend fun saveCredentials(credentials: HermesCredentials?) = sealText(
        KEY_CREDENTIALS,
        credentials?.let { buildJsonObject { put("u", it.username); put("p", it.password) }.toString() },
    )

    /** True when a session is stored (no decryption). */
    fun hasSession(): Boolean = prefs.contains(KEY_TOKENS)

    /** Sign-out: drop everything and the key. */
    fun wipe() {
        prefs.edit().clear().commit()
        box.destroy()
    }

    private fun sealText(key: String, text: String?) {
        val edit = prefs.edit()
        if (text == null) {
            edit.remove(key)
        } else {
            edit.putString(key, Base64.encodeToString(box.seal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        edit.commit()
    }

    private fun openText(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { String(box.open(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8) }
            .onFailure { prefs.edit().remove(key).commit() }
            .getOrNull()
    }

    private companion object {
        const val KEY_TOKENS = "tokens"
        const val KEY_CREDENTIALS = "credentials"
    }
}
