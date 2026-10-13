package com.folio.hermes.cascade

import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {
    private val bank = PhraseBank.load()

    @Test fun shippedBankIsVersion2WithSlots() {
        assertEquals(2, bank.version)
        assertEquals(74, bank.slots.size)
        assertEquals(281, bank.slots.sumOf { it.lines.size })
        assertTrue(bank.slots.all { it.lines.size in 2..6 })
        assertEquals(bank.allLines.size, bank.allLines.toSet().size)
    }

    @Test fun jevSeesConversationalSlotsOnly() {
        val ids = bank.jevSlots(backchannel = false).map { it.id }.toSet()
        assertTrue("ack.do" in ids && "cancel.one" in ids && "social.thanks" in ids)
        AppLines.APP_IDS.forEach { assertFalse(it, it in ids) }
        assertTrue(ids.none { bank[it]!!.group in AppLines.APP_GROUPS })
        assertTrue(ids.none { bank[it]!!.group == PhraseBank.LISTEN })
        val withBackchannel = bank.jevSlots(backchannel = true).map { it.id }
        assertTrue("listen.backchannel" in withBackchannel)
    }

    @Test fun everyAppLineExists() {
        listOf(
            AppLines.HI, AppLines.WELCOME_BACK, AppLines.WAIT, AppLines.SIGNAL, AppLines.LIMIT_FULL, AppLines.NEEDS_OK,
            AppLines.QUESTION, AppLines.HERMES_DOWN, AppLines.OFFLINE, AppLines.ONLINE, AppLines.RETRY, AppLines.GAVE_UP,
            AppLines.BYE, AppLines.BYE_TASKS, AppLines.SAY_AGAIN, AppLines.RESULT_BACK, AppLines.RESULT_FAILED,
            AppLines.ON_SCREEN, AppLines.WHICH_UPDATE, AppLines.WHICH_CANCEL, AppLines.WHICH_ONE,
        ).forEach { assertTrue(it, bank[it] != null) }
    }

    @Test fun eachPassCoversAllLinesBeforeAnyRepeat() {
        val rotator = LineRotator(bank, MemoryRotationStore(), Random(7))
        val slot = bank["ack.do"]!!
        repeat(5) {
            val pass = List(slot.lines.size) { rotator.next(slot.id)!! }
            assertEquals(slot.lines.toSet(), pass.toSet())
        }
    }

    @Test fun neverTheSameLineTwiceInARowAcrossReshuffles() {
        for (seed in 0 until 200) {
            val rotator = LineRotator(bank, MemoryRotationStore(), Random(seed))
            val plays = List(60) { rotator.next("ack.understood")!! }
            plays.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed", a, b) }
        }
    }

    @Test fun aSlotWithTwoLinesAlternates() {
        val rotator = LineRotator(bank, MemoryRotationStore(), Random(3))
        val slot = bank.slots.first { it.lines.size == 2 }
        val plays = List(10) { rotator.next(slot.id)!! }
        plays.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun theCursorSurvivesARestart() {
        val file = Files.createTempFile("rotation", ".json").toFile()
        file.delete()
        val first = LineRotator(bank, FileRotationStore(file), Random(11))
        val before = List(3) { first.next("start.one")!! }
        // Same seed would replay from the start; the saved cursor must win.
        val second = LineRotator(bank, FileRotationStore(file), Random(11))
        val after = List(2) { second.next("start.one")!! }
        assertEquals(bank["start.one"]!!.lines.toSet(), (before + after).toSet())
        file.delete()
    }

    @Test fun unknownSlotIsNull() {
        assertNull(LineRotator(bank, MemoryRotationStore()).next("no.such"))
    }

    @Test fun aChangedSlotStartsAFreshPass() {
        val store = MemoryRotationStore()
        LineRotator(bank, store, Random(1)).next("ack.do")
        val changed = PhraseBank(3, bank.groups, listOf(PhraseSlot("ack.do", "ack", "x", listOf("One.", "Two."))))
        val line = LineRotator(changed, store, Random(1)).next("ack.do")
        assertTrue(line in listOf("One.", "Two."))
    }
}
