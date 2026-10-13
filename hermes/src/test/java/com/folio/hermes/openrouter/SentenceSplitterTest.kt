package com.folio.hermes.openrouter

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    private fun split(vararg deltas: String): List<String> {
        val s = SentenceSplitter()
        return deltas.flatMap { s.push(it) } + s.finish()
    }

    @Test fun leadIsItsOwnPieceSoFirstAudioIsShort() {
        assertEquals(listOf("On it.", "I've started the stock check.", "It runs now."), split("On it. I've started the stock check. It runs now."))
        assertEquals(listOf("Right,", "the stock check is running."), split("Right, the stock check is running."))
        assertEquals(listOf("Sure thing!", "Done."), split("Sure thing! Done."))
    }

    @Test fun aLongFirstSentenceIsNotCutAtItsComma() {
        assertEquals(
            listOf("I have started the stock check, and it is running."),
            split("I have started the stock check, and it is running."),
        )
    }

    @Test fun streamedInTinyDeltas() {
        val text = "Okay. The order is 2.5 days late. Mr. Smith knows… Anything else?"
        assertEquals(listOf("Okay.", "The order is 2.5 days late.", "Mr. Smith knows…", "Anything else?"), split(*text.map { it.toString() }.toTypedArray()))
    }

    @Test fun lastSentenceWithoutPunctuationComesOutAtFinish() {
        val s = SentenceSplitter()
        assertEquals(listOf("Yes."), s.push("Yes. And the rest"))
        assertEquals(listOf("And the rest"), s.finish())
    }

    @Test fun quotesAndNewlinesEndPieces() {
        assertEquals(listOf("Got it.", "He said \"stop.\"", "Then left"), split("Got it. He said \"stop.\" Then left"))
        assertEquals(listOf("Line one", "Line two."), split("Line one\nLine two."))
    }
}
