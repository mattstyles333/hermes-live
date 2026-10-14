package com.folio.hermeslive.cascade

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.GatewayEvent
import com.folio.hermes.cascade.ports.SttEvent
import com.folio.hermeslive.audio.CascadeAudioFactory
import com.folio.hermeslive.audio.CascadeAudioIo
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.call.FocusMode
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bill behind the CallEngine ports (spec v2 §12 phase 2), on fakes and a virtual clock. No network, no mic. */
class CascadeCallEngineTest {
    private inner class Rig(val test: TestScope, settings: LiveSettings = LiveSettings(), runtimeOn: Boolean = true) : AutoCloseable {
        val job = SupervisorJob()
        val scope = CoroutineScope(test.coroutineContext + job)
        val rt = TestRuntime(scope)
        val speech = FakeSpeechFactory()
        val audio = FakeAudio()
        val effects = FakeEffects()
        val source = ToneSource()
        val out = SilentOut()
        val offCalls = mutableListOf<Pair<Int, Boolean>>()
        var released = 0
        lateinit var engine: CascadeCallEngine

        init {
            engine = CascadeCallEngine(
                scope = scope,
                settings = { settings },
                runtime = { if (runtimeOn) rt.runtime else null },
                speech = speech,
                audioFactory = { CascadeAudioFactory { _, _ -> CascadeAudioIo(source, out, out) { released++ } } },
                audio = audio,
                effects = effects,
                offCall = { running -> offCalls += running to engine.state.value.inCall; true },
                io = StandardTestDispatcher(test.testScheduler),
                clock = { test.testScheduler.currentTime },
            )
        }

        fun live() {
            engine.start(CallRequest(entry = "test"))
            test.runCurrent()
        }

        override fun close() = job.cancel()
    }

    private fun rig(settings: LiveSettings = LiveSettings(), runtimeOn: Boolean = true, block: suspend TestScope.(Rig) -> Unit) = runTest {
        Rig(this, settings, runtimeOn).use { block(it) }
    }

    @Test fun aCallPlugsTheMicAndSpeakerIntoTheRuntime() = rig { r ->
        r.engine.start(CallRequest(entry = "test"))
        assertEquals(CallPhase.Connecting, r.engine.state.value.phase)
        assertTrue("audio mode first", r.audio.began == 1)
        assertEquals(FocusMode.Hold, r.audio.focus.first())
        runCurrent()
        assertEquals(CallPhase.Live, r.engine.state.value.phase)
        val stt = r.speech.built.single()
        assertEquals(1, stt.started)
        assertSame(stt, r.rt.stt.attached)
        assertEquals(1, r.rt.gateway.connects)
        assertTrue(r.rt.core.actor.state.value.started)
        assertTrue(Earcon.Connected in r.effects.earcons)
        assertTrue("Parakeet 0.6B by default (the bench)", stt.highAccuracy)
    }

    @Test fun theSmallSecondPassWhenChosen() = rig(LiveSettings(accurateModel = com.folio.hermeslive.stt.SttCatalog.parakeet110m.id)) { r ->
        r.live()
        assertFalse(r.speech.built.single().highAccuracy)
    }

    @Test fun muteSilencesWhatTheSpeechModelsHear() = rig { r ->
        r.live()
        val source = r.speech.built.single().source
        val buffer = ShortArray(8)
        source.read(buffer)
        assertTrue(buffer.all { it.toInt() == 1000 })
        r.engine.toggleMute()
        assertTrue(r.engine.state.value.muted)
        source.read(buffer)
        assertTrue("muted: silence", buffer.all { it.toInt() == 0 })
        r.engine.toggleMute()
        source.read(buffer)
        assertTrue(buffer.all { it.toInt() == 1000 })
    }

    @Test fun hangUpStartsTheOffCallServiceWhileStillInTheCall() = rig { r ->
        r.live()
        r.rt.running.value = 2
        r.engine.end()
        assertEquals("asked while the call service is still foreground", listOf(2 to true), r.offCalls)
        assertEquals(CallPhase.Ended, r.engine.state.value.phase)
        runCurrent()
        assertEquals("mic and models released off the main thread", 1, r.speech.built.single().released)
        assertEquals(1, r.released)
        assertNull(r.rt.stt.attached)
        assertEquals(1, r.audio.ended)
        assertFalse(r.rt.core.actor.state.value.started)
    }

    /** OffCallHooks.holdGateway: the stored state is read back (core.restore) and the gateway stays open until released. */
    @Test fun theOffCallHoldRestoresAndKeepsTheGatewayOpen() = rig { r ->
        runCurrent()
        val before = r.rt.gateway.disconnects
        val hold = r.rt.runtime.holdGateway()
        runCurrent()
        assertEquals(1, r.rt.gateway.connects)
        assertTrue("restored without a call", r.rt.core.actor.state.value.restored)
        assertFalse(r.rt.core.actor.state.value.started)
        assertEquals(before, r.rt.gateway.disconnects)
        hold.close()
        assertEquals(before + 1, r.rt.gateway.disconnects)
        hold.close()
        assertEquals("closing twice releases once", before + 1, r.rt.gateway.disconnects)
    }

