package com.folio.hermeslive.data

import android.content.Context
import com.folio.hermes.telemetry.TelemetrySink
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The user's xAI key for Grok Voice: the same sealed store as the OpenRouter key
 * ([OpenRouterKeyStore]) with its own Keystore alias and prefs file, so neither the Hermes
 * sign-out wipe nor removing the OpenRouter key touches it. Never logged, never in telemetry;
 * only `key()` returns it (for the `Authorization` header). An OpenRouter key cannot stand in:
 * OpenRouter has no realtime speech-to-speech API (checked 2026-10-14).
 */
object XaiKeys {
    const val PREFS = "hermes_live_xai"
    const val KEYSTORE_ALIAS = "hermes_live_xai"
    const val PROVIDER = "xAI"

    fun store(context: Context, telemetry: TelemetrySink = SafeTelemetry()): OpenRouterKeyStore = OpenRouterKeyStore(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        KeystoreSecretBox(KEYSTORE_ALIAS),
        telemetry,
        item = "xai_key",
    )

    /** Clipboard text to an xAI key, or why it is not one. Never echoes the text. */
    fun paste(clip: CharSequence?): KeyPaste.Parsed = KeyPaste.parse(clip, PROVIDER)
}

/**
 * Checks an xAI key with `GET https://api.x.ai/v1/api-key` (the key's own metadata; no model is
 * called, nothing is billed). 200 and not blocked or disabled: valid. 400 (xAI's "invalid API
 * key"), 401 or 403: refused. Anything else: unreachable. Only the status and the blocked flags
 * are read; the body is never logged.
 */
class XaiKeyChecker(
    client: OkHttpClient,
    private val baseUrl: String = "https://api.x.ai/v1",
    private val telemetry: TelemetrySink = TelemetrySink.Default,
) : KeyChecker {
    private val http = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(key: String): KeyCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api-key").header("Authorization", "Bearer $key").header("Accept", "application/json").build()
        val result = try {
            http.newCall(request).execute().use { r ->
                when (r.code) {
                    200 -> {
                        val o = runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
                        val blocked = listOf("api_key_blocked", "api_key_disabled", "team_blocked").any { (o?.get(it) as? JsonPrimitive)?.booleanOrNull == true }
                        if (blocked) KeyCheckResult.Refused else KeyCheckResult.Valid
                    }
                    400, 401, 403 -> KeyCheckResult.Refused
                    else -> KeyCheckResult.Unreachable("http", r.code)
                }
            }
        } catch (e: InterruptedIOException) {
            KeyCheckResult.Unreachable("timeout", null)
        } catch (e: IOException) {
            KeyCheckResult.Unreachable("offline", null)
        }
        runCatching {
            telemetry.event(
                "xai.key_check",
                mapOf("result" to when (result) { KeyCheckResult.Valid -> "valid"; KeyCheckResult.Refused -> "refused"; is KeyCheckResult.Unreachable -> result.kind }, "http_status" to (result as? KeyCheckResult.Unreachable)?.httpStatus),
                result !is KeyCheckResult.Valid,
            )
        }
        result
    }
}
