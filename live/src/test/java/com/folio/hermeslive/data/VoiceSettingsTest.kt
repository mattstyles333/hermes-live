package com.folio.hermeslive.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.CascadeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun voiceDefaultsKeepTheCascadeOff() {
        val d = LiveSettings()
        assertEquals(VoiceEngine.GptLive, d.voiceEngine)
        assertEquals(1_000, d.pauseMs)
        assertEquals(4, d.maxTasks)
        assertEquals("bill", d.billVoice)
        assertEquals("elevenlabs/eleven-v4-turbo", d.ttsModel)
        assertFalse("backchannel off", d.backchannel)
        assertFalse(d.holdToTalk)
        assertTrue(d.vocabulary.isEmpty())
        assertTrue("decision log on (the user's own phone)", d.decisionLog)
        assertTrue(d.echoCancel)
        assertFalse(d.highAccuracySpeech)
    }

    @Test fun defaultsMapOntoTheSpecDefaults() {
        assertEquals(CascadeSettings(), LiveSettings().cascadeSettings())
    }

    @Test fun everyVoiceFieldMapsOntoCascadeSettings() {
        val s = LiveSettings(
            pauseMs = 1_500, maxTasks = 2, billVoice = "george", ttsModel = "vendor/model", backchannel = true,
            holdToTalk = true, vocabulary = listOf("Spex4Less", "InvenTree"), decisionLog = false,
        ).cascadeSettings()
        assertEquals(1_500L, s.pauseMs)
        assertEquals(2, s.maxTasks)
        assertEquals("george", s.voice)
        assertEquals("vendor/model", s.ttsModel)
        assertTrue(s.backchannel)
        assertTrue(s.holdToTalk)
        assertEquals(listOf("Spex4Less", "InvenTree"), s.vocabulary)
        assertFalse(s.decisionLog)
        // Fields the user does not set keep the spec values.
        assertEquals(CascadeSettings().offCallKeepAliveMs, s.offCallKeepAliveMs)
        assertEquals(CascadeSettings().jevTimeoutMs, s.jevTimeoutMs)
    }

    /** Spec v2 §14 item 11: High accuracy waits longer for the 0.6B second pass. */
    @Test fun highAccuracyWaitsLongerForTheAccuratePass() {
        assertEquals(1_500L, LiveSettings().cascadeSettings().accuratePassWaitMs)
        assertEquals(2_500L, LiveSettings(accurateModel = com.folio.hermeslive.stt.SttCatalog.parakeet06b.id).cascadeSettings().accuratePassWaitMs)
        assertEquals(0.45, LiveSettings().cascadeSettings().uncertainBelow, 1e-9)
    }

    @Test fun boundsAreClamped() {
        assertEquals(400, LiveSettings(pauseMs = 10).normalized().pauseMs)
        assertEquals(3_000, LiveSettings(pauseMs = 99_999).normalized().pauseMs)
        assertEquals(1, LiveSettings(maxTasks = 0).normalized().maxTasks)
        assertEquals(6, LiveSettings(maxTasks = 40).normalized().maxTasks)
        assertEquals(400L, LiveSettings(pauseMs = -5).cascadeSettings().pauseMs)
        assertEquals(6, LiveSettings(maxTasks = 9).cascadeSettings().maxTasks)
        assertEquals("bill", LiveSettings(billVoice = "  ").normalized().billVoice)
        assertEquals("elevenlabs/eleven-v4-turbo", LiveSettings(ttsModel = "").normalized().ttsModel)
        assertTrue(LiveSettings.PAUSE_CHOICES.all { it in LiveSettings.MIN_PAUSE_MS..LiveSettings.MAX_PAUSE_MS })
    }

    @Test fun vocabularyIsOnePhrasePerLine() {
        val cleaned = LiveSettings.cleanVocabulary(listOf("  Spex4Less \n\nInvenTree\nspex4less\n  two   words ", "x".repeat(100)))
        assertEquals(listOf("Spex4Less", "InvenTree", "two words", "x".repeat(LiveSettings.MAX_PHRASE_CHARS)), cleaned)
        assertEquals(LiveSettings.MAX_VOCABULARY, LiveSettings.cleanVocabulary((1..500).map { "w$it" }).size)
    }

    @Test fun voiceSettingsRoundTripThroughPrefs() {
        val prefs = context.getSharedPreferences("voice-settings-test", Context.MODE_PRIVATE)
        val store = SettingsStore(prefs)
        store.update {
            it.copy(
                voiceEngine = VoiceEngine.Cascade, pauseMs = 1_200, maxTasks = 6, billVoice = "sarah", ttsModel = "a/b",
                backchannel = true, holdToTalk = true, vocabulary = listOf("Jev", "Hermes"), decisionLog = false,
                echoCancel = false, highAccuracySpeech = true,
            )
        }
        val again = SettingsStore(prefs).settings.value
        assertEquals(VoiceEngine.Cascade, again.voiceEngine)
        assertEquals(1_200, again.pauseMs)
        assertEquals(6, again.maxTasks)
        assertEquals("sarah", again.billVoice)
        assertEquals("a/b", again.ttsModel)
        assertTrue(again.backchannel)
        assertTrue(again.holdToTalk)
        assertEquals(listOf("Jev", "Hermes"), again.vocabulary)
        assertFalse(again.decisionLog)
        assertFalse(again.echoCancel)
        assertTrue(again.highAccuracySpeech)
    }

    @Test fun outOfRangeValuesAreClampedOnSaveAndOnRead() {
        val prefs = context.getSharedPreferences("voice-bounds-test", Context.MODE_PRIVATE)
        val store = SettingsStore(prefs)
        store.update { it.copy(pauseMs = 50, maxTasks = 99) }
        assertEquals(400, store.settings.value.pauseMs)
        assertEquals(6, store.settings.value.maxTasks)
        // A hand-edited or older file is clamped on read too.
        prefs.edit().putInt("cascade_pause_ms", 9_000).putInt("cascade_max_tasks", -3).putString("voice_engine", "nonsense").commit()
        val read = SettingsStore(prefs).settings.value
        assertEquals(3_000, read.pauseMs)
        assertEquals(1, read.maxTasks)
        assertEquals(VoiceEngine.GptLive, read.voiceEngine)
    }

    @Test fun engineWireNames() {
        assertEquals("gpt-live", VoiceEngine.GptLive.wire)
        assertEquals("cascade", VoiceEngine.Cascade.wire)
        assertEquals(VoiceEngine.Cascade, VoiceEngine.parse("cascade"))
        assertEquals(VoiceEngine.GptLive, VoiceEngine.parse(null))
    }

    // ── Speech recognition (cloud STT slice) ────────────────────────────────────────────────

    @Test fun speechRecognitionDefaultsToOnDeviceAndSendsNoModel() {
        val d = LiveSettings()
        assertEquals(SpeechRecognition.OnDevice, d.speechRecognition)
        assertEquals("assemblyai/universal-3-5-pro", d.cloudSttModel)
        assertEquals("on device: no cloud model, so no audio request", null, d.cascadeSettings().cloudSttModel)
        assertEquals(null, LiveSettings(cloudSttModel = "elevenlabs/scribe-v2").cascadeSettings().cloudSttModel)
    }

    @Test fun cloudModeSendsTheChosenModelAndKeepsTheWaitLimit() {
        val s = LiveSettings(speechRecognition = SpeechRecognition.Cloud, cloudSttModel = "elevenlabs/scribe-v2").cascadeSettings()
        assertEquals("elevenlabs/scribe-v2", s.cloudSttModel)
        assertEquals("the cloud never extends the wait", 1_500L, s.accuratePassWaitMs)
        assertEquals("a custom id is kept", "someone/new-stt", LiveSettings(speechRecognition = SpeechRecognition.Cloud, cloudSttModel = " someone/new-stt ").cascadeSettings().cloudSttModel)
        assertEquals("a broken id falls back to the default", "assemblyai/universal-3-5-pro", LiveSettings(cloudSttModel = "not a model").normalized().cloudSttModel)
    }

    @Test fun speechRecognitionIsSavedAndReadBack() {
        val prefs = context.getSharedPreferences("voice-stt-test", Context.MODE_PRIVATE)
        SettingsStore(prefs).update { it.copy(speechRecognition = SpeechRecognition.Cloud, cloudSttModel = "x-ai/grok-stt-1.0") }
        val read = SettingsStore(prefs).settings.value
        assertEquals(SpeechRecognition.Cloud, read.speechRecognition)
        assertEquals("x-ai/grok-stt-1.0", read.cloudSttModel)
        assertEquals(SpeechRecognition.OnDevice, SpeechRecognition.parse("bogus"))
    }
}
