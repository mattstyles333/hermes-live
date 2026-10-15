package com.folio.hermeslive.data

import com.folio.hermes.live.HermesVoiceConfig
import com.folio.hermes.live.VoiceSide
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where Custom's voice and cloud speech go (Hermes voice slice): the pure choice. */
class VoiceRoutingTest {
    private fun direct(wire: String) = VoiceSide.Direct(wire, "p", "https://api.example.com/v1", "k", "m")
    private val hermesDirect = HermesVoiceConfig(direct("openai-multipart"), direct("elevenlabs-tts"), 0)
    private val hermesRelay = HermesVoiceConfig(VoiceSide.Relay("local provider"), VoiceSide.Relay("edge"), 0)
    private val sttOff = HermesVoiceConfig(VoiceSide.Relay("stt disabled"), VoiceSide.Relay("edge"), 0)

    @Test fun ttsPrefersHermesThenOpenRouterThenNothing() {
        assertEquals(VoiceRoute.HermesDirect, VoiceRouting.tts(SourceChoice.Auto, hermesDirect, openRouterUsable = true))
        assertEquals(VoiceRoute.HermesRelay, VoiceRouting.tts(SourceChoice.Hermes, hermesRelay, openRouterUsable = false))
        assertEquals("signed out or unreachable", VoiceRoute.OpenRouter, VoiceRouting.tts(SourceChoice.Auto, null, openRouterUsable = true))
        assertEquals("OpenRouter chosen", VoiceRoute.OpenRouter, VoiceRouting.tts(SourceChoice.OpenRouter, hermesDirect, openRouterUsable = true))
        assertEquals("no source at all", VoiceRoute.None, VoiceRouting.tts(SourceChoice.Auto, null, openRouterUsable = false))
        assertEquals(VoiceRoute.None, VoiceRouting.tts(SourceChoice.OpenRouter, hermesDirect, openRouterUsable = false))
    }

    @Test fun cloudSpeechOnlyWhenCloudIsOnAndFallsBackToTheDevice() {
        assertEquals(SttRoute.OnDevice, VoiceRouting.stt(cloud = false, SourceChoice.Auto, hermesDirect, openRouterUsable = true))
        assertEquals(SttRoute.HermesDirect, VoiceRouting.stt(cloud = true, SourceChoice.Auto, hermesDirect, openRouterUsable = true))
        assertEquals(SttRoute.HermesRelay, VoiceRouting.stt(cloud = true, SourceChoice.Hermes, hermesRelay, openRouterUsable = true))
        assertEquals("Hermes STT off", SttRoute.OpenRouter, VoiceRouting.stt(cloud = true, SourceChoice.Auto, sttOff, openRouterUsable = true))
        assertEquals(SttRoute.OpenRouter, VoiceRouting.stt(cloud = true, SourceChoice.OpenRouter, hermesDirect, openRouterUsable = true))
        assertEquals("signed out, no key", SttRoute.OnDevice, VoiceRouting.stt(cloud = true, SourceChoice.Auto, null, openRouterUsable = false))
    }

    @Test fun thePickedHermesVoiceAppliesOnlyToItsProvider() {
        val s = LiveSettings(hermesVoice = "elevenlabs:v-ada")
        assertEquals("v-ada", s.hermesVoiceFor("elevenlabs"))
        assertEquals(null, s.hermesVoiceFor("openai"))
        assertEquals(null, s.hermesVoiceFor(null))
        assertEquals("", LiveSettings(hermesVoice = "no provider").normalized().hermesVoice)
    }
}
