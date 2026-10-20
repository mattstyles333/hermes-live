package com.folio.hermeslive.call

import com.folio.hermes.ApprovalChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine switch picks per call (spec v2 §12 phase 2). With GPT-Live, Bill is never built (R-Q-1). */
class EngineSwitchTest {
    private class FakeCall : VoiceCall {
        val starts = mutableListOf<CallRequest>()
        override val state = MutableStateFlow(CallUiState())
        override fun start(request: CallRequest) {
            starts += request
            state.value = CallUiState(phase = CallPhase.Live)
        }
        override fun end() { state.value = CallUiState(phase = CallPhase.Ended) }
        override fun toggleMute() = Unit
        override fun selectRoute(option: RouteOption) = Unit
        override fun submitText(text: String) = Unit
        override fun answerApproval(id: String, choice: ApprovalChoice) = Unit
        override fun answerClarify(id: String, answers: Map<String, String?>) = Unit
        override fun dismissSummary() { state.value = CallUiState() }
    }

    private class Rig(var active: Boolean = false, var ready: Boolean = true) {
        val gpt = FakeCall()
        val bills = mutableListOf<FakeCall>()
        val switch = CallSwitch({ gpt }, { FakeCall().also { bills += it } }, { active }, { ready })
    }

    @Test fun gptLiveByDefaultAndBillIsNeverBuilt() {
        val r = Rig()
        r.switch.start(CallRequest())
        assertEquals(1, r.gpt.starts.size)
        assertTrue("R-Q-1: nothing cascade-related is built", r.bills.isEmpty())
        assertSame(r.gpt.state, r.switch.state)
        assertNull(r.switch.cascadeEngine)
        assertNull(r.switch.bill())
        assertTrue(r.bills.isEmpty())
    }

    @Test fun billWhenChosenWithAKeyAndReady() {
        val r = Rig(active = true)
        assertEquals(CallPlan.Ready(Engine.Cascade), r.switch.plan())
        r.switch.start(CallRequest())
        assertEquals(1, r.bills.single().starts.size)
        assertTrue(r.gpt.starts.isEmpty())
        // The service reads the state straight after start: it is Bill's own flow.
        assertSame(r.bills.single().state, r.switch.state as StateFlow<CallUiState>)
    }

    @Test fun billWithoutModelsPreparesAndStartsNothing() {
        val r = Rig(active = true, ready = false)
        assertEquals(CallPlan.PreparingBill, r.switch.plan())
        r.switch.start(CallRequest())
        assertTrue(r.gpt.starts.isEmpty())
        assertTrue(r.bills.isEmpty())
        // "Use GPT-Live this time".
        r.switch.start(CallRequest(forceGptLive = true))
        assertEquals(1, r.gpt.starts.size)
        assertTrue(r.bills.isEmpty())
    }

    @Test fun theEngineStaysForTheWholeCall() {
        val r = Rig(active = true)
        r.switch.start(CallRequest())
        r.active = false // switched back mid-call: this call stays Bill's
        r.switch.start(CallRequest(prompt = "more"))
        assertEquals(2, r.bills.single().starts.size)
        r.switch.end()
        r.switch.start(CallRequest())
        assertEquals("the next call is GPT-Live", 1, r.gpt.starts.size)
    }

    /** Hermes voice slice: no voice at all (Hermes unreachable, no OpenRouter key) says so before a call; today's gate makes it unreachable. */
    @Test fun customWithNoVoiceStartsNothing() {
        val bills = mutableListOf<FakeCall>()
        val gpt = FakeCall()
        var voice = false
        val switch = CallSwitch({ gpt }, { FakeCall().also { bills += it } }, { true }, { true }, voiceReady = { voice })
        assertEquals(CallPlan.NoVoice, switch.plan())
        switch.start(CallRequest())
        assertTrue(bills.isEmpty())
        assertTrue(gpt.starts.isEmpty())
        assertEquals("GPT-Live this time still works", CallPlan.Ready(Engine.GptLive), switch.plan(CallRequest(forceGptLive = true)))
        voice = true
        assertEquals(CallPlan.Ready(Engine.Cascade), switch.plan())
    }

