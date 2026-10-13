package com.folio.fakehermes

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.PromptSurface
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shipped scenario (what run.sh serves by default) answers the everyday prompts plausibly. */
class DefaultScenarioTest {
    private val h = Harness(FakeHermesProcess(scenario = "scenarios/default.json")).apply { signIn() }

    @After fun tearDown() = h.close()

    private fun ask(text: String): List<TurnUpdate> = runBlocking {
        h.conversation().turn(text, PromptSurface.VoiceLive, "User: $text").onEach { u ->
            when (u) {
                is TurnUpdate.ApprovalNeeded -> launch { h.gateway.answerApproval(u.request, ApprovalChoice.Once) }
                is TurnUpdate.ClarifyNeeded -> launch { h.gateway.answerClarify(u.request, mapOf(u.request.questions.first().qid to "email")) }
                else -> Unit
            }
        }.untilDone(30_000)
    }

    @Test fun `stock, email, supplier chase and summary`() {
        val stock = ask("how many of the sample frame are in stock")
        assertTrue(stock.completed().text.contains("14 in stock"))
        assertEquals(2, stock.filterIsInstance<TurnUpdate.ToolStarted>().size)

        val email = ask("reply to the customer email about their order")
        assertTrue(email.any { it is TurnUpdate.ApprovalNeeded })
        assertTrue(email.completed().text.startsWith("Sent."))

        val chase = ask("chase the supplier about the late delivery")
        assertTrue(chase.completed().text.startsWith("Done by email."))

        val summary = ask("catch me up on what happened overnight")
        assertTrue(summary.completed().text.startsWith("Quick summary"))
        assertTrue(summary.any { it is TurnUpdate.Interim })

        assertEquals(TurnStatus.Error, ask("fail on purpose please").completed().status)
        assertEquals("Done. I handled that for you.", ask("tell me a joke").completed().text)
    }
}
