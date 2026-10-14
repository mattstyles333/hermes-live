package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.AppLines
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Decision
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.Route
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.cascade.answer
import com.folio.hermes.openrouter.JevAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    private val bank = PhraseBank.load()
    private val s = CascadeSettings()
    private val tasks = listOf(
        TaskBrief("t1", "stock check", TaskState.Running, "working", 40),
        TaskBrief("t2", "newsletter", TaskState.Done, "complete", 90),
    )

    private fun answers(vararg pairs: Pair<String, JevAnswer>) = mapOf(*pairs)

    @Test fun lowActionConfidenceIsAClarifyingQuestionNeverATask() {
        val d = Router.decide(answers("action" to answer("new_task", 0.5), "line" to answer("ack.do")), tasks, bank, s)
        assertEquals(Route.Clarify, d.route)
        assertTrue("the writer asks", d.writer)
    }

    @Test fun lineOnlySkipsTheWriterOnlyWhenSure() {
        val sure = Router.decide(
            answers("action" to answer("reply"), "line" to answer("social.thanks"), "enough" to answer("line_only", 0.7)), tasks, bank, s,
        )
        assertEquals(Decision(Route.Reply, "social.thanks", writer = false), sure)
        val unsure = Router.decide(
            answers("action" to answer("reply"), "line" to answer("social.thanks"), "enough" to answer("line_only", 0.69)), tasks, bank, s,
        )
        assertTrue("low confidence calls the writer", unsure.writer)
        val noLine = Router.decide(
            answers("action" to answer("reply"), "line" to answer("none"), "enough" to answer("line_only", 0.99)), tasks, bank, s,
        )
        assertNull(noLine.slot)
        assertTrue("line_only without a line still needs the writer", noLine.writer)
    }

    @Test fun jevMayNotPickAppOnlyOrUnknownSlots() {
        val d = Router.decide(answers("action" to answer("reply"), "line" to answer("error.offline")), tasks, bank, s)
        assertNull(d.slot)
        assertNull(Router.decide(answers("action" to answer("reply"), "line" to answer("social.hi")), tasks, bank, s).slot)
        assertNull(Router.decide(answers("action" to answer("reply"), "line" to answer("ack.do", 0.4)), tasks, bank, s).slot)
    }

    @Test fun updatesAreQueuedByDefault() {
        val plain = Router.decide(answers("action" to answer("update_task"), "task" to answer("t1")), tasks, bank, s)
        assertEquals(Route.UpdateTask("t1", UpdateMode.Queue), plain.route)
        val unsure = Router.decide(
            answers("action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect", 0.5)), tasks, bank, s,
        )
        assertEquals(Route.UpdateTask("t1", UpdateMode.Queue), unsure.route)
        val redirect = Router.decide(
            answers("action" to answer("update_task"), "task" to answer("t1"), "update_mode" to answer("redirect", 0.8)), tasks, bank, s,
        )
        assertEquals(Route.UpdateTask("t1", UpdateMode.Redirect), redirect.route)
    }

    @Test fun aTaskActionWithoutASureTaskAsksWhich() {
        val cancel = Router.decide(answers("action" to answer("cancel_task"), "task" to answer("t1", 0.4)), tasks, bank, s)
        assertEquals(Decision(Route.Clarify, AppLines.WHICH_CANCEL, writer = false), cancel)
        val update = Router.decide(answers("action" to answer("update_task"), "task" to answer("none")), tasks, bank, s)
        assertEquals(Decision(Route.Clarify, AppLines.WHICH_UPDATE, writer = false), update)
        val unknown = Router.decide(answers("action" to answer("read_result"), "task" to answer("t7")), tasks, bank, s)
        assertEquals(Route.Clarify, unknown.route)
    }

    @Test fun statusWithoutATaskCoversAll() {
        val d = Router.decide(answers("action" to answer("status"), "task" to answer("none")), tasks, bank, s)
        assertEquals(Route.Status(null), d.route)
    }

    @Test fun readResultAlwaysWrites() {
        val d = Router.decide(
            answers("action" to answer("read_result"), "task" to answer("t2"), "line" to answer("result.found"), "enough" to answer("line_only", 0.95)),
            tasks, bank, s,
        )
        assertEquals(Route.ReadResult("t2"), d.route)
        assertTrue(d.writer)
    }

    @Test fun backchannelOnlyWhenOn() {
        val a = answers("turn" to answer("keep_listening"), "line" to answer("listen.backchannel"))
        assertNull(Router.backchannel(a, bank, s))
        assertEquals("listen.backchannel", Router.backchannel(a, bank, s.copy(backchannel = true)))
        assertNull(Router.backchannel(answers("line" to answer("ack.do")), bank, s.copy(backchannel = true)))
    }

    @Test fun settingsClamp() {
        assertEquals(400L, CascadeSettings(pauseMs = 100).pause)
        assertEquals(3_000L, CascadeSettings(pauseMs = 9_000).pause)
        assertEquals(200L, CascadeSettings(pauseMs = 400).early)
        assertEquals(500L, CascadeSettings(pauseMs = 1_000).early)
        assertEquals(500L, CascadeSettings(pauseMs = 3_000).early)
        assertEquals(6, CascadeSettings(maxTasks = 10).taskCap)
        assertEquals(1, CascadeSettings(maxTasks = 0).taskCap)
        assertFalse(CascadeSettings().backchannel)
    }
}
