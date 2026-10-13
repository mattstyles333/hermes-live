package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.Exchange
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TurnContext
import java.security.MessageDigest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** R-RT-4: the wording and state shape are the measured probe's (`probe5`). Any edit fails here on purpose. */
class JevQuestionsTest {
    private val bank = PhraseBank.load()
    private val running = TaskBrief("t1", "stock check", TaskState.Running, "working", 40)
    private val done = TaskBrief("t2", "newsletter", TaskState.Done, "complete", 90)

    private fun ctx(tasks: List<TaskBrief>) =
        TurnContext("and the blue ones", listOf("check stock of RB2140"), 1_000, listOf(Exchange("hi", "Hello.")), tasks, false)

    @Test fun stateIsUtteranceAndLatest() {
        val state = CascadeBrain.state(ctx(listOf(running, done)))
        assertEquals(listOf("utterance", "latest", "silence_ms", "tasks", "recent", "speaking"), state.keys.toList())
        assertEquals("check stock of RB2140 and the blue ones", state["utterance"]!!.jsonPrimitive.content)
        assertEquals("and the blue ones", state["latest"]!!.jsonPrimitive.content)
        val task = state["tasks"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("id", "label", "status"), task.keys.toList())
        assertEquals("running", task["status"]!!.jsonPrimitive.content)
        assertEquals("Hello.", state["recent"]!!.jsonArray[0].jsonObject["bill"]!!.jsonPrimitive.content)
        assertFalse("no pending/heard fields any more", "pending" in state || "heard" in state)
    }

    @Test fun splitAndClarityAreAskedOnEveryCall() {
        assertEquals(
            listOf("turn", "action", "split", "clarity", "line", "enough"),
            CascadeBrain.questions(ctx(emptyList()), bank, false).keys.toList(),
        )
        assertEquals(
            listOf("turn", "action", "task", "split", "clarity", "line", "enough", "update_mode"),
            CascadeBrain.questions(ctx(listOf(running, done)), bank, false).keys.toList(),
        )
        assertEquals(
            listOf("turn", "action", "task", "split", "clarity", "line", "enough"),
            CascadeBrain.questions(ctx(listOf(done)), bank, false).keys.toList(),
        )
    }

    @Test fun probe5WordingIsVerbatim() {
        assertEquals(
            "Not meant for Bill: noise, filler, a cough, Bill's own words echoed back; the user speaking to another person " +
                "(addresses someone else by name, answers a phone call, chats to someone in the room: 'stick it on the side', " +
                "'ring you back'); or radio/TV/background speech",
            JevQuestions.TURN.criteria["ignore"],
        )
        assertEquals(
            "Sensible overall, but a word looks like a speech-to-text slip: an odd or split word ('Bright Well' for a brand), " +
                "a homophone, a missing small word",
            JevQuestions.CLARITY.criteria["minor_doubt"],
        )
        assertEquals(
            "A name, quantity, price or date the action depends on looks misheard, garbled or oddly phrased " +
                "(e.g. 'hundred' with no 'a', 'for' vs 'four', an unknown supplier-like name)",
            JevQuestions.CLARITY.criteria["key_doubt"],
        )
        assertEquals(
            "Stop or drop a task: 'stop', 'kill', 'cancel', 'scrap that', 'never mind', 'forget it'",
            JevQuestions.ACTION.criteria["cancel_task"],
        )
        // Every string at once: a wording change must come with a new probe run and a new fingerprint.
        val all = listOf(JevQuestions.TURN, JevQuestions.ACTION, JevQuestions.SPLIT, JevQuestions.CLARITY, JevQuestions.ENOUGH, JevQuestions.UPDATE_MODE)
            .joinToString("\n") { q -> q.instructions + "\n" + q.criteria.entries.joinToString("\n") { "${it.key}=${it.value}" } } +
            "\n" + listOf(JevQuestions.TASK, JevQuestions.TASK_NONE, JevQuestions.LINE, JevQuestions.LINE_NONE).joinToString("\n")
        val sha = MessageDigest.getInstance("SHA-256").digest(all.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(FINGERPRINT, sha)
    }

    private companion object {
        /** SHA-256 of the probe5 strings, computed from the probe file itself (not from this copy). */
        const val FINGERPRINT = "1edc308bba0cb44fe237c5a5515957f520a6dbc7104faa8afd1780592565934e"
    }
}
