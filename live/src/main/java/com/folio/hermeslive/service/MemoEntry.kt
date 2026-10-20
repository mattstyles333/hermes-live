package com.folio.hermeslive.service

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R

/**
 * Memo entry (offline-memo slice; R-OFF-2 before it): with Settings › Voice › Offline memos on,
 * whatever the engine, the Quick Settings tile adds [EXTRA_MEMO] when it starts a call offline,
 * and a dynamic "Memo" shortcut ([ACTION_MEMO]) is published. Both open the memo recorder
 * (`MemoCall`, the first branch of `CallSwitch.plan`). Off: no shortcut, the tile starts a call
 * as before.
 */
object MemoEntry {
    const val ACTION_MEMO = "com.folio.hermeslive.action.MEMO"
    const val EXTRA_MEMO = "com.folio.hermeslive.extra.MEMO"
    const val SHORTCUT_ID = "memo"

    enum class Mode {
        /** A call on the chosen engine, as today. */
        Call,

        /** The offline memo recorder: offline, or asked for. */
        Memo,
    }

    fun mode(memosOn: Boolean, online: Boolean): Mode = if (memosOn && !online) Mode.Memo else Mode.Call

    /** Tell the launcher the Memo shortcut was used (S-F calls this when a memo starts from it). */
    fun reportUsed(context: Context) {
        runCatching { context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(SHORTCUT_ID) }
    }

    fun isMemo(intent: Intent?): Boolean =
        intent != null && (intent.action == ACTION_MEMO || intent.getBooleanExtra(EXTRA_MEMO, false))

    /** The tile's intent, marked memo when offline memos are on and the phone is offline. */
    fun forTile(intent: Intent, memosOn: Boolean, online: Boolean): Intent =
        if (mode(memosOn, online) == Mode.Memo) intent.putExtra(EXTRA_MEMO, true) else intent

    /** Publish the "Memo" launcher shortcut while offline memos are on; remove it when off. */
    fun syncShortcut(context: Context, memosOn: Boolean) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            if (memosOn) {
                val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("Memo")
                    .setLongLabel("Record a memo for Hermes (works offline)")
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
