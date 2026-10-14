package com.folio.hermes.cascade.cleanup

import com.folio.hermes.cascade.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-check on a transcript cleanup. The heard / other pairs are recorded on-device passes
 * over the synthetic STT corpus (`stt-ondevice-transcripts-2026-10-14.jsonl`, Zipformer +
 * Parakeet-110M); the cleaned text is what the bench's prompt is asked to produce.
 */
class CleanupCheckTest {
    private val vocab = listOf("Safilo", "Oakley", "Kering", "Xero", "Moretti", "Kestrel", "Brightwell", "Halden", "Haldane", "Gemma", "Priya")

    private fun check(heard: String, cleaned: String, other: String = heard) =
        CleanupCheck.check(CleanupCheck.tokens(heard).map { Word(it) }, cleaned, other, vocab)

    private fun accepted(v: CleanupCheck.Verdict) = v as CleanupCheck.Verdict.Accepted

    @Test fun aSoundAlikeOfAVocabularyNameIsTakenAndCountsAsAKeyChange() {
        val v = accepted(check("Make sure it includes caring too.", "Make sure it includes Kering too."))
        assertEquals("Make sure it includes Kering too.", v.text)
        assertEquals(listOf("caring" to "Kering"), v.changes)
        assertTrue(v.key)
        assertTrue(accepted(check("and get someone on the zero reconciliation.", "And get someone on the Xero reconciliation.")).key)
        assertTrue(accepted(check("Have another go at the Maretti Chase.", "Have another go at the Moretti chase.")).key)
        assertTrue(accepted(check("how many castral aviators", "how many Kestrel aviators")).key)
        assertTrue(accepted(check("check the safe hello frames", "check the Safilo frames")).key)
    }

    @Test fun theOtherRecognisersWordIsTaken() {
        // Halden and Haldane do not sound alike enough; the fast pass heard "haldane".
        val v = accepted(check("never mind the Halden reorder.", "never mind the Haldane reorder.", other = "never mind the haldane reorder"))
        assertEquals(listOf("Halden" to "Haldane"), v.changes)
    }

    @Test fun aClippedWordIsCompletedWithoutAReask() {
        val v = accepted(check("what we have in sto", "what we have in stock"))
        assertEquals(listOf("sto" to "stock"), v.changes)
        assertFalse(v.key)
    }

    @Test fun caseAndPunctuationAndNumberFormsAreNotChanges() {
        assertEquals(CleanupCheck.Verdict.Same, check("Get Me This Week Sales Against Last Week By Brand.", "Get me this week sales against last week, by brand."))
        assertEquals(CleanupCheck.Verdict.Same, check("order forty frames", "order 40 frames"))
        assertEquals(CleanupCheck.Verdict.Same, check("about a hundred units", "about 100 units"))
    }

    @Test fun anImplausibleSwapIsRefused() {
        val v = check("Make sure it includes caring too.", "Make sure it includes Oakley too.")
        assertEquals("not_sound_alike", (v as CleanupCheck.Verdict.Rejected).reason)
        assertEquals("caring" to "Oakley", v.change)
        assertEquals("not_sound_alike", (check("send the orders today", "send the Brightwell invoice today") as CleanupCheck.Verdict.Rejected).reason)
    }

    @Test fun aNameOrNumberNeitherPassHeardIsNeverInvented() {
        assertTrue(check("draft a note to the team", "draft a note to Priya's team") is CleanupCheck.Verdict.Rejected)
        assertEquals("invented", (check("draft a note to team", "draft a note to Priya's team") as CleanupCheck.Verdict.Rejected).reason)
        assertEquals("invented", (check("order frames", "order 40 frames") as CleanupCheck.Verdict.Rejected).reason)
        assertEquals("dropped_key", (check("order 40 Safilo frames", "order frames") as CleanupCheck.Verdict.Rejected).reason)
        // The other pass heard it: then it may come back.
        assertTrue(check("draft a note to the team", "draft a note to Gemma's team", other = "draft a note to Gemma's team") is CleanupCheck.Verdict.Accepted)
    }

    @Test fun aMisheardNumberIsAKeyChange() {
        val v = accepted(check("we need for boxes", "we need four boxes"))
        assertTrue(v.key)
        val digits = accepted(check("order four zero one one eight seven", "order 401187"))
        assertTrue(digits.key)
    }

    @Test fun numbersParseOnlyAsNumbers() {
        assertEquals(21L, CleanupCheck.number(listOf("twenty", "one")))
        assertEquals(105L, CleanupCheck.number(listOf("one", "hundred", "and", "five")))
        assertEquals(100L, CleanupCheck.number(listOf("a", "hundred")))
        assertEquals(2_500L, CleanupCheck.number(listOf("two", "thousand", "five", "hundred")))
        assertNull("digits said one by one are not a sum", CleanupCheck.number(listOf("two", "two")))
        assertNull(CleanupCheck.number(listOf("for")))
    }

    @Test fun unchangedWordsKeepTheirMarks() {
        val heard = listOf(Word("check", 0.9), Word("castral", 0.3, listOf("Kestrel"), uncertain = true), Word("stock", 0.8))
        val v = accepted(CleanupCheck.check(heard, "check Kestrel stock", "check castral stock", vocab))
        assertEquals(0.9, v.words[0].confidence)
        assertEquals(Word("Kestrel", alternatives = listOf("castral")), v.words[1])
        assertEquals(0.8, v.words[2].confidence)
    }
}
