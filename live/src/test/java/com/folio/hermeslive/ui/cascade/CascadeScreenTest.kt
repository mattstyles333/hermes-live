package com.folio.hermeslive.ui.cascade

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermeslive.ui.LiveTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
/** The screen's intents: swipe-cancel with Undo, the task sheet, word choice, Resume, hold-to-talk, haptics. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class CascadeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val intents = object : CascadeIntents {
        override fun task(taskId: String, tap: TaskTap) {
            calls += "task:$taskId:" + when (tap) {
                TaskTap.Cancel -> "cancel"
                TaskTap.Retry -> "retry"
                TaskTap.ReadAll -> "readall"
                TaskTap.Play -> "play"
                is TaskTap.Tell -> "tell:${tap.mode.wire}:${tap.text}"
            }
        }
        override fun wordChosen(turnId: String, index: Int, choice: String) { calls += "word:$turnId:$index:$choice" }
        override fun resume(choice: ResumeChoice) { calls += "resume:$choice" }
        override fun holdToTalk(pressed: Boolean) { calls += "hold:$pressed" }
        override fun mute() { calls += "mute" }
        override fun end() { calls += "end" }
        override fun speaker() { calls += "speaker" }
        override fun openConversation(conversationId: String) { calls += "open:$conversationId" }
        override fun haptic(intent: HapticIntent) { calls += "haptic:$intent" }
    }

    private val ui = mutableStateOf(CascadeUiState())
    private val near = mutableStateOf(false)
    private val gone = mutableStateOf(false)
    private val memory = ScreenMemory()
    private var back: OnBackPressedDispatcher? = null
    private val events = MutableSharedFlow<CascadeEvent>(extraBufferCapacity = 16)
    private val wall = 1_760_000_000_000L
    private var started = false

    private fun show(state: CascadeUiState) {
        ui.value = state
        if (!started) {
            started = true
            compose.setContent {
                back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                LiveTheme {
                    if (!gone.value) CascadeCallContent(
                        ui, events, intents, motion = CascadeMotion.Reduced, proximityNear = near.value,
                        history = FakeCascadeUi.Moments.history(wall), clock = { 10_000L }, wallClock = { wall }, memory = memory,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun running() = FakeCascadeUi().stateAt(9_000)

    /**
     * With the clock paused, a state write from a test action reaches the recomposer only once
     * the main looper sends the snapshot apply notifications; do it now.
     */
    private fun applied() = compose.runOnUiThread { Snapshot.sendApplyNotifications() }

    /** Advances exactly [ms] (not rounded up to a frame). */
    private fun advance(ms: Long) {
        applied()
        compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
    }

    @Test fun swipeCancelsAfterTheUndoWindow() {
        show(running())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-a").performTouchInput { swipeLeft() }
        advance(1_000)
        compose.onNodeWithTag("undo-a").assertIsDisplayed()
        assertTrue("no cancel inside the Undo window", calls.none { it.startsWith("task:a:cancel") })
        advance(UNDO_MS)
        assertEquals(listOf("task:a:cancel"), calls)
    }

    @Test fun undoWindowIsExactlyThreeSeconds() {
        show(running())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card-a").performCustomAccessibilityActionWithLabel("Cancel")
        advance(UNDO_MS - 1)
        assertTrue(calls.isEmpty())
        advance(1)
        assertEquals(listOf("task:a:cancel"), calls)
        advance(10_000)
        assertEquals("sent once", 1, calls.size)
    }

    @Test fun undoBringsTheCardBackAndSendsNothing() {
        show(running())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card-b").performCustomAccessibilityActionWithLabel("Cancel")
        advance(500)
        compose.onNodeWithTag("card-b").assertDoesNotExist()
        compose.onNodeWithTag("undo-b").performClick()
        advance(UNDO_MS * 2)
        compose.onNodeWithTag("card-b").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test fun leavingDuringTheUndoWindowSendsTheCancel() {
        show(running())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card-a").performCustomAccessibilityActionWithLabel("Cancel")
        advance(100)
        gone.value = true
        advance(100)
        assertEquals("the swiped task is cancelled when the screen goes away", listOf("task:a:cancel"), calls)
        advance(UNDO_MS)
        assertEquals(1, calls.size)
    }

    @Test fun releasingOnDisposeWhileHolding() {
        show(CascadeUiState())
        compose.onNodeWithTag("orb-hold").performCustomAccessibilityActionWithLabel("Start talking")
        gone.value = true
        compose.waitForIdle()
        assertEquals(listOf("hold:true", "hold:false"), calls)
    }

    @Test fun sheetButtonsSendTheirTaps() {
        show(running())
        compose.onNodeWithTag("card-a").performClick()
        compose.onNodeWithTag("task-sheet").assertIsDisplayed()
        compose.onNodeWithTag("sheet-tell-text").performTextInput("check warehouse C too")
        compose.onNodeWithTag("sheet-tell").performClick()
        compose.onNodeWithTag("sheet-tell-text").performTextInput("only the black 52")
        compose.onNodeWithTag("sheet-redirect").performClick()
        assertEquals(listOf("task:a:tell:queue:check warehouse C too", "task:a:tell:redirect:only the black 52"), calls)
        calls.clear()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("sheet-cancel").performClick()
        advance(200)
        compose.onNodeWithTag("task-sheet").assertDoesNotExist()
        advance(UNDO_MS)
        assertEquals(listOf("task:a:cancel"), calls)
    }

    @Test fun finishedSheetPlaysAndReadsAll() {
        show(FakeCascadeUi().stateAt(16_500))
        compose.onNodeWithTag("play-a").performClick()
        compose.onNodeWithTag("card-a").performClick()
        compose.onNodeWithTag("sheet-result").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sheet-play").performClick()
        compose.onNodeWithTag("sheet-readall").performClick()
        compose.onNodeWithTag("sheet-close").performClick()
        compose.onNodeWithTag("task-sheet").assertDoesNotExist()
        assertEquals(listOf("task:a:play", "task:a:play", "task:a:readall"), calls)
    }

    @Test fun notSentCardRetriesOnTap() {
        show(FakeCascadeUi.Moments.notSent())
        compose.onNodeWithTag("send-c", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Not sent · tap to retry").assertIsDisplayed()
        compose.onNodeWithTag("card-c").performClick()
        assertEquals(listOf("task:c:retry"), calls)
    }

    @Test fun failedTaskRetriesFromTheSheet() {
        val s = running()
        show(s.copy(tasks = s.tasks.map { if (it.id == "b") it.copy(status = TaskUiStatus.Failed) else it }))
        compose.onNodeWithTag("card-b").performClick()
        compose.onNodeWithTag("sheet-retry").performClick()
        assertEquals(listOf("task:b:retry"), calls)
    }

    @Test fun uncertainWordOffersAlternativesOnceTheTurnIsKnown() {
        show(FakeCascadeUi().stateAt(6_400))
        // No TurnCommitted seen yet: the word is underlined but cannot be chosen.
        compose.onNodeWithTag("word-5").performClick()
        compose.onNodeWithTag("alternatives").assertDoesNotExist()
        events.tryEmit(CascadeEvent.TurnCommitted("t1"))
        compose.waitForIdle()
        compose.onNodeWithTag("word-5").performClick()
        compose.onNodeWithTag("alternatives").assertIsDisplayed()
        compose.onNodeWithTag("alt-2104").performClick()
        compose.onNodeWithTag("alternatives").assertDoesNotExist()
        assertEquals(listOf("haptic:TurnCommitted", "word:t1:5:2104"), calls)
    }

    @Test fun aReplacedWordIsNeverChosen() {
        val s = FakeCascadeUi().stateAt(6_400)
        show(s)
        events.tryEmit(CascadeEvent.TurnCommitted("t1"))
        compose.waitForIdle()
        compose.onNodeWithTag("word-5").performClick()
        compose.onNodeWithTag("alternatives").assertIsDisplayed()
        // The accurate pass lands while the alternatives are open: word 5 is now another word.
        ui.value = s.copy(turn = s.turn.copy(words = FakeCascadeUi.words("check stock on the Ray-Ban 2104 and draft", mapOf("2104" to listOf("2140")))))
        compose.waitForIdle()
        compose.onNodeWithTag("alternatives").assertDoesNotExist()
        assertEquals(listOf("haptic:TurnCommitted"), calls)
    }

    @Test fun backClosesTheSheet() {
        show(running())
        compose.onNodeWithTag("card-a").performClick()
        compose.onNodeWithTag("task-sheet").assertIsDisplayed()
        compose.runOnUiThread { back!!.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("task-sheet").assertDoesNotExist()
    }

    @Test fun resumeButtonsAndHistory() {
        show(FakeCascadeUi.Moments.resume(wall))
        compose.onNodeWithText("Last call 12 min ago · 2 results not heard · 1 held · 1 waiting for you").assertIsDisplayed()
        compose.onNodeWithTag("resume-play").performClick()
        compose.onNodeWithTag("resume-continue").performClick()
        compose.onNodeWithTag("resume-new").performClick()
        compose.onNodeWithTag("history-c-5").performClick()
        assertEquals(listOf("resume:Play", "resume:Continue", "resume:New", "open:c-5"), calls)
    }

    @Test fun holdingTheOrbTalksAndReleaseSends() {
        show(CascadeUiState())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("orb-hold").performTouchInput { down(center) }
        advance(HOLD_MS - 50)
        assertTrue("a short press is not a hold", calls.isEmpty())
        advance(200)
        assertEquals(listOf("hold:true"), calls)
        compose.onNodeWithTag("orb-hold").performTouchInput { up() }
        advance(50)
        assertEquals(listOf("hold:true", "hold:false"), calls)
    }

    @Test fun aTapOnTheOrbIsNotAHold() {
        show(CascadeUiState())
        compose.onNodeWithTag("orb-hold").performClick()
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun holdToTalkFromTalkBack() {
        show(CascadeUiState())
        compose.onNodeWithTag("orb-hold").performCustomAccessibilityActionWithLabel("Start talking")
        compose.onNodeWithTag("orb-hold").performCustomAccessibilityActionWithLabel("Stop and send")
        assertEquals(listOf("hold:true", "hold:false"), calls)
    }

    @Test fun hapticsAreIntentsNotVibrations() {
        show(running())
        events.tryEmit(CascadeEvent.TurnCommitted("t9"))
        events.tryEmit(CascadeEvent.TaskFinished("a", ok = true))
        events.tryEmit(CascadeEvent.ToolStarted("a", "x"))
        compose.waitForIdle()
        assertEquals(listOf("haptic:TurnCommitted", "haptic:TaskFinished"), calls)
    }

    @Test fun controls() {
        show(CascadeUiState())
        compose.onNodeWithTag("cascade-mute").performClick()
        compose.onNodeWithTag("cascade-end").performClick()
        compose.onNodeWithTag("cascade-speaker").performClick()
        assertEquals(listOf("mute", "end", "speaker"), calls)
    }

    @Test fun doneGroupCollapses() {
        show(FakeCascadeUi().stateAt(22_000))
        compose.onNodeWithTag("card-a").assertIsDisplayed()
        compose.onNodeWithTag("done-header").performClick()
        compose.onNodeWithTag("card-a").assertDoesNotExist()
        compose.onNodeWithTag("done-header").performClick()
        compose.onNodeWithTag("card-a").assertIsDisplayed()
    }

    @Test fun longPressOnJevOpensTheDecisions() {
        show(FakeCascadeUi().stateAt(12_000))
        compose.onNodeWithTag("jev-strip").performTouchInput { longClick() }
        compose.onNodeWithTag("decisions").assertIsDisplayed()
        compose.onNodeWithTag("decisions-close").performClick()
        compose.onNodeWithTag("decisions").assertDoesNotExist()
    }

    @Test fun memoBannerShowsHeldCount() {
        show(FakeCascadeUi.Moments.offlineMemo())
        compose.onNodeWithText("Memo · offline · 3 held").assertIsDisplayed()
        compose.onNodeWithTag("held-0").assertIsDisplayed()
    }

    @Test fun nothingButBlackWhenTheProximitySensorIsNear() {
        near.value = true
        show(running())
        compose.onNodeWithTag("tray").assertDoesNotExist()
        compose.onNodeWithTag("caption").assertDoesNotExist()
        compose.onNodeWithTag("cascade-scene").assertExists()
    }
}
