package com.folio.hermeslive.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.folio.hermes.ApprovalChoice
import com.folio.hermeslive.graph

/** Notification actions: Mute, End, and approval answers. Not exported. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.graph.engine
        when (intent.action) {
            ACTION_MUTE -> engine.toggleMute()
            ACTION_END -> engine.end()
            ACTION_APPROVE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val choice = intent.getStringExtra(EXTRA_CHOICE)?.let { wire -> ApprovalChoice.entries.firstOrNull { it.wire == wire } } ?: return
                engine.answerApproval(id, choice)
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "com.folio.hermeslive.action.MUTE"
        const val ACTION_END = "com.folio.hermeslive.action.END"
        const val ACTION_APPROVE = "com.folio.hermeslive.action.APPROVE"
        const val EXTRA_ID = "id"
        const val EXTRA_CHOICE = "choice"

        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun approve(context: Context, id: String, choice: ApprovalChoice): PendingIntent = PendingIntent.getBroadcast(
            context, (id + choice.wire).hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(ACTION_APPROVE)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_CHOICE, choice.wire),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
