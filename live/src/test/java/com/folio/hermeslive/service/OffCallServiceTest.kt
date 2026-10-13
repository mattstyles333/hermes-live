package com.folio.hermeslive.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.data.FakeOffCallHooks
import com.folio.hermeslive.data.RecordingSink
import com.folio.hermeslive.data.SilentPlayer
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OffCallServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val main get() = shadowOf(Looper.getMainLooper())

    private fun start(
        hooks: FakeOffCallHooks,
        tasks: Int = hooks.running.value,
        keepAliveMs: Long = 60_000,
        active: () -> Boolean = { true },
        sink: TelemetrySink = TelemetrySink.None,
    ): ServiceController<OffCallService> {
        val intent = Intent(app, OffCallService::class.java).setAction(OffCallService.ACTION_START)
            .putExtra(OffCallService.EXTRA_TASKS, tasks).putExtra(OffCallService.EXTRA_KEEP_ALIVE_MS, keepAliveMs)
        val controller = Robolectric.buildService(OffCallService::class.java, intent)
        controller.get().apply {
            hooksProvider = { hooks }
            activeProvider = active
            telemetry = sink
        }
        controller.create().startCommand(0, 1)
        main.idle()
        return controller
    }

    // ── policy (pure) ───────────────────────────────────────────────────────────────────────

    @Test fun policyStartsOnlyWithTheCascadeOnAndTasksRunning() {
        assertTrue(OffCallPolicy.shouldStart(true, 1))
        assertFalse(OffCallPolicy.shouldStart(true, 0))
        assertFalse(OffCallPolicy.shouldStart(false, 3))
        assertNull(OffCallPolicy.stopReason(true, 2, 1_000, 60_000))
        assertEquals(OffCallStop.TasksDone, OffCallPolicy.stopReason(true, 0, 1_000, 60_000))
        assertEquals(OffCallStop.Timeout, OffCallPolicy.stopReason(true, 2, 60_000, 60_000))
        assertEquals(OffCallStop.CascadeOff, OffCallPolicy.stopReason(false, 2, 0, 60_000))
        assertEquals("1 task running", OffCallPolicy.runningText(1))
        assertEquals("3 tasks running", OffCallPolicy.runningText(3))
    }

    // ── service ─────────────────────────────────────────────────────────────────────────────

    @Test fun runsInTheForegroundWhileTasksRunAndStopsWhenAllFinish() {
        val hooks = FakeOffCallHooks(initialRunning = 2)
        val sink = RecordingSink()
        val service = start(hooks, sink = sink).get()
        val shadow = shadowOf(service)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals("2 tasks running", shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(1, hooks.held.get())

        hooks.running.value = 1
        main.idle()
        assertFalse(shadow.isStoppedBySelf)
        val shown = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).getNotification(OffCallService.NOTIFICATION_ID)
        assertEquals("1 task running", shown.extras.getString(Notification.EXTRA_TITLE))

        hooks.running.value = 0
        main.idle()
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertEquals("the gateway is let go", 1, hooks.released.get())
        val stop = sink.events.last()
        assertEquals("offcall.stop", stop.first)
        assertEquals("tasks_done", stop.second["reason"])
    }

    @Test fun stopsAtTheKeepAliveTimeout() {
        val hooks = FakeOffCallHooks(initialRunning = 1)
        val sink = RecordingSink()
        val service = start(hooks, keepAliveMs = 30 * 60_000L, sink = sink).get()
        main.idleFor(Duration.ofMinutes(29))
        assertFalse(shadowOf(service).isStoppedBySelf)
        main.idleFor(Duration.ofMinutes(2))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, hooks.released.get())
        assertEquals("timeout", sink.events.last().second["reason"])
    }

    @Test fun stopsAtOnceWhenNothingIsRunningOrTheCascadeIsOff() {
        val idle = FakeOffCallHooks(initialRunning = 0)
        assertTrue(shadowOf(start(idle, tasks = 0).get()).isStoppedBySelf)
        assertEquals("nothing held when nothing runs", 0, idle.held.get())

        val off = FakeOffCallHooks(initialRunning = 2)
        assertTrue(shadowOf(start(off, active = { false }).get()).isStoppedBySelf)
        assertEquals(0, off.held.get())
    }

    @Test fun theStopActionEndsIt() {
        val hooks = FakeOffCallHooks(initialRunning = 3)
        val controller = start(hooks)
        controller.withIntent(Intent(app, OffCallService::class.java).setAction(OffCallService.ACTION_STOP)).startCommand(0, 2)
        main.idle()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(1, hooks.released.get())
    }

    @Test fun theNotificationOpensTheAppAndOffersStop() {
        val n = OffCallService.notification(app, 2)
        assertEquals("2 tasks running", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.extras.getString(Notification.EXTRA_TEXT)!!.contains("mic is off"))
        assertEquals(LiveActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component!!.className)
        val stop = n.actions.single()
        assertEquals("Stop", stop.title)
        val stopIntent = shadowOf(stop.actionIntent)
        assertTrue(stopIntent.isServiceIntent)
        assertEquals(OffCallService.ACTION_STOP, stopIntent.savedIntent.action)
    }

    // ── manifest: the declared types (no microphone) ─────────────────────────────────────────

    @Test fun neitherServiceCanUseTheMicrophone() {
        val pm = app.packageManager
        val off = pm.getServiceInfo(ComponentName(app, OffCallService::class.java), PackageManager.GET_META_DATA)
        val brief = pm.getServiceInfo(ComponentName(app, BriefingService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, off.foregroundServiceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, brief.foregroundServiceType)
        assertEquals(0, off.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertEquals(0, brief.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertFalse(off.exported)
        assertFalse(brief.exported)
    }

    // ── result notification ─────────────────────────────────────────────────────────────────

    @Test fun aResultOffersHearItAndOpenForItsOwnTask() {
        val n = ResultNotifications.build(app, TaskResultNotice("task-1", "Supplier prices", "Prices are in. Two need your OK."))
        assertEquals("Supplier prices", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("A task finished", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Hear it", "Open"), n.actions.map { it.title.toString() })

        val hear = shadowOf(n.actions[0].actionIntent)
        assertTrue("Hear it starts a foreground service", hear.isForegroundServiceIntent)
        assertEquals(BriefingService::class.java.name, hear.savedIntent.component!!.className)
        assertEquals(BriefingService.ACTION_PLAY, hear.savedIntent.action)
        assertEquals("task-1", hear.savedIntent.getStringExtra(ResultNotifications.EXTRA_TASK_ID))

        val open = shadowOf(n.actions[1].actionIntent)
        assertTrue(open.isActivityIntent)
        assertEquals(LiveActivity::class.java.name, open.savedIntent.component!!.className)
        assertEquals(ResultNotifications.ACTION_OPEN_TASK, open.savedIntent.action)
        assertEquals("task-1", open.savedIntent.getStringExtra(ResultNotifications.EXTRA_TASK_ID))
        assertEquals(ResultNotifications.ACTION_OPEN_TASK, shadowOf(n.contentIntent).savedIntent.action)
    }

    @Test fun twoResultsKeepTheirOwnActions() {
        val a = ResultNotifications.build(app, TaskResultNotice("task-a", "A", "a"))
        val b = ResultNotifications.build(app, TaskResultNotice("task-b", "B", "b"))
        assertNotEquals(ResultNotifications.notificationId("task-a"), ResultNotifications.notificationId("task-b"))
        assertEquals("task-a", shadowOf(a.actions[0].actionIntent).savedIntent.getStringExtra(ResultNotifications.EXTRA_TASK_ID))
        assertEquals("task-b", shadowOf(b.actions[0].actionIntent).savedIntent.getStringExtra(ResultNotifications.EXTRA_TASK_ID))
        assertNotEquals(shadowOf(a.actions[0].actionIntent).requestCode, shadowOf(b.actions[0].actionIntent).requestCode)
    }

    @Test fun aResultIsPostedOnlyWhenNotificationsAreAllowed() {
        val notice = TaskResultNotice("task-p", "P", "p")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(OffCall.postResult(app, notice))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(OffCall.postResult(app, notice))
        val nm = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        assertEquals("P", nm.getNotification(ResultNotifications.notificationId("task-p")).extras.getString(Notification.EXTRA_TITLE))
    }

    // ── Hear it ─────────────────────────────────────────────────────────────────────────────

    private fun hear(
        hooks: FakeOffCallHooks,
        player: SilentPlayer,
        taskId: String? = "task-1",
        whilePlaying: (BriefingService) -> Unit = {},
    ): ServiceController<BriefingService> {
        val controller = Robolectric.buildService(BriefingService::class.java, BriefingService.playIntent(app, taskId))
        val service = controller.get()
        player.onPlay = { whilePlaying(service) }
        service.apply {
            hooksProvider = { hooks }
            playerProvider = { player }
            telemetry = TelemetrySink.None
        }
        controller.create().startCommand(0, 1)
        main.idle()
        return controller
    }

    @Test fun hearItPlaysTheResultAsMediaWithTheMicOffThenStops() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val clip = AudioClip(ByteArray(480), SpeechFormat.Pcm())
        val hooks = FakeOffCallHooks(briefing = Briefing(listOf(clip, clip)))
        val player = SilentPlayer()
        var shown: Notification? = null
        val service = hear(hooks, player, whilePlaying = { s -> shown = shadowOf(s).lastForegroundNotification }).get()
        assertEquals(listOf<String?>("task-1"), hooks.briefed)
        assertEquals(2, player.played.size)
        val n = shown!!
        assertTrue("a media session for the lock screen", n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals("Bill · result", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun hearItWithNothingToPlayStops() {
        val hooks = FakeOffCallHooks(briefing = null)
        val player = SilentPlayer()
        val service = hear(hooks, player, taskId = null).get()
        assertEquals(listOf<String?>(null), hooks.briefed)
        assertTrue(player.played.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
