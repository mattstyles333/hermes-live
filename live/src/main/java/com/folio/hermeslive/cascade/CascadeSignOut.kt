package com.folio.hermeslive.cascade

import com.folio.hermeslive.data.VoiceShell

/**
 * Hermes sign-out and Bill (spec v2 §14 item 15). Bill's runtime belongs to the signed-in account,
 * so it is always closed (its gateway and task sessions go). The OpenRouter key is a separate
 * credential with its own Remove button: it is **kept** — the user's decision is pending. To wipe
 * it on sign-out instead, set [WIPE_OPENROUTER_KEY] to true (the one-line change).
 */
object CascadeSignOut {
    const val WIPE_OPENROUTER_KEY = false

    fun apply(shell: VoiceShell) {
        shell.shell.release()
        if (WIPE_OPENROUTER_KEY) shell.keys.remove()
        shell.onSettingsChanged()
    }
}
