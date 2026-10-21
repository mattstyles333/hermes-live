package com.folio.hermeslive.memo

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermeslive.meeting.Events
import com.folio.hermeslive.meeting.FakeTasks
import com.folio.hermeslive.service.CallNotifications
import com.folio.hermeslive.service.Notices
import com.folio.hermeslive.service.ResultNotifications
import com.folio.hermeslive.tasks.HeldTasks
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/** The real job and notices: a held memo schedules one persisted any-network job; nothing held, none. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MemoJobsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val jobs get() = app.getSystemService(JobScheduler::class.java)

    private fun sender(store: MemoStore, transcriber: MemoTranscriber) = MemoSender(
        store, HeldTasks { FakeTasks() }, { transcriber }, { ZoneId.of("UTC") }, { 0L }, Events(), AndroidMemoNotices(app), MemoJobs(app),
        taskStatus = { "done" },
    )

    @Test fun aHeldMemoSchedulesOnePersistedAnyNetworkJob() = runTest {
        val store = MemoryStore().let { kv -> MemoStore({ kv }, tmp.newFolder("memo")) }
        MemoSenderTest.wav(store.chunk("m1", 0), 32_000)
        store.put(MemoRecord("m1", 0, MemoState.Recorded, chunks = 1))
        sender(store) { _, _ -> MemoText.Hold("offline") }.flush()
        sender(store) { _, _ -> MemoText.Hold("offline") }.flush()
        val job = jobs.allPendingJobs.single()
        assertEquals(MemoJobs.JOB_ID, job.id)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue("survives a reboot", job.isPersisted)
        assertEquals(MemoJobService::class.java.name, job.service.className)

        sender(store) { _, _ -> MemoText.Text("hello") }.flush()
        assertTrue("nothing held: no job", jobs.allPendingJobs.isEmpty())
    }

    @Test fun nothingHeldSchedulesNothing() = runTest {
        val store = MemoryStore().let { kv -> MemoStore({ kv }, tmp.newFolder("memo")) }
        sender(store) { _, _ -> error("never asked") }.flush()
        assertTrue(jobs.allPendingJobs.isEmpty())
    }

    /** Final review: the flush inside the job must not cancel the job (that would stop the 9-minute result wait). */
    @Test fun aFlushInsideTheJobNeverCancelsTheRunningJob() = runTest {
        val store = MemoryStore().let { kv -> MemoStore({ kv }, tmp.newFolder("memo")) }
        MemoSenderTest.wav(store.chunk("m1", 0), 32_000)
        store.put(MemoRecord("m1", 0, MemoState.Recorded, chunks = 1))
        MemoJobs(app).schedule()
        val real = sender(store) { _, _ -> MemoText.Text("call the bank") }
        val running = kotlinx.coroutines.flow.MutableStateFlow(1)
        var released = 0
        val work = MemoJobWork(ready = {}, flush = { real.flush(inJob = true) }, holdGateway = { AutoCloseable { released++ } }, tasksRunning = running, memosHeld = { false })
        val run = async { work.run() }
        runCurrent()
        assertEquals(MemoState.Sent, store.get("m1")!!.state)
        assertTrue("still waiting for the task's result", run.isActive)
        assertTrue("the job is not cancelled under itself", jobs.getPendingJob(MemoJobs.JOB_ID) != null)
        running.value = 0
        runCurrent()
        assertEquals(1, released)
        run.await()
    }

    /** Round 3: an older server (404) is held for the next app start, not woken for by a persisted job. */
    @Test fun anOlderServerSchedulesNoJob() = runTest {
        val store = MemoryStore().let { kv -> MemoStore({ kv }, tmp.newFolder("memo")) }
        MemoSenderTest.wav(store.chunk("m1", 0), 32_000)
        store.put(MemoRecord("m1", 0, MemoState.Recorded, chunks = 1))
        val r = sender(store) { _, _ -> MemoText.Hold("server_old") }.flush()
        assertEquals(1, r.held)
        assertTrue(jobs.allPendingJobs.isEmpty())
        sender(store) { _, _ -> MemoText.Hold("timeout") }.flush()
        assertEquals("a transient hold does wake the job", 1, jobs.allPendingJobs.size)
    }

    @Test fun memoNoticesArePrivateAndNeverShareAnIdWithResultsOrApprovals() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        CallNotifications.createChannels(app)
        AndroidMemoNotices(app).failed(MemoRecord("m1", 0))
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("A memo was too long to send", posted.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(posted.extras.getString(Notification.EXTRA_TEXT)!!.contains("recording was deleted"))
        for (key in listOf("m1", "task-1", "a", "")) {
            val ids = setOf(Notices.id(Notices.Kind.Memo, key), ResultNotifications.notificationId(key), CallNotifications.approvalNotificationId(key))
            assertEquals(3, ids.size)
        }
        assertNotEquals(Notices.Kind.Memo.base, Notices.Kind.Result.base)
        assertTrue(Notices.Kind.entries.zipWithNext().all { (a, b) -> a.base + 0xffff < b.base })
    }
}
