package com.folio.hermeslive.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class MathsTest {
    // --- word diff -----------------------------------------------------------------------

    @Test fun sameWordsIgnoringCaseAndPunctuationDontDiffer() {
        val r = WordDiff.diff("SEND IT TO SAM", "Send it to Sam.")
        assertEquals(0, r.leftDiffering)
        assertEquals(0, r.rightDiffering)
    }

    @Test fun substitutionIsHighlightedOnBothSides() {
        val r = WordDiff.diff("CALL THE PRINTER TOMORROW", "Call the painter tomorrow.")
        assertEquals(listOf(false, false, true, false), r.left.map { it.differs })
        assertEquals(listOf(false, false, true, false), r.right.map { it.differs })
    }

    @Test fun insertionsAndDeletions() {
        val r = WordDiff.diff("BOOK A TABLE", "Book a big table for two")
        assertEquals(0, r.leftDiffering)
        assertEquals(listOf("big", "for", "two"), r.right.filter { it.differs }.map { it.text })
    }

    @Test fun punctuationOnlyWordsNeverDiffer() {
        val r = WordDiff.diff("YES", "Yes - yes")
        assertEquals(listOf(false, false, true), r.right.map { it.differs })
    }

    @Test fun wordErrorRate() {
        assertEquals(0.0, WordDiff.wer("THE CAT SAT", "the cat sat"), 1e-9)
        assertEquals(1.0 / 3, WordDiff.wer("THE CAT SAT", "the bat sat"), 1e-9)
        assertEquals(2.0 / 3, WordDiff.wer("THE CAT SAT", "cat"), 1e-9)
        assertEquals(1.0, WordDiff.wer("", "noise"), 1e-9)
    }

    // --- levels ----------------------------------------------------------------------------

    @Test fun dbfsOfKnownSignals() {
        assertEquals(0.0, Dbfs.of(FloatArray(1600) { if (it % 2 == 0) 1f else -1f }), 1e-9)
        assertEquals(-6.0206, Dbfs.of(FloatArray(1600) { if (it % 2 == 0) 0.5f else -0.5f }), 1e-3)
        val sine = FloatArray(16000) { sin(2 * PI * 1000 * it / 16000).toFloat() }
        assertEquals(-3.0103, Dbfs.of(sine), 1e-3)
        assertEquals(Dbfs.FLOOR, Dbfs.of(FloatArray(512)), 0.0)
        assertEquals(Dbfs.FLOOR, Dbfs.of(FloatArray(0)), 0.0)
    }

    @Test fun pcm16Scaling() {
        val f = Dbfs.pcm16ToFloat(shortArrayOf(Short.MIN_VALUE, 0, 16384), 3)
        assertEquals(-1f, f[0], 0f)
        assertEquals(0f, f[1], 0f)
        assertEquals(0.5f, f[2], 0f)
    }

    @Test fun frameMeterCarriesAcrossChunks() {
        val meter = FrameMeter(160)
        repeat(5) { meter.accept(FloatArray(100) { 0.5f }) }
        assertEquals(3, meter.frames)
        assertEquals(-6.0206, meter.toArray()[0], 1e-3)
    }

    @Test fun ysProbsAreLogProbabilities() {
        assertEquals(1f, TokenConfidence.probability(0f), 1e-6f)
        assertEquals(0.5f, TokenConfidence.probability(kotlin.math.ln(0.5f)), 1e-6f)
        assertEquals(0.7f, TokenConfidence.probability(0.7f), 1e-6f)
        assertEquals(0f, TokenConfidence.probability(Float.NaN), 0f)
    }

    @Test fun tokensGroupIntoWordsWithTheLeastConfidentToken() {
        val ln = { p: Float -> kotlin.math.ln(p) }
        val words = TokenConfidence.words(
            listOf("▁HER", "MES", "▁LIVE"),
            floatArrayOf(ln(0.9f), ln(0.4f), ln(0.95f)),
        )
        assertEquals(listOf("HERMES", "LIVE"), words.map { it.word })
        assertEquals(0.4f, words[0].confidence!!, 1e-5f)
        assertEquals(0.95f, words[1].confidence!!, 1e-5f)
        assertNull(TokenConfidence.words(listOf(" hi"), FloatArray(0)).single().confidence)
    }

    @Test fun thresholdSeparatesDisagreeingWords() {
        val s = ConfidenceStats()
        listOf(0.95f, 0.97f, 0.9f, 0.99f, 0.85f).forEach { s.add(it, agreed = true) }
        listOf(0.3f, 0.5f, 0.6f).forEach { s.add(it, agreed = false) }
        val t = s.suggestedThreshold()!!
        assertTrue(t > 0.6 && t <= 0.851)
        assertEquals(0.95, s.percentile(true, 50.0)!!, 1e-6)
        assertNull(ConfidenceStats().suggestedThreshold())
    }

    // --- echo and cut-in -------------------------------------------------------------------

    @Test fun echoWordsCountsPassageWords() {
        val (all, inPassage) = EchoMaths.echoWords(listOf("THE", "GARDEN", "PROJECT", "BANANA"), "the garden project.")
        assertEquals(4, all)
        assertEquals(3, inPassage)
        assertEquals(0.25, EchoMaths.ratio(1, 4), 1e-9)
        assertEquals(0.0, EchoMaths.ratio(1, 0), 1e-9)
    }

    @Test fun onsetIsTheStartOfTheLoudRunBeforeTheVadFired() {
        // Echo residue at -60 dB, user speech from frame 30 (with a short dip at 34), VAD fires at frame 55.
        val frames = DoubleArray(80) { -60.0 }
        for (i in 30 until 80) frames[i] = -30.0
        frames[34] = -58.0
        val baseline = EchoMaths.median(frames, 0, 25)!!
        assertEquals(-60.0, baseline, 0.0)
        assertEquals(30, EchoMaths.onsetFrame(frames, fireFrame = 55, earliest = 10, baselineDb = baseline))
        // 25 frames of 160 samples = 4000 samples = 250 ms at 16 kHz.
        assertEquals(250.0, EchoMaths.latencyMs(30, 160, 55L * 160, 16_000), 1e-9)
        assertNull(EchoMaths.onsetFrame(DoubleArray(40) { -60.0 }, 30, 0, -60.0))
        assertEquals(40, EchoMaths.onsetFrame(frames, fireFrame = 55, earliest = 40, baselineDb = baseline))
    }

    private fun wav(rate: Int, channels: Int, samples: ShortArray, declaredData: Int = samples.size * 2): ByteArray {
        val buf = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        buf.put("data".toByteArray()).putInt(declaredData)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }

    @Test fun parsesWaveFromTts() {
        val pcm = WavPcm.parse(wav(22050, 1, shortArrayOf(1, -2, 3)))
        assertEquals(22050, pcm.sampleRate)
        assertEquals(listOf<Short>(1, -2, 3), pcm.samples.toList())
        // Streaming writers leave the data size unset.
        assertEquals(3, WavPcm.parse(wav(16000, 1, shortArrayOf(1, 2, 3), declaredData = -1)).samples.size)
        assertEquals(3, WavPcm.parse(wav(16000, 1, shortArrayOf(1, 2, 3), declaredData = 0)).samples.size)
        val stereo = WavPcm.parse(wav(16000, 2, shortArrayOf(10, 20, -10, -30)))
        assertEquals(listOf<Short>(15, -20), WavPcm.mono(stereo).toList())
        assertFalse(runCatching { WavPcm.parse("nope".toByteArray()) }.isSuccess)
    }
}
