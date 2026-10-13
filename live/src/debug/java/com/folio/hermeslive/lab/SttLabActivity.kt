package com.folio.hermeslive.lab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.folio.hermeslive.ui.LiveTheme

/**
 * STT Lab (spike S-C0): a debug-only screen for measuring on-device speech-to-text on the
 * phone. It lives in src/debug, so release builds don't contain it. Launcher entry "STT Lab"
 * in debug builds, or: adb shell am start -n com.folio.hermeslive/.lab.SttLabActivity
 */
class SttLabActivity : ComponentActivity() {
    private lateinit var lab: LabController

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lab.startCaptions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The off-call start probe (debug): stand in for a call's microphone service, started while visible.
        if (intent?.getBooleanExtra(com.folio.hermeslive.probe.ProbeReceiver.EXTRA_HOLD, false) == true) {
            androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, com.folio.hermeslive.probe.ProbeHolderService::class.java))
        }
        lab = LabController(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Volume keys set the call volume, which the echo test plays at.
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        setContent {
            LiveTheme {
                LabScreen(
                    lab = lab,
                    onStart = ::startWithMic,
                    onEcho = { all -> withMic { lab.runEcho(all) } },
                    onShare = ::share,
                )
            }
        }
    }

    override fun onDestroy() {
        lab.close()
        super.onDestroy()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWithMic() {
        if (hasMic()) lab.startCaptions() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun withMic(block: () -> Unit) {
        if (hasMic()) block() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun share() {
        val json = lab.report().toJson()
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Hermes Live STT Lab report")
            .putExtra(Intent.EXTRA_TEXT, json)
        startActivity(Intent.createChooser(send, "Share STT Lab report"))
    }
}
