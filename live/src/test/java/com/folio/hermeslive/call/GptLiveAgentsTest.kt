package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.cascade.CascadeTasks
import com.folio.hermes.cascade.TaskNotice
import com.folio.hermes.cascade.TaskReply
import com.folio.hermes.cascade.TaskView
import com.folio.hermes.cascade.UpdateMode
import com.folio.hermes.live.AgentDesk
import com.folio.hermes.live.AgentText
import com.folio.hermes.live.DelegationTasks
import com.folio.hermes.live.HeuristicAgentIntents
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.call.ondemand.FakeVad
import com.folio.hermeslive.call.ondemand.OnDemandSettings
import com.folio.hermeslive.call.ondemand.SessionView
import com.folio.hermeslive.data.CascadeGate
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPT-Live with parallel agents and on-demand sessions, through [CallEngine] with fake WebRTC,
 * a fake Hermes link and a fake task API (the real task API is covered in `:hermes`
 * `AgentDeskTest`). Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GptLiveAgentsTest {
    /** The shared task API in memory: keys make starts idempotent. */
    class FakeTasks : DelegationTasks {
        val started = mutableListOf<Triple<String, String, String>>()
        val keys = linkedMapOf<String, String>()
        val heardIds = mutableListOf<String>()
        val table = MutableStateFlow<List<TaskView>>(emptyList())
        override val notices = MutableSharedFlow<TaskNotice>(extraBufferCapacity = 16)
        override val views: Flow<List<TaskView>> = table

        override suspend fun start(brief: String, label: String, key: String): TaskReply {
            keys[key]?.let { return TaskReply.Started(it, label, queued = false, existing = true) }
            val id = "t${keys.size + 1}"
            keys[key] = id
            started += Triple(brief, label, key)
            table.value = table.value + view(id, label)
            return TaskReply.Started(id, label, queued = false)
        }

        override suspend fun update(taskId: String, text: String, mode: UpdateMode, key: String) = TaskReply.Updated(taskId, "queue")
        override suspend fun cancel(taskId: String, key: String): TaskReply {
            table.value = table.value.map { if (it.id == taskId || taskId == CascadeTasks.ALL) it.copy(status = "cancelled") else it }
            return TaskReply.Cancelled(listOf(taskId))
        }
        override suspend fun heard(taskId: String) { heardIds += taskId }
        override fun status() = table.value
        override fun result(taskId: String): String? = null
        override suspend fun unheard(): List<TaskNotice> = emptyList()

        fun finish(id: String, text: String) {
            val t = table.value.first { it.id == id }
            table.value = table.value.map { if (it.id == id) it.copy(status = "done", hasResult = true, unread = true) else it }
            notices.tryEmit(TaskNotice.Finished(id, t.label, text, ok = true))
        }

        private fun view(id: String, label: String) =
            TaskView(id, label, "running", "started", elapsedS = 0, steps = 0, currentTool = null, lastNote = null, hasResult = false, unread = false)
    }

    class FakeHost : GptLiveAgentsHost {
        override val tasks = FakeTasks()
        override val intents = HeuristicAgentIntents
        override val labeler = com.folio.hermes.live.TaskLabeler { AgentText.heuristicLabel(it.prompt) }
        override val approvals = MutableStateFlow<List<ApprovalCard>>(emptyList())
        override val clarify = MutableStateFlow<ClarifyCard?>(null)
        val answered = mutableListOf<Pair<String, ApprovalChoice>>()
        var holds = 0
        var released = 0
        override suspend fun answerApproval(id: String, choice: ApprovalChoice): Boolean {
            answered += id to choice
            approvals.value = approvals.value.filterNot { it.id == id }
            return true
        }
        override suspend fun answerClarify(id: String, answers: Map<String, String?>) = true
        override fun hold(): AutoCloseable {
            holds++
            return AutoCloseable { released++ }
        }
        override val runtime = null
    }

    private class Rig(
        scope: TestScope,
        var host: FakeHost? = FakeHost(),
        var settings: LiveSettings = LiveSettings(),
        onDemand: ((CallEngine) -> GptLiveOnDemand?)? = null,
    ) {
        val links = mutableListOf<FakeLink>()
        val transports = mutableListOf<FakeTransport>()
        val audio = FakeAudio()
        val effects = FakeEffects()
        val offCalls = mutableListOf<Int>()
        var asked = 0
        val engine = CallEngine(
            scope = scope.backgroundScope,
            settings = { settings },
            links = { FakeLink().also { links += it } },
            transports = { events -> FakeTransport(events).also { transports += it } },
            audio = audio,
            effects = effects,
            memory = FakeMemory(),
            clock = { scope.testScheduler.currentTime },
            agentsFactory = { asked++; host },
            offCall = { offCalls += it; true },
            onDemandFactory = onDemand?.let { f -> GptLiveOnDemandFactory { f(it) } } ?: GptLiveOnDemandFactory.Off,
        )
        val t: FakeTransport get() = transports.last()
        val link: FakeLink get() = links.last()
        val state: CallUiState get() = engine.state.value
        val tasks: FakeTasks get() = host!!.tasks
    }

    private fun TestScope.live(r: Rig) {
        r.engine.start()
        runCurrent()
        r.t.started()
        runCurrent()
    }

    private fun approval(id: String) = ApprovalRequest(id = id, sessionId = "s", requestId = "q-$id", command = "rm x", description = "Delete a file", choices = listOf(ApprovalChoice.Once), toolName = null, params = kotlinx.serialization.json.JsonObject(emptyMap()))

    @Test fun eachDelegationBecomesItsOwnTaskKeyedByItsId() = runTest {
        val r = Rig(this)
        live(r)
        assertEquals(1, r.host!!.holds)
        assertEquals(AgentDesk.SESSION_NOTE, r.t.contents("session.instructions.append").first())
        r.t.user("Check stock of RB2140", 1_000)
        r.t.delegation("del_1")
        r.t.assistant("On it.", 1_500)
        r.t.user("Email Safilo about the order", 2_000)
        r.t.delegation("del_2")
        r.t.delegation("del_2")
        runCurrent()
        assertEquals("the single conversation is never used", 0, r.link.backend.calls.size)
        assertEquals(listOf("del_1", "del_2"), r.tasks.started.map { it.third })
        assertEquals(listOf("Check stock RB2140", "Email Safilo order"), r.tasks.started.map { it.second })
        assertTrue(r.tasks.started[1].first.contains("Recent spoken conversation"))
        // Both work at once: the orb thinks, music may play in the gap.
        advanceTimeBy(2_000)
        assertEquals(CallActivity.Thinking, r.state.activity)
        val thinking = r.t.sent.filter { it["type"]!!.jsonPrimitive.content == "session.thinking.append" }
        assertEquals(listOf("del_1", "del_2"), thinking.map { it["delegation_id"]!!.jsonPrimitive.content })
    }

    @Test fun aResultIsToldInAQuietMomentOnItsDelegation() = runTest {
        val r = Rig(this)
        live(r)
        r.t.user("Check stock of RB2140", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        r.t.user("Thanks", 2_000)
        r.tasks.finish("t1", "Four in Leeds.")
        runCurrent()
        assertTrue("not while the user speaks", r.t.contents("session.commentary.append").isEmpty())
        advanceTimeBy(4_000)
        assertEquals(listOf("Task 'Check stock RB2140' finished: Four in Leeds."), r.t.contents("session.commentary.append"))
        r.t.assistant("Leeds has four.", 9_000)
        runCurrent()
        assertEquals(listOf("t1"), r.tasks.heardIds)
    }

    @Test fun approvalsComeFromTheTaskSessionsAndNeedATap() = runTest {
        val r = Rig(this)
        live(r)
        r.host!!.approvals.value = listOf(ApprovalCard(approval("ap1")))
        runCurrent()
        assertEquals(listOf("ap1"), r.state.approvals.map { it.id })
        r.t.user("approve", 1_000)
        r.t.delegation("del_9")
        runCurrent()
        assertTrue("spoken approvals off: nothing answered", r.host!!.answered.isEmpty())
        assertTrue(r.tasks.started.isEmpty())
        r.engine.answerApproval("ap1", ApprovalChoice.Once)
        runCurrent()
        assertEquals(listOf("ap1" to ApprovalChoice.Once), r.host!!.answered)
        assertTrue(r.link.approvals.isEmpty())
    }

    @Test fun hangUpWithTasksRunningHandsThemToTheOffCallService() = runTest {
        val r = Rig(this)
        live(r)
        r.t.user("Check stock of RB2140", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        r.engine.end()
        r.t.closedBy("client_closed", 30.0)
        runCurrent()
        assertEquals(listOf(1), r.offCalls)
        assertEquals(1, r.host!!.released)
        assertTrue(r.state.summary!!.reason.contains("carry on"))
    }

    @Test fun offStateIsTodaysSingleConversation() = runTest {
        val r = Rig(this, host = null)
        live(r)
        assertEquals(1, r.asked)
        assertTrue(r.t.contents("session.instructions.append").none { it == AgentDesk.SESSION_NOTE })
        r.t.user("Check stock of RB2140", 1_000)
        r.t.delegation("del_1")
        runCurrent()
        assertEquals("Check stock of RB2140", r.link.backend.calls.single().prompt)
        assertNull(r.engine.agentSession)
        assertEquals(SessionView.Connecting, r.engine.onDemandUi.value.view)
        assertFalse(r.engine.onDemandUi.value.enabled)
    }

    @Test fun theGateKeepsAgentsOffByDefaultAndForOtherEngines() {
        var settings = LiveSettings()
        var agents = false
        val gate = CascadeGate({ settings }, { KeyStatus() }, gptLiveAgents = { agents })
        assertFalse(gate.gptLiveAgentsActive)
        assertFalse("nothing builds the runtime", gate.tasksActive)
        agents = true
        assertTrue(gate.gptLiveAgentsActive)
        assertTrue(gate.tasksActive)
        settings = settings.copy(voiceEngine = VoiceEngine.Cascade)
        assertFalse(gate.gptLiveAgentsActive)
    }

    @Test fun signedOutOrSwitchedOffNeverAsksForTheRuntime() {
        var built = 0
        val runtime = { built++; null }
        assertNull(GptLiveAgentsFactory.gated(enabled = { false }, signedIn = { true }, runtime = runtime).create())
        assertNull(GptLiveAgentsFactory.gated(enabled = { true }, signedIn = { false }, runtime = runtime).create())
        assertEquals(0, built)
        assertNull(GptLiveAgentsFactory.gated(enabled = { true }, signedIn = { true }, runtime = runtime).create())
        assertEquals(1, built)
    }

    // ── on-demand sessions ──

    @Test fun closeAndReopenKeepsTheCallAndSendsHistoryWithTheTasksNote() = runTest {
        val r = Rig(this)
        live(r)
        repeat(30) { i -> r.t.user("turn $i ", 1_000L + i * 100) }
        r.t.delegation("del_1")
        runCurrent()
        assertTrue(r.engine.closeSession())
        assertEquals("session.close", r.t.typesSent().last())
        r.t.closedBy("client_closed", 12.0)
        runCurrent()
        assertTrue("the call stays", r.state.inCall)
        assertEquals(CallPhase.Live, r.state.phase)
        assertFalse(r.engine.sessionOpen.value)
        assertTrue(r.t.closed)
        assertEquals(0, r.link.closed)

        assertTrue(r.engine.reopenSession("and Manchester too"))
        runCurrent()
        assertEquals(2, r.transports.size)
        val history = r.link.histories.last()
        assertTrue(history.size <= 24)
        assertEquals("[Note from the app, not said by the user] Tasks running: 'Turn 0 turn' (working).", history.last().content.single().text)
        advanceTimeBy(700)
        r.t.started()
        runCurrent()
        assertEquals(700L, r.engine.lastReopenMs)
        val said = r.t.contents("session.instructions.append")
        assertEquals(AgentDesk.SESSION_NOTE, said[0])
        assertTrue(said[1].contains("While the line was paused the user said: \"and Manchester too\""))
        assertTrue(r.engine.sessionOpen.value)
    }

    @Test fun aFailedReopenGoesBackToClosedInsteadOfEndingTheCall() = runTest {
        val r = Rig(this)
        live(r)
        r.engine.closeSession()
        r.t.closedBy("client_closed", 1.0)
        runCurrent()
        r.engine.reopenSession()
        runCurrent()
        advanceTimeBy(CallEngine.STARTED_TIMEOUT_MS + 100)
        assertTrue(r.state.inCall)
        assertEquals("Couldn't reconnect. Speak to try again.", r.state.notice)
        assertTrue("can try again", r.engine.reopenSession())
    }

    @Test fun whileClosedTheCallStillHangsUpWhenIdle() = runTest {
        val r = Rig(this, settings = LiveSettings(idleTimeoutMinutes = 1))
        live(r)
        r.engine.closeSession()
        r.t.closedBy("client_closed", 1.0)
        runCurrent()
        advanceTimeBy(59_000)
        assertTrue(r.state.inCall)
        advanceTimeBy(2_000)
        assertFalse(r.state.inCall)
    }

    @Test fun theControllerClosesWhenIdleAndReopensOnSpeechWithThePreRollAsText() = runTest {
        val vad = FakeVad()
        val heard = mutableListOf<Int>()
        var od: GptLiveOnDemand? = null
        val r = Rig(this, onDemand = { engine ->
            GptLiveOnDemand(
                engine = engine,
                settings = OnDemandSettings(enabled = true, idleCloseMs = 8_000),
                vadFactory = { vad },
                micFactory = { null },
                transcriber = { pcm -> heard += pcm.size; "what's the weather" },
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
                io = Dispatchers.Unconfined,
                telemetry = TelemetrySink.None,
            ).also { od = it }
        })
        live(r)
        assertEquals(SessionView.Connected, r.engine.onDemandUi.value.view)
        // All idle for 8 s: the controller closes the paid session.
        advanceTimeBy(8_500)
        assertEquals("session.close", r.t.typesSent().last())
        r.t.closedBy("client_closed", 9.0)
        advanceTimeBy(300)
        assertTrue(r.state.inCall)
        assertEquals(SessionView.Listening, r.engine.onDemandUi.value.view)
        assertTrue(r.engine.onDemandUi.value.dimmed)
        // The phone hears the user: reopen, holding what they say meanwhile.
        vad.speaking = true
        repeat(40) { od!!.controller.onMic(ShortArray(320) { 100 }, 320) }
        runCurrent()
        assertEquals(2, r.transports.size)
        assertEquals(SessionView.Connecting, r.engine.onDemandUi.value.view)
        r.t.started()
        advanceTimeBy(300)
        assertEquals(listOf(40 * 320), heard)
        assertTrue(r.t.contents("session.instructions.append").any { it.contains("While the line was paused the user said: \"what's the weather\"") })
        assertEquals(SessionView.Connected, r.engine.onDemandUi.value.view)
        r.engine.end()
        r.t.closedBy("client_closed", 1.0)
        runCurrent()
        assertFalse(r.state.inCall)
    }
}
