package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.ConnectionUi
import com.folio.hermes.cascade.Event
import com.folio.hermes.cascade.JevUi
import com.folio.hermes.cascade.LoggedAnswer
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.ResumeUi
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.cascade.TurnUi
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.routing.CascadeBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text twin of every animation, and the intent mapping. */
class CascadeTextTest {
    private fun task(status: TaskUiStatus, steps: Int = 3, send: SendState = SendState.Accepted, unread: Boolean = false) =
        TaskUi("a", "Stock check · RB2140", status, 1_000, null, steps, "list_stock_receipts", emptyList(), 0, "", null, 0, send = send, unread = unread)

    @Test fun confidences() {
        assertEquals(".94", confidence(0.94))
        assertEquals(".05", confidence(0.049))
        assertEquals("1.0", confidence(0.999))
    }

    @Test fun chipsInOrderWithTaskLabels() {
        val jev = JevUi(
            answers = mapOf(
                CascadeBrain.LINE to FakeCascadeUi.pick("start.parallel", 0.8),
                CascadeBrain.TURN to FakeCascadeUi.pick("done", 0.94),
                CascadeBrain.TASK to FakeCascadeUi.pick("a", 0.93),
                CascadeBrain.SPLIT to FakeCascadeUi.pick(CascadeBrain.ONE, 0.9),
                CascadeBrain.CLARITY to FakeCascadeUi.pick("clear", 0.9),
            ),
        )
        assertEquals(listOf("turn done .94", "task Stock check .93", "line start.parallel"), jevChips(jev, listOf(task(TaskUiStatus.Running))).map { it.text })
    }

    @Test fun cardLines() {
        assertEquals("list stock receipts · 3 steps", taskLine(task(TaskUiStatus.Running)))
        assertEquals("needs you · approve or answer · 3 steps", taskLine(task(TaskUiStatus.Waiting)))
        assertEquals("queued · starts when a slot frees", taskLine(task(TaskUiStatus.Queued)))
        assertEquals("finished · result not heard yet", taskLine(task(TaskUiStatus.Done, unread = true)))
        assertEquals("Hermes did not get it", taskLine(task(TaskUiStatus.Starting, send = SendState.NotSent)))
        assertEquals("Not sent · tap to retry", sendLine(task(TaskUiStatus.Starting, send = SendState.NotSent)))
        assertEquals("sending…", sendLine(task(TaskUiStatus.Starting, send = SendState.Sending)))
        assertNull(sendLine(task(TaskUiStatus.Running)))
    }

    @Test fun cardDescriptionCarriesWhatTheSatelliteShows() {
        val d = taskDescription(task(TaskUiStatus.Running, send = SendState.NotSent, unread = true), 12_000)
        assertEquals("Stock check · RB2140, running, list stock receipts, 3 steps, 12s, Not sent · tap to retry, result not heard yet", d)
    }

    @Test fun progressIsCappedUntilDone() {
        assertEquals(0.92f, taskProgress(task(TaskUiStatus.Running, steps = 50)), 0f)
        assertEquals(1f, taskProgress(task(TaskUiStatus.Done)), 0f)
        assertEquals(0f, taskProgress(task(TaskUiStatus.Queued)), 0f)
    }

    @Test fun times() {
        assertEquals("0:13", elapsed(13_400))
        assertEquals("1:02:03", elapsed(3_723_000))
        assertEquals("8s", shortElapsed(8_900))
        assertEquals("1m 05s", shortElapsed(65_000))
        assertEquals("12 min ago", ago(1_000_000_000, 1_000_000_000 - 12 * 60_000))
    }

    @Test fun banners() {
        val s = CascadeUiState()
        assertNull(connectionBanner(s.copy(connection = ConnectionUi(hermesUp = true))))
        assertEquals("Memo · offline · 3 held", connectionBanner(s.copy(connection = ConnectionUi(online = false, memo = true, outbox = 3))))
        assertEquals("offline · 2 held", connectionBanner(s.copy(connection = ConnectionUi(online = false, outbox = 2))))
        assertEquals("memo", connectionWord(s.copy(connection = ConnectionUi(online = false, memo = true))))
        assertEquals("online", connectionWord(s.copy(connection = ConnectionUi(hermesUp = true))))
    }

    @Test fun resumeLine() {
        val r = ResumeUi("c", 0, unheardResults = 1, heldTurns = 0, waitingTasks = 2, defaultChoice = ResumeChoice.Continue)
        assertEquals("Last call 5 min ago · 1 result not heard · 2 waiting for you", resumeLine(r, 5 * 60_000))
    }

    @Test fun orbInWords() {
        assertEquals("listening", orbStatus(CascadeUiState(turn = TurnUi(phase = TurnPhase.UserSpeaking))))
        assertEquals("listening while you hold", orbStatus(CascadeUiState(turn = TurnUi(holdToTalk = true))))
        assertEquals("Bill: deciding. One agent working.", sceneDescription(CascadeUiState(turn = TurnUi(phase = TurnPhase.Deciding), tasks = listOf(task(TaskUiStatus.Running)))))
        assertEquals("Hold the orb to talk", phaseLabel(CascadeUiState()))
    }

    @Test fun decisionBars() {
        assertEquals(listOf("done" to 0.9, "ignore" to 0.06, "keep_listening" to 0.04), topThree(LoggedAnswer("done", 0.9, mapOf("keep_listening" to 0.04, "done" to 0.9, "ignore" to 0.06, "x" to 0.0))))
        assertEquals(listOf("done" to 0.8), topThree(LoggedAnswer("done", 0.8)))
    }

    @Test fun intentsMapOntoActorEvents() {
        val posted = mutableListOf<Event>()
        val app = mutableListOf<String>()
        val intents = EventIntents(posted::add, object : CascadeIntents {
            override fun mute() { app += "mute" }
            override fun haptic(intent: HapticIntent) { app += "haptic:$intent" }
        })
        intents.task("a", TaskTap.Tell("hi", UpdateMode.Redirect))
        intents.wordChosen("t1", 5, "2104")
        intents.resume(ResumeChoice.New)
        intents.holdToTalk(true)
        intents.mute()
        intents.haptic(HapticIntent.TaskFinished)
        assertEquals(
            listOf(
                Event.TaskTapped("a", TaskTap.Tell("hi", UpdateMode.Redirect)), Event.WordChosen("t1", 5, "2104"),
                Event.ResumeChosen(ResumeChoice.New), Event.HoldToTalk(true),
            ),
            posted,
        )
        assertEquals(listOf("mute", "haptic:TaskFinished"), app)
    }

    @Test fun motionFromSystemSettings() {
        assertTrue(CascadeMotion.fromSystem(0f, true, false).reduceMotion)
        assertTrue(CascadeMotion.fromSystem(1f, false, false).reduceMotion)
        assertTrue(CascadeMotion.fromSystem(1f, true, true).reduceMotion)
        assertEquals(CascadeMotion(false, 2f), CascadeMotion.fromSystem(2f, true, false))
    }
}
