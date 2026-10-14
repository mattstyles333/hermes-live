package com.folio.hermeslive.hooks

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [WavPcm] reads the corpus and TTS WAVs the emulator tests feed into the pipeline (moved from the STT Lab's MathsTest). */
class WavPcmTest {
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
