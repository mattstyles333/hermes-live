package com.folio.hermeslive.meeting

import com.folio.hermeslive.meeting.WakePhrase.Parsed
import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseTest {
    @Test fun notesNeedTheWakeWordThenANoteVerb() {
        assertEquals(Parsed.Note("Note that as an action for Priya"), WakePhrase.parse("Bill, note that as an action for Priya."))
        assertEquals(Parsed.Note("Note that as an action for Priya"), WakePhrase.parse("Bill note that as an action for Priya"))
        assertEquals(Parsed.Note("Mark that"), WakePhrase.parse("hey bill mark that"))
        assertEquals(Parsed.Note("Flag the delivery date"), WakePhrase.parse("OK Bill flag the delivery date"))
        assertEquals(Parsed.Note("Remember Sam owns the budget"), WakePhrase.parse("okay bill remember Sam owns the budget"))
        assertEquals(Parsed.Note("Action for me: call the lab"), WakePhrase.parse("Bill action for me: call the lab"))
        assertEquals(Parsed.Note("Could you write that down"), WakePhrase.parse("Bill could you write that down"))
    }

    @Test fun tasksNeedAnExplicitTaskVerb() {
        assertEquals(Parsed.Task("Draft the Kestrel reorder"), WakePhrase.parse("Bill, start a task to draft the Kestrel reorder."))
        assertEquals(Parsed.Task("Check stock of the Safilo frames"), WakePhrase.parse("bill make a task check stock of the Safilo frames"))
        assertEquals(Parsed.Task("Find last year's invoice"), WakePhrase.parse("Bill new task find last year's invoice"))
        assertEquals(Parsed.Task("Summarise the supplier terms"), WakePhrase.parse("Bill ask Hermes to summarise the supplier terms"))
        assertEquals(Parsed.Task("Book the van"), WakePhrase.parse("Bill can you start a new task to book the van"))
        // A task verb with nothing to do yet waits for the next segment, never an empty task.
        assertEquals(Parsed.Armed, WakePhrase.parse("Bill start a task"))
    }

    @Test fun talkingAboutOrToAPersonCalledBillIsMeetingSpeech() {
        assertEquals(Parsed.None, WakePhrase.parse("Bill thinks we should wait until March"))
        assertEquals(Parsed.None, WakePhrase.parse("Bill, what do you think?"))
        assertEquals(Parsed.None, WakePhrase.parse("Billy will send it"))
        assertEquals(Parsed.None, WakePhrase.parse("the bill came to forty pounds"))
        assertEquals(Parsed.None, WakePhrase.parse("I told Bill to note that"))
        assertEquals(Parsed.None, WakePhrase.parse("Phil note that"))
        assertEquals(Parsed.None, WakePhrase.parse(""))
    }

    @Test fun billAloneArmsTheNextSegment() {
        assertEquals(Parsed.Armed, WakePhrase.parse("Bill"))
        assertEquals(Parsed.Armed, WakePhrase.parse("Hey Bill."))
        assertEquals(Parsed.Note("The budget is fifty thousand"), WakePhrase.parse("the budget is fifty thousand", armed = true))
        assertEquals(Parsed.Task("Email the draft"), WakePhrase.parse("start a task to email the draft", armed = true))
        assertEquals(Parsed.None, WakePhrase.parse("the budget is fifty thousand", armed = false))
    }

    @Test fun taskLabelsAreShort() {
        assertEquals("Meeting task · Draft the Kestrel reorder for", WakePhrase.taskLabel("Draft the Kestrel reorder for next week please"))
    }
}
