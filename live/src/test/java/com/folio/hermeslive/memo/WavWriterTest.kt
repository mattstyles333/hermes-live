package com.folio.hermeslive.memo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The streamed WAV's header and its repair after a kill. */
class WavWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun header(file: File): ByteBuffer = ByteBuffer.wrap(file.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)

    private fun assertHeader(file: File, dataBytes: Int, rate: Int = 16_000) {
        val h = header(file)
        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + dataBytes, h.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(16, h.getInt(16))
        assertEquals(1, h.getShort(20).toInt()) // PCM
        assertEquals(1, h.getShort(22).toInt()) // mono
        assertEquals(rate, h.getInt(24))
        assertEquals(rate * 2, h.getInt(28)) // byte rate
        assertEquals(2, h.getShort(32).toInt()) // block align
        assertEquals(16, h.getShort(34).toInt()) // bits
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(dataBytes, h.getInt(40))
        assertEquals(44L + dataBytes, file.length())
    }

    @Test fun theHeaderIsPatchedOnCloseAndTheSamplesAreLittleEndian() {
        val f = File(tmp.root, "a/chunk-000.wav")
        val w = WavWriter.open(f, 16_000)
        w.write(shortArrayOf(9, 1, -2, 0x1234, 7), 1, 3)
        assertEquals(3L, w.samples)
        w.close()
        assertHeader(f, 6)
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12), f.readBytes().copyOfRange(44, 50))
    }

    @Test fun aWriteBiggerThanTheScratchBufferIsWhole() {
        val f = File(tmp.root, "big.wav")
        val w = WavWriter.open(f, 16_000)
        w.write(ShortArray(20_000) { it.toShort() }, 0, 20_000)
        w.close()
        assertHeader(f, 40_000)
    }

    @Test fun repairFixesSizesAndDropsAHalfSample() {
        val f = File(tmp.root, "torn.wav")
        val w = WavWriter.open(f, 16_000)
        w.write(ShortArray(100), 0, 100)
        // Killed: never closed, the header still says 0, plus half a sample.
        f.appendBytes(byteArrayOf(5))
        assertEquals(100L, WavWriter.repair(f, 16_000))
        assertHeader(f, 200)
    }

    @Test fun repairOfAMissingOrHeaderOnlyFile() {
        val missing = File(tmp.root, "none.wav")
        assertEquals(0L, WavWriter.repair(missing, 16_000))
        assertFalse("a missing file stays missing", missing.exists())
        val short = File(tmp.root, "short.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(0L, WavWriter.repair(short, 16_000))
        assertHeader(short, 0)
        val headerOnly = File(tmp.root, "header.wav")
        WavWriter.open(headerOnly, 16_000).close()
        assertEquals(0L, WavWriter.repair(headerOnly, 16_000))
        assertHeader(headerOnly, 0)
    }
}
