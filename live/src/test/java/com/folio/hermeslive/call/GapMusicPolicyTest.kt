package com.folio.hermeslive.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapMusicPolicyTest {
    private val pending = GapSignals(delegationPending = true, headset = true)

    @Test fun startsHoldingAndReleasesOnlyAfterQuietWithWorkPending() {
        val p = GapMusicPolicy()
        assertEquals(FocusMode.Hold, p.mode)
        p.update(GapSignals(userSpeaking = true, headset = true), 0)
        assertEquals(FocusMode.Hold, p.update(GapSignals(headset = true), 5_000).mode) // idle, no work: hold
        val waiting = p.update(pending, 5_000)
        assertEquals(FocusMode.Release, waiting.mode)
        assertTrue(waiting.changed)
    }

    @Test fun quietClockRestartsOnSpeech() {
        val p = GapMusicPolicy()
        p.update(pending.copy(remoteSpeaking = true), 1_000)
        val early = p.update(pending, 2_400)
        assertEquals(FocusMode.Hold, early.mode)
        assertEquals(2_500L, early.recheckAtMs)
        assertEquals(FocusMode.Release, p.update(pending, 2_500).mode)
    }

    @Test fun speechTakesFocusBackImmediatelyEvenInsideTheMinimum() {
        val p = GapMusicPolicy()
        p.update(pending, 2_000)
        assertEquals(FocusMode.Release, p.mode)
        val barge = p.update(pending.copy(userSpeaking = true), 2_100)
        assertEquals(FocusMode.Hold, barge.mode)
        assertTrue(barge.changed)
    }

    @Test fun silentEndOfWorkWaitsForTheMinimumBeforePausingAgain() {
        val p = GapMusicPolicy()
        p.update(pending, 2_000)
        val done = GapSignals(headset = true)
        val hold = p.update(done, 3_000)
        assertEquals(FocusMode.Release, hold.mode)
        assertEquals(6_000L, hold.recheckAtMs)
        assertEquals(FocusMode.Hold, p.update(done, 6_000).mode)
    }

    @Test fun speakerDucksAndHeadsetResumes() {
        val p = GapMusicPolicy()
        assertEquals(FocusMode.Duck, p.update(pending.copy(headset = false), 2_000).mode)
        assertEquals(FocusMode.Release, p.update(pending, 2_100).mode)
        val noDuck = GapMusicPolicy(GapSettings(duckOnSpeaker = false))
        assertEquals(FocusMode.Release, noDuck.update(pending.copy(headset = false), 2_000).mode)
    }

    @Test fun commentaryTakesFocusWithALeadOnlyWhenMusicWasPlaying() {
        val p = GapMusicPolicy()
        assertEquals(0L, p.beforeCommentary(100))
        p.update(pending, 5_000)
        assertTrue(p.musicAllowed)
        assertEquals(300L, p.beforeCommentary(5_100))
        assertEquals(FocusMode.Hold, p.mode)
        assertFalse(p.musicAllowed)
        // and the quiet clock restarted at the commentary
        assertEquals(FocusMode.Hold, p.update(pending, 6_000).mode)
        assertEquals(FocusMode.Release, p.update(pending, 6_600).mode)
    }

    @Test fun disabledHoldsAllCallAndOtherCallsReleaseEverything() {
        val off = GapMusicPolicy(GapSettings(enabled = false))
        assertEquals(FocusMode.Hold, off.update(pending, 60_000).mode)
        assertEquals(0L, off.beforeCommentary(60_001))
        val p = GapMusicPolicy()
        assertEquals(FocusMode.Release, p.update(GapSignals(userSpeaking = true, suspended = true), 0).mode)
    }

    @Test fun noFlappingWhileWorkContinuesQuietly() {
        val p = GapMusicPolicy()
        var changes = 0
        for (t in 0..20_000L step 150) if (p.update(pending, t).changed) changes++
        assertEquals(1, changes)
    }
}
