package com.folio.hermes.live

import com.folio.hermes.HermesJson
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskView
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.openrouter.JevAnswer
import com.folio.hermes.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of GPT-Live's parallel agents, and the bridge's agents mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPartsTest {
    // ── the injection rule ──

    @Test fun resultsGoInOnlyInAQuietMomentAfterTheVoiceAnsweredTheUser() {
        val gate = InjectionGate()
        assertTrue("nothing has happened", gate.quiet(0, userSpeaking = false, voiceSpeaking = false))
        assertFalse(gate.quiet(0, userSpeaking = true, voiceSpeaking = false))
        assertFalse(gate.quiet(0, userSpeaking = false, voiceSpeaking = true))
        gate.user(10_000)
        assertFalse("settling", gate.quiet(11_000, false, false))
        assertFalse("the voice has not answered that turn yet", gate.quiet(12_000, false, false))
        assertTrue("…or the grace ran out", gate.quiet(13_000, false, false))
        gate.user(20_000)
        gate.voice(20_500)
        assertFalse("the voice was just speaking", gate.quiet(21_000, false, false))
        assertTrue(gate.quiet(21_400, false, false))
    }

    @Test fun oneBatchAtATimeUntilTheVoiceTakesItUp() {
        val gate = InjectionGate()
        gate.injected(1_000)
        assertFalse(gate.quiet(2_000, false, false))
        assertTrue("taken up", gate.voice(2_500))
        assertFalse("only the first output counts", gate.voice(2_600))
        assertTrue(gate.quiet(3_500, false, false))
        gate.injected(4_000)
        assertTrue("not taken up: the next may go after the wait", gate.quiet(12_000, false, false))
    }

    // ── labels and briefs ──

    @Test fun heuristicLabelsDropThePoliteLeadIn() {
        assertEquals("Check stock RB2140", AgentText.heuristicLabel("Can you check the stock of RB2140 in Leeds?"))
        assertEquals("Email Safilo late", AgentText.heuristicLabel("Hey Hermes, could you please email Safilo about the late order"))
        assertEquals("Book table", AgentText.heuristicLabel("I need you to book a table"))
        assertEquals("Task", AgentText.heuristicLabel("  "))
    }

    @Test fun writerLabelsAreCleanedOrFallBack() = runTest {
        assertEquals("Stock check", AgentText.cleanLabel("\"Stock check.\""))
        assertEquals("Safilo reorder email", AgentText.cleanLabel("LABEL: Safilo reorder email\nmore"))
        assertNull(AgentText.cleanLabel("Here is a long sentence that is not a label at all really"))
        val prompt = DelegationPrompt("Can you check the stock of RB2140?", "User: Can you check the stock of RB2140?")
        assertEquals("Stock check", WriterLabeler({ _, _ -> "Stock check" }).label(prompt))
        assertEquals("Check stock RB2140", WriterLabeler({ _, _ -> null }).label(prompt))
        assertEquals("Check stock RB2140", WriterLabeler({ _, _ -> error("offline") }).label(prompt))
        assertEquals("timeout", "Check stock RB2140", WriterLabeler({ _, _ -> kotlinx.coroutines.delay(10_000); "Late" }, timeoutMs = 100).label(prompt))
    }

    @Test fun theBriefIsTheRequestPlusTheRecentConversation() {
        assertEquals("What's on today?", AgentText.brief(DelegationPrompt("What's on today?", "User: What's on today?")))
        val brief = AgentText.brief(DelegationPrompt("Check that one", "User: Find RB2140 frames\nVoice assistant: Found two.\nUser: Check that one"))
        assertTrue(brief.startsWith("Check that one\n\n[From a live voice call. Recent spoken conversation, newest last:\nUser: Find RB2140 frames"))
    }

    // ── classification ──

    private fun view(id: String, label: String, status: String = "running") =
        TaskView(id, label, status, "started", elapsedS = 10, steps = 0, currentTool = null, lastNote = null, hasResult = false, unread = false)

    private val two = listOf(view("t1", "Stock check"), view("t2", "Safilo email"))

    @Test fun theWordsAloneCatchClearStopAndStatusRequests() = runTest {
        val h = HeuristicAgentIntents
        fun p(text: String) = DelegationPrompt(text, "User: $text")
        assertEquals(AgentIntent.New, h.classify(p("Cancel my dentist appointment"), emptyList()))
        assertEquals(AgentIntent.Cancel("t1"), h.classify(p("Cancel the stock check"), two))
        assertEquals(AgentIntent.Cancel(CascadeTasks.ALL), h.classify(p("Stop all of them"), two))
        assertEquals(AgentIntent.Status("t2"), h.classify(p("How's the Safilo email going?"), two))
        assertEquals(AgentIntent.Status(null), h.classify(p("Any news?"), two))
        assertTrue(h.classify(p("Cancel it"), two) is AgentIntent.Ask)
        assertEquals(AgentIntent.Cancel("t1"), h.classify(p("Cancel it"), listOf(view("t1", "Stock check"))))
        assertEquals("long requests are work", AgentIntent.New, h.classify(p("Stop by the Leeds branch tomorrow and check whether the new display stands arrived with the frames"), two))
    }

    @Test fun jevFloorsKeepAWeakCancelFromStoppingAnything() {
        val jev = JevAgentIntents({ _, _, _ -> error("unused") })
        val prompt = DelegationPrompt("drop the email", "User: drop the email")
        fun a(choice: String, c: Double) = JevAnswer("choice", choice, confidence = c)
        assertEquals(AgentIntent.Cancel("t2"), jev.decide(mapOf("action" to a("cancel_task", 0.8), "task" to a("t2", 0.7)), prompt, two))
        assertEquals("a weak cancel is new work", AgentIntent.New, jev.decide(mapOf("action" to a("cancel_task", 0.5), "task" to a("t2", 0.9)), prompt, two))
        assertTrue("no task named, two open", jev.decide(mapOf("action" to a("cancel_task", 0.9), "task" to a("none", 0.9)), prompt, two) is AgentIntent.Ask)
        assertEquals(AgentIntent.Status(null), jev.decide(mapOf("action" to a("status", 0.6), "task" to a("none", 0.9)), prompt, two))
        assertEquals(AgentIntent.Update("t1", UpdateMode.Redirect), jev.decide(mapOf("action" to a("update_task", 0.6), "task" to a("t1", 0.9), "update_mode" to a("redirect", 0.8)), prompt, two))
        assertEquals(AgentIntent.New, jev.decide(mapOf("action" to a("new_task", 0.9)), prompt, two))
        assertEquals("a reply still goes to Hermes (the model delegated it)", AgentIntent.New, jev.decide(mapOf("action" to a("reply", 0.9)), prompt, two))
    }

    // ── history on reopen ──

    @Test fun aReopenedSessionGetsTheTranscriptAndAnOpenTasksNoteWithinTwentyFour() {
        val turns = (1..40).map { LiveTurn(if (it % 2 == 0) Speaker.Assistant else Speaker.User, "turn $it") }
        val history = VoiceLiveApi.history(turns, note = "Tasks running: 'Stock check' (working).")
        assertEquals(VoiceLiveApi.MAX_MESSAGES, history.size)
        assertEquals("user", history.last().role)
        assertEquals("[Note from the app, not said by the user] Tasks running: 'Stock check' (working).", history.last().content.single().text)
        assertEquals("turn 40", history[history.size - 2].content.single().text)
        assertTrue(history.sumOf { it.content.single().text.length } <= VoiceLiveApi.MAX_CHARS)
        assertEquals(VoiceLiveApi.history(turns), VoiceLiveApi.history(turns, note = null))
    }

    // ── the bridge in agents mode ──

    private class FakeAgents : DelegationAgents {
        val got = mutableListOf<Pair<String?, DelegationPrompt>>()
        override var working = false
        override fun onDelegation(delegationId: String?, prompt: DelegationPrompt) {
            got += delegationId to prompt
        }
    }

    private class NoBackend : DelegationBackend {
        var calls = 0
        override fun delegate(prompt: String, voiceContext: String, interruptFirst: Boolean): Flow<TurnUpdate> {
            calls++
            return flow { }
        }
    }

    @Test fun withAgentsEveryDelegationGoesToThemAndNothingIsInterrupted() = runTest {
        val agents = FakeAgents()
        val backend = NoBackend()
        val sent = mutableListOf<JsonObject>()
        val events = mutableListOf<BridgeEvent>()
        val bridge = DelegationBridge(backgroundScope, backend, { sent += HermesJson.parseToJsonElement(it).jsonObject; true }, BridgeSettings(idleHangUpMs = 60_000), agents = agents) { events += it }
        bridge.onDataChannelMessage("""{"type":"session.started","session":{"id":"s"}}""")
        bridge.onDataChannelMessage("""{"type":"session.input_transcript.delta","delta":"Check stock of RB2140","start_ms":0,"end_ms":900}""")
        bridge.onDataChannelMessage("""{"type":"session.delegation.created","delegation":{"id":"del_1"}}""")
        bridge.onDataChannelMessage("""{"type":"session.input_transcript.delta","delta":" and email Safilo","start_ms":1000,"end_ms":1900}""")
        bridge.onDataChannelMessage("""{"type":"session.delegation.created","delegation":{"id":"del_2"}}""")
        bridge.delegateText("Book a table")
        runCurrent()
        assertEquals(0, backend.calls)
        assertEquals(listOf("del_1", "del_2", null), agents.got.map { it.first })
        assertEquals("Check stock of RB2140", agents.got[0].second.prompt)
        assertEquals(BridgeStatus.Listening, bridge.status.value)
        assertTrue(events.filterIsInstance<BridgeEvent.DelegationStarted>().size == 2)

        // The voice port speaks on a delegation or as an instruction.
        bridge.voice.say("del_1", "Task 'Stock check' finished: 4 in Leeds.")
        bridge.voice.say(null, "Task 'Stock check' finished.")
        bridge.voice.think("del_2", "Task 'Safilo email' is working: send email. Not done yet.")
        assertEquals("del_1", sent.last { it.string("type") == "session.commentary.append" }.string("delegation_id"))
        assertTrue(sent.last { it.string("type") == "session.instructions.append" }.string("content")!!.contains("Task 'Stock check' finished."))
        assertEquals("del_2", sent.last { it.string("type") == "session.thinking.append" }.string("delegation_id"))

        // No idle hang-up while agents work; once they stop, the quiet counts again.
        agents.working = true
        advanceTimeBy(150_000)
        assertFalse(events.any { it is BridgeEvent.Stopping })
        agents.working = false
        advanceTimeBy(61_000)
        assertTrue(events.any { it is BridgeEvent.Stopping && it.reason == StopReason.IdleTimeout })
    }
}
