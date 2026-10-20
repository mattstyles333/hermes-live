package com.folio.hermeslive.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The app's per-item notifications (an approval, a task's result, a memo): one id scheme, each kind
 * in its own range so one never replaces another, and one way to post them.
 */
object Notices {
    enum class Kind(val base: Int) { Approval(0x10000), Result(0x20000), Memo(0x30000) }

    fun id(kind: Kind, key: String): Int = kind.base + (key.hashCode() and 0xffff)

    /** Post [notification] as [id]. False when the app may not post notifications. */
    fun post(context: Context, id: Int, notification: Notification): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        nm.notify(id, notification)
        return true
    }
}
