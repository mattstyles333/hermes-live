package com.folio.hermes.live

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.HermesJson
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObject as Obj
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelegationBridgeTest {
    private class FakeBackend : DelegationBackend {
        data class Call(val prompt: String, val context: String, val interruptFirst: Boolean)
        val calls = mutableListOf<Call>()
        val channels = mutableListOf<Channel<TurnUpdate>>()
        var failWith: Throwable? = null

        override fun delegate(prompt: String, voiceContext: String, interruptFirst: Boolean): Flow<TurnUpdate> {
            calls += Call(prompt, voiceContext, interruptFirst)
            failWith?.let { error -> return flow { throw error } }
            val channel = Channel<TurnUpdate>(Channel.UNLIMITED)
            channels += channel
            return channel.consumeAsFlow()
        }
    }

    private class Rig(scope: CoroutineScope, settings: BridgeSettings = BridgeSettings()) {
        val backend = FakeBackend()
        val sent = mutableListOf<JsonObject>()
        val events = mutableListOf<BridgeEvent>()
        val bridge = DelegationBridge(scope, backend, { sent += HermesJson.parseToJsonElement(it).jsonObject; true }, settings) { events += it }

        fun server(json: String) = bridge.onDataChannelMessage(json)
        fun started() = server("""{"type":"session.started","session":{"id":"sess_1"}}""")
        fun user(text: String, end: Long) = server("""{"type":"session.input_transcript.delta","delta":"$text","start_ms":${end - 200},"end_ms":$end}""")
        fun assistant(text: String, end: Long) = server("""{"type":"session.output_transcript.delta","delta":"$text","start_ms":${end - 200},"end_ms":$end}""")
        fun delegation(id: String) = server("""{"type":"session.delegation.created","delegation":{"id":"$id","type":"client","target":"backend"}}""")
        fun sentOf(type: String) = sent.filter { it.string("type") == type }
        fun contents(type: String) = sentOf(type).map { it.string("content") }
    }

    private fun TestScope.rig(settings: BridgeSettings = BridgeSettings()) = Rig(backgroundScope, settings)

    @Test fun delegationStreamsSentencesToolsAndTheTail() = runTest {
        val r = rig()
        r.started()
        r.user("What's the weather ", 1_000)
        r.user("in Leeds?", 1_400)
        r.assistant("Let me ask Hermes.", 2_000)
        r.delegation("del_1")
        runCurrent()
        assertEquals(BridgeStatus.Thinking, r.bridge.status.value)
        val call = r.backend.calls.single()
        assertEquals("What's the weather in Leeds?", call.prompt)
        assertEquals("User: What's the weather in Leeds?\nVoice assistant: Let me ask Hermes.", call.context)
        assertEquals(false, call.interruptFirst)

        val turn = r.backend.channels.single()
        turn.send(TurnUpdate.Accepted("rt1", SubmitStatus.Streaming))
        turn.send(TurnUpdate.Started)
        turn.send(TurnUpdate.ToolStarted("c1", "web_search", "web search"))
        turn.send(TurnUpdate.ToolStarted("c2", "web_search", "web search"))
        turn.send(TurnUpdate.Text("It is **sunny**", "It is **sunny**"))
        runCurrent()
        assertEquals(listOf("Hermes is working: web search. Not done yet."), r.contents("session.thinking.append"))
        assertTrue(r.sentOf("session.commentary.append").isEmpty())
        turn.send(TurnUpdate.Text(". Highs of 18", "It is **sunny**. Highs of 18"))
        runCurrent()
        assertEquals(listOf("It is sunny."), r.contents("session.commentary.append"))
        turn.send(TurnUpdate.Completed("It is **sunny**. Highs of 18.", TurnStatus.Complete, null))
        runCurrent()
        assertEquals(listOf("It is sunny.", "Highs of 18."), r.contents("session.commentary.append"))
        assertTrue(r.sentOf("session.commentary.append").all { it.string("delegation_id") == "del_1" })
        assertEquals(BridgeStatus.Listening, r.bridge.status.value)
        assertTrue(BridgeEvent.DelegationSettled("del_1", spoke = true) in r.events)
    }

    @Test fun aNewDelegationInterruptsAndStaleResultsAreDropped() = runTest {
        val r = rig()
        r.started()
        r.user("Check the build.", 1_000)
        r.delegation("del_1")
        runCurrent()
        r.assistant("Checking.", 2_000)
        r.user("No, check the tests.", 5_000)
        r.delegation("del_2")
        runCurrent()
        assertEquals(listOf(false, true), r.backend.calls.map { it.interruptFirst })
        assertEquals("No, check the tests.", r.backend.calls[1].prompt)
        r.backend.channels[0].trySend(TurnUpdate.Completed("Build is green.", TurnStatus.Complete, null))
        r.backend.channels[1].send(TurnUpdate.Completed("Tests pass.", TurnStatus.Complete, null))
        runCurrent()
        assertEquals(listOf("Tests pass."), r.contents("session.commentary.append"))
        assertTrue(r.sent.none { it.string("delegation_id") == "del_1" })
    }

    @Test fun failuresApologise() = runTest {
        val r = rig()
        r.started()
        r.user("Do it.", 1_000)
        r.delegation("del_1")
        runCurrent()
        r.backend.channels.single().send(TurnUpdate.Failed("Lost the connection to Hermes."))
        runCurrent()
        assertEquals(listOf("Sorry, I could not reach Hermes for that request."), r.contents("session.commentary.append"))
        assertTrue(r.events.any { it is BridgeEvent.DelegationFailed })

        r.backend.failWith = java.io.IOException("offline")
        r.user("Again.", 3_000)
        r.delegation("del_2")
        runCurrent()
        assertEquals(2, r.contents("session.commentary.append").size)
        assertEquals("del_2", r.sentOf("session.commentary.append").last().string("delegation_id"))
    }

    @Test fun silentTurnsSayNothingButTellTheVoice() = runTest {
        val r = rig()
        r.started()
        r.user("Restart the container.", 1_000)
        r.delegation("del_1")
        runCurrent()
        r.backend.channels.single().send(TurnUpdate.Completed("", TurnStatus.Complete, null))
        runCurrent()
        assertTrue(r.sentOf("session.commentary.append").isEmpty())
        assertEquals(listOf("Hermes finished that request without a spoken result."), r.contents("session.thinking.append"))
    }

    @Test fun graceDropsADelegationThatNeverStarts() = runTest {
        val r = rig()
        r.started()
        r.user("Hello?", 1_000)
        r.delegation("del_1")
        runCurrent()
        advanceTimeBy(14_999)
        assertEquals(BridgeStatus.Thinking, r.bridge.status.value)
        advanceTimeBy(2)
        assertEquals(BridgeStatus.Listening, r.bridge.status.value)
        assertEquals(listOf("Hermes finished that request without a spoken result."), r.contents("session.thinking.append"))
        r.backend.channels.single().send(TurnUpdate.Completed("Too late.", TurnStatus.Complete, null))
        runCurrent()
        assertTrue(r.sentOf("session.commentary.append").isEmpty())
    }

    @Test fun spokenStopEndsTheCallAfterTheUtteranceSettles() = runTest {
        val r = rig()
        r.started()
        r.user("Stop the docker container", 1_000)
        advanceTimeBy(1_600)
        assertTrue(r.sentOf("session.close").isEmpty())
        r.user("Okay, ", 3_000)
        r.user("goodbye.", 3_300)
        advanceTimeBy(1_000)
        assertTrue(r.sentOf("session.close").isEmpty())
        r.user("", 3_400) // a later fragment restarts the settle clock
        advanceTimeBy(1_501)
        assertEquals(1, r.sentOf("session.close").size)
        assertTrue(BridgeEvent.Stopping(StopReason.SpokenStop) in r.events)
        r.server("""{"type":"session.closed","reason":"client_closed","usage":{"seconds":42}}""")
        assertEquals(BridgeEvent.Closed("client_closed", 42.0), r.events.last())
        assertEquals(BridgeStatus.Closed, r.bridge.status.value)
    }

    @Test fun aDelegatedStopPhraseEndsTheCallInsteadOfSubmitting() = runTest {
        val r = rig()
        r.started()
        r.user("Hermes, never mind.", 1_000)
        r.delegation("del_1")
        runCurrent()
        assertTrue(r.backend.calls.isEmpty())
        assertEquals(1, r.sentOf("session.close").size)
    }

    @Test fun closeTearsDownAfterTheTimeout() = runTest {
        val r = rig()
        r.started()
        r.bridge.close()
        assertEquals(BridgeStatus.Closing, r.bridge.status.value)
        advanceTimeBy(15_001)
        assertEquals(BridgeEvent.Closed("close_requested", null), r.events.last())
    }

    @Test fun idleCallsHangUpButNotWhileHermesWorks() = runTest {
        val r = rig()
        r.started()
        r.user("Plan my week.", 1_000)
        r.delegation("del_1")
        runCurrent()
        r.backend.channels.single().send(TurnUpdate.Accepted("rt1", SubmitStatus.Streaming))
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        assertTrue(r.sentOf("session.close").isEmpty())
        r.backend.channels.single().send(TurnUpdate.Completed("Done.", TurnStatus.Complete, null))
        runCurrent()
        advanceTimeBy(3 * 60_000L - 1)
        assertTrue(r.sentOf("session.close").isEmpty())
        advanceTimeBy(2)
        assertEquals(1, r.sentOf("session.close").size)
        assertTrue(BridgeEvent.Stopping(StopReason.IdleTimeout) in r.events)
    }

    @Test fun callCapWarnsThenEnds() = runTest {
        val r = rig(BridgeSettings(idleHangUpMs = 0))
        r.started()
        advanceTimeBy(29 * 60_000L + 1)
        assertTrue(BridgeEvent.CapWarning in r.events)
        val warning = r.sentOf("session.commentary.append").single()
        assertEquals(JsonNull, warning["delegation_id"])
        assertTrue(r.sentOf("session.close").isEmpty())
        advanceTimeBy(60_000L)
        assertEquals(1, r.sentOf("session.close").size)
        assertTrue(BridgeEvent.Stopping(StopReason.CallCap) in r.events)
    }

    @Test fun approvalsAreShownNeverAnswered() = runTest {
        val r = rig()
        r.started()
        r.user("Delete the logs.", 1_000)
        r.delegation("del_1")
        runCurrent()
        val request = ApprovalRequest("srq-1", "rt1", "apr-1", "rm logs", "", listOf(ApprovalChoice.Once, ApprovalChoice.Deny), "terminal", Obj(emptyMap()))
        r.backend.channels.single().send(TurnUpdate.ApprovalNeeded(request))
        runCurrent()
        assertEquals(BridgeEvent.ApprovalNeeded(request), r.events.filterIsInstance<BridgeEvent.ApprovalNeeded>().single())
        assertEquals(listOf("Hermes needs the user to approve a step on screen. Not done yet."), r.contents("session.thinking.append"))
        assertEquals(setOf("session.thinking.append"), r.sent.map { it.string("type") }.toSet())
    }

    @Test fun vendorErrorsAndTextDelegations() = runTest {
        val r = rig()
        r.started()
        r.server("""{"type":"error","error":{"code":"context_injection_incomplete","message":"late"}}""")
        assertTrue(r.events.none { it is BridgeEvent.LiveError })
        r.server("""{"type":"error","error":{"code":"rate_limited","message":"slow down"}}""")
        assertEquals(BridgeEvent.LiveError("slow down"), r.events.last())

        r.bridge.delegateText("Read my latest email")
        runCurrent()
        assertEquals("Read my latest email", r.backend.calls.single().prompt)
        r.backend.channels.single().send(TurnUpdate.Completed("You have one new email.", TurnStatus.Complete, null))
        runCurrent()
        assertEquals(JsonNull, r.sentOf("session.commentary.append").single()["delegation_id"])

        r.bridge.setMuted(true)
        assertEquals("session.input_audio.mute", r.sent.last().string("type"))
        r.bridge.nudge()
        assertEquals("session.instructions.append", r.sent.last().string("type"))
    }

    private val goodbye = BridgeSettings(goodbyeMaxMs = 4_000, idleWarningMs = 30_000)

    @Test fun spokenStopSaysGoodbyeThenClosesWhenTheVoiceGoesQuiet() = runTest {
        val r = rig(goodbye)
        r.started()
        r.user("Goodbye.", 1_000)
        advanceTimeBy(1_501)
        assertEquals(listOf("Say a brief goodbye."), r.contents("session.instructions.append"))
        assertTrue(r.sentOf("session.close").isEmpty())
        assertEquals(BridgeStatus.Closing, r.bridge.status.value)
        r.assistant("Bye for now!", 3_000)
        r.bridge.setRemoteSpeaking(true)
        advanceTimeBy(1_000)
        assertTrue(r.sentOf("session.close").isEmpty())
        r.bridge.setRemoteSpeaking(false)
        advanceTimeBy(600)
        assertTrue(r.sentOf("session.close").isEmpty())
        advanceTimeBy(200)
        assertEquals(1, r.sentOf("session.close").size)
        val types = r.sent.map { it.string("type") }
        assertTrue(types.indexOf("session.instructions.append") < types.indexOf("session.close"))
        r.server("""{"type":"session.closed","reason":"client_closed","usage":{"seconds":9}}""")
        assertEquals(BridgeEvent.Closed("client_closed", 9.0), r.events.last())
    }

    @Test fun goodbyeIsCappedAtItsLimitAndCloseStillWaitsForClosed() = runTest {
        val r = rig(goodbye)
        r.started()
        r.user("Hermes, that's all.", 1_000)
        r.delegation("del_1")
        runCurrent()
        assertTrue(r.backend.calls.isEmpty())
        advanceTimeBy(3_950)
        assertTrue(r.sentOf("session.close").isEmpty())
        advanceTimeBy(100)
        assertEquals(1, r.sentOf("session.close").size)
        advanceTimeBy(14_900)
        assertEquals(BridgeStatus.Closing, r.bridge.status.value)
        advanceTimeBy(200)
        assertEquals(BridgeEvent.Closed("close_requested", null), r.events.last())
    }

    @Test fun theEndButtonSkipsAGoodbyeInProgress() = runTest {
        val r = rig(goodbye)
        r.started()
        r.user("Bye.", 1_000)
        advanceTimeBy(1_501)
        assertTrue(r.sentOf("session.close").isEmpty())
        r.bridge.close()
        assertEquals(1, r.sentOf("session.close").size)
        r.bridge.close()
        advanceTimeBy(5_000)
        assertEquals(1, r.sentOf("session.close").size)
    }

    @Test fun idleWarnsAloudThenSaysGoodbyeAndHangsUp() = runTest {
        val r = rig(goodbye)
        r.started()
        advanceTimeBy(150_001)
        assertTrue(BridgeEvent.IdleWarning in r.events)
        assertTrue(r.contents("session.commentary.append").single()!!.contains("30 seconds"))
        r.user("Still here.", 160_000)
        assertTrue(BridgeEvent.IdleWarningCleared in r.events)
        advanceTimeBy(60_000)
        assertTrue(r.sentOf("session.instructions.append").isEmpty())
        advanceTimeBy(150_000)
        assertEquals(2, r.events.count { it == BridgeEvent.IdleWarning })
        advanceTimeBy(30_000)
        assertEquals(listOf("Say a brief goodbye."), r.contents("session.instructions.append"))
        assertTrue(BridgeEvent.Stopping(StopReason.IdleTimeout) in r.events)
        advanceTimeBy(4_100)
        assertEquals(1, r.sentOf("session.close").size)
    }

    @Test fun capSaysGoodbyeFirstWhenAsked() = runTest {
        val r = rig(BridgeSettings(idleHangUpMs = 0, goodbyeMaxMs = 4_000))
        r.started()
        advanceTimeBy(30 * 60_000L + 1)
        assertEquals(listOf("Say a brief goodbye."), r.contents("session.instructions.append"))
        assertTrue(r.sentOf("session.close").isEmpty())
        advanceTimeBy(4_100)
        assertEquals(1, r.sentOf("session.close").size)
    }

    @Test fun anInterceptorCanConsumeADelegation() = runTest {
        val r = rig()
        val seen = mutableListOf<String>()
        r.bridge.delegationInterceptor = { prompt -> seen += prompt.prompt; prompt.prompt.startsWith("Blue") }
        r.started()
        r.user("Paint the fence.", 1_000)
        r.delegation("del_1")
        runCurrent()
        assertEquals(1, r.backend.calls.size)
        r.assistant("Which colour?", 2_000)
        r.user("Blue, please.", 3_000)
        r.delegation("del_2")
        runCurrent()
        assertEquals(listOf("Paint the fence.", "Blue, please."), seen)
        assertEquals(1, r.backend.calls.size)
        assertEquals(BridgeStatus.Thinking, r.bridge.status.value)
        r.backend.channels.single().send(TurnUpdate.Completed("Painted blue.", TurnStatus.Complete, null))
        runCurrent()
        assertEquals(listOf("Painted blue."), r.contents("session.commentary.append"))
        assertTrue(r.sentOf("session.thinking.append").isEmpty())
    }

    @Test fun toolsAreReportedAndTheAppCanSteerTheVoice() = runTest {
        val r = rig()
        r.started()
        r.user("Search it.", 1_000)
        r.delegation("del_1")
        runCurrent()
        r.backend.channels.single().send(TurnUpdate.ToolStarted("t1", "web_search", "web search"))
        runCurrent()
        assertTrue(BridgeEvent.ToolStarted("del_1", "web search") in r.events)
        r.bridge.noteProgress("Still going.")
        assertEquals("del_1", r.sentOf("session.thinking.append").last().string("delegation_id"))
        r.bridge.instruct("Speak slowly.")
        assertEquals(listOf("Speak slowly."), r.contents("session.instructions.append"))
    }

    @Test fun defaultsKeepTheOldImmediateClose() = runTest {
        val r = rig()
        r.started()
        r.user("Stop.", 1_000)
        advanceTimeBy(1_501)
        assertEquals(1, r.sentOf("session.close").size)
        assertTrue(r.sentOf("session.instructions.append").isEmpty())
    }
}
