package com.folio.hermeslive.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IntegrityTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun sha256KnownVectors() {
        assertEquals(abc, Sha256.of("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.of(ByteArray(0)))
        // Streaming over more than one buffer gives the same digest as hashing at once.
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(Sha256.of(big), Sha256.of(ByteArrayInputStream(big)))
    }

    @Test fun pinMatchesOnlyTheSameWellFormedDigest() {
        assertTrue(Sha256.matches(abc, abc))
        assertTrue(Sha256.matches(abc.uppercase(), abc))
        assertFalse(Sha256.matches(abc, abc.replaceFirst('b', 'c')))
        assertFalse(Sha256.matches("", ""))
        assertFalse(Sha256.matches(abc.dropLast(1), abc.dropLast(1)))
        assertFalse(Sha256.matches("z".repeat(64), "z".repeat(64)))
    }

    @Test fun archiveEntriesLoseTheirTopFolder() {
        assertEquals("test_wavs/0.wav", ArchivePaths.relative("./sherpa-x/test_wavs/0.wav"))
        assertEquals("tokens.txt", ArchivePaths.relative("sherpa-x/tokens.txt"))
        assertNull(ArchivePaths.relative("sherpa-x/"))
        assertNull(ArchivePaths.relative("./sherpa-x"))
    }

    @Test fun onlyKeptSafePathsAreExtracted() {
        val keep = listOf("tokens.txt", "test_wavs/0.wav")
        assertEquals("tokens.txt", ArchivePaths.select("m/tokens.txt", keep))
        assertNull(ArchivePaths.select("m/encoder.onnx", keep))
        assertNull(ArchivePaths.select("m/../../tokens.txt", keep + "../../tokens.txt"))
        assertFalse(ArchivePaths.isSafe("a/../b"))
        assertFalse(ArchivePaths.isSafe("/etc/passwd"))
        assertFalse(ArchivePaths.isSafe("a//b"))
        assertTrue(ArchivePaths.isSafe("test_wavs/0.wav"))
    }

    @Test fun catalogPinsAndUrlsAreOfficial() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (m in ModelCatalog.all) {
            assertTrue(m.id, Sha256.isPin(m.sha256))
            assertTrue(m.id, m.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"))
            assertTrue(m.id, m.bytes > 0)
            assertTrue(m.id, m.keep.all(ArchivePaths::isSafe))
            val needed = listOf(m.encoder, m.decoder, m.joiner, m.tokens, m.bpeModel, m.transcripts).filter { it.isNotEmpty() } + m.testWavs
            assertTrue(m.id, m.keep.containsAll(needed))
            if (m.role != ModelRole.VAD) assertTrue(m.id, m.testWavs.isNotEmpty())
            if (m.probeHotword.isNotEmpty()) {
                assertTrue(m.id, m.probeWav in m.testWavs)
                assertTrue(m.id, Hotwords.parse(m.probeHotword, m.upperCaseHotwords).errors.isEmpty())
                assertTrue(m.id, m.vocabSource != VocabSource.NONE)
            }
        }
        assertFalse(ModelCatalog.sileroVad.isArchive)
        assertTrue(ModelCatalog.zipformer.isArchive)
    }

    @Test fun hotwordReadyModelsHaveAVocabSource() {
        assertEquals(VocabSource.SENTENCEPIECE_MODEL, ModelCatalog.zipformer.vocabSource)
        assertEquals(VocabSource.TOKENS_EQUAL_SCORES, ModelCatalog.parakeet110m.vocabSource)
        assertEquals(VocabSource.TOKENS_EQUAL_SCORES, ModelCatalog.parakeet06b.vocabSource)
        assertEquals(VocabSource.NONE, ModelCatalog.nemotron.vocabSource)
        assertEquals("", ModelCatalog.nemotron.vocabFile)
    }

    @Test fun defaultsPickTheSmallPair() {
        val s = LabSettings()
        assertEquals(CaptureSource.VOICE_COMMUNICATION, s.source)
        assertEquals(ModelCatalog.zipformer, s.streaming)
        assertEquals(ModelCatalog.parakeet110m, s.offline)
        assertNull(s.copy(offlineId = LabSettings.NONE).offline)
        assertEquals(7, CaptureSource.VOICE_COMMUNICATION.androidValue)
        assertEquals(6, CaptureSource.VOICE_RECOGNITION.androidValue)
        assertEquals(1, CaptureSource.MIC.androidValue)
    }
}
