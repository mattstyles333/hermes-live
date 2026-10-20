package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyQuestion
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.HermesAuthException
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.live.Speaker
import com.folio.hermeslive.data.LiveSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallEngineTest {
    private class Rig(scope: TestScope, var settings: LiveSettings, memory: SessionMemory) {
        val links = mutableListOf<FakeLink>()
        val resumed = mutableListOf<String?>()
        val transports = mutableListOf<FakeTransport>()
        val audio = FakeAudio()
        val effects = FakeEffects()
        val memory = FakeMemory(memory)
        var linkError: Exception? = null
        var online = true
        val engine = CallEngine(
            scope = scope.backgroundScope,
            settings = { settings },
            links = { stored -> resumed += stored; FakeLink().also { l -> l.prepareError = linkError; links += l } },
            transports = { events -> FakeTransport(events).also { transports += it } },
            audio = audio,
            effects = effects,
            memory = this.memory,
            clock = { scope.testScheduler.currentTime },
            online = { online },
        )
        val t: FakeTransport get() = transports.last()
        val link: FakeLink get() = links.last()
        val state: CallUiState get() = engine.state.value
    }

    private fun TestScope.rig(
        settings: LiveSettings = LiveSettings(),
        memory: SessionMemory = SessionMemory.Empty,
    ) = Rig(this, settings, memory)

    private fun TestScope.live(r: Rig, request: CallRequest = CallRequest()) {
        r.engine.start(request)
        runCurrent()
        r.t.started()
        runCurrent()
    }

    @Test fun connectTalkDelegateAndEnd() = runTest {
        val r = rig()
        r.engine.start()
        assertEquals(CallPhase.Connecting, r.state.phase)
        runCurrent()
        assertEquals(listOf(35 * 60_000L), r.link.prepared)
        assertTrue(r.link.histories.single().isEmpty())
        assertEquals(1, r.audio.began)
        assertEquals(FocusMode.Hold, r.audio.focus.first())
        assertEquals(FakeAudio.SPEAKER, r.state.route)
        r.t.started()
        runCurrent()
        assertEquals(CallPhase.Live, r.state.phase)
        assertEquals(listOf(Earcon.Connected), r.effects.earcons)

        r.t.user("What's on today?", 1_000)
        r.t.assistant("Let me check.", 1_500)
        r.t.delegation("del_1")
        runCurrent()
        assertEquals(CallActivity.HermesSpeaking, r.state.activity)
        assertEquals("What's on today?", r.link.backend.calls.single().prompt)
        val turn = r.link.backend.channels.single()
        turn.send(TurnUpdate.Accepted("rt", SubmitStatus.Streaming))
        turn.send(TurnUpdate.ToolStarted("t1", "calendar_read", "calendar read"))
        runCurrent()
        assertEquals("calendar read", r.state.tool)
        advanceTimeBy(800)
        assertEquals(CallActivity.Thinking, r.state.activity)
        turn.send(TurnUpdate.Completed("Two meetings.", TurnStatus.Complete, null))
        runCurrent()
        advanceTimeBy(400) // commentary lead (music was ducked in the gap)
        assertTrue("Two meetings." in r.t.contents("session.commentary.append"))
        assertEquals(listOf(Caption(Speaker.User, "What's on today?"), Caption(Speaker.Assistant, "Let me check.")), r.state.captions)

        advanceTimeBy(60_000)
        r.engine.end()
        assertEquals(CallPhase.Ending, r.state.phase)
        runCurrent()
        assertEquals("session.close", r.t.typesSent().last())
        r.t.closedBy("client_closed", 72.0)
        runCurrent()
        assertEquals(CallPhase.Ended, r.state.phase)
        val summary = r.state.summary!!
        assertEquals(72.0, summary.voiceSeconds!!, 0.0)
        assertEquals(0.06, summary.estimatedCostUsd, 1e-9)
        assertEquals("Call ended.", summary.reason)
        assertTrue(r.t.closed)
        assertEquals(1, r.audio.ended)
        assertEquals(1, r.link.closed)
        assertEquals(Earcon.Ended, r.effects.earcons.last())
        assertEquals("stored-1", r.memory.memory.storedSessionId)
    }

    @Test fun endWaitsForSessionClosedThenTearsDownAfter15s() = runTest {
        val r = rig()
        live(r)
        r.engine.end()
        runCurrent()
        advanceTimeBy(14_900)
        assertEquals(CallPhase.Ending, r.state.phase)
        assertFalse(r.t.closed)
        advanceTimeBy(200)
        assertEquals(CallPhase.Ended, r.state.phase)
        assertTrue(r.t.closed)
        assertEquals(1, r.audio.ended)
    }

    @Test fun aDroppedLineReconnectsOnceWithHistory() = runTest {
        val r = rig()
        live(r)
        r.t.user("Remind me at five.", 1_000)
        r.t.assistant("Sure, passing that on.", 2_000)
        r.t.delegation("del_1")
        runCurrent()
        val first = r.t
        first.events.onLost("ice failed")
        runCurrent()
        assertEquals(CallPhase.Reconnecting, r.state.phase)
        assertTrue(first.closed)
        advanceTimeBy(700)
        assertEquals(2, r.transports.size)
        val history = r.link.histories.last()
        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals("Remind me at five.", history.first().content.single().text)
        r.t.started()
        runCurrent()
        assertEquals(CallPhase.Live, r.state.phase)
        assertEquals("Reconnected.", r.state.notice)
        assertTrue(r.t.contents("session.instructions.append").single().contains("cut off"))
        assertEquals(2, r.state.captions.size)

        r.t.events.onLost("again")
        runCurrent()
        assertEquals(CallPhase.Ended, r.state.phase)
        assertEquals("The connection dropped.", r.state.summary!!.reason)
        assertEquals(1, r.audio.ended)
    }

    @Test fun idleWarnsThenSaysGoodbyeAndHangsUp() = runTest {
        val r = rig()
        live(r)
        advanceTimeBy(150_100)
        assertTrue(r.state.warning!!.contains("30 seconds"))
        assertTrue(Earcon.Warning in r.effects.earcons)
        advanceTimeBy(30_000)
        assertEquals(CallPhase.Ending, r.state.phase)
        val said = r.t.contents("session.instructions.append")
        assertTrue(said.first().contains("hang up in 30 seconds"))
        assertEquals("Say a brief goodbye.", said.last())
        assertFalse("session.close" in r.t.typesSent())
        advanceTimeBy(4_200)
        assertEquals("session.close", r.t.typesSent().last())
        r.t.closedBy("client_closed", 180.0)
        runCurrent()
        assertEquals(CallPhase.Ended, r.state.phase)
    }

    @Test fun callCapWarnsAtOneMinuteAndEnds() = runTest {
        val r = rig(LiveSettings(idleTimeoutMinutes = 0, callCapMinutes = 10))
        live(r)
        advanceTimeBy(9 * 60_000L + 100)
        assertTrue(r.state.warning!!.contains("1 minute"))
        assertTrue(r.t.contents("session.commentary.append").isEmpty())
        assertTrue(r.t.contents("session.instructions.append").single().contains("one minute"))
        advanceTimeBy(60_000)
        assertEquals(CallPhase.Ending, r.state.phase)
        advanceTimeBy(4_200)
        assertEquals("session.close", r.t.typesSent().last())
    }

    @Test fun aStopPhraseSaysGoodbyeThenCloses() = runTest {
        val r = rig()
        live(r)
        r.t.user("Okay, goodbye.", 1_000)
        advanceTimeBy(1_600)
        assertEquals("Saying goodbye…", r.state.notice)
        assertEquals(listOf("Say a brief goodbye."), r.t.contents("session.instructions.append"))
        r.t.assistant("Bye!", 3_000)
        r.t.events.onRemoteLevel(0.3)
        advanceTimeBy(300)
        r.t.events.onRemoteLevel(0.0)
        advanceTimeBy(2_000)
        assertEquals("session.close", r.t.typesSent().last())
    }

    @Test fun signedOutNeverDials() = runTest {
        val r = rig()
        r.linkError = HermesAuthException.SignInRequired()
        r.engine.start()
        runCurrent()
        assertEquals(CallPhase.Failed, r.state.phase)
        assertEquals("Sign in to Hermes again.", r.state.error)
        assertTrue(r.transports.isEmpty())
        assertEquals(1, r.audio.ended)
        assertNull(r.state.summary)
    }

    @Test fun musicPlaysInTheGapsAndStopsForTheAnswer() = runTest {
        val r = rig()
        r.audio.available = listOf(FakeAudio.EARPIECE, FakeAudio.SPEAKER, FakeAudio.BUDS)
        live(r)
        assertEquals(FakeAudio.BUDS, r.state.route)
        r.t.user("Find me a recipe.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(FocusMode.Hold, r.audio.focus.last())
        advanceTimeBy(1_500)
        assertEquals(FocusMode.Release, r.audio.focus.last()) // headset: full volume
        assertTrue(r.state.musicInGap)
        assertEquals(CallActivity.Thinking, r.state.activity)

        val turn = r.link.backend.channels.single()
        turn.send(TurnUpdate.Completed("Try a risotto.", TurnStatus.Complete, null))
        runCurrent()
        assertEquals(FocusMode.Hold, r.audio.focus.last())
        assertTrue(r.t.contents("session.commentary.append").isEmpty())
        advanceTimeBy(299)
        assertTrue(r.t.contents("session.commentary.append").isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("Try a risotto."), r.t.contents("session.commentary.append"))
        assertFalse(r.state.musicInGap)
    }

    @Test fun bargeInTakesFocusBackAtOnce() = runTest {
        val r = rig()
        live(r)
        r.t.user("Summarise my inbox.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        advanceTimeBy(2_500)
        assertEquals(FocusMode.Duck, r.audio.focus.last()) // speaker: duck by default
        // Music has been back a while (the VAD re-baselines for 1.2 s as it returns), then the user talks.
        repeat(30) { r.t.events.onMicLevel(-60.0, 50); advanceTimeBy(50) }
        r.t.events.onMicLevel(-20.0, 200)
        advanceTimeBy(150)
        assertEquals(FocusMode.Hold, r.audio.focus.last())
        assertEquals(CallActivity.UserSpeaking, r.state.activity)
    }

    @Test fun duckedMusicOnTheSpeakerDoesNotPauseItself() = runTest {
        val r = rig()
        live(r)
        r.t.user("Plan the trip.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        repeat(60) { r.t.events.onMicLevel(-65.0, 50); advanceTimeBy(50) } // quiet room, 3 s
        assertEquals(FocusMode.Duck, r.audio.focus.last())
        val ducks = r.audio.focus.size
        // The music comes back through the phone's speaker and the mic hears it, steadily, for 10 s.
        repeat(200) { r.t.events.onMicLevel(-32.0, 50); advanceTimeBy(50) }
        assertEquals(ducks, r.audio.focus.size)
        assertEquals(FocusMode.Duck, r.audio.focus.last())
        // The user talks over it: well above the music, focus comes back at once.
        repeat(6) { r.t.events.onMicLevel(-14.0, 50); advanceTimeBy(50) } // 300 ms: onset (120 ms) plus one 150 ms tick
        assertEquals(FocusMode.Hold, r.audio.focus.last())
    }

    @Test fun cardsSurviveAReconnect() = runTest {
        val r = rig()
        live(r)
        r.t.user("Clean the build.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        r.link.backend.channels.single().send(TurnUpdate.ApprovalNeeded(approval()))
        runCurrent()
        r.t.events.onLost("ice")
        runCurrent()
        assertEquals(CallPhase.Reconnecting, r.state.phase)
        assertEquals(1, r.state.approvals.size)
        advanceTimeBy(700)
        r.t.started()
        runCurrent()
        r.engine.answerApproval("srq-1", ApprovalChoice.Once)
        runCurrent()
        assertEquals(listOf("srq-1" to ApprovalChoice.Once), r.link.approvals)
    }

    @Test fun musicInGapsOffHoldsFocusAllCall() = runTest {
        val r = rig(LiveSettings(musicInGaps = false))
        live(r)
        r.t.user("Do the thing.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(setOf(FocusMode.Hold), r.audio.focus.toSet())
    }

    private fun approval(id: String = "srq-1") =
        ApprovalRequest(id, "rt", "apr", "rm -rf build", "Delete the build folder", listOf(ApprovalChoice.Once, ApprovalChoice.Deny), "terminal", JsonObject(emptyMap()))

    @Test fun approvalsWaitForATapAndSpeechIsNotEnoughByDefault() = runTest {
        val r = rig()
        live(r)
        r.t.user("Clean the build.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        r.link.backend.channels.single().send(TurnUpdate.ApprovalNeeded(approval()))
        runCurrent()
        assertEquals("srq-1", r.state.approvals.single().id)
        assertTrue(r.t.contents("session.thinking.append").any { it.contains("approve a step on screen") })

        r.t.assistant("Please approve on screen.", 2_000)
        r.t.user("Approve.", 3_000)
        r.t.delegation("del_2")
        advanceTimeBy(2_000)
        assertEquals(1, r.link.backend.calls.size) // consumed, not a new turn
        assertTrue(r.link.approvals.isEmpty())
        assertTrue(r.t.contents("session.thinking.append").any { it.contains("tap on the screen") })
        assertEquals(1, r.state.approvals.size)

        r.engine.answerApproval("srq-1", ApprovalChoice.Once)
        runCurrent()
        assertEquals(listOf("srq-1" to ApprovalChoice.Once), r.link.approvals)
        assertTrue(r.state.approvals.isEmpty())
    }

    @Test fun spokenApprovalsWhenTurnedOn() = runTest {
        val r = rig(LiveSettings(spokenApprovals = true))
        live(r)
        r.t.user("Clean the build.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        r.link.backend.channels.single().send(TurnUpdate.ApprovalNeeded(approval()))
        runCurrent()
        r.t.assistant("Shall I?", 2_000)
        r.t.user("Deny.", 3_000)
        advanceTimeBy(1_600)
        assertEquals(listOf("srq-1" to ApprovalChoice.Deny), r.link.approvals)
    }

    @Test fun clarifyCanBeAnsweredByVoice() = runTest {
        val r = rig()
        live(r)
        r.t.user("Book a table.", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        val question = ClarifyQuestion("q1", "Which night?", listOf("Friday", "Saturday"), false)
        r.link.backend.channels.single().send(TurnUpdate.ClarifyNeeded(ClarifyRequest("srq-9", "rt", listOf(question), JsonObject(emptyMap()))))
        runCurrent()
        assertEquals("srq-9", r.state.clarify!!.id)
        r.t.assistant("Which night?", 2_000)
        r.t.user("Saturday please.", 3_000)
        r.t.delegation("del_2")
        runCurrent()
        assertEquals(listOf("srq-9" to mapOf("q1" to "Saturday")), r.link.clarifies)
        assertNull(r.state.clarify)
        assertEquals(1, r.link.backend.calls.size)
    }

    @Test fun anotherCallMutesAndPausesThenResumes() = runTest {
        val r = rig()
        live(r)
        r.audio.listener!!.onOtherCall(true)
        runCurrent()
        assertTrue(r.state.muted)
        assertTrue(r.state.onOtherCall)
        assertFalse(r.t.micOn)
        assertTrue(r.t.speakerSilenced)
        assertEquals("session.input_audio.mute", r.t.typesSent().last())
        advanceTimeBy(200)
        assertEquals(FocusMode.Release, r.audio.focus.last())
        assertTrue(r.effects.earcons.none { it == Earcon.Muted })

        r.audio.listener!!.onOtherCall(false)
        runCurrent()
        assertFalse(r.state.muted)
        assertTrue(r.t.micOn)
        assertFalse(r.t.speakerSilenced)
        assertEquals(1, r.audio.reasserted)
        assertEquals("session.input_audio.unmute", r.t.typesSent().last())
        assertEquals(CallPhase.Live, r.state.phase)
    }

    @Test fun routesFollowHeadsetsAndTheEarpieceTurnsOnProximity() = runTest {
        val r = rig()
        live(r)
        r.engine.selectRoute(FakeAudio.EARPIECE)
        assertEquals(FakeAudio.EARPIECE, r.audio.selected.last())
        assertTrue(r.effects.proximity.last())
        r.audio.available = listOf(FakeAudio.EARPIECE, FakeAudio.SPEAKER, FakeAudio.WIRED)
        r.audio.listener!!.onDevicesChanged(r.audio.available)
        assertEquals(FakeAudio.WIRED, r.state.route)
        assertFalse(r.effects.proximity.last())
        r.audio.listener!!.onBecomingNoisy()
        assertEquals(FakeAudio.SPEAKER, r.state.route)
    }

    @Test fun mutingTogglesTrackVendorAndEarcons() = runTest {
        val r = rig()
        live(r)
        r.engine.toggleMute()
        assertTrue(r.state.muted)
        assertFalse(r.t.micOn)
        assertEquals("session.input_audio.mute", r.t.typesSent().last())
        assertEquals(Earcon.Muted, r.effects.earcons.last())
        r.engine.toggleMute()
        assertEquals("session.input_audio.unmute", r.t.typesSent().last())
        assertEquals(Earcon.Unmuted, r.effects.earcons.last())
    }

    @Test fun aStartPromptBecomesTheFirstRequest() = runTest {
        val r = rig()
        live(r, CallRequest(prompt = "Read me the news", muted = true))
        assertEquals("Read me the news", r.link.backend.calls.single().prompt)
        assertEquals(Caption(Speaker.User, "Read me the news"), r.state.captions.single())
        assertFalse(r.t.micOn)
        assertTrue("session.input_audio.mute" in r.t.typesSent())
    }

    @Test fun conversationsResumeWithinThirtyMinutes() = runTest {
        advanceTimeBy(60 * 60_000L)
        val now = testScheduler.currentTime
        val r = rig(memory = SessionMemory("old-session", now - 10 * 60_000L))
        r.engine.start()
        runCurrent()
        assertEquals(listOf<String?>("old-session"), r.resumed)
        r.engine.end()
        runCurrent()

        val stale = rig(memory = SessionMemory("old-session", now - 31 * 60_000L))
        stale.engine.start()
        runCurrent()
        assertEquals(listOf<String?>(null), stale.resumed)

        val fresh = rig(memory = SessionMemory("old-session", now - 60_000L))
        fresh.engine.start(CallRequest(newConversation = true))
        runCurrent()
        assertEquals(listOf<String?>(null), fresh.resumed)
    }

    @Test fun endWhileConnectingTearsDownAtOnce() = runTest {
        val r = rig()
        r.engine.start()
        runCurrent()
        r.engine.end()
        assertEquals(CallPhase.Ended, r.state.phase)
        assertNull(r.state.summary)
        assertTrue(r.t.closed)
        assertEquals(1, r.audio.ended)
    }

    @Test fun reasonsReadPlainly() {
        assertEquals("Call ended.", CallEngine.endReason("client_closed"))
        assertEquals("Hermes Live ended the call (max duration).", CallEngine.endReason("max_duration"))
    }

    // ── offline-memo slice: a structured failure kind beside the message ───────────────────

    @Test fun offlineAStartFailureSaysOffline() = runTest {
        val r = rig()
        r.online = false
        r.linkError = java.net.UnknownHostException("fake-hermes.ts.net")
        r.engine.start()
        runCurrent()
        assertEquals(CallPhase.Failed, r.state.phase)
        assertEquals(CallFailure.Offline, r.state.failure)
        assertTrue(r.state.failure!!.offersMemo)
        assertEquals("the message is unchanged", "Could not connect to Hermes Live.", r.state.error)
    }

    @Test fun onlineButHermesDownIsHermesUnreachable() = runTest {
        val r = rig()
        r.linkError = java.net.ConnectException("refused")
        r.engine.start()
        runCurrent()
        assertEquals(CallFailure.HermesUnreachable, r.state.failure)
    }

    @Test fun signInProblemsOfferNoMemo() = runTest {
        val r = rig()
        r.linkError = HermesAuthException.SignInRequired()
        r.engine.start()
        runCurrent()
        assertEquals(CallFailure.Other, r.state.failure)
        assertFalse(r.state.failure!!.offersMemo)
    }

    @Test fun theFailureKinds() {
        fun kind(e: Throwable, online: Boolean = true) = CallEngine.failureKind(e, online)
        assertEquals(CallFailure.Offline, kind(RuntimeException("anything"), online = false))
        assertEquals(CallFailure.VoiceUnavailable, kind(com.folio.hermes.live.VoiceLiveException(com.folio.hermes.live.VoiceLiveException.Kind.NotConfigured, 503, "")))
        assertEquals(CallFailure.VoiceUnavailable, kind(com.folio.hermes.live.VoiceLiveException(com.folio.hermes.live.VoiceLiveException.Kind.VendorRejected, 502, "")))
        assertEquals(CallFailure.VoiceUnavailable, kind(com.folio.hermes.live.VoiceLiveException(com.folio.hermes.live.VoiceLiveException.Kind.Failed, 503, "")))
        assertEquals(CallFailure.HermesUnreachable, kind(com.folio.hermes.live.VoiceLiveException(com.folio.hermes.live.VoiceLiveException.Kind.Failed, 500, "")))
        assertEquals(CallFailure.Other, kind(com.folio.hermes.live.VoiceLiveException(com.folio.hermes.live.VoiceLiveException.Kind.Unauthorized, 401, "")))
        assertEquals(CallFailure.HermesUnreachable, kind(HermesAuthException.Unavailable(502, "bad gateway")))
        assertEquals(CallFailure.Other, kind(HermesAuthException.InvalidCredentials()))
        assertEquals(CallFailure.HermesUnreachable, kind(java.net.SocketTimeoutException("timeout")))
        assertEquals(CallFailure.Other, kind(IllegalStateException("microphone busy")))
    }

    @Test fun noSessionStartedIsVoiceUnavailable() = runTest {
        val r = rig()
        r.engine.start()
        runCurrent()
        advanceTimeBy(CallEngine.STARTED_TIMEOUT_MS + 1)
        assertEquals(CallPhase.Failed, r.state.phase)
        assertEquals(CallFailure.VoiceUnavailable, r.state.failure)
    }

    @Test fun aDroppedCallWhoseReconnectFailsCarriesTheKind() = runTest {
        val r = rig()
        live(r)
        r.online = false
        r.link.prepareError = java.net.UnknownHostException("gone")
        r.t.events.onLost("ice failed")
        runCurrent()
        advanceTimeBy(700)
        assertEquals(CallPhase.Ended, r.state.phase)
        assertEquals(CallFailure.Offline, r.state.failure)
        assertTrue(r.state.summary != null)
    }

    @Test fun aCallThatEndsWellHasNoFailure() = runTest {
        val r = rig()
        live(r)
        r.engine.end()
        runCurrent()
        r.t.closedBy("client_closed", 3.0)
        runCurrent()
        assertNull(r.state.failure)
    }
}
