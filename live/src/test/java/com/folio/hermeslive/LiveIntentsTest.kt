package com.folio.hermeslive

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folio.hermeslive.audio.AndroidCallAudio
import com.folio.hermeslive.audio.EarconSynth
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.RouteKind
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveIntentsTest {
    @Test fun launcherObeysStartOnOpen() {
        val launch = LiveIntents.parse(Intent(Intent.ACTION_MAIN))
        assertEquals(CallRequest(), launch.request)
        assertTrue(launch.onlyIfStartOnOpen)
        assertFalse(launch.wake)
    }

    @Test fun folioCallWithPromptAndMuted() {
        val intent = Intent(LiveIntents.ACTION_CALL)
            .putExtra(LiveIntents.EXTRA_PROMPT, "  What's next?  ")
            .putExtra(LiveIntents.EXTRA_MUTED, true)
        assertEquals(LiveIntents.Launch(CallRequest(prompt = "What's next?", muted = true)), LiveIntents.parse(intent))
        val stringMuted = Intent(LiveIntents.ACTION_CALL).putExtra(LiveIntents.EXTRA_MUTED, "true")
        assertTrue(LiveIntents.parse(stringMuted).request!!.muted)
        assertEquals("com.folio.hermeslive.action.CALL", LiveIntents.ACTION_CALL)
        assertEquals("com.folio.hermeslive.extra.PROMPT", LiveIntents.EXTRA_PROMPT)
        assertEquals("com.folio.hermeslive.extra.MUTED", LiveIntents.EXTRA_MUTED)
    }

    @Test fun shortcutsAssistantHeadsetTileAndShare() {
        assertTrue(LiveIntents.parse(Intent(LiveIntents.ACTION_TALK_MUTED)).request!!.muted)
        assertTrue(LiveIntents.parse(Intent(LiveIntents.ACTION_NEW_CONVERSATION)).request!!.newConversation)
        for (action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, LiveIntents.ACTION_TILE)) {
            val launch = LiveIntents.parse(Intent(action))
            assertTrue(action, launch.wake)
            assertEquals(CallRequest(), launch.request)
        }
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Summarise this article")
        assertEquals("Summarise this article", LiveIntents.parse(share).request!!.prompt)
        assertNull(LiveIntents.parse(Intent(Intent.ACTION_SEND).setType("text/plain")).request)
        assertNull(LiveIntents.parse(Intent("something.else")).request)
        assertNull(LiveIntents.parse(null).request)
    }

    @Test fun otherCallsAndDeviceKinds() {
        assertTrue(AndroidCallAudio.isOtherCall(AudioManager.MODE_IN_CALL))
        assertTrue(AndroidCallAudio.isOtherCall(AudioManager.MODE_RINGTONE))
        assertFalse(AndroidCallAudio.isOtherCall(AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(AndroidCallAudio.isOtherCall(AudioManager.MODE_NORMAL))
        assertEquals(RouteKind.Bluetooth, AndroidCallAudio.kindOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(RouteKind.Wired, AndroidCallAudio.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(RouteKind.Earpiece, AndroidCallAudio.kindOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(RouteKind.Other, AndroidCallAudio.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test fun earconsAreShortAndQuiet() {
        for (kind in Earcon.entries) {
            val pcm = EarconSynth.render(kind)
            val ms = pcm.size * 1000 / EarconSynth.SAMPLE_RATE
            assertTrue("$kind $ms ms", ms in 100..500)
            assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE / 4)
            assertEquals(0, pcm.first().toInt())
        }
    }
}
