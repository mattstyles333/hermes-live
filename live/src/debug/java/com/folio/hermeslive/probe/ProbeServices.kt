package com.folio.hermeslive.probe

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.folio.hermeslive.R
import com.folio.hermeslive.service.CallNotifications
import java.util.concurrent.atomic.AtomicReference

/*
 * Debug only: the off-call start probe (spec v2 §14 item 14). [ProbeHolderService] stands in for
 * the call's microphone service; [ProbeTargetService] for the off-call service (same type,
 * specialUse). The device test records whether Android lets the second start from the
 * background with the screen off. Nothing records audio: the holder never opens the mic.
 */

private fun notice(service: Service, title: String): Notification =
    Notification.Builder(service, CallNotifications.CHANNEL_TASKS)
        .setSmallIcon(R.drawable.ic_stat_call)
        .setContentTitle(title)
        .build()

class ProbeHolderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(41, notice(this, "Probe: call"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        const val STOP = "probe.stop"
    }
}

class ProbeTargetService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        outcome.set(
            try {
                startForeground(42, notice(this, "Probe: off-call"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                "foreground"
            } catch (e: RuntimeException) {
                "refused_in_service:" + e.javaClass.simpleName
            },
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        /** What the last start did: `foreground`, or `refused_in_service:<type>`. */
        val outcome = AtomicReference<String?>(null)
    }
}
