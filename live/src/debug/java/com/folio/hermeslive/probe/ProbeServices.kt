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

/**
 * Debug only: the probe's second half, with no instrumentation in the process (an instrumented
 * app can inherit the shell's exemptions). After `am start … --ez probe_hold true` on the STT Lab,
 * Home, screen off and a wait, `am broadcast -n com.folio.hermeslive/.probe.ProbeReceiver -a
 * com.folio.hermeslive.probe.OFFCALL` waits [DELAY_MS] more (past any broadcast allowance), then
 * starts the specialUse service the way `OffCall.afterHangUp` does, and also calls
 * `OffCall.afterHangUp` itself. Logcat `OFFCALL_PROBE …`.
 */
class ProbeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ProbeTargetService.outcome.set(null)
            val start = runCatching {
                androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ProbeTargetService::class.java))
                "started"
            }.getOrElse { "refused_at_start:" + it.javaClass.simpleName }
            val gate = com.folio.hermeslive.data.CascadeGate(
                { com.folio.hermeslive.data.LiveSettings(voiceEngine = com.folio.hermeslive.data.VoiceEngine.Cascade) },
                { com.folio.hermeslive.data.KeyStatus(saved = true, check = com.folio.hermeslive.data.KeyCheck.Valid) },
            )
            val afterHangUp = runCatching { com.folio.hermeslive.service.OffCall.afterHangUp(app, tasksRunning = 1, gate = gate) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.util.Log.i(
                    "HermesProbe",
                    "OFFCALL_PROBE start=$start in_service=${ProbeTargetService.outcome.get() ?: "never_ran"} " +
                        "after_hang_up=${afterHangUp.getOrNull()} threw=${afterHangUp.exceptionOrNull()?.javaClass?.simpleName}",
                )
                app.startService(Intent(app, com.folio.hermeslive.service.OffCallService::class.java).setAction(com.folio.hermeslive.service.OffCallService.ACTION_STOP))
                app.startService(Intent(app, ProbeHolderService::class.java).setAction(ProbeHolderService.STOP))
            }, 2_000)
        }, DELAY_MS)
    }

    companion object {
        const val EXTRA_HOLD = "probe_hold"
        const val DELAY_MS = 12_000L
    }
}
