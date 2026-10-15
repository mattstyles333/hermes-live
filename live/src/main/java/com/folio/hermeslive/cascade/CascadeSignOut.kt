package com.folio.hermeslive.cascade

import com.folio.hermeslive.data.VoiceShell
import java.io.File
import java.security.MessageDigest

/**
 * Hermes sign-out and Bill (spec v2 §14 item 15; hub decision 2026-10-14). Bill's state belongs to
 * the signed-in account: held memos, tasks with their stored session ids, the outbox, the
 * conversation and its history must never reach a different account's server. So a sign-out
 * - closes Bill's runtime first (its gateway and task sessions go; nothing writes after), then
 * - deletes every `cascade.*` key of his store (the core, tasks, conversations, call times, the
 *   learned vocabulary and the "voice prepared" marker), the store's logs and quarantined values,
 *   and the decision log.
 *
 * Kept: the OpenRouter key (a separate credential with its own Remove button; the user's decision
 * is pending: [WIPE_OPENROUTER_KEY] is the one-line change), the speech models, the TTS cache and
 * the phrase rotations. None of them is tied to a Hermes account.
 *
 * A session can also end without a sign-out (an expired session read back as signed out), and the
 * next sign-in may be someone else. [ownedBy] guards that: the store remembers a hash of the
 * account it belongs to, and Bill's runtime wipes it before use when another account signs in.
 */
object CascadeSignOut {
    const val WIPE_OPENROUTER_KEY = false

    /** Every key the store holds for Bill starts with this. */
    const val STORE_PREFIX = "cascade."

    /** SHA-256 of the account (server and user) the store belongs to; never the names themselves. */
    const val OWNER_KEY = "cascade.shell.owner"

    fun apply(shell: VoiceShell) {
        shell.shell.release()
        // Hermes's voice config and the provider keys in it (memory only) go with the account.
        shell.hermesVoices.forget()
        wipe(shell)
        if (WIPE_OPENROUTER_KEY) shell.keys.remove()
        shell.onSettingsChanged()
    }

    /** Delete Bill's account-bound state (see the class comment). No runtime may be open. */
    fun wipe(shell: VoiceShell) {
        val store = shell.storage.store
        store.keys(STORE_PREFIX).forEach { store.put(it, null) }
        // The store's logs and the values it quarantined as corrupt (old account data too).
        for (sub in listOf("logs", "corrupt")) File(shell.dirs.store, sub).listFiles()?.forEach { it.deleteRecursively() }
        shell.decisionLog.clear()
        shell.dirs.decisions.delete()
    }

    /**
     * Before Bill's runtime uses the store for [server] and [user]: if the store belongs to another
     * account, wipe it first. A store with no owner yet (from before this check) is adopted.
     * Returns true if it wiped.
     */
    fun ownedBy(shell: VoiceShell, server: String, user: String?): Boolean {
        val store = shell.storage.store
        val owner = owner(server, user)
        val previous = store.get(OWNER_KEY)
        val wiped = previous != null && previous != owner
        if (wiped) wipe(shell)
        if (previous != owner) store.put(OWNER_KEY, owner)
        return wiped
    }

    fun owner(server: String, user: String?): String =
        MessageDigest.getInstance("SHA-256").digest("$server\n${user.orEmpty()}".toByteArray()).joinToString("") { "%02x".format(it) }
}
