package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.RunKind
import com.folio.hermes.cascade.State
import com.folio.hermes.cascade.TaskEvent
import com.folio.hermes.cascade.TaskRecord
import com.folio.hermes.cascade.TaskRun
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TaskTable
import com.folio.hermes.cascade.TurnVerdict
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.answer
import com.folio.hermes.openrouter.JevAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-RT-3, R-RT-5..7, R-TASK-4, R-STT-5: the CEO routing rules, as a pure function of state and answers. */
class CeoRouterTest {
    private val bank = PhraseBank.load()
    private val now = 1_000_000L

    private fun running(id: String, ageMs: Long, label: String = "task $id") =
        TaskRecord(id, label, now - ageMs, events = listOf(TaskEvent.Submitted(now - ageMs, "start")), runs = mapOf(1L to TaskRun(RunKind.Start, false)), active = 1)

    private fun state(vararg tasks: TaskRecord, settings: CascadeSettings = CascadeSettings()) =
        State(wallMs = now, settings = settings, tasks = TaskTable(tasks.toList(), counter = tasks.size))

    private fun decide(s: State, vararg a: Pair<String, JevAnswer>) = CeoRouter.decide(s, mapOf(*a), bank)

    @Test fun turnFloorsWithIgnoreFrom04() {
        val s = CascadeSettings()
        // §14 item 16: `done` needs turnDoneFloor (0.60), not the 0.55 action floor.
        assertEquals(0.60, s.turnDoneFloor, 0.0)
        assertEquals(TurnVerdict.KeepListening, Router.verdict(mapOf("turn" to answer("done", 0.59)), s))
        assertEquals(TurnVerdict.Done, Router.verdict(mapOf("turn" to answer("done", 0.60)), s))
        assertEquals("the floor is a setting", TurnVerdict.Done, Router.verdict(mapOf("turn" to answer("done", 0.56)), s.copy(turnDoneFloor = 0.55)))
        assertEquals("ignore is accepted at 0.4", TurnVerdict.Ignore, Router.verdict(mapOf("turn" to answer("ignore", 0.4)), s))
        assertEquals(TurnVerdict.KeepListening, Router.verdict(mapOf("turn" to answer("ignore", 0.39)), s))
        assertEquals("no turn answer at all keeps listening", TurnVerdict.KeepListening, Router.verdict(emptyMap(), s))
    }

    /** Hub decision 2026-10-14: a sure goodbye ends the turn even when `turn = done` is under its floor. */
    @Test fun aSureGoodbyeEndsTheTurnOnlyWithTurnDone() {
        val s = CascadeSettings()
        assertEquals(0.85, s.endCallDoneFloor, 0.0)
        fun v(turn: JevAnswer, action: JevAnswer) = Router.verdict(mapOf("turn" to turn, "action" to action), s)
        assertEquals("cheers, bye", TurnVerdict.Done, v(answer("done", 0.25), answer("end_call", 0.85)))
        assertEquals(TurnVerdict.KeepListening, v(answer("done", 0.25), answer("end_call", 0.84)))
        assertEquals("only end_call", TurnVerdict.KeepListening, v(answer("done", 0.52), answer("reply", 0.99)))
        assertEquals("a goodbye to someone else stays ignored", TurnVerdict.Ignore, v(answer("ignore", 0.92), answer("end_call", 0.93)))
        assertEquals("an unfinished turn keeps listening", TurnVerdict.KeepListening, v(answer("keep_listening", 0.9), answer("end_call", 0.99)))
    }

