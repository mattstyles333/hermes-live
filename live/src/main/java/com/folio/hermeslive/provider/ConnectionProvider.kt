package com.folio.hermeslive.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesTokens
import com.folio.hermeslive.graph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Folio's door into Hermes Live (contract: authority `com.folio.hermeslive.connection`).
 *
 * - `call("connection")` → `signed_in`, `base_url`, `access_token`, `expires_at_ms`; the token is
 *   refreshed first when it has under 5 minutes left. Signed out: `signed_in=false` only.
 *   With extras `force_refresh=true` (Folio got a 401 for that token) it refreshes regardless.
 * - `call("state")` → `in_call`.
 * - Sign-in and sign-out call `notifyChange(content://com.folio.hermeslive.connection)` so Folio
 *   can drop or pick up Hermes at once.
 *
 * `ContentProvider.call` is not covered by `android:permission`, so every call checks the
 * caller holds `com.folio.permission.HERMES` (signature: only apps signed with the same key).
 * The password never leaves this app.
 */
class ConnectionProvider : ContentProvider() {
    /** Seams for tests; the app wiring is the default. */
    internal var connection: suspend (Context, Boolean) -> Connection = ::liveConnection
    internal var inCall: (Context) -> Boolean = { it.graph.engine.state.value.inCall }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (ctx.checkCallingOrSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Requires $PERMISSION")
        }
        return when (method) {
            METHOD_CONNECTION -> runBlocking {
                val force = extras?.getBoolean(EXTRA_FORCE_REFRESH, false) == true
                (withTimeoutOrNull(TIMEOUT_MS) { connection(ctx, force) } ?: Connection.Unavailable("Hermes Live timed out")).toBundle()
            }
            METHOD_STATE -> Bundle().apply { putBoolean(KEY_IN_CALL, inCall(ctx)) }
            else -> null
        }
    }

    /** What `connection` answers. */
    sealed interface Connection {
        fun toBundle(): Bundle

        data object SignedOut : Connection {
            override fun toBundle() = Bundle().apply { putBoolean(KEY_SIGNED_IN, false) }
        }

        data class SignedIn(val baseUrl: String, val tokens: HermesTokens) : Connection {
            override fun toBundle() = Bundle().apply {
                putBoolean(KEY_SIGNED_IN, true)
                putString(KEY_BASE_URL, baseUrl)
                putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                putLong(KEY_EXPIRES_AT_MS, tokens.expiresAtMs)
            }

            override fun toString() = "SignedIn($baseUrl, ${tokens})"
        }

        /** Signed in, but no usable token right now (offline, server down). No token is handed out. */
        data class Unavailable(val reason: String) : Connection {
            override fun toBundle() = Bundle().apply {
                putBoolean(KEY_SIGNED_IN, true)
                putString(KEY_ERROR, reason)
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.folio.hermeslive.connection"
        const val PERMISSION = "com.folio.permission.HERMES"
        const val METHOD_CONNECTION = "connection"
        const val METHOD_STATE = "state"
        const val KEY_SIGNED_IN = "signed_in"
        const val KEY_BASE_URL = "base_url"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        const val KEY_IN_CALL = "in_call"
        const val KEY_ERROR = "error"
        const val EXTRA_FORCE_REFRESH = "force_refresh"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        const val MIN_VALIDITY_MS = 5 * 60_000L
        private const val TIMEOUT_MS = 15_000L

        private suspend fun liveConnection(context: Context, forceRefresh: Boolean): Connection {
            val graph = context.graph
            graph.ready.await()
            val baseUrl = graph.account.config()?.endpoint?.toString() ?: return Connection.SignedOut
            return try {
                val tokens = if (forceRefresh) graph.account.forceRefresh() else graph.account.session(MIN_VALIDITY_MS)
                Connection.SignedIn(baseUrl, tokens)
            } catch (e: HermesAuthException.SignInRequired) {
                Connection.SignedOut
            } catch (e: HermesAuthException.InvalidCredentials) {
                Connection.SignedOut
            } catch (e: java.io.IOException) {
                Connection.Unavailable("Hermes is unreachable")
            }
        }
    }
}
