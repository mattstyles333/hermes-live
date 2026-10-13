package com.folio.hermes.cascade.writer

import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-TASK-2 briefs, R-STT-6 repair and the verbatim rule, as pure functions. */
class WriterRulesTest {
    @Test fun briefsParseWithAFenceAndClampLabelAndSummary() {
        val raw = "```json\n{\"briefs\":[{\"brief\":\"Goal: chase the order\",\"label\":\"Safilo order chase now please\"," +
            "\"summary\":\"chasing the Safilo order today and also tomorrow and then again on the following Monday morning\"}]," +
            "\"repaired\":\"chase Safilo\",\"notes\":\"Sapphire -> Safilo\"}\n```"
        val set = Briefs.parse(raw)!!
        assertTrue(set.complete)
        val b = set.briefs.single()
        assertEquals("Safilo order chase now", b.label)
        assertEquals(12, b.summary!!.removeSuffix(".").split(' ').size)
        assertTrue(b.summary!!.first().isUpperCase())
        assertEquals("chase Safilo", set.repaired)
        assertEquals("Sapphire -> Safilo", set.notes)
    }

    @Test fun aCutShortAnswerKeepsTheWholeBriefsBeforeTheCut() {
        val raw = "{\"briefs\":[{\"brief\":\"Goal: one {braces} \\\"quoted\\\"\",\"label\":\"first\",\"summary\":\"one\"}," +
            "{\"brief\":\"Goal: two\",\"label\":\"second\",\"summary\":\"two\"},{\"brief\":\"Goal: thr"
        val set = Briefs.parse(raw)!!
        assertFalse(set.complete)
        assertEquals(listOf("first", "second"), set.briefs.map { it.label })
        assertEquals("Goal: one {braces} \"quoted\"", set.briefs[0].brief)
        assertNull(Briefs.parse("I could not do that."))
        assertNull(Briefs.parse("{\"briefs\":[]}"))
    }

    @Test fun aCutAfterTheBriefsKeepsTheSetCompleteAndFixesApplyInCode() {
        // The likeliest cut (a short token budget): inside the fixes, after the briefs array closed.
        val raw = "{\"briefs\":[{\"brief\":\"Goal: email Safilo\",\"label\":\"supplier email\",\"summary\":\"emailing Safilo\"}],\"fixes\":[{\"from\":\"Sapph"
        val set = Briefs.parse(raw)!!
        assertTrue("only the fixes were lost", set.complete)
        assertEquals(1, set.briefs.size)
        val whole = Briefs.parse("{\"briefs\":[{\"brief\":\"Goal: x\",\"label\":\"x job\",\"summary\":\"x\"}],\"fixes\":[{\"from\":\"Sapphire\",\"to\":\"Safilo\"}],\"notes\":\"\"}")!!
        assertEquals(listOf("Sapphire" to "Safilo"), whole.fixes)
        assertEquals("email Safilo about the Sapphires", Briefs.applyFixes("email sapphire about the Sapphires", whole.fixes))
        assertNull(Briefs.applyFixes("no match here", whole.fixes))
    }

    @Test fun inventedNumbersAreCaught() {
        val said = "order forty frames, two hundred and fifty lenses and 1,500 cases by the 3rd; order 40-1187"
        assertEquals(emptyList<String>(), Verbatim.invented(said, "Order 40 frames, 250 lenses, 1500 cases by the 3rd (order 40-1187)."))
        assertEquals(listOf("14"), Verbatim.invented(said, "Order 14 frames."))
        assertEquals(listOf("2,000"), Verbatim.invented("a couple of thousand", "Order 2,000."))
        assertTrue(Verbatim.numbers("one thousand five hundred").contains("1500"))
        assertTrue(Verbatim.numbers("a hundred units").contains("100"))
        assertTrue(Verbatim.numbers("forty five").contains("45"))
    }

    @Test fun repairChangesOnlyUncertainOrVocabularyWords() {
        val heard = Transcript(
            "email Sapphire about the forty units",
            listOf(Word("email"), Word("Sapphire", 0.3, listOf("Safilo"), uncertain = true), Word("about"), Word("the"), Word("forty"), Word("units")),
        )
        val ok = Repair.check(heard, "Email Safilo about the forty units.", emptyList())
        assertTrue(ok.accepted)
        assertEquals("Email Safilo about the forty units.", ok.text)
        assertEquals(listOf("Sapphire" to "Safilo"), ok.changes)
        // A certain word changed ("forty" -> "fourteen"): the whole repair is refused.
        val bad = Repair.check(heard, "email Safilo about the fourteen units", emptyList())
        assertFalse(bad.accepted)
        assertEquals(heard.text, bad.text)
        assertEquals(listOf("forty" to "fourteen"), bad.rejected)
        // A vocabulary match may change a word Jev doubted even with no confidence data.
        val vocab = Repair.check(Transcript("ask Bright Well for frames"), "ask Brightwell for frames", listOf("Brightwell"))
        assertTrue(vocab.accepted)
        assertEquals(listOf("Bright Well" to "Brightwell"), vocab.changes)
        // Inserted words are not allowed.
        assertFalse(Repair.check(Transcript("order frames"), "order the frames", emptyList()).accepted)
        // No repair at all: the verbatim text.
        assertEquals("order frames", Repair.check(Transcript("order frames"), null, emptyList()).text)
    }

    @Test fun asHeardMarksUncertainWords() {
        val t = Transcript("email Sapphire now", listOf(Word("email"), Word("Sapphire", 0.3, listOf("Safilo"), true), Word("now")))
        assertEquals("email [Sapphire|Safilo] now", Repair.marked(t))
        assertEquals("plain words", Repair.marked(Transcript("plain words")))
    }

    @Test fun theBriefEndsWithTheTranscriptBlockAndAskFirstLine() {
        val block = TranscriptBlock("email Safilo", "email [Sapphire|Safilo]", "minor_doubt (0.72)", "", "Dictated offline 14:32–14:41.")
        val text = BriefTemplate.build("Goal: Email Safilo.\nReport back: whatever", block, 2, 3, listOf("stock check", "newsletter"))
        val lines = text.lines()
        assertEquals("Goal: Email Safilo.", lines[0])
        assertTrue(lines[1].startsWith("Scope: job 2 of 3"))
        assertEquals(1, lines.count { it.startsWith("Report back") })
        assertEquals(BriefTemplate.REPORT_BACK, lines[2])
        assertTrue("Transcript: email Safilo" in lines)
        assertTrue("As heard: email [Sapphire|Safilo]" in lines)
        assertTrue("Clarity: minor_doubt (0.72)" in lines)
        assertTrue("Notes: none" in lines)
        assertTrue("Dictated offline 14:32–14:41." in lines)
        assertEquals(BriefTemplate.ASK_FIRST, lines.last())
        assertTrue(BriefTemplate.build(null, block).startsWith(BriefTemplate.FALLBACK_GOAL))
    }

    @Test fun speakableKeepsEverythingButMarkup() {
        assertEquals(
            listOf("Results.", "RB2140: 14.", "Two left, see a link."),
            Speakable.sentences("## Results\n* **RB2140**: 14\n1. Two left, see https://example.invalid/a"),
        )
    }
}
