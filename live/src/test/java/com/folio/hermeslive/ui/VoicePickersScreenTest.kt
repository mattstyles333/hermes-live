package com.folio.hermeslive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.data.GptLivePrefs
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.TtsModelInfo
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceList
import com.folio.hermeslive.data.VoiceOption
import com.folio.hermeslive.data.VoicePickerUi
import com.folio.hermeslive.data.VoiceSource
import com.folio.hermeslive.data.VoiceUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings › Voice after the ondemand slice: the engine names, the save-cost switch, and the voice pickers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoicePickersScreenTest {
    @get:Rule val compose = createComposeRule()

    private var settings by mutableStateOf(LiveSettings())
    private val calls = mutableListOf<String>()
    private val picker = VoicePickerUi(
        ttsModels = VoiceList(
            listOf(
                TtsModelInfo("elevenlabs/eleven-v4-turbo", "ElevenLabs v4 Turbo", listOf("george", "bill", "sarah"), 20.0),
                TtsModelInfo("google/gemini-tts", "Gemini TTS", listOf("Zephyr", "Puck")),
            ),
            VoiceSource.Live,
        ),
        grokVoices = VoiceList(listOf(VoiceOption("eve", "Eve"), VoiceOption("ara", "Ara")), VoiceSource.BuiltIn),
    )

    private fun show() {
        val key = KeyStatus(true, KeyCheck.Valid)
        compose.setContent {
            LiveTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceSettingsSection(
                        VoiceUi(settings, key, engine = EngineStatus.of(settings, key, PrepState(), key)),
                        VoiceActions(onChange = { settings = it(settings).normalized() }),
                        picker,
                        VoicePickerActions(
                            onLoad = { calls += "load:$it" },
                            onChooseTtsModel = { calls += "model:$it" },
                            onChooseVoice = { calls += "voice:$it" },
                            onChooseGrokVoice = { calls += "grok:$it" },
                            onPreviewCustom = { calls += "preview:$it" },
                            onPreviewGrok = { calls += "grok-preview:$it" },
                        ),
                    )
                }
            }
        }
    }

    @Test fun gptLiveHasItsOptionsAndAnExperimentalSaveCostSwitchOff() {
        val prefs = GptLivePrefs.of(ApplicationProvider.getApplicationContext())
        show()
        // GPT-Live's options: parallel agents (on by default) and the server's voice, read-only.
        compose.onNodeWithTag("gptlive-agents").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gptlive-voice").performScrollTo().assertIsDisplayed()
        assertTrue("parallel agents are on by default", prefs.agents.value)
        compose.onNodeWithTag("gptlive-agents").performClick()
        assertFalse("and can be switched off", prefs.agents.value)
        compose.onNodeWithTag("gptlive-agents").performClick()
        assertTrue(prefs.agents.value)
        // Save cost for GPT-Live is experimental and off by default.
        compose.onNodeWithText("Save cost (experimental): close the voice session while agents work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save-cost-switch").assertIsOff().performClick()
        assertTrue(settings.gptLiveSaveCost)
        assertTrue("Grok's switch is its own", settings.grokSaveCost)
        compose.onNodeWithTag("save-cost-switch").assertIsOn()
        assertTrue("GPT-Live loads no voice list", calls.none { it.startsWith("load") })
    }

    @Test fun theEnginesAreGptLiveGrokVoiceAndCustom() {
        show()
        compose.onNodeWithText("GPT-Live, through Hermes (default)").assertIsDisplayed()
        compose.onNodeWithTag("engine").performClick()
        compose.onNodeWithText("Custom: on-phone speech recognition, Jev routing, your choice of voice").assertIsDisplayed()
        compose.onNodeWithTag("save-cost").assertDoesNotExist()
        compose.onNodeWithTag("engine").performClick()
        compose.onNodeWithText("Grok Voice: xAI's speech-to-speech voice, delegating to Hermes agents").assertIsDisplayed()
        compose.onNodeWithText("Save cost: close the voice session while agents work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save-cost-switch").performScrollTo().assertIsOn().performClick()
        assertFalse(settings.grokSaveCost)
        assertFalse(settings.gptLiveSaveCost)
        compose.onNodeWithText("Bill", substring = true).assertDoesNotExist()
    }

    @Test fun customListsTheModelsVoicesWithPreviewsAndBillIsTheDefault() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show()
        assertTrue(calls.contains("load:false"))
        compose.onNodeWithText("Bill (default)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice-picker").performScrollTo().performClick()
        compose.onNodeWithTag("voice-george").performScrollTo().performClick()
        compose.onNodeWithTag("voice-sarah-preview").performScrollTo().performClick()
        assertEquals(listOf("load:false", "voice:george", "preview:sarah"), calls)
        compose.onNodeWithTag("tts-model-picker").performScrollTo().performClick()
        compose.onNodeWithText("3 voices · \$20.00 per million characters").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tts-model-google/gemini-tts").performScrollTo().performClick()
        assertEquals("model:google/gemini-tts", calls.last())
        compose.onNodeWithText("Voices from OpenRouter.").performScrollTo().assertIsDisplayed()
    }

    @Test fun grokListsItsVoicesWithPreviews() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Grok)
        show()
        compose.onNodeWithTag("grok-voice").performScrollTo().performClick()
        compose.onNodeWithTag("grok-voice-ara").performScrollTo().performClick()
        compose.onNodeWithTag("grok-voice-eve-preview").performScrollTo().performClick()
        assertEquals(listOf("load:false", "grok:ara", "grok-preview:eve"), calls)
        compose.onNodeWithText("xAI's list isn't available; showing the built-in voices.").performScrollTo().assertIsDisplayed()
    }
}
