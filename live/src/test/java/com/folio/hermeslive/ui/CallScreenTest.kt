package com.folio.hermeslive.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.call.CallUiState
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.LiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class CallScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CallActions(
        onTalk = { calls += "talk" },
        onEnd = { calls += "end" },
        onMute = { calls += "mute" },
        onRoute = { calls += "route:${it.kind}" },
        onApprove = { id, choice -> calls += "approve:$id:${choice.wire}" },
        onClarify = { id, answers -> calls += "clarify:$id:$answers" },
        onSettings = { calls += "settings" },
        onNewConversation = { calls += "new" },
    )

    private var screen by mutableStateOf<CallUiState?>(null)
    private var voiceLine by mutableStateOf<String?>(null)
    private var started = false

    private fun show(state: CallUiState, voice: String? = null) {
        screen = state
        voiceLine = voice
        if (!started) {
            started = true
            compose.setContent { LiveTheme { screen?.let { CallScreen(it, actions, elapsed = "1:05", voiceLine = voiceLine) } } }
        }
        compose.waitForIdle()
    }

    private fun status(text: String) = compose.onNodeWithTag("status").assertIsDisplayed().assertTextEquals(text)

    @Test fun idleOffersTalkAndNewConversation() {
        show(DemoStates.idle, voice = "Voice: gpt-live-1 · voice marin")
        status("Tap to talk")
        compose.onNodeWithText("Voice: gpt-live-1 · voice marin").assertIsDisplayed()
        compose.onNodeWithTag("talk").performClick()
        compose.onNodeWithTag("new-conversation").performClick()
        compose.onNodeWithTag("settings").performClick()
        assertEquals(listOf("talk", "new", "settings"), calls)
    }

    @Test fun connectingThenListeningWithCaptions() {
        show(DemoStates.connecting)
        status("Connecting…")
        compose.onNodeWithTag("end").assertIsDisplayed()
        show(DemoStates.listening)
        status("Listening")
        compose.onNodeWithText("And the weather in Leeds.").assertIsDisplayed()
        compose.onNodeWithTag("elapsed").assertIsDisplayed()
    }

    @Test fun thinkingShowsTheToolAndMusicInTheGap() {
        show(DemoStates.thinking)
        status("Hermes is working")
        compose.onNodeWithText("web search").assertIsDisplayed()
        show(DemoStates.music)
        compose.onNodeWithTag("substatus").assertTextEquals("web search · music in the gap")
    }

    @Test fun speakingMutedAndControls() {
        show(DemoStates.speaking)
        status("Hermes is speaking")
        show(DemoStates.muted)
        status("Muted")
        compose.onNodeWithTag("mute").performClick()
        compose.onNodeWithTag("end").performClick()
        assertEquals(listOf("mute", "end"), calls)
    }

    @Test fun routePickerListsDevices() {
        show(DemoStates.listening)
        compose.onNodeWithTag("route").performClick()
        compose.onNodeWithTag("route-Bluetooth").performClick()
        assertEquals(listOf("route:Bluetooth"), calls)
    }

    @Test fun approvalsNeedATap() {
        show(DemoStates.approval)
        status("Waiting for your approval")
        compose.onNodeWithText("rm -rf ./build/cache").assertIsDisplayed()
        compose.onNodeWithText("Hermes never approves by itself.").assertIsDisplayed()
        compose.onNodeWithTag("approve-once").performClick()
        compose.onNodeWithTag("approve-session").performClick()
        compose.onNodeWithTag("approve-deny").performClick()
        assertEquals(listOf("approve:srq-1:once", "approve:srq-1:session", "approve:srq-1:deny"), calls)
        assertTrue(calls.none { it.contains("always") })
    }

    @Test fun clarifyChoicesAndFreeText() {
        show(DemoStates.clarify)
        compose.onNodeWithText("Which night suits you?").assertIsDisplayed()
        compose.onNodeWithText("Saturday").performClick()
        assertEquals("clarify:srq-2:{q1=Saturday}", calls.single())
        calls.clear()
        val freeText = DemoStates.clarify.copy(
            clarify = DemoStates.clarify.clarify!!.copy(
                request = DemoStates.clarify.clarify!!.request.copy(
                    questions = listOf(com.folio.hermes.ClarifyQuestion("q1", "What time?", emptyList(), false)),
                ),
            ),
        )
        show(freeText)
        compose.onNodeWithTag("clarify-text").performTextInput("Seven")
        compose.onNodeWithTag("clarify-send").performClick()
        compose.onNodeWithTag("clarify-skip").performClick()
        assertEquals(listOf("clarify:srq-2:{q1=Seven}", "clarify:srq-2:{q1=null}"), calls)
    }

    @Test fun warningAndEndedSummary() {
        show(DemoStates.warning)
        compose.onNodeWithText("It has gone quiet. Hanging up in 30 seconds unless you speak.").assertIsDisplayed()
        show(DemoStates.ended)
        status("Call ended")
        compose.onNodeWithText("4:12 · voice 3:58 · about $0.20").assertIsDisplayed()
        compose.onNodeWithText("Talk again").assertIsDisplayed()
    }

    @Test fun failureExplainsItself() {
        show(DemoStates.failed)
        status("Could not connect")
        compose.onNodeWithText("Voice is not set up on this Hermes server (no OpenAI key).").assertIsDisplayed()
    }

    @Test fun transcriptExpandsOnTap() {
        show(DemoStates.speaking)
        compose.onNodeWithTag("captions").performClick()
        compose.onNodeWithTag("transcript").assertIsDisplayed()
        compose.onNodeWithText("What's on my list for tomorrow?").assertIsDisplayed()
    }

    @Test fun signInFormSubmits() {
        var got: Triple<String, String, String>? = null
        compose.setContent { LiveTheme { SignInScreen(SignInUi(error = "Hermes did not accept that user name and password."), { u, n, p -> got = Triple(u, n, p) }, {}) } }
        compose.onNodeWithText("Hermes did not accept that user name and password.").assertIsDisplayed()
        compose.onNodeWithTag("server").performTextInput("https://hermes.example.com")
        compose.onNodeWithTag("username").performTextInput("matt")
        compose.onNodeWithTag("password").performTextInput("not-a-real-password")
        compose.onNodeWithTag("signin").performClick()
        assertEquals(Triple("https://hermes.example.com", "matt", "not-a-real-password"), got)
    }

    @Test fun settingsShowDefaultsAndActions() {
        var settings = LiveSettings()
        compose.setContent {
            LiveTheme {
                SettingsScreen(
                    settings, AccountState.SignedIn("hermes.example.com", "matt"),
                    VoiceLiveStatus(ok = true, mode = "gpt-live", available = true, model = "gpt-live-1", voice = "marin"),
                    "1.0.0",
                    SettingsActions(onChange = { settings = it(settings) }, onSignOut = { calls += "signout" }, onResetConversation = { calls += "reset" }),
                )
            }
        }
        compose.onNodeWithText("gpt-live-1 · voice marin").assertIsDisplayed()
        compose.onNodeWithTag("spoken-approvals").performScrollTo().performClick()
        assertTrue(settings.spokenApprovals)
        compose.onNodeWithTag("signout").performScrollTo().performClick()
        assertEquals(listOf("signout"), calls)
    }
}
