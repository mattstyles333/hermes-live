package com.folio.hermeslive.service

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R

/**
 * Memo entry (R-OFF-2): with the cascade on, a call never fails just because there is no
 * network; it starts in memo mode (captions and the pause ring work, turns are held and sent on
 * reconnect, R-OFF-1..4). The Quick Settings tile adds [EXTRA_MEMO] when it starts a call
 * offline, and a dynamic "Memo" shortcut ([ACTION_MEMO]) is published while the cascade is on.
 *
 * S-F reads [isMemo] in the activity's intent handling (`LiveIntents` is not this slice's file)
 * and starts the cascade call with memo on.
 */
object MemoEntry {
    const val ACTION_MEMO = "com.folio.hermeslive.action.MEMO"
    const val EXTRA_MEMO = "com.folio.hermeslive.extra.MEMO"
    const val SHORTCUT_ID = "memo"

    enum class Mode {
        /** GPT-Live (the cascade is off). It needs the network, as today. */
        GptLive,

        /** The cascade, online. */
        Cascade,

        /** The cascade in memo mode: offline, or asked for. */
        Memo,
    }

    fun mode(cascadeActive: Boolean, online: Boolean, memoAsked: Boolean = false): Mode = when {
        !cascadeActive -> Mode.GptLive
        !online || memoAsked -> Mode.Memo
        else -> Mode.Cascade
    }

    fun isMemo(intent: Intent?): Boolean =
        intent != null && (intent.action == ACTION_MEMO || intent.getBooleanExtra(EXTRA_MEMO, false))

    /** The tile's intent, marked memo when the cascade is on and the phone is offline. */
    fun forTile(intent: Intent, cascadeActive: Boolean, online: Boolean): Intent =
        if (mode(cascadeActive, online) == Mode.Memo) intent.putExtra(EXTRA_MEMO, true) else intent

    /** Publish the "Memo" launcher shortcut while the cascade is on; remove it when it is off. */
    fun syncShortcut(context: Context, cascadeActive: Boolean) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            if (cascadeActive) {
                val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("Memo")
                    .setLongLabel("Dictate a memo (works offline)")
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_talk))
                    .setIntent(Intent(context, LiveActivity::class.java).setAction(ACTION_MEMO))
                    .build()
                sm.addDynamicShortcuts(listOf(shortcut))
            } else {
                sm.removeDynamicShortcuts(listOf(SHORTCUT_ID))
            }
        }
    }
}
