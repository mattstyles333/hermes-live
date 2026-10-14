package com.folio.hermeslive.ui.cascade

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermeslive.ui.LiveTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The end transition on the screen, on the virtual clock: every way a call ends (a tap here, an
 * end elsewhere, an error) winds down the same way; the summary leaves by itself after its hold
 * or on a tap; tasks running and drops stay until Close; TalkBack hears "Call ended".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class EndTransitionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val intents = object : CascadeIntents {
        override fun end() { calls += "end" }
        override fun closeEnded() { calls += "close" }
        override fun callAgain() { calls += "again" }
        override fun haptic(intent: HapticIntent) { calls += "haptic:$intent" }
    }
    private val ui = mutableStateOf(CascadeUiState())
    private val ended = mutableStateOf<CallEnd?>(null)
    private val near = mutableStateOf(false)
    private val start = mutableStateOf<Long?>(0L)
    private val events = MutableSharedFlow<CascadeEvent>(extraBufferCapacity = 16)
    private var back: OnBackPressedDispatcher? = null
    private var started = false

    private fun show(state: CascadeUiState, motion: CascadeMotion = CascadeMotion()) {
        ui.value = state
        if (!started) {
            started = true
            compose.setContent {
                back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                LiveTheme {
                    CascadeCallContent(
                        ui, events, intents, motion = motion, proximityNear = near.value,
                        chrome = CascadeChrome(callStartedAtMs = if (ended.value == null) start.value else null),
                        clock = { 30_000L }, wallClock = { 1_760_000_000_000L }, ended = ended.value,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun advance(ms: Long) {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
    }

    private fun end(reason: EndReason = EndReason.HungUp, error: String? = null) {
        ended.value = CallEnd(reason, 252_000, error)
        advance(16)
    }

    private val leaveMs = EndTimeline.leaveAtMs(false)

    @Test fun tappingEndPressesAndEndsOnceThenTheSummaryLeavesAfterItsHold() {
        show(FakeCascadeUi.Moments.endedAllDone())
        compose.onNodeWithTag("cascade-end").performClick()
        assertEquals("one end; the engine's own end haptic is the tap's haptic", listOf("end"), calls)
        end()
        compose.onNodeWithTag("cascade-end").assertIsNotEnabled()
        compose.onNodeWithTag("cascade-screen").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Call ended"))
        advance(leaveMs - 150)
        compose.onNodeWithTag("end-summary").assertIsDisplayed()
        compose.onNodeWithText("2 tasks done · 1 result unheard").assertIsDisplayed()
        assertEquals("held, not left yet", listOf("end"), calls)
        advance(300)
        assertEquals(listOf("end", "close"), calls)
        advance(5_000)
        assertEquals("closed once", 1, calls.count { it == "close" })
    }

    @Test fun aTapSkipsTheSummary() {
        show(FakeCascadeUi.Moments.endedAllDone())
        end(EndReason.Goodbye)
        advance(1_100)
        compose.onNodeWithTag("end-skip").performClick()
        assertEquals(listOf("close"), calls)
        advance(leaveMs)
        assertEquals("the hold running out sends nothing more", listOf("close"), calls)
    }

    @Test fun endsFromElsewhereGetTheSameTransition() {
        for (reason in listOf(EndReason.HungUp, EndReason.Notification, EndReason.Headset, EndReason.Tile, EndReason.IdleHangUp, EndReason.CallCap, EndReason.BluetoothLost)) {
            calls.clear()
            show(FakeCascadeUi.Moments.endedAllDone())
            ended.value = null
            advance(16)
            end(reason)
            compose.onNodeWithTag("cascade-screen").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Call ended"))
            advance(leaveMs + 50)
            assertEquals("$reason", listOf("close"), calls)
        }
    }

    @Test fun tasksStillRunningStayWithTalkAgainAndClose() {
        show(FakeCascadeUi.Moments.endedWithTasks())
        end()
        advance(EndTimeline.WIND_MS + 50)
        compose.onNodeWithTag("end-line", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Call ended · 4:12 · 3 tasks still running").assertIsDisplayed()
        compose.onNodeWithTag("tray").assertIsDisplayed()
        advance(60_000)
        assertTrue("never leaves by itself", calls.isEmpty())
        compose.onNodeWithTag("end-talk-again").performClick()
        compose.onNodeWithTag("end-talk-again").performClick()
        assertEquals("once", listOf("again"), calls)
        // The new call did not take the screen: Close still leaves (once).
        compose.onNodeWithTag("end-close").performClick()
        compose.runOnUiThread { back!!.onBackPressed() }
        assertEquals(listOf("again", "close"), calls)
    }

    @Test fun aReconnectThatFailsWhileConnectingShowsNoOldTime() {
        show(FakeCascadeUi.Moments.endedDropped())
        end(EndReason.Error, error = "The microphone stopped.")
        advance(EndTimeline.WIND_MS + 300)
        compose.onNodeWithTag("end-reconnect").performClick()
        // The new call connects (no start yet), then fails before going live: the engine has no duration.
        start.value = null
        ended.value = null
        advance(50)
        ended.value = CallEnd(EndReason.Error, null, "The microphone or the speech models could not start.")
        advance(EndTimeline.WIND_MS + 300)
        compose.onNodeWithTag("call-time").assertDoesNotExist()
        compose.onNodeWithText("Call dropped · 2 tasks still running").assertIsDisplayed()
    }

    @Test fun theRunningCountFollowsTheTasks() {
        val s = FakeCascadeUi.Moments.endedWithTasks()
        show(s)
        end()
        advance(EndTimeline.WIND_MS + 50)
        ui.value = s.copy(tasks = s.tasks.map { if (it.id == "c") it.copy(status = com.folio.hermes.cascade.TaskUiStatus.Done, finishedAtMs = 20_000) else it })
        advance(50)
        compose.onNodeWithText("Call ended · 4:12 · 2 tasks still running").assertIsDisplayed()
    }

    @Test fun backClosesTheEnd() {
        show(FakeCascadeUi.Moments.endedWithTasks())
        end()
        advance(100)
        compose.runOnUiThread { back!!.onBackPressed() }
        assertEquals(listOf("close"), calls)
    }

    @Test fun anErrorShowsTheCalmDroppedCardWithReconnect() {
        show(FakeCascadeUi.Moments.endedDropped())
        end(EndReason.Error, error = "The microphone or the speech models could not start.")
        compose.onNodeWithTag("cascade-screen").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Call dropped"))
        advance(EndTimeline.WIND_MS + 300)
        compose.onNodeWithText("Call dropped · 2 tasks still running").assertIsDisplayed()
        compose.onNodeWithTag("end-error").assertIsDisplayed()
        advance(60_000)
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("end-reconnect").performClick()
        assertEquals(listOf("again"), calls)
    }

    @Test fun reduceMotionUsesTheShortTimeline() {
        show(FakeCascadeUi.Moments.endedAllDone(), CascadeMotion.Reduced)
        end()
        advance(EndTimeline.leaveAtMs(true) - 100)
        assertTrue(calls.isEmpty())
        advance(200)
        assertEquals(listOf("close"), calls)
    }

    @Test fun nearTheEarNothingIsDrawnButTheEndStillCompletes() {
        show(FakeCascadeUi.Moments.endedAllDone())
        near.value = true
        end()
        advance(1_200)
        compose.onNodeWithTag("end-summary").assertDoesNotExist()
        compose.onNodeWithTag("cascade-end").assertDoesNotExist()
        advance(leaveMs)
        assertEquals(listOf("close"), calls)
    }

    @Test fun aNewCallClearsTheEnd() {
        show(FakeCascadeUi.Moments.endedWithTasks())
        end()
        advance(EndTimeline.WIND_MS + 50)
        compose.onNodeWithTag("end-talk-again").performClick()
        ended.value = null
        advance(50)
        compose.onNodeWithTag("end-still-working").assertDoesNotExist()
        compose.onNodeWithTag("cascade-mute").assertIsDisplayed()
        compose.onNodeWithTag("orb-hold").assertIsDisplayed()
    }
}
