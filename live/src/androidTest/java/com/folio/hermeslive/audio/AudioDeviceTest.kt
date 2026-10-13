package com.folio.hermeslive.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.folio.hermes.cascade.AudioClip
import com.folio.hermes.openrouter.SpeechFormat
import com.folio.hermeslive.stt.Device
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** AudioTrackOut and the mic on the emulator. What only the S23 can prove is in the report. */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private fun tone(ms: Int, rate: Int = 24_000, amplitude: Double = 0.3): AudioClip {
        val n = rate * ms / 1000
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (sin(2 * PI * 440 * i / rate) * amplitude * 32767).toInt()
            bytes[2 * i] = (s and 0xff).toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        return AudioClip(bytes, SpeechFormat.Pcm(rate))
    }

    @Test fun playsAWholeClipAndReportsItsLength() = runBlocking {
        val levels = ArrayList<Float>()
        val out = AudioTrackOut(onLevel = { synchronized(levels) { levels += it } })
        val played = out.play(tone(600))
        assertTrue("played $played", played in 500..700)
        assertFalse(out.playing)
        assertTrue(synchronized(levels) { levels.any { it > 0.5f } })
        out.release()
    }

    @Test fun stopIsInstantAndReturnsPlayedMs() = runBlocking {
        val out = AudioTrackOut()
        val run = async { out.play(tone(3_000)) }
        delay(400)
        assertTrue(out.playing)
        assertTrue("level at head ${out.levelDb()}", out.levelDb() > -20)
        val t0 = System.nanoTime()
        out.stop()
        val played = run.await()
        val stopMs = (System.nanoTime() - t0) / 1e6
        assertTrue("played $played", played in 150..600)
        assertTrue("stop took $stopMs ms", stopMs < 100)
        assertFalse(out.playing)
        assertEquals(played, out.playedMs())
        Device.results("audio-out.json").writeText(JSONObject().put("stop_to_return_ms", stopMs).put("played_ms_at_400", played).toString(2))
        out.release()
    }

    @Test fun followsTheClipRate() = runBlocking {
        val out = AudioTrackOut()
        val played = out.play(tone(500, rate = 16_000))
        assertTrue("played $played", played in 400..600)
        out.release()
    }

    @Test fun micOpensOnVoiceCommunicationAndReportsEffects() {
        val source = MicAudioSource()
        val result = runCatching {
            source.open()
            val buf = ShortArray(512)
            var got = 0
            val t0 = System.nanoTime()
            while (got < 16_000 && System.nanoTime() - t0 < 3_000_000_000L) got += source.read(buf).coerceAtLeast(0)
            got
        }
        val effects = source.effects
        source.close()
        Device.results("mic.json").writeText(
            JSONObject()
                .put("opened", result.isSuccess)
                .put("samples_in_3s", result.getOrNull() ?: -1)
                .put("source", effects?.source?.name)
                .put("aec_available", effects?.aecAvailable)
                .put("aec_on", effects?.aecOn)
                .put("ns_available", effects?.nsAvailable)
                .put("ns_on", effects?.nsOn)
                .toString(2),
        )
        // The headless emulator may have no real mic: report, don't fail, unless it opened and gave nothing.
        if (result.isSuccess) assertTrue("no samples", result.getOrThrow() > 0)
        assertEquals(CaptureSource.VOICE_COMMUNICATION, source.source)
    }
}
