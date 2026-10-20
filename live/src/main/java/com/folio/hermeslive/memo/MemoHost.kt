package com.folio.hermeslive.memo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R
import com.folio.hermeslive.cascade.AndroidNetwork
import com.folio.hermeslive.cascade.appRuntime
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.graph
import com.folio.hermeslive.meeting.CascadeMeetingTasks
import com.folio.hermeslive.meeting.HeldTasks
import com.folio.hermeslive.service.CallNotifications
import com.folio.hermeslive.service.OffCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * Offline memos in the app (one per process): the [MemoSender] on the cascade's store, Hermes's
 * speech-to-text and the shared task API. Built only while Settings › Voice › Offline memos is on
 * ([ifEnabled]): off, nothing of it runs, no job is scheduled, no file is written.
 *
 * Sends: once at start (after the account is read back), after each memo, whenever the network is
 * back while the process lives, when a backoff is due, and from [MemoJobService] (which wakes a
 * dead process once the network is back).
 */
class MemoHost private constructor(private val app: Context) {
    private val shell = VoiceShell.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val network by lazy { AndroidNetwork(app) }
    val store = MemoStore({ shell.storage.store }, shell.dirs.memo)

    private val tasks = CascadeMeetingTasks(
        tasks = { withContext(Dispatchers.Default) { shell.shell.appRuntime()?.core?.tasks } },
        signedIn = { app.graph.account.state.value is AccountState.SignedIn },
        // Keep the gateway open while the memo's task runs (only allowed while the app is in front).
        afterStart = { OffCall.afterHangUp(app, maxOf(1, shell.offCallHooks.tasksRunning.value)) },
        noRuntime = "no_runtime",
    )

    val sender = MemoSender(
        store = store,
        held = HeldTasks { tasks },
        transcriber = { HermesMemoTranscriber { shell.hermesVoices.voice() } },
        zone = { ZoneId.systemDefault() },
        wall = System::currentTimeMillis,
        telemetry = shell.telemetry,
        notices = AndroidMemoNotices(app),
        scheduler = MemoJobs(app),
        recording = { id -> (app.graph.calls.memoEngine as? MemoCall)?.recordingId == id },
    )

    private var retry: Job? = null

    init {
        scope.launch {
            withTimeoutOrNull(READY_WAIT_MS) { app.graph.ready.await() }
            if (waiting()) flush(force = true)
        }
        // Held memos go when the network comes back.
        scope.launch {
            network.online.distinctUntilChanged().drop(1).filter { it }.collect { if (waiting()) flush(force = true) }
        }
    }

    /** Anything to send (or left by a kill mid-recording)? Only then is the task runtime built for a flush. */
    private fun waiting(): Boolean = runCatching { store.all().any { it.pending || it.state == MemoState.Recording } }.getOrDefault(false)

    /** A memo ended (or the user asked): try what is due now. */
    fun kick() = scope.launch { flush(force = false) }

    /** Flush, then wait for the earliest backoff while the process lives. */
    suspend fun flush(force: Boolean): MemoSender.Flush {
        val r = runCatching { sender.flush(force) }.getOrElse { MemoSender.Flush(0, 0, null) }
        retry?.cancel()
        r.nextAttemptAt?.let { at ->
            retry = scope.launch {
                delay((at - System.currentTimeMillis()).coerceAtLeast(0))
                flush(force = false)
            }
        }
        return r
    }

    companion object {
        const val READY_WAIT_MS = 5_000L

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: MemoHost? = null

        /** The process's host; builds it (use [ifEnabled]). */
        fun of(context: Context): MemoHost = instance ?: synchronized(this) {
            instance ?: MemoHost(context.applicationContext).also { instance = it }
        }

        /** The host while offline memos are on (or one is built already), else null. Nothing is built while off. */
        fun ifEnabled(context: Context): MemoHost? =
            instance ?: if (context.applicationContext.graph.settings.settings.value.offlineMemos) of(context) else null
    }
}

/** The quiet memo notifications (the tasks channel: low importance, no sound). */
class AndroidMemoNotices(private val context: Context) : MemoNotices {
    override fun sent(record: MemoRecord) = post(record, "Memo sent to Hermes", "It's working on it as a task; you'll get the result here.")

    override fun empty(record: MemoRecord) = post(record, "The memo had no speech", "Nothing was sent to Hermes.")

    private fun post(record: MemoRecord, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = android.app.PendingIntent.getActivity(
            context, 4_100, Intent(context, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_BASE + (record.id.hashCode() and 0xfff),
            Notification.Builder(context, CallNotifications.CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val NOTIFICATION_BASE = 4_000
    }
}

/** The one job that wakes the app to send held memos once any network is there (no new dependency). */
class MemoJobs(private val context: Context) : MemoScheduler {
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)

    override fun schedule() {
        val js = scheduler ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        runCatching {
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, MemoJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(MemoSender.BACKOFF_MIN_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build(),
            )
        }
    }

    override fun cancel() {
        runCatching { scheduler?.cancel(JOB_ID) }
    }

    companion object {
        const val JOB_ID = 4_201
    }
}

/**
 * Wakes with the network: waits for the account, transcribes and submits held memos. It can't
 * start the off-call service from the background, so after a submit it holds the runtime's gateway
 * and waits for the task within its own time ([HOLD_MS], or until [onStopJob]); the result is
 * posted as usual. A longer task's result is posted when the app next runs (`core.restore()`), or
 * at once if the process is still alive.
 */
class MemoJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val host = MemoHost.ifEnabled(this@MemoJobService)
            if (host == null) {
                jobFinished(params, false)
                return@launch
            }
            withTimeoutOrNull(MemoHost.READY_WAIT_MS) { graph.ready.await() }
            val r = host.flush(force = true)
            if (r.sent > 0) {
                val hold = runCatching { VoiceShell.of(this@MemoJobService).offCallHooks.holdGateway() }.getOrNull()
                try {
                    withTimeoutOrNull(HOLD_MS) { VoiceShell.of(this@MemoJobService).offCallHooks.tasksRunning.first { it == 0 } }
                } finally {
                    runCatching { hold?.close() }
                }
            }
            // Still held for a reason the network won't fix by itself: the job's own backoff tries again.
            jobFinished(params, r.held > 0 && r.reasons.any { it !in MemoSender.NO_BACKOFF })
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        return MemoHost.ifEnabled(this)?.store?.all()?.any { it.pending } == true
    }

    companion object {
        /** Under the job's 10-minute budget. */
        const val HOLD_MS = 9 * 60_000L
    }
}
