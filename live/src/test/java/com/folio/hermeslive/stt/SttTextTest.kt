package com.folio.hermeslive.stt

import com.folio.hermes.cascade.TranscriptPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text normalisation, WER, pass merge and hotwords. Names here are made up. */
class SttTextTest {
    @Test fun spellsNumbersTheBritishWay() {
        assertEquals("ninety", TextNorm.spell(90))
        assertEquals("one hundred and twelve", TextNorm.spell(112))
        assertEquals("four thousand two hundred and twelve", TextNorm.spell(4212))
        assertEquals("forty thousand and seven", TextNorm.spell(40007))
        assertEquals("two million", TextNorm.spell(2_000_000))
    }

    @Test fun normalisesDigitsPercentCurrencyHyphensAndCase() {
        assertEquals(listOf("the", "last", "ninety", "days"), TextNorm.normalize("The last 90 days."))
        assertEquals(listOf("eight", "percent", "or", "so"), TextNorm.normalize("8% or so,"))
        assertEquals(listOf("four", "thousand", "two", "hundred", "and", "twelve", "pounds"), TextNorm.normalize("£4,212"))
        assertEquals(listOf("ta", "ra"), TextNorm.normalize("Ta-ra!"))
        assertEquals(listOf("this", "week's", "sales"), TextNorm.normalize("THIS WEEK'S SALES"))
        assertEquals(listOf("okay"), TextNorm.normalize("OK"))
        assertEquals(listOf("two", "point", "five"), TextNorm.normalize("2.5"))
    }

    @Test fun werCountsSubstitutionsInsertionsAndDeletions() {
        assertEquals(0.0, Align.wer("Chase the Zorbel order.", "CHASE THE ZORBEL ORDER"), 1e-9)
        val e = Align.errors("chase the zorbel order", "chase a zorbel order now")
        assertEquals(1, e.sub)
        assertEquals(1, e.ins)
        assertEquals(0, e.del)
        assertEquals(0.5, e.wer, 1e-9)
        assertEquals(0.2, Align.wer("pull the last ninety days", "pull last 90 days"), 1e-9)
    }

    @Test fun wrongWordsLabelsEachHypothesisWord() {
        val wrong = Align.wrongWords("email the quorvex rep", listOf("Email", "the", "corvex", "rep", "please"))
        assertEquals(listOf(false, false, true, false, true), wrong)
    }

    @Test fun mergeKeepsAgreedWordsConfidenceAndFlagsDisagreements() {
        val fast = listOf(FastWord("chase", 0.95), FastWord("the", 0.9), FastWord("zorba", 0.4), FastWord("order", 0.97))
        val t = TranscriptMerge.merge(fast, "Chase the Zorbel order.", uncertainBelow = 0.5)
        assertEquals(TranscriptPass.Accurate, t.pass)
        assertEquals("Chase the Zorbel order.", t.text)
        assertEquals(listOf("Chase", "the", "Zorbel", "order."), t.words.map { it.text })
        assertEquals(listOf(false, false, true, false), t.words.map { it.uncertain })
        assertEquals(listOf("zorba"), t.words[2].alternatives)
        assertEquals(null, t.words[2].confidence)
        assertEquals(0.95, t.words[0].confidence!!, 1e-9)
    }

    @Test fun agreedWordsUseTheirOwnThreshold() {
        val fast = listOf(FastWord("ninety", 0.3), FastWord("days", 0.9))
        val strict = TranscriptMerge.merge(fast, "90 days", uncertainBelow = 0.5)
        assertEquals(listOf(true, false), strict.words.map { it.uncertain })
        assertTrue(strict.words[0].alternatives.isEmpty())
        // The calibrated default: when the passes agree, the streaming confidence is not used.
        val calibrated = TranscriptMerge.merge(fast, "90 days", uncertainBelow = 0.5, agreedBelow = 0.0)
        assertEquals(listOf(false, false), calibrated.words.map { it.uncertain })
        assertEquals(0.3, calibrated.words[0].confidence!!, 1e-9)
    }

