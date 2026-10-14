package com.folio.fakehermes

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A second prompt while a turn runs: `queued: true`, the default interrupt (redirect), queue-and-interrupt. */
class FakeHermesBusyTest {
    private val h = Harness().apply { signIn() }

    @After fun tearDown() = h.close()

    /** Start [text] in the background; returns its result and waits until it has started. */
    private suspend fun kotlinx.coroutines.CoroutineScope.startTurn(conv: com.folio.hermes.HermesConversation, text: String) =
        CompletableDeferred<Unit>().let { started ->
            val result = async {
                conv.turn(text).onEach { if (it is TurnUpdate.Started) started.complete(Unit) }.untilDone()
            }
            withTimeout(5_000) { started.await() }
            result
        }

    @Test fun `queued true runs after the live turn, which is not stopped`() = runBlocking<Unit> {
        val conv = h.conversation()
        val first = startTurn(conv, "slow job A")
        val second = conv.turn("then a quick one", queued = true).untilDone()
        assertEquals(SubmitStatus.Queued, (second.first() as TurnUpdate.Accepted).status)
        assertEquals("Done: then a quick one", second.completed().text)
        val a = first.await().completed()
        assertEquals(TurnStatus.Complete, a.status)
        assertEquals("Slow job finished for: slow job A", a.text)
        val outcomes = h.prompts(conv.storedSessionId!!).map { it.str("outcome") to it.bool("queued") }
        assertEquals(listOf("streaming" to false, "queued" to true), outcomes)
        assertEquals(0, h.fake.get("/__fake/assert/interrupts").int("count"))
    }

    @Test fun `default interrupt mode redirects the live turn`() = runBlocking<Unit> {
        val conv = h.conversation()
        val first = startTurn(conv, "slow job B")
        val second = conv.turn("make it the blue one").untilDone()
        assertEquals(SubmitStatus.Redirected, (second.first() as TurnUpdate.Accepted).status)
        val text = second.completed().text
        assertTrue(text, text.startsWith("Slow job finished for: slow job B") && text.contains("make it the blue one"))
        assertEquals(text, first.await().completed().text)
        // The correction is stored as a user row, as the real server's redirect does.
        val users = h.session(conv.storedSessionId!!).arr("messages").filter { it.str("role") == "user" }.map { it.str("text") }
        assertEquals(listOf("slow job B", "make it the blue one"), users)
    }

    @Test fun `interrupt style queue stops the live turn and runs the new text`() = runBlocking<Unit> {
        h.fake.post("/__fake/config", """{"interrupt_style": "queue"}""")
        val conv = h.conversation()
        val first = startTurn(conv, "hang forever")
        val second = conv.turn("do this instead").untilDone()
        assertEquals(SubmitStatus.Queued, (second.first() as TurnUpdate.Accepted).status)
        assertEquals("Done: do this instead", second.completed().text)
        assertEquals(TurnStatus.Interrupted, first.await().completed().status)
    }

    @Test fun `busy mode steer joins the live turn`() = runBlocking<Unit> {
        h.fake.post("/__fake/config", """{"busy_mode": "steer"}""")
        val conv = h.conversation()
        val first = startTurn(conv, "slow job C")
        val second = conv.turn("and add the red one").untilDone()
        assertEquals(SubmitStatus.Steered, (second.first() as TurnUpdate.Accepted).status)
        assertTrue(first.await().completed().text.contains("and add the red one"))
    }

    @Test fun `session interrupt ends the turn and is recorded`() = runBlocking<Unit> {
        val conv = h.conversation()
        val first = startTurn(conv, "hang forever")
        assertTrue(conv.interrupt())
        assertEquals(TurnStatus.Interrupted, first.await().completed().status)
        val interrupts = h.fake.get("/__fake/assert/interrupts")
        assertEquals(1, interrupts.int("count"))
        assertEquals(true, interrupts.arr("interrupts").single().bool("running"))
    }

    @Test fun `4009 session busy is retried by the client`() = runBlocking<Unit> {
        val conv = h.conversation()
        val first = startTurn(conv, "slow job D")
        h.faults("""{"busy_errors": 2}""")
        val second = conv.turn("after the busy errors", queued = true).untilDone()
        assertEquals(SubmitStatus.Queued, (second.first() as TurnUpdate.Accepted).status)
        first.await()
        val outcomes = h.prompts(conv.storedSessionId!!).map { it.str("outcome") }
        assertEquals(listOf("streaming", "error:4009", "error:4009", "queued"), outcomes)
    }
}