    /** Offline-memo slice: the memo is the first branch when it is on; off, nothing of it is built. */
    private class MemoRig(var memosOn: Boolean = true, var online: Boolean = true, var active: Boolean = false, var grok: Boolean = false) {
        val gpt = FakeCall()
        val bills = mutableListOf<FakeCall>()
        val groks = mutableListOf<FakeCall>()
        val memos = mutableListOf<FakeCall>()
        val switch = CallSwitch(
            { gpt }, { FakeCall().also { bills += it } }, { active }, { true },
            grok = { FakeCall().also { groks += it } }, grokActive = { grok }, billAvailable = { active },
            memo = { FakeCall().also { memos += it } }, memoActive = { memosOn }, online = { online },
        )
    }

    @Test fun offlineWithMemosOnAGptLiveCallIsAMemo() {
        val r = MemoRig(online = false)
        assertEquals(CallPlan.Ready(Engine.Memo), r.switch.plan())
        r.switch.start(CallRequest(entry = "launcher"))
        val start = r.memos.single().starts.single()
        assertTrue(start.memo)
        assertEquals("offline", start.memoCause)
        assertEquals("launcher", start.entry)
        assertTrue(r.gpt.starts.isEmpty())
        assertSame(r.memos.single().state, r.switch.state)
        r.switch.end()
        r.online = true
        r.switch.start(CallRequest())
        assertEquals("online again: GPT-Live", 1, r.gpt.starts.size)
        assertEquals("one recorder per memo", 1, r.memos.size)
    }

    @Test fun aMemoAskedForIsAMemoWhateverTheEngine() {
        for (engine in listOf("gpt", "bill", "grok")) {
            val r = MemoRig(active = engine == "bill", grok = engine == "grok")
            assertEquals(engine, CallPlan.Ready(Engine.Memo), r.switch.plan(CallRequest(memo = true)))
            r.switch.start(CallRequest(memo = true, entry = "memo"))
            assertEquals("asked", r.memos.single().starts.single().memoCause)
        }
    }

    @Test fun offlineWithCustomOrGrokChosenKeepsTheirOwnOfflinePaths() {
        assertEquals(CallPlan.Ready(Engine.Cascade), MemoRig(online = false, active = true).switch.plan())
        assertEquals(CallPlan.Ready(Engine.Grok), MemoRig(online = false, grok = true).switch.plan())
        // Grok offline: "Continue with Bill (memo)" stays Bill's memo mode.
        assertEquals(CallPlan.Ready(Engine.Cascade), MemoRig(online = false, grok = true, active = true).switch.plan(CallRequest(memo = true, forceBill = true)))
    }

    @Test fun memosOffIsTodaysSwitchAndTheMemoIsNeverBuilt() {
        val r = MemoRig(memosOn = false, online = false)
        assertEquals(CallPlan.Ready(Engine.GptLive), r.switch.plan())
        assertEquals(CallPlan.Ready(Engine.GptLive), r.switch.plan(CallRequest(memo = true)))
        r.switch.start(CallRequest(memo = true))
        r.switch.end()
        r.switch.start(CallRequest())
        assertEquals(2, r.gpt.starts.size)
        assertTrue(r.memos.isEmpty())
        // Bill chosen, memos off: a memo is Bill's memo mode, as before.
        assertEquals(CallPlan.Ready(Engine.Cascade), MemoRig(memosOn = false, active = true).switch.plan(CallRequest(memo = true)))
    }

    @Test fun theOnlineCheckIsAskedOnlyWhenMemosAreOn() {
        var asked = 0
        val switch = CallSwitch({ FakeCall() }, { FakeCall() }, { false }, { true }, memo = { FakeCall() }, memoActive = { false }, online = { asked++; false })
        switch.plan()
        assertEquals(0, asked)
    }
}
