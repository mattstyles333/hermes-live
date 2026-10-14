package com.folio.hermeslive.ui

import com.folio.hermeslive.stt.SttCatalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermes.cascade.RenderProgress
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.ModelRole
import com.folio.hermeslive.data.ModelState
import com.folio.hermeslive.data.PrepPhase
import com.folio.hermeslive.data.PrepState
import com.folio.hermeslive.data.SpeechModelStatus
import com.folio.hermeslive.data.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The idle screen's voice line follows the chosen engine. With Bill chosen it shows his readiness
 * (ready, preparing with progress, models to fetch, no key, key refused), never GPT-Live's
 * server status, which reads "Unavailable: no OpenAI API key…" on a cascade-only server.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class HomeVoiceLineTest {
    @get:Rule
    val compose = createComposeRule()

    private val noOpenAi = VoiceLiveStatus(ok = true, mode = "gpt-live", available = false, reason = "no OpenAI API key (set OPENAI_API_KEY)")
    private val bill = LiveSettings(voiceEngine = VoiceEngine.Cascade)
    private val validKey = KeyStatus(saved = true, check = KeyCheck.Valid, hint = "abcd")
    private val mb = 1_048_576L

    private fun model(role: ModelRole, state: ModelState, done: Long = 0, total: Long = 0, id: String? = null) =
        SpeechModelStatus(id ?: if (role == ModelRole.Streaming) SttCatalog.zipformer.id else SttCatalog.parakeet110m.id, role.name, role, state, done, total)

    /** The standard pair installed; High accuracy not downloaded (it is optional). */
    private val installed = listOf(
        model(ModelRole.Streaming, ModelState.Ready, 70 * mb, 70 * mb),
        model(ModelRole.Accurate, ModelState.Ready, 120 * mb, 120 * mb),
        model(ModelRole.Accurate, ModelState.Missing, 0, 600 * mb, id = SttCatalog.parakeet06b.id),
    )

    /** Render the idle screen with [line] and check it is what the user sees. */
    private fun shows(line: String?, expected: String) {
        assertEquals(expected, line)
        compose.setContent { LiveTheme { CallScreen(DemoStates.idle, CallActions(), voiceLine = line) } }
        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test fun billReady() {
        val line = HomeVoiceLine.of(bill, noOpenAi, validKey, PrepState(), installed)
        shows(line, "Voice: Bill · ready")
        compose.onNodeWithText("Unavailable", substring = true).assertDoesNotExist()
        // Rendering the stock lines alone does not hold a call back (the call switch needs only the models).
        val rendering = PrepState(phase = PrepPhase.Running, phrases = RenderProgress(total = 285, done = 40))
        assertEquals("Voice: Bill · ready (lines 40 of 285)", HomeVoiceLine.of(bill, noOpenAi, validKey, rendering, installed))
    }

    @Test fun billPreparingShowsProgress() {
        val downloading = listOf(
            model(ModelRole.Streaming, ModelState.Ready, 70 * mb, 70 * mb),
            model(ModelRole.Accurate, ModelState.Downloading, 30 * mb, 120 * mb),
        )
        val prep = PrepState(phase = PrepPhase.Running, phrases = RenderProgress(total = 285, done = 100, cached = 20))
        shows(HomeVoiceLine.of(bill, noOpenAi, validKey, prep, downloading), "Voice: Bill · preparing (speech models 25%, lines 120 of 285)")
    }

    @Test fun billNeedsHisModels() {
        val missing = listOf(model(ModelRole.Streaming, ModelState.Missing, 0, 70 * mb), model(ModelRole.Accurate, ModelState.Paused, 20 * mb, 120 * mb))
        shows(HomeVoiceLine.of(bill, noOpenAi, validKey, PrepState(), missing), "Voice: Bill · needs his speech models (about 170 MB), downloaded on Wi-Fi")
    }

    @Test fun billNeedsAKey() {
        shows(HomeVoiceLine.of(bill, noOpenAi, KeyStatus(), PrepState(), installed), "Voice: Bill · needs an OpenRouter key (Settings › Voice)")
    }

    @Test fun billKeyRefused() {
        val refused = KeyStatus(saved = true, check = KeyCheck.Refused, hint = "abcd")
        shows(HomeVoiceLine.of(bill, noOpenAi, refused, PrepState(), installed), "Voice: Bill · OpenRouter refused the key (Settings › Voice)")
    }

    @Test fun gptLiveKeepsTheServerStatus() {
        // Control: with GPT-Live chosen the line is the server's voice status, as before.
        val gpt = LiveSettings(voiceEngine = VoiceEngine.GptLive)
        shows(HomeVoiceLine.of(gpt, noOpenAi, validKey, PrepState(), installed), "Voice: Unavailable: no OpenAI API key (set OPENAI_API_KEY)")
        assertEquals(null, HomeVoiceLine.of(gpt, null, validKey, PrepState(), installed))
    }
}