    /** Hub decision 2026-10-14: "stop everything" cancels every running task without asking which. */
    @Test fun stopEverythingCancelsAllOnlyWithStopAllWords() {
        val three = state(running("t1", 90_000), running("t2", 60_000), running("t3", 5_000))
        val stop = arrayOf("action" to answer("cancel_task", 0.99), "task" to answer("none", 0.9))
        fun routed(s: State, text: String, vararg a: Pair<String, JevAnswer>) = CeoRouter.withWords(s, mapOf(*a), text, decide(s, *a))
        val all = Decision(Route.CancelTask(CeoRouter.ALL_TASKS), CeoRouter.CANCEL_ALL, writer = false)
        listOf("Stop everything.", "Cancel all of them", "Kill them all.", "Stop everything. Cancel the lot.", "cancel all", "Scrap every task")
            .forEach { assertEquals(it, all, routed(three, it, *stop)) }
        // Without stop-all words (a misheard name, "all" inside a phrase): the newest, just started, as before.
        listOf("Cancel the chase for Barden.", "cancel the chase for all the Barden frames", "stop that")
            .forEach { assertEquals(it, Route.CancelTask("t3"), routed(three, it, *stop).route) }
        // Nothing started lately: a misheard name still asks which.
        val older = state(running("t1", 90_000), running("t2", 60_000))
        assertEquals(Decision(Route.Clarify, AppLines.WHICH_CANCEL, false), routed(older, "Cancel the chase for Barden.", *stop))
        assertEquals(all, routed(older, "Stop everything.", *stop))
        // A named task wins; one task is that task (R-RT-3); other actions never cancel all.
        assertEquals(Route.CancelTask("t2"), routed(three, "stop everything on the trade one", "action" to answer("cancel_task"), "task" to answer("t2")).route)
        assertEquals(Route.CancelTask("t1"), routed(state(running("t1", 90_000)), "Stop everything.", *stop).route)
        assertEquals(Route.Status(null), routed(three, "how is everything", "action" to answer("status"), "task" to answer("none")).route)
    }

    @Test fun unsureActionPlaysClarifyConfirmAndNeverStartsATask() {
        val d = decide(state(), "action" to answer("new_task", 0.39), "line" to answer("start.one"))
        assertEquals(Decision(Route.Clarify, "clarify.confirm", writer = false), d)
        // The action floor is its own (0.40), below the other answers' 0.55.
        assertEquals(Route.NewTask, decide(state(), "action" to answer("new_task", 0.40), "line" to answer("start.one")).route)
    }

    @Test fun lowTaskConfidenceAsksWhichUnlessOneCandidate() {
        val two = state(running("t1", 60_000), running("t2", 50_000))
        assertEquals(Decision(Route.Clarify, AppLines.WHICH_UPDATE, false), decide(two, "action" to answer("update_task"), "task" to answer("t1", 0.49)))
        assertEquals(Route.UpdateTask("t1", UpdateMode.Queue), decide(two, "action" to answer("update_task"), "task" to answer("t1", 0.5)).route)
        // Exactly one candidate: a low-confidence task (or `none`) resolves to it.
        val one = state(running("t1", 60_000))
        assertEquals(Route.CancelTask("t1"), decide(one, "action" to answer("cancel_task"), "task" to answer("none", 0.9)).route)
        assertEquals(Route.UpdateTask("t1", UpdateMode.Queue), decide(one, "action" to answer("update_task"), "task" to answer("t1", 0.02)).route)
        // Cancel candidates are running tasks only.
        val doneAndRunning = state(running("t1", 60_000), running("t2", 60_000).copy(state = TaskState.Done))
        assertEquals(Route.CancelTask("t1"), decide(doneAndRunning, "action" to answer("cancel_task"), "task" to answer("t2", 0.3)).route)
    }

    @Test fun scrapThatRightAfterAStartCancelsTheNewest() {
        val s = state(running("t1", 120_000), running("t2", 5_000))
        assertEquals(Route.CancelTask("t2"), decide(s, "action" to answer("cancel_task"), "task" to answer("none", 0.6)).route)
        // Long after the start, an unsure cancel asks which.
        val later = state(running("t1", 120_000), running("t2", 25_000))
        assertEquals(Route.Clarify, decide(later, "action" to answer("cancel_task"), "task" to answer("none", 0.6)).route)
        // A sure pick wins over the newest.
        assertEquals(Route.CancelTask("t1"), decide(s, "action" to answer("cancel_task"), "task" to answer("t1", 0.8)).route)
    }

