package com.folio.hermeslive.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R

/** A task that finished while the call was off: its short [label] and the spoken [headline] (≤ 2 sentences). */
data class TaskResultNotice(val taskId: String, val label: String, val headline: String)

/**
 * The finished-task notification (R-CONV-3): the label and headline, "Hear it" (plays the
 * result through [BriefingService], mic off, also from the lock screen) and "Open" (the call
 * screen on that task). Private on the lock screen: the public version says only that a task
 * finished. Each task gets its own notification id and request codes, so one result never
 * overwrites another's actions.
 */
object ResultNotifications {
    const val ACTION_OPEN_TASK = "com.folio.hermeslive.action.OPEN_TASK"
    const val EXTRA_TASK_ID = "com.folio.hermeslive.extra.TASK_ID"

    fun notificationId(taskId: String): Int = 3000 + (taskId.hashCode() and 0x7fff)

    fun build(context: Context, notice: TaskResultNotice): Notification {
        // Hear it on the public version too: a lock screen that hides sensitive content shows only this.
        val public = Notification.Builder(context, CallNotifications.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("A task finished")
            .setContentText("Hermes Live")
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_call), "Hear it", hearIt(context, notice.taskId)).build())
            .build()
        return Notification.Builder(context, CallNotifications.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(notice.label.ifBlank { "Task finished" })
            .setContentText(notice.headline)
            .setStyle(Notification.BigTextStyle().bigText(notice.headline))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open(context, notice.taskId))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_call), "Hear it", hearIt(context, notice.taskId)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_call), "Open", open(context, notice.taskId)).build())
            .build()
    }

    /** Post [notice]. False when the app may not post notifications. */
    fun post(context: Context, notice: TaskResultNotice): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        nm.notify(notificationId(notice.taskId), build(context, notice))
        return true
    }

    fun cancel(context: Context, taskId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(taskId))
    }

    /** Starts [BriefingService] for [taskId] (a notification action may start a foreground service). */
    fun hearIt(context: Context, taskId: String): PendingIntent = PendingIntent.getForegroundService(
        context, ("hear:$taskId").hashCode(),
        BriefingService.playIntent(context, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens the call screen on [taskId]: [ACTION_OPEN_TASK] with [EXTRA_TASK_ID] (S-F reads it). */
    fun open(context: Context, taskId: String): PendingIntent = PendingIntent.getActivity(
        context, ("open:$taskId").hashCode(),
        Intent(context, LiveActivity::class.java).setAction(ACTION_OPEN_TASK).putExtra(EXTRA_TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