    @Test fun noAccurateTextFallsBackToFast() {
        val fast = listOf(FastWord("get", 0.9), FastWord("me", 0.2))
        val t = TranscriptMerge.merge(fast, null, 0.5)
        assertEquals(TranscriptPass.Fast, t.pass)
        assertEquals("Get me", t.text)
        assertEquals(listOf(false, true), t.words.map { it.uncertain })
    }

    @Test fun tokensGroupIntoWordsWithTheirLeastConfidentToken() {
        val words = TokenConfidence.words(listOf("▁ZOR", "BEL", "▁ORDER"), floatArrayOf(-0.1f, -2f, -0.01f))
        assertEquals(listOf("ZORBEL", "ORDER"), words.map { it.text })
        assertEquals(kotlin.math.exp(-2.0), words[0].confidence!!, 1e-6)
        assertEquals("I'm", Casing.fromUpper("I'M"))
        assertEquals("here", Casing.fromUpper("HERE"))
        assertEquals("I", Casing.fromUpper("I"))
    }

    @Test fun hotwordsParseScoreAndDeduplicate() {
        val p = Hotwords.parse(listOf("Zorbel :3", "  quorvex optics ", "zorbel", "", "bad :x", "o'brien-smith"), upperCase = false)
        assertEquals(listOf(listOf("Zorbel"), listOf("quorvex", "optics"), listOf("o'briensmith")), p.map { it.words })
        assertEquals("Zorbel :3.0\nquorvex optics :2.0\no'briensmith :2.0", Hotwords.toSherpa(p))
        assertEquals(listOf("ZORBEL"), Hotwords.parse(listOf("zorbel"), upperCase = true).single().words)
    }

    @Test fun hotwordsOnlyWhereSherpaTakesThemSafely() {
        val list = listOf("Zorbel")
        assertEquals("ZORBEL :2.0", Hotwords.argument(list, SttCatalog.zipformer, Hotwords.BEAM, vocabPresent = true))
        assertEquals("", Hotwords.argument(list, SttCatalog.zipformer, Hotwords.GREEDY, vocabPresent = true))
        assertEquals("", Hotwords.argument(list, SttCatalog.zipformer, Hotwords.BEAM, vocabPresent = false))
        // Nemotron would exit the process: never.
        assertEquals("", Hotwords.argument(list, SttCatalog.nemotron, Hotwords.BEAM, vocabPresent = true))
        assertEquals(Hotwords.GREEDY, Hotwords.decodingFor(ModelKind.NEMOTRON_STREAMING, Hotwords.BEAM))
        assertEquals("Zorbel :2.0", Hotwords.argument(list, SttCatalog.parakeet06b, Hotwords.BEAM, vocabPresent = true))
        assertEquals("", Hotwords.argument(emptyList(), SttCatalog.parakeet110m, Hotwords.BEAM, vocabPresent = true))
    }

    @Test fun catalogPinsAreWellFormedAndKeepNoTestAudio() {
        for (m in SttCatalog.all) {
            assertTrue(m.id, Regex("^[0-9a-f]{64}$").matches(m.sha256))
            assertTrue(m.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"))
            assertFalse(m.keep.any { it.endsWith(".wav") })
        }
        assertEquals(listOf(SttCatalog.zipformer, SttCatalog.parakeet110m), ModelSet.STANDARD.models)
        assertEquals(SttCatalog.VAD_SHA256.length, 64)
    }

    @Test fun archivePathsRejectEscapes() {
        assertEquals("tokens.txt", ArchivePaths.select("./sherpa-x/tokens.txt", listOf("tokens.txt")))
        assertEquals(null, ArchivePaths.select("sherpa-x/../tokens.txt", listOf("../tokens.txt")))
        assertEquals(null, ArchivePaths.select("sherpa-x/test_wavs/0.wav", listOf("tokens.txt")))
    }
}
