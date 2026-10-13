package com.folio.hermeslive.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.folio.hermes.ApprovalChoice
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R
import com.folio.hermeslive.call.ApprovalCard
import com.folio.hermeslive.call.CallActivity
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallUiState

/** The ongoing-call notification (CallStyle: the status-bar call chip) and approval alerts. */
object CallNotifications {
    const val CHANNEL_CALL = "call"
    const val CHANNEL_APPROVALS = "approvals"
    const val CALL_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Call", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The ongoing Hermes Live call"
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hermes asks before it runs something"
            },
        )
    }

    fun call(context: Context, state: CallUiState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = CallActionReceiver.pending(context, CallActionReceiver.ACTION_END)
        val mute = CallActionReceiver.pending(context, CallActionReceiver.ACTION_MUTE)
        val hermes = Person.Builder()
            .setName("Hermes")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_stat_call))
            .setImportant(true)
            .build()
        val builder = Notification.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setStyle(Notification.CallStyle.forOngoingCall(hermes, end))
            .setContentIntent(open)
            .setContentText(statusLine(state))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_call),
                    if (state.muted) "Unmute" else "Mute",
                    mute,
                ).build(),
            )
        state.startedAtMs?.let { builder.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        return builder.build()
    }

    fun statusLine(state: CallUiState): String = when {
        state.phase == CallPhase.Connecting -> "Connecting…"
        state.phase == CallPhase.Reconnecting -> "Reconnecting…"
        state.phase == CallPhase.Ending -> "Ending…"
        state.onOtherCall -> "Paused for your other call"
        state.approvals.isNotEmpty() -> "Waiting for your approval"
        state.muted -> "Muted"
        state.activity == CallActivity.HermesSpeaking -> "Hermes is speaking"
        state.activity == CallActivity.Thinking -> state.tool?.let { "Hermes is working: $it" } ?: "Hermes is working"
        state.musicInGap -> "Listening · music in the gap"
        else -> "Listening"
    }

    /** One high-priority alert per open approval: Approve once / Deny (and Session when offered). */
    fun approval(context: Context, card: ApprovalCard): Notification {
        val open = PendingIntent.getActivity(
            context, card.id.hashCode(),
            Intent(context, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val request = card.request
        val text = listOf(request.description, request.command).filter { it.isNotBlank() }.joinToString("\n")
        val builder = Notification.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Hermes needs your approval")
            .setContentText(request.description.ifBlank { request.command })
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOngoing(true)
        approvalActions(card.choices).forEach { choice ->
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_call),
                    label(choice),
                    CallActionReceiver.approve(context, card.id, choice),
                ).build(),
            )
        }
        return builder.build()
    }

    /** Up to three actions: Approve once, Deny, then Session when the server offers it. */
    fun approvalActions(offered: List<ApprovalChoice>): List<ApprovalChoice> =
        listOf(ApprovalChoice.Once, ApprovalChoice.Deny, ApprovalChoice.Session).filter { it in offered || it == ApprovalChoice.Deny }

    fun label(choice: ApprovalChoice): String = when (choice) {
        ApprovalChoice.Once -> "Approve once"
        ApprovalChoice.Session -> "Approve for this session"
        ApprovalChoice.Always -> "Always approve"
        ApprovalChoice.Deny -> "Deny"
    }

    fun approvalNotificationId(id: String): Int = 1000 + (id.hashCode() and 0x7fff)
}
