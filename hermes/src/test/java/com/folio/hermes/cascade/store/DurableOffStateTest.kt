package com.folio.hermes.cascade.store

import com.folio.hermes.cascade.CascadeActor
import com.folio.hermes.cascade.CascadeEnv
import com.folio.hermes.cascade.CascadeSettings
import com.folio.hermes.cascade.FakeAudioOut
import com.folio.hermes.cascade.FakeHermes
import com.folio.hermes.cascade.FakeOpenRouter
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TestClock
import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.registry.DurableRig
import com.folio.hermes.telemetry.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dark by default: only an env built with [durableEnv] writes to the store; the S0 default writes nothing. */
class DurableOffStateTest {
    @Test fun theDefaultEnvNeverWritesTheStore() = runTest {
        val store = MemoryStore()
        val clock = TestClock(this)
        val job = SupervisorJob()
        val actor = CascadeActor(
            scope = CoroutineScope(coroutineContext + job), env = CascadeEnv(PhraseBank.load()), settings = { CascadeSettings() },
            openRouter = FakeOpenRouter(clock).apply { jevDefault = FakeOpenRouter.JevScript(10, DurableRig.NEW_TASK) },
            hermes = FakeHermes(), audio = FakeAudioOut(clock), clock = clock, store = store, telemetry = TelemetrySink.None,
        )
        actor.start()
        runCurrent()
        actor.speechStarted()
        actor.final("check stock of RB2140")
        actor.speechEnded()
        advanceTimeBy(2_000)
        advanceUntilIdle()
        assertEquals("a task ran", 1, actor.state.value.tasks.tasks.size)
        assertTrue("nothing stored", store.keys().isEmpty())
        job.cancel()
    }

    @Test fun theDurableEnvStoresTheSameCall() = runTest {
        DurableRig(this).use { r ->
            r.say("check stock of RB2140")
            advanceUntilIdle()
            assertTrue(StoreKeys.task("t1") in r.store.keys())
            assertTrue(StoreKeys.CORE in r.store.keys())
        }
    }
}
