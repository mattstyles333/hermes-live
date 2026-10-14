package com.folio.hermeslive.data

import com.folio.hermeslive.call.GptLiveOnDemandFactory
import com.folio.hermeslive.call.FallbackRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSettingsTest {
    @Test fun shippedDefaults() {
        val d = LiveSettings()
        assertTrue(d.musicInGaps)
        assertTrue(d.duckOnSpeaker)
        assertTrue(d.earcons)
        assertTrue(d.haptics)
        assertFalse("approvals need a tap by default", d.spokenApprovals)
        assertEquals(3, d.idleTimeoutMinutes)
        assertEquals(30, d.callCapMinutes)
        assertTrue(d.startOnOpen)
        assertFalse(d.keepScreenOn)
        assertEquals(FallbackRoute.Speaker, d.fallbackRoute)
        assertTrue("no real host is built in", LiveSettings.SERVER_HINT.contains("example.com"))
        assertTrue("diagnostics on by default (the URL comes from release CI)", d.diagnostics)
        assertEquals("", d.diagnosticsUrl)
        assertTrue(LiveSettings.DIAGNOSTICS_HINT.contains("example.com"))
    }

    @Test fun bridgeAndGapSettingsFollowTheChoices() {
        val b = LiveSettings().bridgeSettings()
        assertEquals(180_000L, b.idleHangUpMs)
        assertEquals(1_800_000L, b.callCapMs)
        assertEquals(4_000L, b.goodbyeMaxMs)
        assertEquals(30_000L, b.idleWarningMs)
        val never = LiveSettings(idleTimeoutMinutes = 0, callCapMinutes = 0).bridgeSettings()
        assertEquals(0L, never.idleHangUpMs)
        assertEquals(0L, never.idleWarningMs)
        assertEquals(0L, never.callCapMs)
        assertFalse(LiveSettings(musicInGaps = false).gapSettings().enabled)
    }

    @Test fun saveCostIsOnForGrokOffForGptLiveAndNeverForCustom() {
        val d = LiveSettings()
        assertTrue(d.grokSaveCost)
        assertFalse("GPT-Live's on-demand path is experimental: off by default", d.gptLiveSaveCost)
        assertTrue(d.onDemandSettings(VoiceEngine.Grok).enabled)
        assertFalse(d.onDemandSettings(VoiceEngine.GptLive).enabled)
        assertFalse("GPT-Live's factory reads the same switch", GptLiveOnDemandFactory.settings(d).enabled)
        assertTrue(GptLiveOnDemandFactory.settings(LiveSettings(gptLiveSaveCost = true)).enabled)
        assertFalse(d.onDemandSettings(VoiceEngine.Cascade).enabled)
        assertFalse(LiveSettings(grokSaveCost = false).onDemandSettings(VoiceEngine.Grok).enabled)
        assertEquals(8_000L, d.onDemandSettings(VoiceEngine.Grok).idleCloseMs)
        assertEquals("the engine's default is still GPT-Live", VoiceEngine.GptLive, d.voiceEngine)
    }
}
