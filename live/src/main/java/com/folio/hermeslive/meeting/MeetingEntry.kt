package com.folio.hermeslive.meeting

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R

/**
 * How meeting mode is opened: the home screen's Meeting button, the "Take meeting notes"
 * launcher shortcut ([ACTION_MEETING], published only while Settings › Meeting mode is on), and
 * the notification (its tap shows the meeting; its End is [ACTION_MEETING_END]).
 */
object MeetingEntry {
    const val ACTION_MEETING = "com.folio.hermeslive.action.MEETING"
    const val ACTION_MEETING_END = "com.folio.hermeslive.action.MEETING_END"
    const val SHORTCUT_ID = "meeting"

    /** What an intent asks of meeting mode. */
    enum class Ask { None, Open, End }

    fun parse(intent: Intent?): Ask = when (intent?.action) {
        ACTION_MEETING -> Ask.Open
        ACTION_MEETING_END -> Ask.End
        else -> Ask.None
    }

    /** Publish "Take meeting notes" while meeting mode is on; remove it when off. */
    fun syncShortcut(context: Context, enabled: Boolean) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            if (enabled) {
                val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("Meeting notes")
                    .setLongLabel("Take meeting notes")
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_talk))
                    .setIntent(Intent(context, LiveActivity::class.java).setAction(ACTION_MEETING))
                    .build()
                sm.addDynamicShortcuts(listOf(shortcut))
            } else {
                sm.removeDynamicShortcuts(listOf(SHORTCUT_ID))
            }
        }
    }

    fun reportUsed(context: Context) {
        runCatching { context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(SHORTCUT_ID) }
    }
}
