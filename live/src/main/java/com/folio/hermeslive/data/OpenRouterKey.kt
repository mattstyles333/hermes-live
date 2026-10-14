package com.folio.hermeslive.data

import android.content.SharedPreferences
import android.util.Base64
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/** What OpenRouter last said about the saved key (`GET /api/v1/key`). */
enum class KeyCheck(val wire: String) {
    /** Saved, not checked yet (or the check could not reach OpenRouter). */
    Unchecked("unchecked"),
    Valid("valid"),

    /** OpenRouter refused it (401/403). The cascade will not use it until it passes again. */
    Refused("refused"),
    ;

    companion object {
        fun parse(wire: String?): KeyCheck = entries.firstOrNull { it.wire == wire } ?: Unchecked
    }
}

/** The key as the screen may show it: never the key itself. [hint] is its last four characters. */
data class KeyStatus(val saved: Boolean = false, val check: KeyCheck = KeyCheck.Unchecked, val hint: String? = null) {
    /** The cascade may use the key (saved and not refused). */
    val usable: Boolean get() = saved && check != KeyCheck.Refused
}

/**
 * The user's OpenRouter key (contract v1 "Key", R-Q-2): sealed with its own Keystore key
 * ([box], alias `hermes_live_openrouter`) in its own prefs file, so the Hermes sign-out wipe
 * (which clears `hermes_live_secure` and destroys `hermes_live_session`) does not touch it.
 * [status] and [hasKey] never decrypt. The key is never logged, never put in telemetry, and only
 * [key] returns it (for the `Authorization` header).
 */
class OpenRouterKeyStore(
    private val prefs: SharedPreferences,
    private val box: SecretBox,
    private val telemetry: TelemetrySink = TelemetrySink.Default,
    /** The telemetry item name (`openrouter_key`, `xai_key`): which key failed, never the key. */
    private val item: String = "openrouter_key",
) {
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<KeyStatus> = _status.asStateFlow()

    /** True while a key is saved and not refused. No decryption (the off-state gate asks this). */
    fun hasKey(): Boolean = _status.value.usable

    /** The key, or null. Something that will not open (Keystore key lost) is removed. */
    fun key(): String? {
        val stored = prefs.getString(K_SEALED, null) ?: return null
        return try {
            String(box.open(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            report("keystore.open_failed", e)
            remove()
            null
        }
    }

    /** Seal and save [key] (already normalised by [KeyPaste]) with what the check said. */
    fun save(key: String, check: KeyCheck) {
        val sealed = try {
            box.seal(key.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            report("keystore.seal_failed", e)
            throw IllegalStateException("could not seal the key")
        }
        val hint = key.takeLast(HINT_CHARS)
        prefs.edit()
            .putString(K_SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(K_CHECK, check.wire)
            .putString(K_HINT, hint)
            .commit()
        _status.value = KeyStatus(true, check, hint)
    }

    /** Record a later check of the saved key. */
    fun setCheck(check: KeyCheck) {
        if (!prefs.contains(K_SEALED)) return
        prefs.edit().putString(K_CHECK, check.wire).commit()
        _status.value = _status.value.copy(check = check)
    }

    fun remove() {
        prefs.edit().clear().commit()
        box.destroy()
        _status.value = KeyStatus()
    }

    private fun readStatus(): KeyStatus =
        if (!prefs.contains(K_SEALED)) KeyStatus()
        else KeyStatus(true, KeyCheck.parse(prefs.getString(K_CHECK, null)), prefs.getString(K_HINT, null))

    // The exception's type only: a message could echo what was being sealed.
    private fun report(name: String, e: Exception) = runCatching {
        telemetry.event(name, mapOf("item" to item, "type" to e.javaClass.simpleName.lowercase()), true)
    }

    companion object {
        const val PREFS = "hermes_live_openrouter"
        const val KEYSTORE_ALIAS = "hermes_live_openrouter"
        private const val K_SEALED = "key"
        private const val K_CHECK = "check"
        private const val K_HINT = "hint"
        private const val HINT_CHARS = 4
    }
}

/** Turns clipboard text into a key, or says why it is not one. Never echoes the text. */
object KeyPaste {
    sealed interface Parsed {
        data class Key(val key: String) : Parsed
        data class Problem(val message: String) : Parsed
    }

    /** [provider] names the key in the messages (`OpenRouter`, `xAI`). */
    fun parse(clip: CharSequence?, provider: String = "OpenRouter"): Parsed {
        val text = clip?.toString()?.trim()?.removePrefix("Bearer ")?.trim().orEmpty()
        return when {
            text.isEmpty() -> Parsed.Problem("The clipboard is empty. Copy your $provider key first.")
            text.any { it.isWhitespace() } -> Parsed.Problem("That isn't a key: it has spaces in it.")
            text.length !in MIN_LENGTH..MAX_LENGTH -> Parsed.Problem("That doesn't look like an $provider key.")
            text.any { it.code !in 0x21..0x7e } -> Parsed.Problem("That doesn't look like an $provider key.")
            else -> Parsed.Key(text)
        }
    }

    private const val MIN_LENGTH = 20
    private const val MAX_LENGTH = 256
}

/** Result of checking a key with OpenRouter. */
sealed interface KeyCheckResult {
    data object Valid : KeyCheckResult
    data object Refused : KeyCheckResult

    /** OpenRouter could not be asked ([kind]: `offline`, `timeout`, `http`…). */
    data class Unreachable(val kind: String, val httpStatus: Int?) : KeyCheckResult
}

/** `GET /api/v1/key` for one candidate key. Builds an [OpenRouter] only for the check. */
fun interface KeyChecker {
    suspend fun check(key: String): KeyCheckResult

    companion object {
        fun openRouter(
            client: OkHttpClient,
            config: OpenRouterConfig = OpenRouterConfig(),
            telemetry: TelemetrySink = TelemetrySink.Default,
        ): KeyChecker = from { key -> OpenRouter(config, key = key, client = client, telemetry = telemetry) }

        /** A checker on [openRouters] (one client per check, for that candidate key only). */
        fun from(openRouters: (key: () -> String?) -> OpenRouter): KeyChecker = KeyChecker { key ->
            when (val r = openRouters { key }.checkKey()) {
                is OpenRouterResult.Ok -> if (r.value) KeyCheckResult.Valid else KeyCheckResult.Refused
                is OpenRouterResult.Failed -> KeyCheckResult.Unreachable(r.kind.wire, r.httpStatus)
            }
        }
    }
}
