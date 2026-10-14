package com.folio.hermeslive.ui.meeting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.folio.hermeslive.meeting.MeetingHost
import com.folio.hermeslive.meeting.MeetingPhase
import com.folio.hermeslive.meeting.MeetingScreen
import com.folio.hermeslive.meeting.MeetingService
import kotlinx.coroutines.delay

/**
 * Starts meeting mode from the visible activity: the mic (and notification) permission first,
 * then the screen and the mic service. Returns the starter for the Meeting button, the shortcut
 * and the Resume of an interrupted meeting.
 */
class MeetingStarter internal constructor(private val context: Context, private val host: MeetingHost, private val ask: (Array<String>) -> Unit) {
    internal var pending: (() -> Unit)? = null

    /** The Meeting button or the shortcut ([entry] for telemetry). */
    fun open(entry: String) = withPermissions {
        if (host.flow.open(entry)) MeetingService.start(context)
    }

    /** Resume a meeting cut off by a kill. */
    fun resumeInterrupted() = withPermissions {
        MeetingService.start(context)
        host.flow.resumeInterrupted()
    }

    private fun withPermissions(then: () -> Unit) {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) then() else {
            pending = then
            ask(missing.toTypedArray())
        }
    }

    internal fun granted(mic: Boolean) {
        val next = pending
        pending = null
        if (mic) next?.invoke()
    }
}

@Composable
fun rememberMeetingStarter(host: MeetingHost): MeetingStarter {
    val context = LocalContext.current
    val holder = remember { mutableStateOf<MeetingStarter?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        holder.value?.granted(mic)
    }
    return remember(host) { MeetingStarter(context, host, launcher::launch).also { holder.value = it } }
}

/** The meeting screen on the app's [MeetingHost]. [onOpenSettings]: "Prepare speech models first". */
@Composable
fun MeetingRoute(host: MeetingHost, screen: MeetingScreen, starter: MeetingStarter, onOpenSettings: () -> Unit) {
    val recording = (screen as? MeetingScreen.Taking)?.live?.meta?.phase == MeetingPhase.Recording
    // Ticks once a second only while recording and on screen (the composition stops with the screen).
    val now by produceState(System.currentTimeMillis(), recording) {
        while (recording) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val f = host.flow
    MeetingScreenView(
        screen = screen,
        nowMs = now,
        actions = MeetingActions(
            onSpeakTitle = { f.speakTitle() },
            onStart = { f.start(it) },
            onCancel = { f.cancelIntro() },
            onPause = { f.pause() },
            onResume = { f.resume() },
            onMark = { f.mark() },
            onEnd = { f.end() },
            onAdd = f::add,
            onSpeakAddition = { f.speakAddition() },
            onSend = { f.send() },
            onSave = { f.save() },
            onDiscard = { f.discard() },
            onResumeInterrupted = starter::resumeInterrupted,
            onEndInterrupted = f::endInterrupted,
            onClose = f::close,
            onPrepareModels = {
                f.close()
                onOpenSettings()
            },
        ),
    )
}