    @Test fun lineIsLimitedToTheActionsGroups() {
        val s = state(running("t1", 60_000))
        // A status line for a new task is outside `start`: the default start slot plays instead.
        assertEquals("start.parallel", decide(s, "action" to answer("new_task"), "line" to answer("status.working")).slot)
        assertEquals("start.one", decide(state(), "action" to answer("new_task"), "line" to answer("cancel.one")).slot)
        assertEquals("start.lookup", decide(state(), "action" to answer("new_task"), "line" to answer("start.lookup")).slot)
        assertEquals("start.parallel", decide(state(), "action" to answer("new_task"), "split" to answer("several"), "line" to answer("none")).slot)
        // Reply: ack or social only; `none` lets the writer open.
        assertEquals(null, decide(state(), "action" to answer("reply"), "line" to answer("start.one")).slot)
        assertEquals("social.thanks", decide(state(), "action" to answer("reply"), "line" to answer("social.thanks")).slot)
        // Cancel: `cancel` group; Jev may not pick app-only lines.
        assertEquals("cancel.one", decide(s, "action" to answer("cancel_task"), "line" to answer("ack.do")).slot)
        // Read result: `result` group, default result.back.
        val done = state(running("t1", 60_000).copy(state = TaskState.Done, result = "14 in stock"))
        assertEquals(AppLines.RESULT_BACK, decide(done, "action" to answer("read_result"), "line" to answer("status.working")).slot)
        // End call: the app's own goodbye.
        assertEquals(AppLines.BYE_TASKS, decide(s, "action" to answer("end_call"), "line" to answer("social.thanks")).slot)
        assertEquals(AppLines.BYE, decide(state(), "action" to answer("end_call")).slot)
    }

    @Test fun enoughOnlyForReplyUpdateCancelEnd() {
        val lineOnly = "enough" to answer("line_only", 0.7)
        assertFalse(decide(state(), "action" to answer("reply"), "line" to answer("social.thanks"), lineOnly).writer)
        assertTrue("0.69 is not enough", decide(state(), "action" to answer("reply"), "line" to answer("social.thanks"), "enough" to answer("line_only", 0.69)).writer)
        assertTrue("new work always goes to the writer", decide(state(), "action" to answer("new_task"), "line" to answer("start.one"), lineOnly).writer)
        val s = state(running("t1", 60_000))
        assertTrue("status always writes", decide(s, "action" to answer("status"), "line" to answer("status.working"), lineOnly).writer)
        assertFalse(decide(s, "action" to answer("cancel_task"), "line" to answer("cancel.one"), lineOnly).writer)
        assertFalse(decide(s, "action" to answer("update_task"), "line" to answer("update.queued"), lineOnly).writer)
    }

    @Test fun correctionWithin20sRedirectsAfter20sJevDecides() {
        val fresh = state(running("t1", 120_000), running("t2", 19_000))
        assertEquals(Route.UpdateTask("t2", UpdateMode.Redirect), decide(fresh, "action" to answer("update_task"), "task" to answer("t2"), "update_mode" to answer("queue", 0.9)).route)
        assertEquals("update.redirect", decide(fresh, "action" to answer("update_task"), "task" to answer("t2"), "line" to answer("update.queued")).slot)
        // Not the newest: Jev decides.
        assertEquals(Route.UpdateTask("t1", UpdateMode.Queue), decide(fresh, "action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect", 0.59)).route)
        val old = state(running("t1", 120_000), running("t2", 21_000))
        assertEquals(Route.UpdateTask("t2", UpdateMode.Queue), decide(old, "action" to answer("update_task"), "task" to answer("t2"), "update_mode" to answer("redirect", 0.59)).route)
        assertEquals(Route.UpdateTask("t2", UpdateMode.Redirect), decide(old, "action" to answer("update_task"), "task" to answer("t2"), "update_mode" to answer("redirect", 0.6)).route)
        assertEquals(Route.UpdateTask("t2", UpdateMode.Queue), decide(old, "action" to answer("update_task"), "task" to answer("t2")).route)
    }

    @Test fun garbledPlaysClarifyAgain() {
        assertEquals(Decision(Route.AskRepeat, null, false), decide(state(), "action" to answer("new_task"), "clarity" to answer("garbled", 0.8)))
        assertEquals(Route.NewTask, decide(state(), "action" to answer("new_task"), "clarity" to answer("garbled", 0.5)).route)
        assertEquals(Route.NewTask, decide(state(), "action" to answer("new_task"), "clarity" to answer("key_doubt", 0.9)).route)
    }

    @Test fun statusFocusesTheOnlyTaskOrAll() {
        assertEquals(Route.Status("t1"), decide(state(running("t1", 1_000)), "action" to answer("status"), "task" to answer("none")).route)
        assertEquals(Route.Status(null), decide(state(running("t1", 1_000), running("t2", 1_000)), "action" to answer("status"), "task" to answer("none")).route)
    }
}
