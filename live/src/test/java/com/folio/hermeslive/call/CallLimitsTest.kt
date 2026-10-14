package com.folio.hermeslive.call

import com.folio.hermeslive.data.LiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grok's idle hang-up and call cap, on the GPT-Live settings (pure; the clock is a number). */
class CallLimitsTest {
    private val defaults = LiveSettings().bridgeSettings()

    @Test fun theDefaultsAreThreeMinutesQuietAndAThirtyMinuteCap() {
        assertEquals(180_000L, defaults.idleHangUpMs)
        assertEquals(30_000L, defaults.idleWarningMs)
        assertEquals(1_800_000L, defaults.callCapMs)
        assertEquals(60_000L, defaults.capWarningMs)
    }

    @Test fun quietWarnsAtTwoAndAHalfMinutesThenHangsUpAtThree() {
        val l = CallLimits(defaults, 0)
        assertEquals(CallLimits.Step.None, l.tick(149_999, busy = false))
        assertEquals(CallLimits.Step.IdleWarning, l.tick(150_000, busy = false))
        assertTrue(l.warnedIdle)
        assertEquals("It has gone quiet. Hanging up in 30 seconds unless you speak.", l.idleWarning)
        assertEquals("warned once", CallLimits.Step.None, l.tick(160_000, busy = false))
        assertEquals(CallLimits.Step.HangUp("idle_timeout"), l.tick(180_000, busy = false))
    }

    @Test fun activityTakesTheWarningBackAndRestartsTheQuiet() {
        val l = CallLimits(defaults, 0)
        l.tick(150_000, busy = false)
        assertEquals(CallLimits.Step.IdleWarningCleared, l.tick(170_000, busy = true))
        assertFalse(l.warnedIdle)
        assertEquals(CallLimits.Step.None, l.tick(300_000, busy = false))
        assertEquals(CallLimits.Step.IdleWarning, l.tick(320_000, busy = false))
        assertEquals(CallLimits.Step.HangUp("idle_timeout"), l.tick(350_000, busy = false))
    }

    @Test fun stayingBusyNeverHangsUpUntilTheCap() {
        val l = CallLimits(defaults, 0)
        var t = 0L
        val steps = mutableListOf<CallLimits.Step>()
        while (t < 1_800_000L) {
            steps += l.tick(t, busy = true)
            t += 150
        }
        assertEquals(listOf(CallLimits.Step.CapWarning), steps.filter { it != CallLimits.Step.None })
        assertEquals("the cap ends even a busy call", CallLimits.Step.HangUp("call_cap"), l.tick(1_800_000, busy = true))
    }

    @Test fun theCapWarnsAMinuteBefore() {
        val l = CallLimits(LiveSettings(idleTimeoutMinutes = 0).bridgeSettings(), 1_000)
        assertEquals(CallLimits.Step.None, l.tick(1_000 + 1_739_999, busy = false))
        assertEquals(CallLimits.Step.CapWarning, l.tick(1_000 + 1_740_000, busy = false))
        assertEquals(CallLimits.Step.None, l.tick(1_000 + 1_799_999, busy = false))
        assertEquals(CallLimits.Step.HangUp("call_cap"), l.tick(1_000 + 1_800_000, busy = false))
    }

    @Test fun zeroMeansNever() {
        val l = CallLimits(LiveSettings(idleTimeoutMinutes = 0, callCapMinutes = 0).bridgeSettings(), 0)
        assertEquals(CallLimits.Step.None, l.tick(10 * 3_600_000L, busy = false))
    }
}
