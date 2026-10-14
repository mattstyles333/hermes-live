package com.folio.hermes.cascade.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the accurate pass's text may replace a turn's text (R-STT-2, R-CTX-3). */
class AccurateSpanTest {
    @Test fun aMisheardWordIsTheSameSpan() {
        assertTrue(AccurateSpan.same("Check how many Castrel aviators we have in stock.", "Check how many Kestrel aviators we have in stock."))
        assertTrue("first and last words misheard", AccurateSpan.same("Castrel aviators in stock for Brightwall", "Kestrel aviators in stock for Brightwell"))
        assertTrue("case and punctuation", AccurateSpan.same("email sapphire, please", "Email Safilo please."))
    }

    @Test fun wordsHeldBeforeADroppedPauseAreNotLost() {
        // `pending` ("book the Leeds room for") stayed in the turn; its audio went with an ignored pause.
        assertFalse(AccurateSpan.same("book the Leeds room for Friday at ten", "Friday at ten"))
        assertFalse("two held words before a long rest", AccurateSpan.same("right so " + LONG, LONG))
        assertFalse("held words at the end", AccurateSpan.same("$LONG and the trade list", LONG))
    }

    @Test fun unrelatedOrEmptyTextIsNot() {
        assertFalse(AccurateSpan.same("Check the Kestrel stock", "Draft the Friday newsletter"))
        assertFalse(AccurateSpan.same("Check the Kestrel stock", ""))
        assertFalse("typed text with stray audio", AccurateSpan.same("Check how many Kestrel aviators we have in stock", "um"))
    }

    @Test fun longDictationIsCheckedAtItsEnds() {
        val words = (1..3_000).joinToString(" ") { "w$it" }
        assertTrue(AccurateSpan.same(words, words.replace("w1500 ", "x1500 ")))
        assertFalse(AccurateSpan.same("first second $words", words))
    }

    private companion object {
        const val LONG = "check the stock of the Kestrel aviators and email the supplier about the late order for the Leeds shop"
    }
}
