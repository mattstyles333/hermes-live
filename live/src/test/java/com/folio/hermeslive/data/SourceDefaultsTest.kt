package com.folio.hermeslive.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Voice from" and "Cloud speech recognition from" (Hermes voice slice): existing users keep what
 * they had (OpenRouter); new users, and anyone who never used Custom, get Hermes when available.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SourceDefaultsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String, seed: (android.content.SharedPreferences.Editor) -> Unit = {}) =
        app.getSharedPreferences(name, Context.MODE_PRIVATE).also { p -> p.edit().also(seed).commit() }

    @Test fun aNewInstallHasNeverChosen() {
        val s = SettingsStore(prefs("src-new")).settings.value
        assertEquals(SourceChoice.Auto, s.voiceFrom)
        assertEquals(SourceChoice.Auto, s.sttFrom)
        assertEquals("", s.hermesVoice)
    }

    @Test fun anInstallThatUsedCustomKeepsOpenRoutersVoice() {
        val s = SettingsStore(prefs("src-custom") { it.putString("voice_engine", "cascade") }).settings.value
        assertEquals(SourceChoice.OpenRouter, s.voiceFrom)
        assertEquals("speech stayed on the phone: never chose", SourceChoice.Auto, s.sttFrom)
    }

    @Test fun anInstallThatPickedAVoiceKeepsOpenRouterEvenOnGptLiveNow() {
        val s = SettingsStore(prefs("src-voice") { it.putString("voice_engine", "gpt-live").putString("cascade_voice", "rachel") }).settings.value
        assertEquals(SourceChoice.OpenRouter, s.voiceFrom)
    }

    @Test fun anInstallWithCloudSpeechKeepsOpenRoutersRecognition() {
        val s = SettingsStore(prefs("src-cloud") { it.putString("cascade_speech_recognition", "cloud") }).settings.value
        assertEquals(SourceChoice.OpenRouter, s.sttFrom)
    }

    @Test fun anInstallThatOnlyEverSavedDefaultsHasNeverChosen() {
        // Saving any setting writes every key, with the defaults for Custom's voice.
        val s = SettingsStore(prefs("src-defaults") { it.putString("voice_engine", "gpt-live").putString("cascade_voice", "bill").putString("cascade_tts_model", "elevenlabs/eleven-v4-turbo") }).settings.value
        assertEquals(SourceChoice.Auto, s.voiceFrom)
    }

    @Test fun aNewUserWhoThenPicksCustomStaysOnHermes() {
        val p = prefs("src-new-custom")
        SettingsStore(p).update { it.copy(voiceEngine = VoiceEngine.Cascade, speechRecognition = SpeechRecognition.Cloud) }
        val again = SettingsStore(p).settings.value
        assertEquals(SourceChoice.Auto, again.voiceFrom)
        assertEquals(SourceChoice.Auto, again.sttFrom)
    }

    @Test fun anExplicitChoiceIsKept() {
        val p = prefs("src-explicit") { it.putString("voice_engine", "cascade") }
        SettingsStore(p).update { it.copy(voiceFrom = SourceChoice.Hermes, sttFrom = SourceChoice.OpenRouter, hermesVoice = "elevenlabs:v-ada") }
        val again = SettingsStore(p).settings.value
        assertEquals(SourceChoice.Hermes, again.voiceFrom)
        assertEquals(SourceChoice.OpenRouter, again.sttFrom)
        assertEquals("elevenlabs:v-ada", again.hermesVoice)
    }
}
