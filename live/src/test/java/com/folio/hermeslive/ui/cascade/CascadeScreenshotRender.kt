package com.folio.hermeslive.ui.cascade

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.DecisionRecord
import com.folio.hermes.cascade.LoggedAnswer
import com.folio.hermeslive.ui.LiveTheme
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Still renders of the cascade call screen (S23-sized: 393×851 dp at xxhdpi = 1179×2553 px),
 * each a fixed moment: the scene is replayed to that moment at 60 fps and drawn once (no
 * clock, no frame loop), so the PNGs are the same on every run.
 *
 * Written to `build/screenshots/cascade`. To refresh the committed (half-size) copies:
 * `CASCADE_SCREENSHOTS_DIR=$PWD/live/docs/screenshots ./gradlew :live:renderScreenshots` (the renders are not in `test`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class CascadeScreenshotRender {
    @get:Rule
    val compose = createComposeRule()

    private val docs = System.getenv("CASCADE_SCREENSHOTS_DIR") != null
    private val out = File(System.getenv("CASCADE_SCREENSHOTS_DIR") ?: "build/screenshots/cascade").apply { mkdirs() }
    private var screen by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)
    private var started = false

    private val wall = 1_760_000_000_000L
    private val w = 1179f
    private val h = 2553f
    private val d = 3f

    private fun shoot(name: String, content: @Composable () -> Unit) {
        screen = name to content
        if (!started) {
            started = true
            compose.setContent { LiveTheme { screen?.let { (n, c) -> key(n) { c() } } } }
        }
        compose.waitForIdle()
        val full = compose.onRoot().captureToImage().asAndroidBitmap()
        // The committed copies are half size (the repo is public and PNG gradients are large).
        val bitmap = if (docs) Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true) else full
        File(out, "cascade-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A still of [state] after [scene] was replayed; [memory] set as the moment needs. */
    private fun still(
        name: String,
        state: CascadeUiState,
        scene: SceneModel,
        memory: ScreenMemory = ScreenMemory(),
        motion: CascadeMotion = CascadeMotion(),
        nowMs: Long,
        history: List<CascadeHistoryItem> = emptyList(),
        decisions: List<DecisionRecord> = emptyList(),
    ) {
        val holder = mutableStateOf(state)
        shoot(name) { Still(holder, scene, memory, motion, nowMs, history, decisions) }
    }

    @Composable
    private fun Still(
        holder: State<CascadeUiState>,
        scene: SceneModel,
        memory: ScreenMemory,
        motion: CascadeMotion,
        nowMs: Long,
        history: List<CascadeHistoryItem>,
        decisions: List<DecisionRecord>,
    ) {
        CascadeCallContent(
            ui = holder, events = emptyFlow(), intents = NoCascadeIntents,
            chrome = CascadeChrome(callStartedAtMs = 0L), motion = motion, history = history, decisions = { decisions },
            clock = { nowMs }, wallClock = { wall }, scene = scene, memory = memory, animate = false,
        )
    }

    private fun scripted(
        name: String,
        atMs: Long,
        motion: CascadeMotion = CascadeMotion(),
        decisions: List<DecisionRecord> = emptyList(),
        configure: ScreenMemory.() -> Unit = {},
    ) {
        val fake = FakeCascadeUi()
        val scene = SceneModel().apply { reduceMotion = motion.reduceMotion; replay(fake, atMs, w, h, d) }
        val memory = ScreenMemory()
        fake.eventsBetween(-1, atMs).forEach { memory.onEvent(it.second) }
        memory.configure()
        still(name, fake.stateAt(atMs), scene, memory, motion, atMs, decisions = decisions)
    }

    private fun preset(name: String, state: CascadeUiState, atMs: Long, motion: CascadeMotion = CascadeMotion(), memory: ScreenMemory = ScreenMemory()) {
        val scene = SceneModel().apply {
            reduceMotion = motion.reduceMotion
            replay(2_000, w, h, d, { state })
        }
        still(name, state, scene, memory, motion, atMs)
    }

    @Test fun render() {
        scripted("01-idle", 1_900)
        scripted("02-user-pausing", 5_100)
        scripted("03-jev-deciding", 5_560)
        preset("04-three-agents", FakeCascadeUi.Moments.threeAgents(), 14_000)
        scripted("05-agent-flying-in", 16_200)
        preset("06-waiting-approval", FakeCascadeUi.Moments.waiting(), 9_000)
        preset("07-not-sent", FakeCascadeUi.Moments.notSent(), 9_000)
        val offline = FakeCascadeUi.Moments.offlineMemo()
        preset("08-offline-memo", offline, 9_000)
        scripted("09-task-sheet", 13_600) { sheetTaskId = "a" }
        val resume = FakeCascadeUi.Moments.resume(wall)
        val scene = SceneModel().apply { replay(1_000, w, h, d, { resume }) }
        still("10-resume", resume, scene, nowMs = 0, history = FakeCascadeUi.Moments.history(wall))
        scripted("11-uncertain-words", 6_400) { selectedWord = SelectedWord(5, "2140") }
        preset("12-reduce-motion", FakeCascadeUi.Moments.threeAgents(), 14_000, CascadeMotion.Reduced)
        scripted("13-decisions", 12_000, decisions = sampleDecisions()) { decisionsOpen = true }
    }

    private fun sampleDecisions(): List<DecisionRecord> = listOf(
        DecisionRecord(
            "t2", 11_100, "done",
            answers = mapOf(
                "turn" to LoggedAnswer("done", 0.97, mapOf("done" to 0.97, "keep_listening" to 0.03)),
                "action" to LoggedAnswer("status", 0.95, mapOf("status" to 0.95, "reply" to 0.04, "new_task" to 0.01)),
                "task" to LoggedAnswer("a", 0.93, mapOf("a" to 0.93, "b" to 0.07)),
            ),
            route = "status", slot = "status.one", jevMs = 300,
        ),
        DecisionRecord(
            "t1", 5_450, "done",
            answers = mapOf(
                "turn" to LoggedAnswer("done", 0.94, mapOf("done" to 0.94, "keep_listening" to 0.05, "ignore" to 0.01)),
                "action" to LoggedAnswer("new_task", 0.91, mapOf("new_task" to 0.91, "reply" to 0.06, "status" to 0.02)),
            ),
            route = "new_task", slot = "start.parallel", jevMs = 350,
        ),
        DecisionRecord(
            "t0", 1_200, "keep_listening",
            answers = mapOf("turn" to LoggedAnswer("keep_listening", 0.62, mapOf("keep_listening" to 0.62, "done" to 0.36, "ignore" to 0.02))),
            jevMs = 290,
        ),
    )
}
