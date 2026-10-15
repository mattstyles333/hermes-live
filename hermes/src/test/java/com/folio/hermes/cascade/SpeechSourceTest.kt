package com.folio.hermes.cascade

import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.openrouter.SpeechFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pluggable voice behind [BillVoice] and its cache keys (Hermes voice slice). */
class SpeechSourceTest {
    private val format = SpeechFormat.Pcm()

    private class Fixed(var id: VoiceIdentity?, val fail: Boolean = false, val learn: VoiceIdentity? = null) : SpeechSource {
        var calls = 0
        var prepared = 0
        override fun identity() = id
        override suspend fun prepare() {
            prepared++
            if (learn != null) id = learn
        }
        override suspend fun synth(text: String): OpenRouterResult<SpokenClip> {
            calls++
            return if (fail) OpenRouterResult.Failed(FailureKind.Offline)
            else OpenRouterResult.Ok(SpokenClip(AudioClip(ByteArray(48), SpeechFormat.Pcm()), id!!))
        }
    }

    @Test fun openRouterKeysAreTodaysKeys() {
        val cache = TtsCache(MemoryClipStore())
        val src = Fixed(VoiceIdentity("elevenlabs/eleven-v4-turbo", "bill"))
        val voice = BillVoice(cache, { src })
        assertEquals(TtsCache.key("elevenlabs/eleven-v4-turbo", "bill", format, "Got it."), voice.key("Got it."))
    }

    @Test fun aHermesIdentityIsAnotherKey() {
        val cache = TtsCache(MemoryClipStore())
        val openRouter = BillVoice(cache, { Fixed(VoiceIdentity("elevenlabs/eleven-v4-turbo", "bill")) })
        val hermes = BillVoice(cache, { Fixed(VoiceIdentity("hermes:elevenlabs/eleven_flash_v2_5", "v-bill")) })
        val relay = BillVoice(cache, { Fixed(VoiceIdentity("hermes:relay/-/-", "configured")) })
        assertEquals(3, setOf(openRouter.key("Got it."), hermes.key("Got it."), relay.key("Got it.")).size)
    }

    @Test fun aCachedLineMakesNoRequest() = runBlocking {
        val cache = TtsCache(MemoryClipStore())
        val src = Fixed(VoiceIdentity("hermes:openai/tts-1", "alloy"))
        val voice = BillVoice(cache, { src })
        voice.clip("Got it.")
        voice.clip("Got it.")
        assertEquals(1, src.calls)
        assertTrue(voice.isCached("Got it."))
    }

    @Test fun aFallbackLineIsCachedUnderTheFallbacksIdentityNeverThePrimarys() = runBlocking {
        val cache = TtsCache(MemoryClipStore())
        val primary = Fixed(VoiceIdentity("hermes:elevenlabs/x", "v"), fail = true)
        val fallback = Fixed(VoiceIdentity("elevenlabs/eleven-v4-turbo", "bill"))
        val voice = BillVoice(cache, { primary }, fallback = { fallback })
        assertTrue(voice.clip("Got it.") is OpenRouterResult.Ok)
        assertFalse(voice.isCached("Got it."))
        assertTrue(cache.contains(TtsCache.key("elevenlabs/eleven-v4-turbo", "bill", format, "Got it.")))
        // Next time the primary is asked again (it may be back); the fallback's line comes from its cache.
        voice.clip("Got it.")
        assertEquals(2, primary.calls)
        assertEquals(1, fallback.calls)
    }

    @Test fun noFallbackPassesTheFailureOn() = runBlocking {
        val voice = BillVoice(TtsCache(MemoryClipStore()), { Fixed(VoiceIdentity("a", "b"), fail = true) })
        assertEquals(OpenRouterResult.Failed(FailureKind.Offline), voice.clip("x"))
    }

    @Test fun anUnknownIdentityIsLearnedBeforeTheCacheIsRead() = runBlocking {
        val cache = TtsCache(MemoryClipStore())
        val id = VoiceIdentity("hermes:openai/tts-1", "alloy")
        cache.put(TtsCache.key(id.model, id.voice, format, "Got it."), AudioClip(ByteArray(48), format))
        val src = Fixed(null, learn = id)
        val voice = BillVoice(cache, { src })
        assertFalse(voice.isCached("Got it."))
        assertTrue(voice.clip("Got it.") is OpenRouterResult.Ok)
        assertEquals(1, src.prepared)
        assertEquals(0, src.calls)
        assertNotEquals(voice.key("Other."), voice.key("Got it."))
    }
}
