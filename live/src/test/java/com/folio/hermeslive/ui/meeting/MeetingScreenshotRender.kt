package com.folio.hermeslive.ui.meeting

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.meeting.LiveLine
import com.folio.hermeslive.meeting.MarkKind
import com.folio.hermeslive.meeting.MeetingLive
import com.folio.hermeslive.meeting.MeetingMeta
import com.folio.hermeslive.meeting.MeetingPhase
import com.folio.hermeslive.meeting.MeetingScreen
import com.folio.hermeslive.ui.LiveTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Still renders of meeting mode (S23-sized), made-up content only. Written to
 * `build/screenshots/meeting`; `CASCADE_SCREENSHOTS_DIR=$PWD/live/docs/screenshots ./gradlew
 * :live:renderScreenshots` refreshes the committed half-size copies (`meeting-*.png`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class MeetingScreenshotRender {
    @get:Rule
    val compose = createComposeRule()

    private val docs = System.getenv("CASCADE_SCREENSHOTS_DIR") != null
    private val out = File(System.getenv("CASCADE_SCREENSHOTS_DIR") ?: "build/screenshots/meeting").apply { mkdirs() }
    private var shown by mutableStateOf<Pair<MeetingScreen, Long>?>(null)
    private var started = false
    private val t0 = 1_791_968_400_000L

    private fun shoot(name: String, screen: MeetingScreen, now: Long) {
        shown = screen to now
        if (!started) {
            started = true
            compose.setContent { LiveTheme { shown?.let { (s, n) -> MeetingScreenView(s, n, MeetingActions()) } } }
        }
        compose.waitForIdle()
        val full = compose.onRoot().captureToImage().asAndroidBitmap()
        val bitmap = if (docs) Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true) else full
        File(out, "meeting-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun render() {
        shoot("intro", MeetingScreen.Intro(loading = false, heard = "Weekly supplier call with Priya"), t0)
        val live = MeetingLive(
            meta = MeetingMeta("demo", title = "Weekly supplier call with Priya", startedAt = t0),
            lines = listOf(
                LiveLine(t0 + 1, "So the frames from the Kestrel range are three weeks late again."),
                LiveLine(t0 + 2, "Priya thinks we can split the order and take the black ones first."),
                LiveLine(t0 + 3, "Marked", MarkKind.Mark),
                LiveLine(t0 + 4, "If they ship by the twentieth we keep the launch date."),
                LiveLine(t0 + 5, "Note that as an action for Priya", MarkKind.Note),
                LiveLine(t0 + 6, "Next item is the returns process for the lab."),
            ),
            partial = "and Sam will check the",
            segments = 214,
            marks = 4,
        )
        shoot("taking", MeetingScreen.Taking(live), t0 + 23 * 60_000L + 41_000L)
        val ended = MeetingMeta(
            "demo", title = "Weekly supplier call with Priya", startedAt = t0, phase = MeetingPhase.Ended,
            endedAt = t0 + 47 * 60_000L, additions = listOf("Priya, Sam and me. I need the action items."),
        )
        shoot("ending", MeetingScreen.Ending(ended, segments = 431, marks = 6, canSpeak = true), t0 + 47 * 60_000L)
    }
}
