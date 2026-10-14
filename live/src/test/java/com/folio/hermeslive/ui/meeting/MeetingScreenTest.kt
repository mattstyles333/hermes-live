package com.folio.hermeslive.ui.meeting

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.cascade.CascadeSignOut
import com.folio.hermeslive.data.TestShell
import com.folio.hermeslive.meeting.LiveLine
import com.folio.hermeslive.meeting.LogLine
import com.folio.hermeslive.meeting.MarkKind
import com.folio.hermeslive.meeting.MeetingEntry
import com.folio.hermeslive.meeting.MeetingHost
import com.folio.hermeslive.meeting.MeetingLive
import com.folio.hermeslive.meeting.MeetingLog
import com.folio.hermeslive.meeting.MeetingMeta
import com.folio.hermeslive.meeting.MeetingOutbox
import com.folio.hermeslive.meeting.MeetingPhase
import com.folio.hermeslive.meeting.MeetingPrefs
import com.folio.hermeslive.meeting.MeetingScreen
import com.folio.hermeslive.meeting.MeetingSettings
import com.folio.hermeslive.ui.CallActions
import com.folio.hermeslive.ui.CallScreen
import com.folio.hermeslive.ui.LiveTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The meeting screen's states and taps, the home button, and meeting mode's off state (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class MeetingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = MeetingActions(
        onSpeakTitle = { calls += "speak-title" },
        onStart = { calls += "start:$it" },
        onCancel = { calls += "cancel" },
        onPause = { calls += "pause" },
        onResume = { calls += "resume" },
        onMark = { calls += "mark" },
        onEnd = { calls += "end" },
        onAdd = { calls += "add:$it" },
        onSpeakAddition = { calls += "speak-add" },
        onSend = { calls += "send" },
        onSave = { calls += "save" },
        onDiscard = { calls += "discard" },
        onResumeInterrupted = { calls += "resume-interrupted" },
        onEndInterrupted = { calls += "end-interrupted" },
        onClose = { calls += "close" },
        onPrepareModels = { calls += "prepare" },
    )

    private var screen by mutableStateOf<MeetingScreen>(MeetingScreen.None)
    private var started = false
    private val t0 = 1_791_968_400_000L

    private fun show(s: MeetingScreen, now: Long = t0 + 23 * 60_000L + 41_000L) {
        screen = s
        if (!started) {
            started = true
            compose.setContent { LiveTheme { MeetingScreenView(screen, now, actions) } }
        }
        compose.waitForIdle()
    }

    private fun live(phase: MeetingPhase = MeetingPhase.Recording, pausedAt: Long? = null) = MeetingLive(
        meta = MeetingMeta("m1", title = "Weekly supplier call", startedAt = t0, phase = phase, pausedAt = pausedAt),
        lines = listOf(
            LiveLine(t0 + 1, "We'll move the Kestrel order to next week."),
            LiveLine(t0 + 2, "Note that as an action for Priya", MarkKind.Note),
        ),
        partial = "and the invoice",
        segments = 42,
        marks = 3,
    )

    @Test fun takingNotesShowsTheElapsedTimeTheLinesAndTheControls() {
        show(MeetingScreen.Taking(live()))
        compose.onNodeWithTag("meeting-state").assertTextEquals("TAKING NOTES")
        compose.onNodeWithTag("meeting-elapsed").assertTextEquals("23:41")
        compose.onNodeWithTag("meeting-title").assertTextEquals("Weekly supplier call")
        compose.onNodeWithText("42 lines · 3 marked").assertIsDisplayed()
        compose.onNodeWithTag("meeting-line-note").assertIsDisplayed()
        compose.onNodeWithText("and the invoice").assertIsDisplayed()
        compose.onNodeWithTag("meeting-mark").performClick()
        compose.onNodeWithTag("meeting-pause").performClick()
        compose.onNodeWithTag("meeting-end").performClick()
        assertEquals(listOf("mark", "pause", "end"), calls)
    }

    @Test fun pausedFreezesTheClockAndOffersResume() {
        // Paused at 10:00 in; "now" is much later: the clock stays at 10:00.
        show(MeetingScreen.Taking(live(MeetingPhase.Paused, pausedAt = t0 + 600_000)))
        compose.onNodeWithTag("meeting-state").assertTextEquals("PAUSED")
        compose.onNodeWithTag("meeting-elapsed").assertTextEquals("10:00")
        compose.onNodeWithText("and the invoice").assertDoesNotExistCompat()
        compose.onNodeWithTag("meeting-resume").performClick()
        compose.onNodeWithTag("meeting-mark").performClick() // disabled while paused
        assertEquals(listOf("resume"), calls)
    }

    @Test fun theIntroTakesATitleOrSkips() {
        show(MeetingScreen.Intro(loading = false))
        compose.onNodeWithTag("meeting-intro").assertIsDisplayed()
        compose.onNodeWithTag("meeting-start").assertTextEquals("Skip and start")
        compose.onNodeWithTag("meeting-speak-title").performClick()
        compose.onNodeWithTag("meeting-title-field").performTextInput("Budget review")
        compose.onNodeWithTag("meeting-start").performClick()
        assertEquals(listOf("speak-title", "start:Budget review"), calls)
    }

    @Test fun whileTheModelsLoadStartWaits() {
        show(MeetingScreen.Intro(loading = true))
        compose.onNodeWithTag("meeting-loading").assertIsDisplayed()
        compose.onNodeWithTag("meeting-start").performClick()
        assertTrue(calls.isEmpty())
    }

    @Test fun endingAsksAnythingToAddThenSends() {
        val meta = MeetingMeta("m1", title = "Weekly", startedAt = t0, phase = MeetingPhase.Ended, endedAt = t0 + 3_600_000)
        show(MeetingScreen.Ending(meta, segments = 120, marks = 4, canSpeak = true))
        compose.onNodeWithTag("meeting-ending").assertTextEquals("Anything to add?")
        compose.onNodeWithTag("meeting-summary").assertTextEquals("1 h 0 min · 120 lines · 4 marked")
        compose.onNodeWithTag("meeting-add-field").performTextInput("Priya, Sam and me")
        compose.onNodeWithTag("meeting-send").performClick()
        assertEquals(listOf("add:Priya, Sam and me", "send"), calls)
    }

    @Test fun noModelsSaysPrepareThemFirst() {
        show(MeetingScreen.NeedsModels)
        compose.onNodeWithText("Prepare speech models first").assertIsDisplayed()
        compose.onNodeWithTag("meeting-prepare").performClick()
        assertEquals(listOf("prepare"), calls)
    }

    @Test fun anInterruptedMeetingOffersResumeEndOrDiscard() {
        show(MeetingScreen.Interrupted(MeetingMeta("m1", title = "Weekly", startedAt = t0, pausedAt = t0 + 300_000), segments = 12))
        compose.onNodeWithTag("meeting-interrupted").assertIsDisplayed()
        compose.onNodeWithTag("meeting-resume-interrupted").performClick()
        compose.onNodeWithTag("meeting-end-interrupted").performClick()
        compose.onNodeWithTag("meeting-discard").performClick()
        assertEquals(listOf("resume-interrupted", "end-interrupted", "discard"), calls)
    }

    @Test fun doneSaysWhereTheNotesWent() {
        val meta = MeetingMeta("m1", title = "Weekly", startedAt = t0, phase = MeetingPhase.Queued)
        show(MeetingScreen.Done(meta, MeetingOutbox.Outcome.Held("bill_off")))
        compose.onNodeWithText("Saved; it goes to Hermes later").assertIsDisplayed()
        compose.onNodeWithText(heldText("bill_off")).assertIsDisplayed()
        show(MeetingScreen.Done(meta, MeetingOutbox.Outcome.Sent("t1")))
        compose.onNodeWithText("Sent for notes").assertIsDisplayed()
    }

    @Test fun homeShowsTheMeetingButtonOnlyWhenGivenOne() {
        var home by mutableStateOf(CallActions())
        compose.setContent { LiveTheme { CallScreen(CallUiState(), home) } }
        compose.onNodeWithTag("talk").assertIsDisplayed()
        compose.onNodeWithTag("meeting").assertDoesNotExistCompat()
        home = CallActions(onMeeting = { calls += "meeting" })
        compose.waitForIdle()
        compose.onNodeWithTag("meeting").performClick()
        assertEquals(listOf("meeting"), calls)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}

/** Meeting mode ships dark: off by default, nothing built; sign-out wipes meetings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MeetingOffStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun offByDefaultNothingIsBuiltAndTheShortcutIsNotPublished() {
        context.getSharedPreferences(MeetingPrefs.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = MeetingPrefs(context.getSharedPreferences(MeetingPrefs.PREFS, Context.MODE_PRIVATE))
        assertEquals(MeetingSettings(enabled = false, wakeWord = true, retentionDays = 30), prefs.settings.value)
        assertFalse(MeetingHost.prefs(context).settings.value.enabled)
        assertNull(MeetingHost.ifEnabled(context))
        assertNull(MeetingHost.existing())
        val sm = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        assertTrue(sm.dynamicShortcuts.none { it.id == MeetingEntry.SHORTCUT_ID })
        MeetingEntry.syncShortcut(context, true)
        assertTrue(sm.dynamicShortcuts.any { it.id == MeetingEntry.SHORTCUT_ID })
        MeetingEntry.syncShortcut(context, false)
        assertTrue(sm.dynamicShortcuts.none { it.id == MeetingEntry.SHORTCUT_ID })
    }

    @Test fun theSettingIsSaved() {
        val p = context.getSharedPreferences(MeetingPrefs.PREFS + "-t", Context.MODE_PRIVATE)
        MeetingPrefs(p).update { it.copy(enabled = true, wakeWord = false, retentionDays = 7) }
        assertEquals(MeetingSettings(true, false, 7), MeetingPrefs(p).settings.value)
    }

    @Test fun signOutWipesMeetingsWithBillsState() {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val t = TestShell(context, "meeting-signout", scope)
        try {
            val log = MeetingLog { t.shell.storage.store }
            log.create(MeetingMeta("m1", title = "Private", startedAt = 1L, phase = MeetingPhase.Queued))
            log.append("m1", LogLine.Seg(1, 1L, "private words"))
            log.putBrief("m1", "brief")
            assertEquals(1, log.all().size)
            CascadeSignOut.apply(t.shell)
            assertTrue(log.all().isEmpty())
            assertTrue(log.lines("m1").isEmpty())
            assertNull(log.brief("m1"))
            assertNull(t.shell.storage.store.get(MeetingLog.ACTIVE))
        } finally {
            scope.cancel()
            t.shell.dirs.root.deleteRecursively()
        }
    }

    @Test fun aMemoryStoreRoundTripsEveryLine() {
        val store = MemoryStore()
        val log = MeetingLog { store }
        log.append("m", LogLine.Seg(1, 5L, "a"))
        log.append("m", LogLine.Rev(1, 1, "A", "accurate"))
        log.append("m", LogLine.Mark(6L, MarkKind.Task, "do it", seg = 1))
        log.append("m", LogLine.Gap(7L, "pause"))
        val t = log.transcript("m")
        assertEquals("A", t.segments.single().text)
        assertEquals(MarkKind.Task, t.markers.single().kind)
        assertEquals("pause", t.gaps.single().why)
    }
}
