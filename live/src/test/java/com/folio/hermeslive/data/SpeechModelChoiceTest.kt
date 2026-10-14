package com.folio.hermeslive.data

import com.folio.hermeslive.stt.SttCatalog
import com.folio.hermeslive.stt.SttModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Voice › Speech models (picker slice): defaults, existing installs, fallbacks, delete rules. */
class SpeechModelChoiceTest {
    private fun on(vararg models: SttModel): (SttModel) -> Boolean = { it in models }

    @Test fun aNewInstallGetsTheDefaults() {
        val s = SpeechModelChoice.firstRun(LiveSettings(), on())
        assertEquals(SpeechModelChoice.DEFAULT_LIVE.id, s.liveModel)
        assertEquals(SpeechModelChoice.DEFAULT_ACCURATE.id, s.accurateModel)
        assertFalse(s.speechModelsCard)
    }

    @Test fun anExistingInstallKeepsItsModelsUntilTheUserPicks() {
        val s = SpeechModelChoice.firstRun(LiveSettings(), on(SttCatalog.zipformer, SttCatalog.parakeet110m))
        assertEquals(SttCatalog.zipformer.id, s.liveModel)
        assertEquals(SttCatalog.parakeet110m.id, s.accurateModel)
        assertEquals(
            "told once only when the defaults are different",
            SpeechModelChoice.DEFAULT_LIVE != SttCatalog.zipformer || SpeechModelChoice.DEFAULT_ACCURATE != SttCatalog.parakeet110m,
            s.speechModelsCard,
        )
        val high = SpeechModelChoice.firstRun(LiveSettings(highAccuracySpeech = true), on(SttCatalog.zipformer, SttCatalog.parakeet06b))
        assertEquals("High accuracy became the 0.6B pick", SttCatalog.parakeet06b.id, high.accurateModel)
        val chosen = LiveSettings(liveModel = SttCatalog.nemotron.id, accurateModel = SttCatalog.parakeet110m.id)
        assertEquals("a choice is never overwritten", chosen, SpeechModelChoice.firstRun(chosen, on(SttCatalog.zipformer)))
    }

    @Test fun aCallUsesTheFallbackWhileTheChosenModelIsNotDownloaded() {
        val s = LiveSettings(liveModel = SttCatalog.nemotron.id, accurateModel = SttCatalog.parakeet06b.id)
        assertEquals(SttCatalog.zipformer to SttCatalog.parakeet110m, SpeechModelChoice.forCall(s, on(SttCatalog.zipformer, SttCatalog.parakeet110m)))
        assertEquals(SttCatalog.nemotron to SttCatalog.parakeet06b, SpeechModelChoice.forCall(s, on(SttCatalog.nemotron, SttCatalog.parakeet06b)))
        assertNull("no live model at all: not ready", SpeechModelChoice.forCall(s, on(SttCatalog.parakeet110m)))
    }

    @Test fun theGuardsFallbackIsWantedWithAHeavyLiveModel() {
        val nemotron = LiveSettings(liveModel = SttCatalog.nemotron.id, accurateModel = SttCatalog.parakeet110m.id)
        assertEquals(setOf(SttCatalog.nemotron, SttCatalog.parakeet110m, SttCatalog.zipformer), SpeechModelChoice.wanted(nemotron).toSet())
        assertEquals(SttCatalog.zipformer, SpeechModelChoice.guardFallback(nemotron))
        val zipformer = LiveSettings(liveModel = SttCatalog.zipformer.id, accurateModel = SttCatalog.parakeet110m.id)
        assertNull("nothing to fall back to", SpeechModelChoice.guardFallback(zipformer))
    }

    @Test fun inUseAndFallbackModelsCannotBeDeleted() {
        val s = LiveSettings(liveModel = SttCatalog.moonshineBase.id, accurateModel = SttCatalog.parakeet06b.id)
        assertFalse(SpeechModelChoice.deletable(s, SttCatalog.moonshineBase.id))
        assertFalse(SpeechModelChoice.deletable(s, SttCatalog.parakeet06b.id))
        assertFalse("the guard's and the download's fallbacks", SpeechModelChoice.deletable(s, SttCatalog.zipformer.id))
        assertFalse(SpeechModelChoice.deletable(s, SttCatalog.parakeet110m.id))
        assertTrue(SpeechModelChoice.deletable(s, SttCatalog.nemotron.id))
    }

    @Test fun anUnknownSavedIdMeansTheDefault() {
        val s = LiveSettings(liveModel = "gone-model", accurateModel = "gone-too")
        assertEquals(SpeechModelChoice.DEFAULT_LIVE, SpeechModelChoice.live(s))
        assertEquals(SpeechModelChoice.DEFAULT_ACCURATE, SpeechModelChoice.accurate(s))
    }
}
