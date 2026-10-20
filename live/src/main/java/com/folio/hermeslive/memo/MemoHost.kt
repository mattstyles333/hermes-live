package com.folio.hermeslive.memo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.folio.hermeslive.LiveActivity
import com.folio.hermeslive.R
import com.folio.hermeslive.cascade.AndroidNetwork
import com.folio.hermeslive.cascade.appRuntime
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.graph
import com.folio.hermeslive.tasks.CascadeTaskSubmitter
import com.folio.hermeslive.tasks.HeldTasks
import com.folio.hermeslive.service.CallNotifications
import com.folio.hermeslive.service.Notices
import com.folio.hermeslive.service.OffCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * When held memos are tried: once at start (after the account is read back), after each memo, and
 * each time the network comes back while the process lives; plus [MemoJobService], which wakes a
 * dead process once a network is there. Only when something [waiting] (else the task runtime is
 * never built for a flush).
 */
class MemoTriggers(
    private val scope: CoroutineScope,
    private val online: Flow<Boolean>,
    private val ready: suspend () -> Unit,
    private val waiting: () -> Boolean,
    private val flush: suspend (force: Boolean) -> Unit,
) {
    fun start() {
        scope.launch {
            ready()
            if (waiting()) flush(true)
        }
        scope.launch {
            online.distinctUntilChanged().drop(1).filter { it }.collect { if (waiting()) flush(true) }
        }
    }

    /** A memo was saved: send it now if it can go. */
    fun memoSaved() = scope.launch { flush(false) }
}

/**
 * Offline memos in the app (one per process): the [MemoSender] on the cascade's store, Hermes's
 * speech-to-text and the shared task API. Built while new memos may be recorded (Settings › Voice ›
 * Offline memos on, signed in), or while a memo already recorded waits: those were recorded with
 * consent and are always sent, even after the switch is turned off, in a fresh process too
 * ([ifNeeded]). Neither: nothing of it runs, no job is scheduled, no file is written.
 */
class MemoHost private constructor(private val app: Context) {
    private val shell = VoiceShell.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = MemoStore({ shell.storage.store }, shell.dirs.memo)

    private val tasks = CascadeTaskSubmitter(
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
        recording = { id -> (app.graph.calls.active.value as? MemoCall)?.recordingId == id },
    )

    private val triggers = MemoTriggers(
        scope = scope,
        online = AndroidNetwork(app).online,
        ready = { withTimeoutOrNull(READY_WAIT_MS) { app.graph.ready.await() } },
        waiting = ::waiting,
        flush = { force -> flush(force) },
    )

    init {
        // Opens the task gate for memos already recorded, before the first flush builds the runtime.
        shell.memosHeld = waiting()
        triggers.start()
    }

    /** A memo was saved. */
    fun kick() {
        shell.memosHeld = true
        triggers.memoSaved()
    }

    suspend fun flush(force: Boolean): MemoSender.Flush {
        val r = runCatching { sender.flush(force) }.getOrElse { MemoSender.Flush(0, 0, null) }
        shell.memosHeld = waiting()
        return r
    }

    private fun waiting(): Boolean = waiting(store)

    companion object {
        const val READY_WAIT_MS = 5_000L

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: MemoHost? = null

        /** The process's host; builds it (use [ifNeeded]). */
        fun of(context: Context): MemoHost = instance ?: synchronized(this) {
            instance ?: MemoHost(context.applicationContext).also { instance = it }
        }

        /** The host while memos may be recorded or one waits (or one is built already), else null. Reads the store only if it exists. */
        fun ifNeeded(context: Context): MemoHost? = instance ?: if (wanted(VoiceShell.of(context))) of(context) else null

        /** New memos may be recorded, or (signed in) one already recorded waits to be sent. */
        fun wanted(shell: VoiceShell): Boolean = shell.memosOn() || (shell.isSignedIn && shell.dirs.store.exists() && waiting(MemoStore({ shell.storage.store }, shell.dirs.memo)))

        /** Something to send, or left by a kill mid-recording. */
        fun waiting(store: MemoStore): Boolean = runCatching { store.all().any { it.pending || it.state == MemoState.Recording } }.getOrDefault(false)

        /** Tests: forget the process's host. */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() {
            instance = null
        }
    }
}

/** The quiet memo notifications (the tasks channel: low importance, no sound; private on the lock screen). */
class AndroidMemoNotices(private val context: Context) : MemoNotices {
    override fun sent(record: MemoRecord) = post(record, "Memo sent to Hermes", "It's working on it as a task; you'll get the result here.")

    override fun empty(record: MemoRecord) = post(record, "The memo had no speech", "Nothing was sent to Hermes.")

    override fun failed(record: MemoRecord) = post(record, "A memo was too long to send", "Hermes could not take it, so it was deleted.")

    private fun post(record: MemoRecord, title: String, text: String) {
        val open = android.app.PendingIntent.getActivity(
            context, Notices.id(Notices.Kind.Memo, record.id), Intent(context, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notices.post(
            context,
            Notices.id(Notices.Kind.Memo, record.id),
            Notification.Builder(context, CallNotifications.CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
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
                    // Survives a reboot (RECEIVE_BOOT_COMPLETED), so a memo held overnight still goes.
                    .setPersisted(true)
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
            val host = MemoHost.ifNeeded(this@MemoJobService)
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
        return MemoHost.ifNeeded(this)?.store?.all()?.any { it.pending } == true
    }

    companion object {
        /** Under the job's 10-minute budget. */
        const val HOLD_MS = 9 * 60_000L
    }
}
