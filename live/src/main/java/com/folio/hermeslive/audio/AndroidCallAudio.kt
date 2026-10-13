package com.folio.hermeslive.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.folio.hermeslive.call.CallAudio
import com.folio.hermeslive.call.FocusMode
import com.folio.hermeslive.call.RouteKind
import com.folio.hermeslive.call.RouteOption

/**
 * [CallAudio] over AudioManager (API 31+): MODE_IN_COMMUNICATION for the platform echo
 * canceller, `setCommunicationDevice` routes, transient audio focus for music in the gaps, and
 * listeners for device changes, unplugs (`ACTION_AUDIO_BECOMING_NOISY`) and other calls (mode
 * changes). [end] puts the mode back and clears the device this call set. Main thread only.
 */
class AndroidCallAudio(context: Context) : CallAudio {
    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: CallAudio.Listener? = null
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var active = false
    private var focus: FocusMode = FocusMode.Release
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Another app took focus. Keep the call going; the policy asks again when it needs focus.
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            focus = FocusMode.Release
            focusRequest = null
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        if (active) listener?.onOtherCall(isOtherCall(mode))
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = report()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = report()
        private fun report() {
            // Communication devices settle a moment after the media device appears.
            main.postDelayed({ if (active) listener?.onDevicesChanged(devices()) }, 300)
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (active && intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener?.onBecomingNoisy()
        }
    }

    override fun begin(listener: CallAudio.Listener) {
        if (active) end()
        this.listener = listener
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        active = true
        am.addOnModeChangedListener(ContextCompat.getMainExecutor(app), modeListener)
        am.registerAudioDeviceCallback(deviceCallback, main)
        ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun end() {
        if (!active) return
        active = false
        setFocus(FocusMode.Release)
        runCatching { am.removeOnModeChangedListener(modeListener) }
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { app.unregisterReceiver(noisy) }
        main.removeCallbacksAndMessages(null)
        am.clearCommunicationDevice()
        am.mode = savedMode
        listener = null
    }

    override fun devices(): List<RouteOption> =
        am.availableCommunicationDevices
            .map { RouteOption(it.id, kindOf(it.type), it.productName?.toString().orEmpty()) }
            .filter { it.kind != RouteKind.Other }
            .distinctBy { it.kind to it.name }

    override fun select(route: RouteOption) {
        val device = am.availableCommunicationDevices.firstOrNull { it.id == route.id } ?: return
        if (am.communicationDevice?.id != device.id) am.setCommunicationDevice(device)
    }

    override fun setFocus(mode: FocusMode) {
        if (mode == focus) return
        when (mode) {
            FocusMode.Release -> abandon()
            FocusMode.Hold, FocusMode.Duck -> {
                // Hold → Duck: let the player have focus back first (it resumes on GAIN), then
                // take a may-duck focus on top so the system lowers it. Duck → Hold replaces.
                if (focus == FocusMode.Hold && mode == FocusMode.Duck) abandon()
                val gain = if (mode == FocusMode.Hold) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                val request = AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(ATTRIBUTES)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .setWillPauseWhenDucked(false)
                    .build()
                am.requestAudioFocus(request)
                focusRequest = request
            }
        }
        focus = mode
    }

    override fun reassert() {
        if (active) am.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    override fun isMusicActive(): Boolean = am.isMusicActive

    private fun abandon() {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // MODE_CALL_SCREENING (30), MODE_CALL_REDIRECT and MODE_COMMUNICATION_REDIRECT (33) as values.
        private const val MODE_CALL_SCREENING = 4
        private const val MODE_CALL_REDIRECT = 5
        private const val MODE_COMMUNICATION_REDIRECT = 6

        /** A cellular call ringing, in progress, screened or redirected. Our own IN_COMMUNICATION is not. */
        fun isOtherCall(mode: Int): Boolean = mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == MODE_CALL_SCREENING ||
            mode == MODE_CALL_REDIRECT ||
            mode == MODE_COMMUNICATION_REDIRECT

        fun kindOf(type: Int): RouteKind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteKind.Earpiece
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteKind.Speaker
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> RouteKind.Wired
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> RouteKind.Usb
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> RouteKind.Bluetooth
            AudioDeviceInfo.TYPE_HEARING_AID -> RouteKind.HearingAid
            else -> RouteKind.Other
        }
    }
}
