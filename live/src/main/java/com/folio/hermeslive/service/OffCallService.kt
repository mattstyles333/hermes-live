package com.folio.hermeslive.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R
import com.folio.hermeslive.data.SafeTelemetry
import com.folio.hermeslive.data.VoiceShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After hang-up, keeps the process (and through [OffCallHooks.holdGateway] the Hermes gateway)
 * alive while voice tasks finish, with **no microphone** (R-CONV-3). Stops when every task has
 * finished, after `offCallKeepAliveMs` (30 min), when the cascade is switched off, or on Stop.
 *
 * Type `specialUse` (see AGENTS.md "Off-call service"): `microphone` would keep the mic
 * indicator on, `dataSync` is time-limited on Android 15, and `remoteMessaging` is defined for
 * moving text messages between devices, which this is not.
 */
class OffCallService : Service() {
    /** Seams for tests; the app's shell by default. */
    internal var hooksProvider: () -> OffCallHooks = { VoiceShell.of(this).offCallHooks }
    internal var activeProvider: () -> Boolean = { VoiceShell.of(this).gate.active }
    internal var telemetry: TelemetrySink = SafeTelemetry()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var timer: Job? = null
    private var gateway: AutoCloseable? = null
    private var startedAt = 0L
    private var lastCount = 0
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish(OffCallStop.Stopped)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val tasks = intent.getIntExtra(EXTRA_TASKS, 0)
        val keepAlive = intent.getLongExtra(EXTRA_KEEP_ALIVE_MS, CascadeSettings().offCallKeepAliveMs)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(this, tasks), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // API 31-33 have no specialUse constant; the manifest's type applies.
                startForeground(NOTIFICATION_ID, notification(this, tasks))
            }
        } catch (e: RuntimeException) {
            runCatching { telemetry.event("offcall.stop", mapOf("reason" to OffCallStop.Refused.wire, "type" to e.javaClass.simpleName), true) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher?.isActive == true) {
            restartTimer(keepAlive) // a second hang-up: the keep-alive starts again
            return START_NOT_STICKY
        }
        startedAt = SystemClock.elapsedRealtime()
        stopped = false
        lastCount = tasks
        OffCallPolicy.stopReason(activeProvider(), tasks, 0, keepAlive)?.let {
            finish(it)
            return START_NOT_STICKY
        }
        val hooks = hooksProvider()
        gateway = runCatching { hooks.holdGateway() }.getOrNull()
        runCatching { telemetry.event("offcall.start", mapOf("tasks" to tasks, "keep_alive_ms" to keepAlive), false) }
        restartTimer(keepAlive)
        watcher = scope.launch {
            hooks.tasksRunning.collect { n ->
                lastCount = n
                val reason = OffCallPolicy.stopReason(activeProvider(), n, 0, keepAlive)
                if (reason != null) {
                    finish(reason)
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this@OffCallService, n))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun restartTimer(keepAliveMs: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(keepAliveMs)
            finish(OffCallStop.Timeout)
        }
    }

    private fun finish(reason: OffCallStop) {
        if (stopped) return
        stopped = true
        watcher?.cancel()
        timer?.cancel()
        gateway?.let { runCatching { it.close() } }
        gateway = null
        if (startedAt > 0) {
            runCatching {
                telemetry.event(
                    "offcall.stop",
                    mapOf("reason" to reason.wire, "tasks" to lastCount, "ms" to SystemClock.elapsedRealtime() - startedAt),
                    false,
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopped) finish(OffCallStop.Stopped)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.folio.hermeslive.action.OFF_CALL_START"
        const val ACTION_STOP = "com.folio.hermeslive.action.OFF_CALL_STOP"
        const val EXTRA_KEEP_ALIVE_MS = "keep_alive_ms"
        const val EXTRA_TASKS = "tasks"
        const val NOTIFICATION_ID = 2

        /** "N tasks running", with Stop and a tap that opens the app. No task names (they show on the lock screen). */
        fun notification(context: Context, tasks: Int): Notification {
            val open = PendingIntent.getActivity(
                context, REQUEST_OPEN,
                Intent(context, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, REQUEST_STOP,
                Intent(context, OffCallService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CallNotifications.CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(OffCallPolicy.runningText(tasks))
                .setContentText("Hermes is still working. The mic is off.")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_call), "Stop", stop,
                    ).build(),
                )
                .build()
        }

        private const val REQUEST_OPEN = 21
        private const val REQUEST_STOP = 22
    }
}
