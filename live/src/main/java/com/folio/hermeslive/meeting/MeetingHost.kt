package com.folio.hermeslive.meeting

import android.annotation.SuppressLint
import android.content.Context
import com.folio.hermes.cascade.ports.Clock
import com.folio.hermes.openrouter.OpenRouter
import com.folio.hermes.openrouter.OpenRouterConfig
import com.folio.hermeslive.audio.AndroidCallEffects
import com.folio.hermeslive.cascade.AndroidNetwork
import com.folio.hermeslive.stt.OpenRouterCloudStt
import com.folio.hermeslive.cascade.appRuntime
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.graph
import com.folio.hermeslive.service.OffCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Meeting mode in the app (one per process): the [MeetingFlow] on the real speech pipeline, the
 * cascade's store, the shared task API and the phone's haptics. Built only while Settings ›
 * Meeting mode is on ([ifEnabled]), so with it off nothing of meeting mode runs; GPT-Live calls
 * never touch it.
 *
 * - **Gate:** Bill's speech models on the phone ([SherpaMeetingSpeech.ready]). Not the voice
 *   engine and not the OpenRouter key: meetings work offline and without Hermes. Sending the
 *   notes needs Bill (engine and key) and a signed-in Hermes; until then they wait on the phone.
 * - **On start-up:** old transcripts are deleted per the retention setting, a meeting cut off by a
 *   kill is offered again, and anything held is tried again (and again whenever the network is back).
 */
class MeetingHost private constructor(private val app: Context) {
    private val shell = VoiceShell.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs: MeetingPrefs = prefs(app)
    val log = MeetingLog { shell.storage.store }
    private val effects by lazy { AndroidCallEffects(app) }
    private val network by lazy { AndroidNetwork(app) }

    /** OpenRouter on the user's key: the writer (chunk summaries), cleanup and cloud speech. No key: no request. */
    private val openRouter by lazy { OpenRouter(OpenRouterConfig(), key = shell.keys::key, client = app.graph.client, telemetry = shell.telemetry) }

    val tasks: MeetingTasks = CascadeMeetingTasks(
        tasks = { withContext(Dispatchers.Default) { shell.shell.appRuntime()?.core?.tasks } },
        signedIn = { app.graph.account.state.value is AccountState.SignedIn },
        // Keep the gateway open while the notes task runs, so its result comes back as a notification.
        afterStart = { OffCall.afterHangUp(app, maxOf(1, shell.offCallHooks.tasksRunning.value)) },
    )

    val outbox = MeetingOutbox(log, { tasks }, { openRouter.meetingWriter() }, { ZoneId.systemDefault() }, System::currentTimeMillis, shell.telemetry)

    val flow = MeetingFlow(
        log = log,
        outbox = outbox,
        settings = { prefs.settings.value },
        modelsReady = { SherpaMeetingSpeech.ready(app.graph.modelManager, app.graph.settings.settings.value) },
        loadSpeech = { withContext(Dispatchers.IO) { loadSpeech() } },
        clock = Clock.System,
        scope = scope,
        cleaner = ::cleaner,
        ack = { kind -> effects.haptic(strong = kind == Ack.Task) },
        telemetry = shell.telemetry,
    )

    private fun loadSpeech(): MeetingSpeech {
        val live = app.graph.settings.settings.value
        val settings = { app.graph.settings.settings.value.cascadeSettings() }
        return SherpaMeetingSpeech.load(
            app.assets, app.graph.modelManager, live, settings,
            hotwords = { shell.vocabulary.hotwords(app.graph.settings.settings.value.vocabulary) },
            // Cloud speech recognition only when the user chose it and a key is saved (as for calls).
            cloud = if (settings().cloudSttModel != null) OpenRouterCloudStt(openRouter.transcription, network::isOnline) else null,
        )
    }

    /** Settings › Voice › Clean up transcripts, applied per stretch of meeting speech while online with a key. */
    private fun cleaner(): SegmentCleaner? {
        val s = app.graph.settings.settings.value
        if (!s.cascadeSettings().cleanup || !shell.keys.status.value.usable || !network.isOnline()) return null
        return OpenRouterSegmentCleaner(openRouter, { shell.vocabulary.hotwords(s.vocabulary) })
    }

    init {
        scope.launch {
            MeetingRetention.prune(log, System.currentTimeMillis(), prefs.settings.value.retentionDays, shell.telemetry)
            flow.restore()
        }
        // Held notes and tasks go when the network comes back (and once now).
        scope.launch {
            network.online.distinctUntilChanged().filter { it }.collect {
                if (log.all().any { m -> m.phase == MeetingPhase.Queued } || log.tasks().any { t -> t.taskId == null }) outbox.flush()
            }
        }
    }

    /** Settings › Meeting mode › Keep transcripts changed: apply it now. */
    fun prune() = scope.launch { MeetingRetention.prune(log, System.currentTimeMillis(), prefs.settings.value.retentionDays, shell.telemetry) }

    /** Try everything held now (Settings › history, or the Done screen's "Try again"). */
    fun retry() = scope.launch { outbox.flush() }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: MeetingHost? = null

        @SuppressLint("StaticFieldLeak")
        @Volatile private var prefsInstance: MeetingPrefs? = null

        fun prefs(context: Context): MeetingPrefs = prefsInstance ?: synchronized(this) {
            prefsInstance ?: MeetingPrefs(context.applicationContext.getSharedPreferences(MeetingPrefs.PREFS, Context.MODE_PRIVATE)).also { prefsInstance = it }
        }

        /** The process's host; builds it (use [ifEnabled] from the UI). */
        fun of(context: Context): MeetingHost = instance ?: synchronized(this) {
            instance ?: MeetingHost(context.applicationContext).also { instance = it }
        }

        /** The host when meeting mode is on (or one is already built: a meeting in progress), else null. Nothing is built while off. */
        fun ifEnabled(context: Context): MeetingHost? = instance ?: if (prefs(context).settings.value.enabled) of(context) else null

        /** The host if one was built, without building it. */
        fun existing(): MeetingHost? = instance
    }
}
