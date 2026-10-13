package com.folio.hermes.cascade.budget

import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.Exchange
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskBrief
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TurnContext
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.budget.CappedJevStateBudget.Companion.size
import com.folio.hermes.cascade.routing.CascadeBrain
import com.folio.hermes.cascade.routing.JevExtras
import com.folio.hermes.cascade.routing.RoutingEvals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-CTX-1: the cap holds for any input, in the v1 §9 priority order. */
class CappedJevStateBudgetTest {
    private val budget = CappedJevStateBudget()
    private val settings = CascadeSettings()
    private val bank = PhraseBank.load()

    private fun state(
        heard: String,
        pending: List<String> = emptyList(),
        tasks: List<TaskBrief> = emptyList(),
        recent: List<Exchange> = emptyList(),
        extras: JevExtras = JevExtras(),
    ): JsonObject = CascadeBrain.state(TurnContext(heard, pending, 1_000, recent, tasks, false), extras)

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test fun smallStatesPassUnchanged() {
        val s = state("check stock of RB2140", tasks = listOf(TaskBrief("t1", "stock check", TaskState.Running, "working", 4)))
        assertEquals(s, budget.fit(s, settings))
    }

    @Test fun longLatestKeepsHeadAndTailWithAWordCount() {
        val words = (1..600).joinToString(" ") { "w$it" }
        val fitted = budget.fit(state(words), settings)
        val latest = fitted.str("latest")
        assertTrue(latest.startsWith("w1 w2 w3"))
        assertTrue(latest.endsWith("w599 w600"))
        assertTrue(Regex("…\\[\\d+ words]…").containsMatchIn(latest))
        assertTrue(latest.length < words.length)
        assertEquals("utterance ends as latest does", latest, fitted.str("utterance"))
        assertTrue(fitted.size() <= settings.jevStateCapChars)
    }

    @Test fun longHeldTextIsCutBeforeTheLatestPart() {
        val held = (1..400).joinToString(" ") { "held$it" }
        val fitted = budget.fit(state("and the blue ones", pending = listOf(held)), settings)
        val utterance = fitted.str("utterance")
        assertTrue(utterance.startsWith("held1 held2"))
        assertTrue("Jev judges how the utterance ends", utterance.endsWith("held400 and the blue ones"))
        assertTrue("…[" in utterance && "words held]…" in utterance)
        assertEquals("and the blue ones", fitted.str("latest"))
    }

    @Test fun runningTasksComeFirstAtMostEightAndThreeOthers() {
        val tasks = (1..6).map { TaskBrief("d$it", "done $it", TaskState.Done, "complete", 100L) } +
            (1..7).map { TaskBrief("r$it", "running $it", TaskState.Running, "working", 10L) }
        val fitted = budget.fit(state("how's it going", tasks = tasks), settings)
        val ids = fitted["tasks"]!!.jsonArray.map { it.jsonObject.str("id") }
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5", "r6", "r7", "d6"), ids)
    }

    @Test fun dropsFromTheLowestPriorityUp() {
        val recent = (1..6).map { Exchange("question $it " + "x".repeat(150), "answer $it " + "y".repeat(150)) }
        val tasks = (1..8).map { TaskBrief("t$it", "label $it " + "z".repeat(60), TaskState.Running, "e".repeat(200), 5L) }
        val full = state("check it", tasks = tasks, recent = recent, extras = JevExtras(conversationSummary = "s".repeat(600)))
        val tight = settings.copy(jevStateCapChars = 2_000)
        val fitted = budget.fit(full, tight)
        assertTrue(fitted.size() <= 2_000)
        assertFalse("the summary goes first", "conversation_summary" in fitted)
        assertTrue("recent goes before tasks", fitted["recent"]!!.jsonArray.size < 6)
        assertEquals("check it", fitted.str("latest"))
        // Every kept task is shortened, never dropped before recent is empty.
        if (fitted["tasks"]!!.jsonArray.size < 8) assertTrue(fitted["recent"]!!.jsonArray.isEmpty())
        fitted["tasks"]!!.jsonArray.forEach { assertTrue(it.jsonObject.str("last_event").length <= 80) }
    }

    @Test fun capHoldsForAdversarialInputAndKeysKeepTheirOrder() {
        val nasty = "\"\\\u0000\u0001\u001f" + "😀" + "<>&'"
        val huge = nasty.repeat(20_000)
        val tasks = (1..50).map { TaskBrief("t$it$nasty", nasty.repeat(100), TaskState.Running, nasty.repeat(100), 1L) }
        val recent = (1..40).map { Exchange(nasty.repeat(300), nasty.repeat(300)) }
        val extras = JevExtras(List(40) { Word(nasty.repeat(50), 0.1, List(10) { nasty.repeat(20) }, true) }, nasty.repeat(500), nasty.repeat(500))
        for (cap in listOf(4_000, 2_500, 1_000, 500)) {
            val input = state(huge, pending = listOf(huge, huge), tasks = tasks, recent = recent, extras = extras)
            val fitted = budget.fit(input, settings.copy(jevStateCapChars = cap))
            assertTrue("cap $cap: ${fitted.size()}", fitted.size() <= cap)
            assertEquals(
                input.keys.filter { it in fitted }.toList(),
                fitted.keys.toList(),
            )
            assertTrue(listOf("utterance", "latest", "silence_ms", "speaking").all { it in fitted })
        }
    }

    @Test fun neverSplitsASurrogatePair() {
        val emoji = "😀".repeat(2_000)
        val fitted = budget.fit(state(emoji), settings)
        val latest = fitted.str("latest")
        latest.forEachIndexed { i, c ->
            if (Character.isHighSurrogate(c)) assertTrue(i + 1 < latest.length && Character.isLowSurrogate(latest[i + 1]))
            if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(latest[i - 1]))
        }
    }

    @Test fun everyEvalCaseFitsTheCap() {
        val cases = RoutingEvals.load(RoutingEvals.TUNED) + RoutingEvals.load(RoutingEvals.HELD_OUT)
        assertEquals(88, cases.size)
        for (case in cases) {
            val request = RoutingEvals.request(case, bank)
            assertTrue("${case.id}: ${request.state.size()}", request.state.size() <= settings.jevStateCapChars)
            // Short cases go through untouched: what the probe measured is what the app sends.
            assertEquals(case.id, (case.pending + " " + case.heard).trim(), request.state.str("utterance"))
        }
    }
}
