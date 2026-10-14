package com.folio.hermeslive.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.ui.cascade.CascadeCallContent
import com.folio.hermeslive.ui.cascade.CascadeChrome
import com.folio.hermeslive.ui.cascade.CascadeMotion
import com.folio.hermeslive.ui.cascade.FakeCascadeUi
import com.folio.hermeslive.ui.cascade.NoCascadeIntents
import com.folio.hermeslive.ui.cascade.SceneModel
import com.folio.hermeslive.ui.cascade.ScreenMemory
import com.folio.hermeslive.ui.cascade.replay
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The one render left in Tier 0 (test-shape plan): the GPT-Live call screen and Bill's busiest
 * screen (three agents) are composed, laid out and drawn once, so a composition or drawing crash
 * fails `test`. No file is written; the full PNG sets are `./gradlew :live:renderScreenshots`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class SmokeRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun bothCallScreensDraw() {
        val bill = FakeCascadeUi.Moments.threeAgents()
        val scene = SceneModel().apply { replay(2_000, 1179f, 2553f, 3f, { bill }) }
        val billUi = mutableStateOf(bill)
        val gptLive = mutableStateOf(true)
        compose.setContent {
            LiveTheme {
                if (gptLive.value) {
                    CallScreen(DemoStates.approval, CallActions(), elapsed = "1:05")
                } else {
                    CascadeCallContent(
                        ui = billUi, events = emptyFlow(), intents = NoCascadeIntents,
                        chrome = CascadeChrome(callStartedAtMs = 0L), motion = CascadeMotion(), history = emptyList(), decisions = { emptyList() },
                        clock = { 14_000L }, wallClock = { 1_760_000_000_000L }, scene = scene, memory = ScreenMemory(), animate = false,
                    )
                }
            }
        }
        for (screen in listOf("gpt-live approval", "bill three agents")) {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("$screen drew something", bitmap.width > 0 && bitmap.height > 0 && distinctColours(bitmap) > 4)
            gptLive.value = false
        }
    }

    private fun distinctColours(b: android.graphics.Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until b.height step 37) for (x in 0 until b.width step 37) seen += b.getPixel(x, y)
        return seen.size
    }
}
