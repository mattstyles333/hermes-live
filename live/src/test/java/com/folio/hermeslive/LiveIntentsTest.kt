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
        assertEquals(CallRequest(entry = "launcher"), launch.request)
        assertTrue(launch.onlyIfStartOnOpen)
        assertFalse(launch.wake)
    }

    /** Spec v2 §14 item 9: memo, the tile offline, and a result notification's Open. */
    @Test fun memoTileAndOpenTask() {
        val memo = LiveIntents.parse(Intent(com.folio.hermeslive.service.MemoEntry.ACTION_MEMO))
        assertEquals(CallRequest(entry = "memo", memo = true), memo.request)
        val tileOffline = LiveIntents.parse(Intent(LiveIntents.ACTION_TILE).putExtra(com.folio.hermeslive.service.MemoEntry.EXTRA_MEMO, true))
        assertEquals(CallRequest(entry = "tile", memo = true), tileOffline.request)
        assertTrue(tileOffline.wake)
        assertFalse(LiveIntents.parse(Intent(LiveIntents.ACTION_TILE)).request!!.memo)
        val open = LiveIntents.parse(
            Intent(com.folio.hermeslive.service.ResultNotifications.ACTION_OPEN_TASK).putExtra(com.folio.hermeslive.service.ResultNotifications.EXTRA_TASK_ID, "task-7"),
        )
        assertNull("Open starts no call", open.request)
        assertEquals("task-7", open.openTaskId)
        assertNull(LiveIntents.parse(Intent(com.folio.hermeslive.service.ResultNotifications.ACTION_OPEN_TASK)).openTaskId)
    }

    @Test fun folioCallWithPromptAndMuted() {
        val intent = Intent(LiveIntents.ACTION_CALL)
            .putExtra(LiveIntents.EXTRA_PROMPT, "  What's next?  ")
            .putExtra(LiveIntents.EXTRA_MUTED, true)
        assertEquals(LiveIntents.Launch(CallRequest(prompt = "What's next?", muted = true, entry = "folio")), LiveIntents.parse(intent))
        val stringMuted = Intent(LiveIntents.ACTION_CALL).putExtra(LiveIntents.EXTRA_MUTED, "true")
        assertTrue(LiveIntents.parse(stringMuted).request!!.muted)
        assertEquals("com.folio.hermeslive.action.CALL", LiveIntents.ACTION_CALL)
        assertEquals("com.folio.hermeslive.extra.PROMPT", LiveIntents.EXTRA_PROMPT)
        assertEquals("com.folio.hermeslive.extra.MUTED", LiveIntents.EXTRA_MUTED)
    }

    @Test fun shortcutsAssistantHeadsetTileAndShare() {
        assertTrue(LiveIntents.parse(Intent(LiveIntents.ACTION_TALK_MUTED)).request!!.muted)
        assertTrue(LiveIntents.parse(Intent(LiveIntents.ACTION_NEW_CONVERSATION)).request!!.newConversation)
        val entries = mapOf(Intent.ACTION_ASSIST to "assist", Intent.ACTION_VOICE_COMMAND to "headset", LiveIntents.ACTION_TILE to "tile")
        for ((action, entry) in entries) {
            val launch = LiveIntents.parse(Intent(action))
            assertTrue(action, launch.wake)
            assertEquals(CallRequest(entry = entry), launch.request)
        }
        assertEquals("shortcut", LiveIntents.parse(Intent(LiveIntents.ACTION_TALK)).request!!.entry)
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Summarise this article")
        assertEquals("Summarise this article", LiveIntents.parse(share).request!!.prompt)
        assertEquals("share", LiveIntents.parse(share).request!!.entry)
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
