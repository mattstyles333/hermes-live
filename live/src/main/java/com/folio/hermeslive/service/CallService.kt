package com.folio.hermeslive.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.service.quicksettings.TileService
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.folio.hermes.telemetry.Telemetry
import com.folio.hermeslive.call.VoiceCall
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.CallSwitch
import com.folio.hermeslive.call.MemoCause
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.call.HeadsetButtons
import com.folio.hermeslive.call.HeadsetCommand
import com.folio.hermeslive.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the call alive in the foreground (type microphone) with the CallStyle notification,
 * posts approval alerts, and takes headset buttons through a MediaSession. Started only from the
 * visible activity (Android 14 while-in-use rule for the microphone); stops itself when the
 * call ends.
 */
class CallService : Service() {
    /** Seam for tests; the app's engine by default. */
    internal var engineProvider: () -> VoiceCall = { graph.calls }
    private val engine: VoiceCall get() = engineProvider()

    /** The call this service's latest start began (the switch's engine for it); onDestroy ends only this one. */
    private var call: VoiceCall? = null
    private var watcher: Job? = null
    private var media: MediaSession? = null
    private var longPressHandled = false
    private val shownApprovals = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                CallNotifications.CALL_ID,
                CallNotifications.call(this, engine.state.value.copy(phase = com.folio.hermeslive.call.CallPhase.Connecting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: RuntimeException) {
            // Not allowed from here (e.g. not started from the foreground): do not record without the notice.
            Telemetry.error("call.foreground_refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // Start first: the watcher collects inline (Main.immediate) and must never see the idle
        // state from before this call, or it would stop the service at once.
        engine.start(
            CallRequest(
                prompt = intent.getStringExtra(EXTRA_PROMPT),
                muted = intent.getBooleanExtra(EXTRA_MUTED, false),
                newConversation = intent.getBooleanExtra(EXTRA_NEW_CONVERSATION, false),
                entry = intent.getStringExtra(EXTRA_ENTRY) ?: "app",
                memo = intent.getBooleanExtra(EXTRA_MEMO, false),
                forceGptLive = intent.getBooleanExtra(EXTRA_FORCE_GPT_LIVE, false),
                memoCause = MemoCause.of(intent.getStringExtra(EXTRA_MEMO_CAUSE)),
            ),
        )
        val e = engine
        watch((e as? CallSwitch)?.active?.value ?: e, startId)
        return START_NOT_STICKY
    }

    /**
     * Follow [started] (the call [startId] began). A newer start re-binds it, so a call that failed
     * just before (GPT-Live offline, with its memo auto-started) can never stop the service under
     * the new one; and the stop names its own start ([stopSelfResult]), so Android keeps the service
     * for a start it has not seen yet.
     */
    private fun watch(started: VoiceCall, startId: Int) {
        watcher?.cancel()
        call = started
        startMediaSession()
        watcher = graph.mainScope.launch {
            // Only what the notification, approvals and tile show: the orb level changes every 150 ms.
            started.state
                .map { NotificationView(it.inCall, it.muted, CallNotifications.statusLine(it), it.startedAtMs, it.approvals.map { a -> a.id }, it) }
                .distinctUntilChanged { a, b -> a.copy(state = b.state) == b }
                .collectLatest { view ->
                val state = view.state
                val nm = getSystemService(NotificationManager::class.java)
                syncApprovals(nm, state)
                TileService.requestListeningState(this@CallService, ComponentName(this@CallService, CallTileService::class.java))
                if (state.inCall) {
                    nm.notify(CallNotifications.CALL_ID, CallNotifications.call(this@CallService, state))
                } else if (call === started) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
    }

    private data class NotificationView(
        val inCall: Boolean,
        val muted: Boolean,
        val status: String,
        val startedAtMs: Long?,
        val approvals: List<String>,
        val state: CallUiState,
    )

    private fun syncApprovals(nm: NotificationManager, state: CallUiState) {
        val open = state.approvals.associateBy { it.id }
        (shownApprovals - open.keys).forEach { nm.cancel(CallNotifications.approvalNotificationId(it)) }
        shownApprovals.retainAll(open.keys)
        open.values.filter { it.id !in shownApprovals }.forEach { card ->
            nm.notify(CallNotifications.approvalNotificationId(card.id), CallNotifications.approval(this, card))
            shownApprovals += card.id
        }
    }

    private fun startMediaSession() {
        if (media != null) return
        media = MediaSession(this, "HermesLive").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    val command = HeadsetButtons.map(
                        event.keyCode, event.action, event.repeatCount,
                        event.flags and KeyEvent.FLAG_LONG_PRESS != 0, longPressHandled,
                    )
                    if (event.action == KeyEvent.ACTION_DOWN && command == HeadsetCommand.EndCall) longPressHandled = true
                    if (event.action == KeyEvent.ACTION_UP) longPressHandled = false
                    when (command) {
                        HeadsetCommand.ToggleMute -> engine.toggleMute()
                        HeadsetCommand.EndCall -> engine.end()
                        null -> return event.keyCode in HEADSET_KEYS
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }
    }

    override fun onDestroy() {
        watcher?.cancel()
        media?.release()
        media = null
        val nm = getSystemService(NotificationManager::class.java)
        shownApprovals.forEach { nm.cancel(CallNotifications.approvalNotificationId(it)) }
        shownApprovals.clear()
        // Never leave a call running without its foreground notice (only the one this service started).
        call?.takeIf { it.state.value.inCall }?.end()
        call = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.folio.hermeslive.action.START"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_NEW_CONVERSATION = "new_conversation"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_MEMO = "memo"
        const val EXTRA_FORCE_GPT_LIVE = "force_gpt_live"
        const val EXTRA_MEMO_CAUSE = "memo_cause"
        private val HEADSET_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        /** Start a call. Call only from a visible activity, after RECORD_AUDIO is granted. */
        fun start(context: Context, request: CallRequest) {
            val intent = Intent(context, CallService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PROMPT, request.prompt)
                .putExtra(EXTRA_MUTED, request.muted)
                .putExtra(EXTRA_NEW_CONVERSATION, request.newConversation)
                .putExtra(EXTRA_ENTRY, request.entry)
                .putExtra(EXTRA_MEMO, request.memo)
                .putExtra(EXTRA_FORCE_GPT_LIVE, request.forceGptLive)
                .putExtra(EXTRA_MEMO_CAUSE, request.memoCause.wire)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
