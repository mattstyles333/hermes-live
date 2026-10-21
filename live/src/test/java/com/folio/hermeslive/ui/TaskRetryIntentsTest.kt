package com.folio.hermeslive.ui

import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.BusyMode
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskTap
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermeslive.call.CallEngine
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.call.FakeMemory
import com.folio.hermeslive.cascade.TestRuntime
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.grok.GrokCallEngine
import com.folio.hermeslive.grok.GrokNetwork
import com.folio.hermeslive.meeting.TestClock
import com.folio.hermeslive.ui.cascade.CascadeIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A card's Retry on a Grok call and on a GPT-Live agents call (task-viewer-retry slice). The real
 * intents ([grokIntents], [gptLiveIntents]) go through the shared task API to the registry, as
 * Bill's own tap does: a not-sent task is resent as itself, a failed one starts again as one new
 * task with exactly its brief. Before, both sent "Please try this again." as a queued update.
 * Real actor and task API on a scripted Hermes, virtual clock; the engines are built, never started.
 */
class TaskRetryIntentsTest {
    /** Hermes as the phone sees it: records each prompt; [failSubmits] fail before `Accepted`, [errorNext] fail after it. */
    private class ScriptedHermes : HermesPort {
        val prompts = mutableListOf<String>()
        var failSubmits = 0
        var errorNext = 0

        override fun open(taskId: String, label: String, storedSessionId: String?) = Unit
        override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> {
            if (failSubmits > 0) {
                failSubmits--
                return flowOf(TurnUpdate.Failed("unreachable"))
            }
            prompts += text
            val accepted = TurnUpdate.Accepted("s${prompts.size}", SubmitStatus.Streaming)
            if (errorNext > 0) {
                errorNext--
                return flowOf(accepted, TurnUpdate.Started, TurnUpdate.Completed("", TurnStatus.Error, null))
            }
            return flow {
                emit(accepted)
                emit(TurnUpdate.Started)
                awaitCancellation()
            }
        }
        override suspend fun interrupt(taskId: String): Boolean = false
        override suspend fun busyMode(): BusyMode? = null
        override val sessions: Flow<Pair<String, String>> = emptyFlow()
        override val connected: Flow<Boolean> = flowOf(true)
    }

    /** The runtime on the test scheduler but outside the test's job (as `:hermes` `TaskApiTest`): delays are virtual, [close] ends it. */
    private class Rig(scope: TestScope) : AutoCloseable {
        val job = SupervisorJob()
        val work = CoroutineScope(scope.coroutineContext + job)
        val hermes = ScriptedHermes()
        val rt = TestRuntime(work, hermes = hermes, clock = TestClock(scope))
        val tasks get() = rt.runtime.core.tasks
        val ids get() = rt.runtime.core.actor.state.value.tasks.tasks.map { it.id }
        fun task(id: String) = rt.runtime.core.actor.state.value.tasks[id]!!
        override fun close() = job.cancel()
    }

    private val brief = "Check stock of RB2140 in Leeds.\nThen email Safilo the count."

    /** t1 never reached Hermes (two failed submits); t2 was accepted, then failed. Then Retry on each through [intents]. */
    private fun TestScope.retriesAsBillDoes(rig: Rig, intents: CascadeIntents) {
        rig.hermes.failSubmits = 2
        launchAndSettle { rig.tasks.startTask("check stock of RB2140", "Stock") }
        assertEquals("not_sent", rig.tasks.status("t1").single().status)
        rig.hermes.errorNext = 1
        launchAndSettle { rig.tasks.startTask(brief, "Stock email") }
        assertEquals("failed", rig.tasks.status("t2").single().status)

        intents.task("t1", TaskTap.Retry)
        advanceUntilIdle()
        assertEquals("not sent: the same task again, no new one", listOf("t1", "t2"), rig.ids)
        assertEquals(SendState.Accepted, rig.task("t1").send)
        assertEquals("running", rig.tasks.status("t1").single().status)

        intents.task("t2", TaskTap.Retry)
        advanceUntilIdle()
        assertEquals("failed: one new task", listOf("t1", "t2", "t3"), rig.ids)
        assertEquals("its brief exactly", brief, rig.task("t3").brief)
        assertEquals(listOf(brief, "check stock of RB2140", brief), rig.hermes.prompts)
    }

    private fun TestScope.launchAndSettle(block: suspend () -> Unit) {
        launch { block() }
        advanceUntilIdle()
    }

    @Test fun grokRetryIsBillsRetry() = runTest {
        Rig(this).use { rig -> grok(rig) }
    }

    @Test fun gptLiveRetryIsBillsRetry() = runTest {
        Rig(this).use { rig -> gptLive(rig) }
    }

    private fun TestScope.grok(rig: Rig) {
        val engine = GrokCallEngine(
            scope = rig.work, settings = { LiveSettings() }, runtime = { rig.rt.runtime }, key = { null },
            client = OkHttpClient(), audioFactory = { error("never started") }, audio = FakeAudio(), effects = FakeEffects(),
            offCall = { true },
            network = object : GrokNetwork {
                override fun online() = true
                override val changes = MutableSharedFlow<Boolean>()
            },
        )
        retriesAsBillDoes(rig, grokIntents(engine, rig.tasks, this, {}, {}, {}))
    }

    private fun TestScope.gptLive(rig: Rig) {
        val engine = CallEngine(
            scope = rig.work, settings = { LiveSettings() }, links = { error("never started") },
            transports = { error("never started") }, audio = FakeAudio(), effects = FakeEffects(), memory = FakeMemory(),
        )
        retriesAsBillDoes(rig, gptLiveIntents(engine, rig.tasks, this, {}, {}, {}))
    }
}
