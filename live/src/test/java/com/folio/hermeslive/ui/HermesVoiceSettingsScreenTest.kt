package com.folio.hermeslive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.live.HermesVoiceState
import com.folio.hermes.live.HermesVoiceView
import com.folio.hermes.live.SideView
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.HermesVoiceUi
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.SourceChoice
import com.folio.hermeslive.data.SpeechRecognition
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceOption
import com.folio.hermeslive.data.VoiceUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings › Voice › Custom with Hermes as the source (Hermes voice slice). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class HermesVoiceSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var settings by mutableStateOf(LiveSettings(voiceEngine = VoiceEngine.Cascade))
    private val calls = mutableListOf<String>()
    private val key = KeyStatus(true, KeyCheck.Valid, "fake")

    private val elevenDirect = SideView("direct", "elevenlabs", "eleven_flash_v2_5", "v-bill", "elevenlabs-tts", null)
    private val sttRelay = SideView("relay", null, null, null, null, "xai oauth (server-managed) or no credentials")

    private fun ready(tts: SideView, stt: SideView = sttRelay) = HermesVoiceState.Ready(HermesVoiceView(stt, tts))

    private fun show(hermes: HermesVoiceUi) {
        compose.setContent {
            LiveTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceSettingsSection(
                        VoiceUi(settings, key, engine = EngineStatus.of(settings, key, PrepState())),
                        VoiceActions(onChange = { settings = it(settings).normalized() }),
                        hermes = hermes,
                        hermesActions = HermesVoiceActions(
                            onRefresh = { calls += "refresh" },
                            onVoiceFrom = { c -> settings = settings.copy(voiceFrom = c) },
                            onSttFrom = { c -> settings = settings.copy(sttFrom = c) },
                            onChooseVoice = { v -> calls += "voice:$v" },
                            onPreview = { v -> calls += "preview:$v" },
                        ),
                    )
                }
            }
        }
    }

    @Test fun hermesIsTheDefaultAndShowsWhatItUsesWithItsVoices() {
        show(HermesVoiceUi(ready(elevenDirect), signedIn = true, voices = listOf(VoiceOption("v-bill", "Bill (premade)"), VoiceOption("v-ada", "Ada (cloned)"))))
        compose.onNodeWithText("Hermes (default)").assertIsDisplayed()
        compose.onNodeWithText("ElevenLabs · eleven_flash_v2_5", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Straight from your phone to ElevenLabs", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("voice-picker").assertDoesNotExist()
        compose.onNodeWithTag("hermes-voice-picker").performScrollTo().performClick()
        compose.onNodeWithTag("hermes-voice-v-ada").performScrollTo().performClick()
        compose.onNodeWithTag("hermes-voice-v-ada-preview").performScrollTo().performClick()
        assertEquals(listOf("refresh", "voice:v-ada", "preview:v-ada"), calls)
    }

    @Test fun aRelayedVoiceIsReadOnly() {
        show(HermesVoiceUi(ready(SideView("relay", null, null, null, null, "provider 'edge' has no client wire")), signedIn = true))
        compose.onNodeWithText("Relayed through your Hermes server", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("hermes-voice-readonly").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hermes-voice-picker").assertDoesNotExist()
    }

    @Test fun signedOutOpenRouterSpeaksAndItsPickerShows() {
        show(HermesVoiceUi(signedIn = false))
        compose.onNodeWithText("Sign in to Hermes to use its voice", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("voice-picker").performScrollTo().assertIsDisplayed()
    }

    @Test fun choosingOpenRouterGoesBackToTodaysPicker() {
        show(HermesVoiceUi(ready(elevenDirect), signedIn = true))
        compose.onNodeWithTag("voice-from").performClick()
        assertEquals(SourceChoice.OpenRouter, settings.voiceFrom)
        compose.onNodeWithText("OpenRouter (ElevenLabs)").assertIsDisplayed()
        compose.onNodeWithTag("voice-picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hermes-tts").assertDoesNotExist()
    }

    @Test fun theKeyBlockSaysWhatStillNeedsOpenRouter() {
        show(HermesVoiceUi(ready(elevenDirect), signedIn = true))
        compose.onNodeWithTag("key-still-needed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Jev (turn-taking and routing), the writer and transcript clean-up", substring = true).assertIsDisplayed()
    }

    @Test fun cloudSpeechFromHermesSaysWhereTheAudioGoes() {
        settings = settings.copy(speechRecognition = SpeechRecognition.Cloud)
        show(HermesVoiceUi(ready(elevenDirect, SideView("direct", "openai", "whisper-1", null, "openai-multipart", null)), signedIn = true))
        compose.onNodeWithTag("stt-from").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("goes straight to OpenAI", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cloud-stt-model").assertDoesNotExist()
        compose.onNodeWithTag("stt-from").performClick()
        assertEquals(SourceChoice.OpenRouter, settings.sttFrom)
        compose.onNodeWithTag("cloud-stt-model").performScrollTo().assertIsDisplayed()
    }
}
