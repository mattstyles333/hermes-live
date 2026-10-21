package com.folio.hermeslive.memo

import com.folio.hermes.BusyMode
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.HermesPort
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.telemetry.TelemetrySink
import com.folio.hermeslive.cascade.CascadeAppRuntime
import com.folio.hermeslive.cascade.TestRuntime
import com.folio.hermeslive.data.CascadeDirs
import com.folio.hermeslive.data.CascadeEnvironment
import com.folio.hermeslive.data.CascadeFactory
import com.folio.hermeslive.data.CascadeGate
import com.folio.hermeslive.data.CascadeShell
import com.folio.hermeslive.data.CascadeStorage
import com.folio.hermeslive.data.KeyStatus
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.data.SpeechModels
import com.folio.hermeslive.meeting.Events
import com.folio.hermeslive.meeting.TestClock
import com.folio.hermeslive.tasks.CascadeTaskSubmitter
import com.folio.hermeslive.tasks.HeldTasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

/**
 * Final review: a held memo sent with Settings › Voice › Offline memos switched off (and GPT-Live's
 * agents off, so nothing else keeps the task gate open). `Started` only means the task is stored on
 * the phone; the gate must stay open, and the runtime must not be released, until that task is
 * finished in the task table, so its prompt reaches Hermes, also across a process restart.
 * The real shell, gate, actor, task API and sender; a scripted Hermes; virtual time.
 */
class MemoTaskGateTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Hermes: records each prompt; [hang] never answers a submit (the process dies first); [finish] ends the running turn. */
    private class ScriptedHermes(private val hang: Boolean = false) : HermesPort {
        val prompts = mutableListOf<String>()
        private val done = CompletableDeferred<Unit>()
        fun finish() = done.complete(Unit)

        override fun open(taskId: String, label: String, storedSessionId: String?) = Unit
        override fun submit(taskId: String, text: String, voiceContext: String, queued: Boolean, interruptFirst: Boolean): Flow<TurnUpdate> {
            if (hang) return flow { awaitCancellation() }
            prompts += text
            return flow {
                emit(TurnUpdate.Accepted("s${prompts.size}", SubmitStatus.Streaming))
                emit(TurnUpdate.Started)
                done.await()
                emit(TurnUpdate.Completed("Booked.", TurnStatus.Complete, null))
            }
        }
        override suspend fun interrupt(taskId: String): Boolean = false
        override suspend fun busyMode(): BusyMode? = null
        override val sessions: Flow<Pair<String, String>> = emptyFlow()
        override val connected: Flow<Boolean> = flowOf(true)
    }

    /** One process: its own runtime scope, shell, gate and sender, on the stores that outlive it. */
    private inner class Process(scope: TestScope, val hermes: ScriptedHermes, coreStore: MemoryStore, val memos: MemoStore) {
        val job = SupervisorJob()
        private val work = CoroutineScope(scope.coroutineContext + job)
        var held = MemoHost.waiting(memos)
        val gate = CascadeGate({ LiveSettings() }, { KeyStatus() }, gptLiveAgents = { false }, memosOnOrHeld = { held })
        val shell = CascadeShell(gate, CascadeFactory { TestRuntime(work, hermes = hermes, clock = TestClock(scope), store = coreStore).runtime }) {
            CascadeEnvironment({ com.folio.hermes.cascade.CascadeSettings() }, { null }, CascadeStorage(CascadeDirs(tmp.root, tmp.root)), SpeechModels.None, TelemetrySink.None)
        }
        private fun tasks() = (shell.runtime() as? CascadeAppRuntime)?.core?.tasks
        val sender = MemoSender(
            memos,
            HeldTasks { CascadeTaskSubmitter(tasks = { tasks() }, signedIn = { true }, noRuntime = "no_runtime") },
            { MemoTranscriber { _, _ -> MemoText.Text("book the dentist") } },
            { ZoneId.of("Europe/London") }, { 0L }, Events(), MemoSenderTest.RecordingNotices(), MemoSenderTest.CountingScheduler(),
            taskStatus = { id -> MemoSender.statusIn(tasks(), id) },
            retryTask = { id -> tasks()?.retry(id) },
        )
        val memoGate = MemoTaskGate(sender, shell) { held = it }
        val runtime get() = shell.existing() as? CascadeAppRuntime
    }

    private fun TestScope.flush(p: Process) {
        launch { p.memoGate.flush() }
        advanceUntilIdle()
    }

    private fun heldMemo(): Pair<MemoryStore, MemoStore> {
        val kv = MemoryStore()
        val memos = MemoStore({ kv }, tmp.newFolder("memo"))
        MemoSenderTest.wav(memos.chunk("m1", 0), 32_000)
        memos.put(MemoRecord("m1", 0, MemoState.Recorded, chunks = 1))
        return kv to memos
    }

    @Test fun theGateStaysOpenUntilTheMemosTaskIsFinishedThenTheRuntimeGoes() = runTest {
        val (_, memos) = heldMemo()
        val p = Process(this, ScriptedHermes(), MemoryStore(), memos)
        assertTrue("a held memo opens the gate", p.gate.tasksActive)
        flush(p)
        assertEquals(MemoState.Sent, memos.get("m1")!!.state)
        assertEquals("the prompt reached Hermes once", 1, p.hermes.prompts.size)
        assertTrue(p.hermes.prompts.single().contains("book the dentist"))
        assertTrue("the task is still running: the gate stays open", p.gate.tasksActive)
        assertNotNull("and the runtime is not released", p.runtime)

        p.hermes.finish()
        advanceUntilIdle()
        flush(p)
        assertFalse("the task is done: nothing keeps the gate open", p.gate.tasksActive)
        assertNull("the runtime is released", p.runtime)
        assertEquals(1, p.hermes.prompts.size)
        p.job.cancel()
    }

    @Test fun aProcessRestartBeforeThePromptLeftStillDeliversItOnce() = runTest {
        val (_, memos) = heldMemo()
        val coreStore = MemoryStore()
        val first = Process(this, ScriptedHermes(hang = true), coreStore, memos)
        flush(first)
        assertEquals(MemoState.Sent, memos.get("m1")!!.state)
        assertTrue(first.gate.tasksActive)
        // The process dies with the task stored on the phone but its prompt not yet taken by Hermes.
        first.job.cancel()

        val second = Process(this, ScriptedHermes(), coreStore, memos)
        assertTrue("a sent memo whose task is not finished still opens the gate in a new process", second.held)
        flush(second)
        assertEquals("recovered and sent once", 1, second.hermes.prompts.size)
        assertNotNull(second.runtime)
        second.hermes.finish()
        advanceUntilIdle()
        flush(second)
        assertNull(second.runtime)
        assertEquals(1, second.hermes.prompts.size)
        second.job.cancel()
    }
}