    @Test fun hangUpWithNothingRunningClosesTheGatewayAndStartsNoService() = rig { r ->
        r.live()
        r.engine.end()
        runCurrent()
        assertTrue(r.offCalls.isEmpty())
        assertTrue(r.rt.gateway.disconnects >= 1)
    }

    @Test fun approvalsAreCardsAndOnlyATapAnswersThem() = rig { r ->
        r.live()
        val request = ApprovalRequest("srq-1", "s1", "q1", "rm -rf build", "Delete the build folder", listOf(ApprovalChoice.Once), null, JsonObject(emptyMap()))
        r.rt.gateway.events.emit(GatewayEvent.ApprovalRequested(request))
        advanceTimeBy(CascadeCallEngine.TICK_MS + 1)
        runCurrent()
        assertEquals(listOf("srq-1"), r.engine.state.value.approvals.map { it.id })
        assertTrue("never answered by itself", r.rt.gateway.approved.isEmpty())
        r.engine.answerApproval("srq-1", ApprovalChoice.Once)
        advanceTimeBy(CascadeCallEngine.TICK_MS + 1)
        runCurrent()
        assertEquals(listOf("srq-1" to ApprovalChoice.Once), r.rt.gateway.approved)
        assertTrue(r.engine.state.value.approvals.isEmpty())
    }

    @Test fun holdToTalkGoesThroughThePipeline() = rig { r ->
        r.live()
        r.engine.holdToTalk(true)
        r.engine.holdToTalk(false)
        assertEquals(listOf(true, false), r.speech.built.single().holds)
    }

    @Test fun speechEventsReachTheActor() = rig { r ->
        r.live()
        val stt = r.speech.built.single()
        stt.channel.send(SttEvent.SpeechStarted)
        stt.channel.send(SttEvent.Partial("order more"))
        runCurrent()
        assertEquals("order more", r.rt.core.actor.ui.value.turn.partial)
    }

    @Test fun modelsThatFailToLoadFailTheCall() = rig { r ->
        r.speech.failWith = IllegalStateException("missing model files: encoder.onnx")
        r.live()
        assertEquals(CallPhase.Failed, r.engine.state.value.phase)
        assertTrue(r.engine.state.value.error!!.contains("Prepare Bill's voice"))
        assertEquals(1, r.audio.ended)
        assertNull(r.rt.stt.attached)
    }

    @Test fun noRuntimeMeansNoCall() = rig(runtimeOn = false) { r ->
        r.live()
        assertEquals(CallPhase.Failed, r.engine.state.value.phase)
        assertNotNull(r.engine.state.value.error)
        assertEquals("nothing touched the audio", 0, r.audio.began)
        assertTrue(r.speech.built.isEmpty())
    }

    @Test fun aSecondStartDuringTheCallStartsNothingNew() = rig { r ->
        r.live()
        r.engine.start(CallRequest(prompt = "typed text", muted = true))
        runCurrent()
        assertEquals(1, r.speech.built.size)
        assertTrue(r.engine.state.value.muted)
        assertNotNull(r.engine.state.value.notice)
    }

    /** Hanging up while the models load: the speech that finishes loading afterwards is released, never plugged in. */
    @Test fun hangingUpWhileBillIsStartingReleasesWhatFinishesLoading() = rig { r ->
        r.engine.start(CallRequest(entry = "test"))
        assertEquals(CallPhase.Connecting, r.engine.state.value.phase)
        r.engine.end()
        assertEquals(CallPhase.Ended, r.engine.state.value.phase)
        runCurrent()
        val stt = r.speech.built.single()
        assertEquals("started by the load", 1, stt.started)
        assertEquals("then released", 1, stt.released)
        assertEquals(1, r.released)
        assertNull("never plugged in", r.rt.stt.attached)
        assertEquals(CallPhase.Ended, r.engine.state.value.phase)
    }

    /** Folio's PROMPT (or shared text) is the Bill call's first turn, before the mic. */
    @Test fun aPromptAtStartIsTheFirstTurn() = rig { r ->
        r.engine.start(CallRequest(prompt = "  Check the Kestrel stock  ", entry = "folio"))
        runCurrent()
        assertEquals("Check the Kestrel stock", r.rt.core.actor.state.value.turn.heard)
        assertTrue("the pause runs: Jev decides as for speech", r.rt.core.actor.state.value.turn.deciding)
    }

    /** The no-call viewer (a "Needs you" notification's Open): a tap answers through the process's runtime. */
    @Test fun anEngineThatNeverStartedStillAnswersApprovals() = rig { r ->
        val request = ApprovalRequest("srq-9", "s1", "q9", "send", "Send the email", listOf(ApprovalChoice.Once), null, JsonObject(emptyMap()))
        runCurrent()
        r.rt.gateway.events.emit(GatewayEvent.ApprovalRequested(request))
        runCurrent()
        r.engine.answerApproval("srq-9", ApprovalChoice.Deny)
        runCurrent()
        assertEquals(listOf("srq-9" to ApprovalChoice.Deny), r.rt.gateway.approved)
        assertEquals("no call was started", 0, r.audio.began)
    }

    @Test fun hotwordsAreVocabularyPlusTaskLabels() = rig(LiveSettings(vocabulary = listOf("Halden"))) { r ->
        r.live()
        assertEquals(listOf("Halden"), r.speech.hotwords.single()())
    }
}
