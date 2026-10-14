package com.folio.hermeslive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.RenderProgress
import com.folio.hermeslive.data.Correction
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.ModelRole
import com.folio.hermeslive.data.ModelState
import com.folio.hermeslive.data.PrepPhase
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.SpeechModelStatus
import com.folio.hermeslive.data.SpeechRecognition
import com.folio.hermeslive.stt.SttCatalog
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class VoiceSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var settings by mutableStateOf(LiveSettings())

    private fun ui(key: KeyStatus = KeyStatus(), prep: PrepState = PrepState(), metered: Boolean = false, corrections: List<Correction> = emptyList(), turns: Int? = 12) =
        VoiceUi(settings, key, false, null, EngineStatus.of(settings, key, prep), prep, metered, turns, corrections)

    private fun show(make: () -> VoiceUi) {
        compose.setContent {
            LiveTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceSettingsSection(
                        make(),
                        VoiceActions(
                            onChange = { settings = it(settings).normalized() },
                            onPasteKey = { calls += "paste" },
                            onCheckKey = { calls += "check" },
                            onRemoveKey = { calls += "remove" },
                            onPrepare = { calls += "prepare" },
                            onCancelPrepare = { calls += "cancel" },
                            onShareLog = { calls += "share" },
                            onClearLog = { calls += "clear" },
                            onForgetCorrection = { calls += "forget:$it" },
                            onChooseLive = { id -> settings = settings.copy(liveModel = id) },
                            onChooseAccurate = { id -> settings = settings.copy(accurateModel = id) },
                            onDownloadModel = { calls += "download:$it" },
                            onCancelModel = { calls += "pause:$it" },
                            onDeleteModel = { calls += "delete:$it" },
                            onDismissModelsCard = { settings = settings.copy(speechModelsCard = false) },
                        ),
                    )
                }
            }
        }
    }

    @Test fun gptLiveIsTheDefaultAndHidesTheCascadeSettings() {
        show { ui() }
        compose.onNodeWithText("GPT-Live, through your Hermes server.").assertIsDisplayed()
        compose.onNodeWithText("GPT-Live, through Hermes (default)").assertIsDisplayed()
        compose.onNodeWithTag("pause").assertDoesNotExist()
        compose.onNodeWithTag("prepare").assertDoesNotExist()
        compose.onNodeWithTag("engine").performClick()
        assertEquals(VoiceEngine.Cascade, settings.voiceEngine)
    }

    @Test fun cascadeWithoutAKeyAsksForOneAndOffersNoPrepare() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui() }
        compose.onNodeWithText("paste an OpenRouter key", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not saved", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("key-paste").performClick()
        compose.onNodeWithTag("key-remove").assertDoesNotExist()
        compose.onNodeWithTag("prepare").assertDoesNotExist()
        assertEquals(listOf("paste"), calls)
    }

    @Test fun aSavedKeyShowsOnlyItsEndAndRemovingTakesTwoTaps() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui(KeyStatus(true, KeyCheck.Valid, "fake")) }
        compose.onNodeWithText("ends …fake · checked", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("key-check").performClick()
        compose.onNodeWithTag("key-remove").performClick()
        assertEquals(listOf("check"), calls)
        compose.onNodeWithText("Tap to remove").performClick()
        assertEquals(listOf("check", "remove"), calls)
    }

    @Test fun pauseTasksTogglesAndVocabularyMapOntoSettings() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui(KeyStatus(true, KeyCheck.Valid)) }
        compose.onNodeWithTag("pause").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1_500f) }
        assertEquals(1_500, settings.pauseMs)
        compose.onNodeWithTag("max-tasks").performScrollTo().performClick()
        compose.onNodeWithTag("max-tasks").performClick()
        assertEquals(6, settings.maxTasks)
        compose.onNodeWithTag("max-tasks").performClick()
        assertEquals("wraps to 1", 1, settings.maxTasks)
        listOf("backchannel", "hold-to-talk").forEach { compose.onNodeWithTag(it).performScrollTo().performClick() }
        compose.onNodeWithTag("echo-cancel").performScrollTo().performClick()
        assertTrue(settings.backchannel)
        assertTrue(settings.holdToTalk)
        assertFalse(settings.echoCancel)
        compose.onNodeWithTag("vocabulary").performScrollTo().performTextInput("Spex4Less\nInvenTree\n\n")
        assertEquals(listOf("Spex4Less", "InvenTree"), settings.vocabulary)
        assertEquals(listOf("Spex4Less", "InvenTree"), settings.cascadeSettings().vocabulary)
        compose.onNodeWithTag("decision-log").performScrollTo().performClick()
        assertFalse(settings.decisionLog)
    }

    @Test fun aHalfTypedModelIdIsNotSaved() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui(KeyStatus(true, KeyCheck.Valid)) }
        compose.onNodeWithTag("tts-model").performScrollTo().performTextClearance()
        compose.onNodeWithTag("tts-model").performTextInput("elevenlabs")
        assertEquals("elevenlabs/eleven-v4-turbo", settings.ttsModel)
        compose.onNodeWithTag("tts-model").performTextInput("/eleven-v5")
        assertEquals("elevenlabs/eleven-v5", settings.ttsModel)
        assertTrue(isModelId("elevenlabs/eleven-v4-turbo"))
        assertFalse(isModelId("eleven labs/x"))
        assertFalse(isModelId("noslash"))
    }

    @Test fun learnedCorrectionsAndTheDecisionLogHaveTheirActions() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui(KeyStatus(true, KeyCheck.Valid), corrections = listOf(Correction("jeff", "Jev", 3, 1))) }
        compose.onNodeWithText("“jeff” → Jev  ×3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("forget-jeff").performScrollTo().performClick()
        compose.onNodeWithTag("log-count").performScrollTo().assertTextContains("12 turns kept")
        compose.onNodeWithTag("log-share").performScrollTo().performClick()
        compose.onNodeWithTag("log-clear").performScrollTo().performClick()
        assertEquals(listOf("forget:jeff", "share", "clear"), calls)
    }

    @Test fun prepareShowsLinesModelsAndTheWifiHint() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        val models = listOf(
            SpeechModelStatus("zipformer", "Streaming model", ModelRole.Streaming, ModelState.Downloading, 36_500_000, 73_000_000),
            SpeechModelStatus("silero", "Speech detector", ModelRole.Vad, ModelState.Ready, 600_000, 600_000),
        )
        var prep by mutableStateOf(PrepState(models = models))
        show { ui(KeyStatus(true, KeyCheck.Valid), prep = prep, metered = true) }
        compose.onNodeWithText("Prepare the voice before the first call", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("model-zipformer").performScrollTo().assertTextContains("Streaming model: 50% of 70 MB")
        compose.onNodeWithTag("wifi-hint").performScrollTo().assertTextContains("mobile data", substring = true)
        compose.onNodeWithTag("prepare").performScrollTo().performClick()
        prep = prep.copy(phase = PrepPhase.Running, phrases = RenderProgress(total = 285, done = 100))
        compose.onNodeWithTag("prepare-lines").performScrollTo().assertTextContains("Lines: 100 of 285")
        compose.onNodeWithTag("prepare-cancel").performScrollTo().performClick()
        prep = prep.copy(phase = PrepPhase.Cancelled)
        compose.onNodeWithText("Resume").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("prepare", "cancel"), calls)
    }

    @Test fun speechRecognitionSaysWhereAudioGoesAndShowsTheCost() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui(KeyStatus(true, KeyCheck.Valid)) }
        compose.onNodeWithTag("speech-recognition").performScrollTo().assertTextContains("never leaves the phone", substring = true)
        compose.onNodeWithTag("cloud-stt-model").assertDoesNotExist()
        compose.onNodeWithTag("speech-recognition").performClick()
        assertEquals(SpeechRecognition.Cloud, settings.speechRecognition)
        compose.onNodeWithTag("speech-recognition").performScrollTo().assertTextContains("sent to OpenRouter", substring = true)
        compose.onNodeWithTag("cloud-stt-cost").performScrollTo().assertTextContains("About $0.45 an hour of speech", substring = true)
        compose.onNodeWithTag("cloud-stt-model").performScrollTo().assertTextContains("AssemblyAI", substring = true)
        compose.onNodeWithTag("cloud-stt-model").performClick()
        assertEquals("elevenlabs/scribe-v2", settings.cloudSttModel)
        compose.onNodeWithTag("cloud-stt-model").performClick()
        compose.onNodeWithTag("cloud-stt-model").performClick()
        assertEquals("wraps", "assemblyai/universal-3-5-pro", settings.cloudSttModel)
        compose.onNodeWithTag("cloud-stt-model-id").performScrollTo().performTextClearance()
        compose.onNodeWithTag("cloud-stt-model-id").performTextInput("someone/new-stt")
        assertEquals("someone/new-stt", settings.cloudSttModel)
        compose.onNodeWithTag("cloud-stt-cost").performScrollTo().assertTextContains("this model's price", substring = true)
        compose.onNodeWithTag("cloud-stt-model").performClick()
        assertEquals("a custom id cycles back to the list", "assemblyai/universal-3-5-pro", settings.cloudSttModel)
    }

    @Test fun transcriptCleanupSaysWhatGoesAndCycles() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade)
        show { ui(KeyStatus(true, KeyCheck.Valid)) }
        compose.onNodeWithTag("transcript-cleanup").performScrollTo().assertTextContains("On device only (default)", substring = true)
        compose.onNodeWithTag("transcript-cleanup").assertTextContains("Never the audio", substring = true)
        compose.onNodeWithTag("transcript-cleanup").performClick()
        assertEquals(com.folio.hermeslive.data.TranscriptCleanupMode.Always, settings.transcriptCleanup)
        compose.onNodeWithTag("transcript-cleanup").performClick()
        assertEquals(com.folio.hermeslive.data.TranscriptCleanupMode.Off, settings.transcriptCleanup)
        compose.onNodeWithTag("transcript-cleanup").performScrollTo().assertTextContains("as the phone heard them", substring = true)
        compose.onNodeWithTag("transcript-cleanup").performClick()
        assertEquals("wraps", com.folio.hermeslive.data.TranscriptCleanupMode.OnDeviceOnly, settings.transcriptCleanup)
    }

    @Test fun speechModelsPickDownloadAndDelete() {
        settings = LiveSettings(voiceEngine = VoiceEngine.Cascade, liveModel = SttCatalog.zipformer.id, accurateModel = SttCatalog.parakeet110m.id, speechModelsCard = true)
        val models = listOf(
            SpeechModelStatus(SttCatalog.zipformer.id, "Zipformer", ModelRole.Streaming, ModelState.Ready),
            SpeechModelStatus(SttCatalog.nemotron.id, "Nemotron", ModelRole.Streaming, ModelState.Downloading, 100_000_000, SttCatalog.nemotron.bytes),
            SpeechModelStatus(SttCatalog.moonshineBase.id, "Moonshine", ModelRole.Streaming, ModelState.Ready),
            SpeechModelStatus(SttCatalog.parakeet110m.id, "Parakeet 110M", ModelRole.Accurate, ModelState.Ready),
            SpeechModelStatus(SttCatalog.parakeet06b.id, "Parakeet 0.6B", ModelRole.Accurate, ModelState.Missing),
        )
        show { ui(KeyStatus(true, KeyCheck.Valid)).copy(speechModels = models) }
        compose.onNodeWithTag("speech-models-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("speech-model-${SttCatalog.parakeet06b.id}").performScrollTo().assertTextContains("460 MB to download", substring = true)
        compose.onNodeWithTag("model-download-${SttCatalog.parakeet06b.id}").performScrollTo().performClick()
        compose.onNodeWithTag("model-cancel-${SttCatalog.nemotron.id}").performScrollTo().performClick()
        compose.onNodeWithTag("model-delete-${SttCatalog.moonshineBase.id}").performScrollTo().performClick()
        compose.onNodeWithTag("model-delete-${SttCatalog.zipformer.id}").assertDoesNotExist()
        compose.onNodeWithTag("model-delete-${SttCatalog.parakeet110m.id}").assertDoesNotExist()
        assertEquals(listOf("download:${SttCatalog.parakeet06b.id}", "pause:${SttCatalog.nemotron.id}", "delete:${SttCatalog.moonshineBase.id}"), calls)
        compose.onNodeWithTag("speech-model-${SttCatalog.parakeet06b.id}").performScrollTo().performClick()
        assertEquals(SttCatalog.parakeet06b.id, settings.accurateModel)
        compose.onNodeWithTag("speech-model-${SttCatalog.nemotron.id}").performScrollTo().performClick()
        assertEquals(SttCatalog.nemotron.id, settings.liveModel)
        compose.onNodeWithTag("speech-models-card-dismiss").performScrollTo().performClick()
        assertFalse(settings.speechModelsCard)
    }
}
