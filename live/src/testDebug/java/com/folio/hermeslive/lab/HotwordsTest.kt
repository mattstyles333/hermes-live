package com.folio.hermeslive.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotwordsTest {
    @Test fun parsesPhrasesAndScores() {
        val parsed = Hotwords.parse("Hermes Live :2.5\n\n  spex four less  \nBill", upperCase = false)
        assertEquals(emptyList<String>(), parsed.errors)
        assertEquals(
            listOf(
                Hotwords.Phrase(listOf("Hermes", "Live"), 2.5f),
                Hotwords.Phrase(listOf("spex", "four", "less"), null),
                Hotwords.Phrase(listOf("Bill"), null),
            ),
            parsed.phrases,
        )
        assertEquals("Hermes Live :2.5\nspex four less\nBill", Hotwords.toSherpa(parsed.phrases))
    }

    @Test fun upperCasesForLibriSpeechTokens() {
        val parsed = Hotwords.parse("hermes live", upperCase = true)
        assertEquals(listOf("HERMES", "LIVE"), parsed.phrases.single().words)
    }

    @Test fun badScoresAreErrorsNotPhrases() {
        val parsed = Hotwords.parse("a :x\nb :0\n:3\nc :2 d\ne :25", upperCase = false)
        assertTrue(parsed.phrases.isEmpty())
        assertEquals(5, parsed.errors.size)
    }

    @Test fun slashIsNotPassedThrough() {
        // sherpa treats "/" as a phrase separator; the lab keeps one phrase per line.
        val parsed = Hotwords.parse("either/or", upperCase = false)
        assertEquals(listOf("either", "or"), parsed.phrases.single().words)
    }

    @Test fun nemotronAlwaysDecodesGreedy() {
        assertEquals(Hotwords.GREEDY, Hotwords.decodingFor(ModelKind.NEMOTRON_STREAMING, Hotwords.BEAM))
        assertEquals(Hotwords.BEAM, Hotwords.decodingFor(ModelKind.ZIPFORMER_STREAMING, Hotwords.BEAM))
        assertEquals(Hotwords.BEAM, Hotwords.decodingFor(ModelKind.PARAKEET_TDT, Hotwords.BEAM))
        assertEquals(Hotwords.GREEDY, Hotwords.decodingFor(ModelKind.PARAKEET_TDT, "anything else"))
    }

    @Test fun hotwordsOnlyWhereTheyCantCrash() {
        for (kind in ModelKind.entries) {
            for (decoding in listOf(Hotwords.BEAM, Hotwords.GREEDY)) {
                for (vocab in listOf(true, false)) {
                    val yes = Hotwords.support(kind, Hotwords.decodingFor(kind, decoding), vocab) == Hotwords.Support.Yes
                    val expected = (kind == ModelKind.ZIPFORMER_STREAMING || kind == ModelKind.PARAKEET_TDT) &&
                        decoding == Hotwords.BEAM && vocab
                    assertEquals("$kind $decoding vocab=$vocab", expected, yes)
                }
            }
        }
    }

    @Test fun vocabIsConfiguredOnlyForBeamSearch() {
        assertTrue(Hotwords.vocabConfigured(Hotwords.BEAM, vocabFileExists = true))
        assertFalse(Hotwords.vocabConfigured(Hotwords.GREEDY, vocabFileExists = true))
        assertFalse(Hotwords.vocabConfigured(Hotwords.BEAM, vocabFileExists = false))
        // A recogniser built greedy never gets hotwords, whatever the settings say later.
        val phrases = Hotwords.parse("HERMES", upperCase = true).phrases
        val built = Hotwords.vocabConfigured(Hotwords.GREEDY, vocabFileExists = true)
        assertEquals("", Hotwords.safeArgument(phrases, ModelKind.PARAKEET_TDT, Hotwords.GREEDY, built))
    }

    @Test fun unsafeCombinationsGetAnEmptyArgument() {
        val phrases = Hotwords.parse("HERMES", upperCase = true).phrases
        assertEquals("", Hotwords.safeArgument(phrases, ModelKind.NEMOTRON_STREAMING, Hotwords.BEAM, true))
        assertEquals("", Hotwords.safeArgument(phrases, ModelKind.ZIPFORMER_STREAMING, Hotwords.GREEDY, true))
        assertEquals("", Hotwords.safeArgument(phrases, ModelKind.ZIPFORMER_STREAMING, Hotwords.BEAM, false))
        assertEquals("", Hotwords.safeArgument(emptyList(), ModelKind.ZIPFORMER_STREAMING, Hotwords.BEAM, true))
        assertEquals("HERMES", Hotwords.safeArgument(phrases, ModelKind.ZIPFORMER_STREAMING, Hotwords.BEAM, true))
    }

    @Test fun encodableNeedsEveryWordToSplitIntoVocabPieces() {
        val vocab = setOf("▁HER", "MES", "▁L", "I", "V", "E", "▁")
        assertTrue(Hotwords.encodable(Hotwords.Phrase(listOf("HERMES", "LIVE"), null), vocab))
        assertFalse(Hotwords.encodable(Hotwords.Phrase(listOf("HERMES", "LOVE"), null), vocab))
    }
}
