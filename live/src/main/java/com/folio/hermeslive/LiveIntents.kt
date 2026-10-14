package com.folio.hermeslive

import android.content.Intent
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.service.MemoEntry
import com.folio.hermeslive.service.ResultNotifications

/**
 * How Hermes Live is opened, read from the intent (contract names are final).
 *
 * | Intent | Does |
 * |---|---|
 * | launcher (MAIN) | opens; starts a call when "Start talking when opened" is on |
 * | `com.folio.hermeslive.action.CALL` (+ `extra.PROMPT` String, `extra.MUTED` Boolean) | starts a call; PROMPT is the first request |
 * | shortcuts TALK / TALK_MUTED / NEW_CONVERSATION | starts a call (muted / fresh conversation) |
 * | `ASSIST`, `VOICE_COMMAND` (headset long press), the QS tile | starts a call, over the lock screen |
 * | `SEND` text/plain (share sheet) | starts a call with the shared text as the first request |
 * | `action.MEMO` (Memo shortcut), or the tile with `extra.MEMO` (offline) | starts a Bill call in memo mode (R-OFF-2) |
 * | `action.OPEN_TASK` + `extra.TASK_ID` (a result notification's Open) | opens Bill's screen on that task, no call |
 */
object LiveIntents {
    const val ACTION_CALL = "com.folio.hermeslive.action.CALL"
    const val EXTRA_PROMPT = "com.folio.hermeslive.extra.PROMPT"
    const val EXTRA_MUTED = "com.folio.hermeslive.extra.MUTED"
    const val ACTION_TALK = "com.folio.hermeslive.action.TALK"
    const val ACTION_TALK_MUTED = "com.folio.hermeslive.action.TALK_MUTED"
    const val ACTION_NEW_CONVERSATION = "com.folio.hermeslive.action.NEW_CONVERSATION"
    const val ACTION_TILE = "com.folio.hermeslive.action.TILE"

    /**
     * [request]: the call to start (null: just open). [onlyIfStartOnOpen]: obey the setting.
     * [wake]: show over the lock screen. [openTaskId]: show Bill's screen on this task (no call).
     */
    data class Launch(
        val request: CallRequest?,
        val onlyIfStartOnOpen: Boolean = false,
        val wake: Boolean = false,
        val openTaskId: String? = null,
    )

    fun parse(intent: Intent?): Launch {
        val action = intent?.action ?: return Launch(null)
        return when (action) {
            Intent.ACTION_MAIN -> Launch(CallRequest(entry = "launcher"), onlyIfStartOnOpen = true)
            ACTION_CALL -> Launch(CallRequest(prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim()?.ifEmpty { null }, muted = mutedExtra(intent), entry = "folio"))
            ACTION_TALK -> Launch(CallRequest(entry = "shortcut"))
            ACTION_TALK_MUTED -> Launch(CallRequest(muted = true, entry = "shortcut"))
            ACTION_NEW_CONVERSATION -> Launch(CallRequest(newConversation = true, entry = "shortcut"))
            Intent.ACTION_ASSIST -> Launch(CallRequest(entry = "assist"), wake = true)
            Intent.ACTION_VOICE_COMMAND -> Launch(CallRequest(entry = "headset"), wake = true)
            ACTION_TILE -> Launch(CallRequest(entry = "tile", memo = MemoEntry.isMemo(intent)), wake = true)
            MemoEntry.ACTION_MEMO -> Launch(CallRequest(entry = "memo", memo = true))
            ResultNotifications.ACTION_OPEN_TASK -> Launch(null, openTaskId = intent.getStringExtra(ResultNotifications.EXTRA_TASK_ID)?.ifBlank { null })
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                    .joinToString("\n").trim().take(MAX_SHARED_CHARS)
                Launch(if (text.isEmpty()) null else CallRequest(prompt = text, entry = "share"))
            }
            else -> Launch(null)
        }
    }

    private fun mutedExtra(intent: Intent): Boolean = when (val v = intent.extras?.get(EXTRA_MUTED)) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> false
    }

    const val MAX_SHARED_CHARS = 4_000
}
