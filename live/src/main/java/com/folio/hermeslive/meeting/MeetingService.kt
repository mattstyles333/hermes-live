package com.folio.hermeslive.meeting

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps meeting mode's mic alive in the foreground (type microphone) with the "Taking meeting
 * notes" notification (a chronometer; Pause/Resume and End), so the screen can be off for a
 * two-hour meeting. Started only from the visible activity (Android 14's while-in-use rule);
 * it stays in the foreground through a pause (the mic is closed, and reopened on Resume from
 * here) and stops itself once the meeting is sent, saved or discarded. A partial wake lock is
 * held only while recording.
 */
class MeetingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var idle: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = MeetingHost.existing()
        if (host == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> start(host)
            ACTION_PAUSE -> host.flow.pause()
            ACTION_RESUME -> host.flow.resume()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    private fun start(host: MeetingHost) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        createChannel(this)
        try {
            startForeground(NOTIFICATION_ID, notification(this, host.flow.screen.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            Telemetry.error("meeting.foreground_refused", e)
            stopSelf()
            return
        }
        scope.launch { host.flow.serviceStarted() }
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            host.flow.screen.map { View.of(it) }.distinctUntilChanged().collect { view ->
                if (view == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                holdWake(view.recording)
                idle?.cancel()
                // The intro or "Anything to add?" left alone: free the mic and the models.
                if (view.phase == "intro" || view.phase == "ending") {
                    idle = scope.launch {
                        delay(IDLE_MS)
                        if (view.phase == "intro") host.flow.cancelIntro() else host.flow.releaseMic()
                    }
                }
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this@MeetingService, host.flow.screen.value))
            }
        }
    }

    @Suppress("WakelockTimeout")
    private fun holdWake(on: Boolean) {
        if (on && wake?.isHeld != true) {
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesLive:meeting")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_MAX_MS)
            }
        } else if (!on) {
            wake?.takeIf { it.isHeld }?.release()
        }
    }

    override fun onDestroy() {
        holdWake(false)
        scope.cancel()
        super.onDestroy()
    }

    /** What the notification shows; null: the service has nothing left to do. */
    data class View(val phase: String, val title: String, val since: Long, val recording: Boolean) {
        companion object {
            fun of(screen: MeetingScreen): View? = when (screen) {
                is MeetingScreen.Intro -> View("intro", "", 0, false)
                is MeetingScreen.Taking -> screen.live.meta.let { m ->
                    View(m.phase.name, m.title, m.startedAt + m.pausedMs, m.phase == MeetingPhase.Recording)
                }
                // Only while the mic is kept for one spoken addition.
                is MeetingScreen.Ending -> if (screen.canSpeak) View("ending", screen.meta.title, 0, false) else null
                else -> null
            }
        }
    }

    companion object {
        const val ACTION_START = "com.folio.hermeslive.meeting.START"
        const val ACTION_PAUSE = "com.folio.hermeslive.meeting.PAUSE"
        const val ACTION_RESUME = "com.folio.hermeslive.meeting.RESUME"
        const val CHANNEL = "meeting"
        const val NOTIFICATION_ID = 7
        const val WAKE_MAX_MS = 4 * 60 * 60_000L

        /** The intro or "Anything to add?" with nobody there: the mic service stops after this. */
        const val IDLE_MS = 10 * 60_000L

        /** From the visible activity only (the mic foreground-service rule). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeetingService::class.java).setAction(ACTION_START))
        }

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Meeting notes", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Hermes Live is taking meeting notes (the mic is on)"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }

        /** "Taking meeting notes · 23:41" (a chronometer), Pause/Resume and End; paused shows the time it stopped at. */
        fun notification(context: Context, screen: MeetingScreen): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, LiveActivity::class.java).setAction(MeetingEntry.ACTION_MEETING).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val icon = Icon.createWithResource(context, R.drawable.ic_stat_call)
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            when (screen) {
                is MeetingScreen.Taking -> {
                    val m = screen.live.meta
                    val now = System.currentTimeMillis()
                    if (m.phase == MeetingPhase.Recording) {
                        b.setContentTitle("Taking meeting notes").setWhen(now - m.elapsedMs(now)).setUsesChronometer(true).setShowWhen(true)
                        b.addAction(Notification.Action.Builder(icon, "Pause", service(context, ACTION_PAUSE)).build())
                    } else {
                        b.setContentTitle("Meeting notes paused · " + clock(m.elapsedMs(now))).setShowWhen(false)
                        b.addAction(Notification.Action.Builder(icon, "Resume", service(context, ACTION_RESUME)).build())
                    }
                    b.setContentText(if (m.phase == MeetingPhase.Recording) "Listening silently" else "The mic is off")
                    b.addAction(Notification.Action.Builder(icon, "End", activity(context, MeetingEntry.ACTION_MEETING_END)).build())
                }
                is MeetingScreen.Ending -> b.setContentTitle("Meeting ended").setContentText("Add anything, then send the notes")
                else -> b.setContentTitle("Meeting notes").setContentText("Getting ready…")
            }
            return b.build()
        }

        private fun service(context: Context, action: String): PendingIntent =
            PendingIntent.getService(context, action.hashCode(), Intent(context, MeetingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

        private fun activity(context: Context, action: String): PendingIntent = PendingIntent.getActivity(
            context, action.hashCode(),
            Intent(context, LiveActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** "23:41", "1:02:05". */
        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
