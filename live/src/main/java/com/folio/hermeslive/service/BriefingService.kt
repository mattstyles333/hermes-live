package com.folio.hermeslive.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import com.folio.hermes.cascade.AudioClip
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plays whole PCM clips for "Hear it". Never touches the mic. */
interface BriefingPlayer {
    /** Play [clip] to the end or until [stop]. */
    suspend fun play(clip: AudioClip)

    fun stop()
}

/** [BriefingPlayer] on an `AudioTrack` as media speech (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`), so it plays off-call and on the lock screen. */
class TrackBriefingPlayer : BriefingPlayer {
    @Volatile private var track: AudioTrack? = null

    override suspend fun play(clip: AudioClip) = withContext(Dispatchers.IO) {
        val f = clip.format
        val channel = if (f.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(f.sampleRateHz, channel, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4_096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(f.sampleRateHz).setChannelMask(channel).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            var offset = 0
            while (isActive && track === t && offset < clip.bytes.size) {
                val n = t.write(clip.bytes, offset, minOf(min, clip.bytes.size - offset))
                if (n <= 0) break
                offset += n
            }
            // Let the tail play out.
            val frames = clip.bytes.size / f.frameBytes
            while (isActive && track === t && t.playbackHeadPosition < frames) Thread.sleep(20)
        } finally {
            runCatching { t.stop() }
            t.release()
            if (track === t) track = null
        }
    }

    override fun stop() {
        track = null
    }
}

/**
 * "Hear it" (R-CONV-3): Bill reads a finished task's result, or the opening briefing (R-CONV-4),
 * as media playback (`mediaPlayback` foreground service) with a [MediaSession], so it can be
 * played and stopped from the lock screen. The microphone is never opened. The clips come from
 * [OffCallHooks.briefing] (S-F); with nothing to play the service stops at once.
 */
class BriefingService : Service() {
    internal var hooksProvider: () -> OffCallHooks = { VoiceShell.of(this).offCallHooks }
    internal var playerProvider: () -> BriefingPlayer = { TrackBriefingPlayer() }
    internal var telemetry: TelemetrySink = SafeTelemetry()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var player: BriefingPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                done("stopped")
                return START_NOT_STICKY
            }
            ACTION_PLAY -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val s = session ?: newSession().also { session = it }
        try {
            startForeground(NOTIFICATION_ID, notification(this, s, "Bill · result"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: RuntimeException) {
            runCatching { telemetry.event("briefing.play", mapOf("outcome" to "refused", "type" to e.javaClass.simpleName), true) }
            done("refused")
            return START_NOT_STICKY
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        taskId?.let { ResultNotifications.cancel(this, it) }
        job?.cancel()
        player?.stop()
        job = scope.launch { play(taskId, s) }
        return START_NOT_STICKY
    }

    private suspend fun play(taskId: String?, s: MediaSession) {
        val started = SystemClock.elapsedRealtime()
        val briefing = runCatching { hooksProvider().briefing(taskId) }.getOrNull()
        if (briefing == null || briefing.clips.isEmpty()) {
            report("nothing", 0, started)
            done(null)
            return
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, briefing.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Hermes Live")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, briefing.clips.sumOf { it.durationMs })
                .build(),
        )
        setState(s, PlaybackState.STATE_PLAYING)
        requestFocus()
        val p = playerProvider().also { player = it }
        var played = 0
        for (clip in briefing.clips) {
            if (player !== p) break
            p.play(clip)
            played++
        }
        report(if (played == briefing.clips.size) "played" else "stopped", played, started)
        if (player === p) done(null)
    }

    private fun report(outcome: String, clips: Int, started: Long) = runCatching {
        telemetry.event("briefing.play", mapOf("outcome" to outcome, "clips" to clips, "ms" to SystemClock.elapsedRealtime() - started), false)
    }

    private fun newSession(): MediaSession = MediaSession(this, "HermesLiveBriefing").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPause() = done("paused")
            override fun onStop() = done("stopped")
        })
        setState(this, PlaybackState.STATE_BUFFERING)
        isActive = true
    }

    private fun setState(s: MediaSession, state: Int) = s.setPlaybackState(
        PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build(),
    )

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) done("focus_lost") }
            .build()
        focus = request
        am.requestAudioFocus(request)
    }

    private fun done(reason: String?) {
        reason?.let { runCatching { telemetry.event("briefing.play", mapOf("outcome" to it), false) } }
        job?.cancel()
        player?.stop()
        player = null
        focus?.let { f -> getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }
        focus = null
        session?.let { s ->
            setState(s, PlaybackState.STATE_STOPPED)
            s.isActive = false
            s.release()
        }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (session != null) done(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.folio.hermeslive.action.HEAR_IT"
        const val ACTION_STOP = "com.folio.hermeslive.action.HEAR_IT_STOP"
        const val EXTRA_TASK_ID = ResultNotifications.EXTRA_TASK_ID
        const val NOTIFICATION_ID = 4

        fun playIntent(context: Context, taskId: String?): Intent =
            Intent(context, BriefingService::class.java).setAction(ACTION_PLAY).apply { taskId?.let { putExtra(EXTRA_TASK_ID, it) } }

        /** Media-style, public on the lock screen (generic title only), with Stop. */
        fun notification(context: Context, session: MediaSession, title: String): Notification {
            val stop = PendingIntent.getService(
                context, REQUEST_STOP,
                Intent(context, BriefingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                context, REQUEST_OPEN,
                Intent(context, LiveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CallNotifications.CHANNEL_PLAYBACK)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(title)
                .setContentText("Hermes Live · mic off")
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_call), "Stop", stop).build())
                .build()
        }

        private const val REQUEST_STOP = 41
        private const val REQUEST_OPEN = 42
    }
}
