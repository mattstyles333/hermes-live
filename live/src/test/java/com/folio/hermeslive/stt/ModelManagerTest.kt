package com.folio.hermeslive.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var archive: ByteArray
    private lateinit var model: SttModel

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        archive = tarBz2(
            "pkg/encoder.int8.onnx" to "enc",
            "pkg/decoder.int8.onnx" to "dec",
            "pkg/joiner.int8.onnx" to "join",
            "pkg/tokens.txt" to "<blk> 0\n▁zor 1\nbel 2\n",
            "pkg/test_wavs/0.wav" to "not kept",
            "pkg/../escape.txt" to "never",
        )
        model = SttCatalog.parakeet110m.copy(
            id = "fake-parakeet",
            fileName = "fake.tar.bz2",
            sha256 = Sha256.of(ByteArrayInputStream(archive)),
            bytes = archive.size.toLong(),
        )
    }

    @After fun tearDown() = server.close()

    private fun manager(root: File = tmp.root) =
        ModelManager(root, server.url("/").toString(), CoroutineScope(Dispatchers.Unconfined)) { set ->
            if (set == ModelSet.STANDARD) listOf(model) else set.models
        }

    @Test fun nothingIsFetchedUntilDownloadIsCalled() {
        val m = manager()
        assertEquals(ModelStatus.Missing, m.status.value[ModelSet.STANDARD])
        assertFalse(m.isReady(ModelSet.STANDARD))
        assertEquals(0, server.requestCount)
    }

    @Test fun downloadsChecksUnpacksAndWritesTheVocab() {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(archive)).build())
        val m = manager()
        m.download(ModelSet.STANDARD)
        assertTrue(m.status.value[ModelSet.STANDARD] is ModelStatus.Ready)
        assertTrue(m.isReady(ModelSet.STANDARD))
        val dir = m.dir(model)
        assertEquals(setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "bpe.vocab", ".ready"), dir.list()!!.toSet())
        assertEquals("<blk>\t-1.0\n▁zor\t-1.0\nbel\t-1.0\n", File(dir, "bpe.vocab").readText())
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(File(tmp.root, "download/fake.tar.bz2.part").exists())
        // Ready already: a second call fetches nothing.
        m.download(ModelSet.STANDARD)
        assertEquals(1, server.requestCount)
        // A fresh manager sees it installed.
        assertTrue(manager().status.value[ModelSet.STANDARD] is ModelStatus.Ready)
    }

    @Test fun aWrongChecksumInstallsNothing() {
        val bad = archive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bad)).build())
        val m = manager()
        m.download(ModelSet.STANDARD)
        assertEquals(ModelStatus.Failed(ModelStatus.Reason.CHECKSUM), m.status.value[ModelSet.STANDARD])
        assertFalse(m.dir(model).exists())
        assertFalse(File(tmp.root, "download/fake.tar.bz2.part").exists())
    }

    @Test fun resumesAPartialDownloadWithARangeRequest() {
        val half = archive.size / 2
        File(tmp.root, "download").mkdirs()
        File(tmp.root, "download/fake.tar.bz2.part").writeBytes(archive.copyOfRange(0, half))
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes $half-${archive.size - 1}/${archive.size}")
                .body(Buffer().write(archive.copyOfRange(half, archive.size)))
                .build(),
        )
        val m = manager()
        m.download(ModelSet.STANDARD)
        assertEquals("bytes=$half-", server.takeRequest().headers["Range"])
        assertTrue(m.isReady(ModelSet.STANDARD))
    }

    @Test fun aServerThatIgnoresRangeRestartsFromZero() {
        File(tmp.root, "download").mkdirs()
        File(tmp.root, "download/fake.tar.bz2.part").writeBytes(ByteArray(10) { 7 })
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(archive)).build())
        val m = manager()
        m.download(ModelSet.STANDARD)
        assertTrue(m.isReady(ModelSet.STANDARD))
    }

    @Test fun httpErrorsAreReportedAsCodes() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val m = manager()
        m.download(ModelSet.STANDARD)
        assertEquals(ModelStatus.Failed(ModelStatus.Reason.HTTP), m.status.value[ModelSet.STANDARD])
    }

    @Test fun deleteRemovesFilesAndPartials() {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(archive)).build())
        val m = manager()
        m.download(ModelSet.STANDARD)
        File(tmp.root, "download/other.part").writeText("x")
        m.delete(ModelSet.STANDARD)
        assertFalse(m.dir(model).exists())
        assertEquals(ModelStatus.Missing, m.status.value[ModelSet.STANDARD])
    }

    @Test fun installsFromALocalArchiveWithTheSameChecks() {
        val file = File(tmp.root, "side.tar.bz2").apply { writeBytes(archive) }
        val m = manager()
        m.installFrom(model, file)
        assertTrue(m.isReady(ModelSet.STANDARD))
        assertEquals(0, server.requestCount)
        val bad = File(tmp.root, "bad.tar.bz2").apply { writeBytes(archive.copyOf(archive.size - 1)) }
        val other = manager(tmp.newFolder("other"))
        assertTrue(runCatching { other.installFrom(model, bad) }.exceptionOrNull() is ModelManager.ChecksumException)
    }

    @Test fun fetchesOneModelForAPerModelList() = kotlinx.coroutines.runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(archive)).build())
        val m = manager()
        assertTrue(m.fetch(model))
        assertTrue(m.models.value[model.id] is ModelStatus.Ready)
        assertTrue(m.status.value[ModelSet.STANDARD] is ModelStatus.Ready)
        // Already there: no request.
        assertTrue(m.fetch(model))
        assertEquals(1, server.requestCount)
    }

    @Test fun aFailedFetchReportsTheModelAndReturnsFalse() = kotlinx.coroutines.runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        val m = manager()
        assertFalse(m.fetch(model))
        assertEquals(ModelStatus.Failed(ModelStatus.Reason.HTTP), m.models.value[model.id])
    }

    @Test fun progressReportsBytes() {
        val s = ModelStatus.Downloading(25, 100)
        assertEquals(0.25f, s.fraction)
        assertEquals(0f, ModelStatus.Downloading(1, 0).fraction)
    }

    private fun tarBz2(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, text) in entries) {
                val bytes = text.toByteArray()
                val e = TarArchiveEntry(name, true)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }
}
