package com.folio.hermeslive.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.LiveSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens with made-up states to PNGs in build/screenshots (S23-sized: 393×851 dp at
 * xxhdpi ≈ 1080×2340). Not assertions: a look at the UI without a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class ScreenshotRender {
    @get:Rule
    val compose = createComposeRule()

    private val out = File("build/screenshots").apply { mkdirs() }

    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var started = false

    private fun shoot(name: String, content: @Composable () -> Unit) {
        screen = content
        if (!started) {
            started = true
            compose.setContent { LiveTheme { screen?.invoke() } }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun call(name: String, state: CallUiState, voice: String? = null) =
        shoot(name) { CallScreen(state, CallActions(), elapsed = "1:05", voiceLine = voice) }

    @Test fun render() {
        shoot("01-sign-in") { SignInScreen(SignInUi(), { _, _, _ -> }, {}) }
        call("02-idle", DemoStates.idle, "Voice: gpt-live-1 · voice marin")
        call("03-connecting", DemoStates.connecting)
        call("04-listening", DemoStates.listening)
        call("05-thinking-music", DemoStates.music)
        call("06-speaking", DemoStates.speaking)
        call("07-muted", DemoStates.muted)
        call("08-approval", DemoStates.approval)
        call("09-clarify", DemoStates.clarify)
        call("10-ended", DemoStates.ended)
        shoot("11-settings") {
            SettingsScreen(
                LiveSettings(), AccountState.SignedIn("hermes.example.com", "demo"),
                VoiceLiveStatus(ok = true, mode = "gpt-live", available = true, model = "gpt-live-1", voice = "marin"),
                "1.0.0", SettingsActions(),
            )
        }
    }
}
