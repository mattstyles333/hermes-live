package com.folio.hermeslive.ui.cascade

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermeslive.ui.LiveTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Levels and pause progress arrive at audio rate: they must only redraw the canvas. A change to
 * one task recomposes only that card (and the tray that lists it).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class CascadeRecompositionTest {
    @get:Rule
    val compose = createComposeRule()

    private val counts = HashMap<String, Int>()
    private val probe = RecomposeProbe { counts[it] = (counts[it] ?: 0) + 1 }

    @Test fun levelsOnlyRedrawAndATaskChangeRecomposesItsCard() {
        val base = FakeCascadeUi.Moments.threeAgents().let { it.copy(turn = it.turn.copy(phase = TurnPhase.Pausing)) }
        val ui = mutableStateOf(base)
        val scene = SceneModel()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalRecomposeProbe provides probe) {
                LiveTheme { CascadeCallContent(ui, emptyFlow(), NoCascadeIntents, clock = { 14_000L }, scene = scene) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("first composition seen", (counts["card:a"] ?: 0) >= 1)
        // Bill starts speaking (a real change for the scene, not for the sections).
        ui.value = ui.value.let { it.copy(bill = it.bill.copy(speaking = true, level = 0.5f)) }
        compose.mainClock.advanceTimeBy(100)
        val t0 = scene.time
        counts.clear()

        // 120 frames of audio-rate changes: Bill's level and the pause ring.
        repeat(120) { i ->
            val s: CascadeUiState = ui.value
            ui.value = s.copy(
                bill = s.bill.copy(speaking = true, level = (i % 10) / 10f),
                turn = s.turn.copy(pauseProgress = (i % 60) / 60f),
            )
            compose.mainClock.advanceTimeBy(16)
        }
        assertTrue("the scene kept animating (${scene.time - t0} s)", scene.time - t0 > 1.5f)
        val sections = listOf("root", "tray", "card:a", "card:b", "card:c", "top", "jev", "caption", "banner", "controls", "resume")
        for (section in sections) assertEquals("$section recomposed on level changes: $counts", 0, counts[section] ?: 0)

        // One task's step count changes: only its card.
        val s = ui.value
        ui.value = s.copy(tasks = s.tasks.map { if (it.id == "a") it.copy(steps = it.steps + 1, currentTool = "summarise") else it })
        compose.mainClock.advanceTimeBy(32)
        assertTrue("card a recomposed: $counts", (counts["card:a"] ?: 0) >= 1)
        assertEquals("card b: $counts", 0, counts["card:b"] ?: 0)
        assertEquals("card c: $counts", 0, counts["card:c"] ?: 0)
        assertEquals("root: $counts", 0, counts["root"] ?: 0)
        assertTrue("tray at most once: $counts", (counts["tray"] ?: 0) <= 1)
    }
}
