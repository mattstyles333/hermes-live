package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.cascade.ports.Network
import com.folio.hermes.cascade.registry.ClientHermes
import com.folio.hermes.cascade.registry.FakeServer
import com.folio.hermes.cascade.routing.CeoHarness.Companion.brief
import com.folio.hermes.cascade.routing.CeoHarness.Companion.briefs
import com.folio.hermes.openrouter.FailureKind
import com.folio.hermes.openrouter.OpenRouterResult
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two bugs `CascadePropertyTest` found (2026-10-14), pinned as plain scenarios. */
class PropertyFindingsTest {
    private val bank = PhraseBank.load()
    private val newTask = OpenRouterResult.Ok(mapOf("turn" to answer("done", 0.95), "action" to answer("new_task", 0.95), "split" to answer("one", 0.95)))

    private inner class Rig(val ts: TestScope) {
        val scope = CoroutineScope(ts.coroutineContext + SupervisorJob())
        val clock = TestClock(ts)
        val server = FakeServer()
        val hermes = ClientHermes(server)
        val online = MutableStateFlow(true)
        val audio = FakeAudioOut(clock)
        val router = FakeOpenRouter(clock).apply {
            cached += bank.allLines
            plan = { prompt -> OpenRouterResult.Ok(briefs(brief(prompt.substringAfter("Transcript: \"").substringBefore("\"\n"), "job", "On it."))) }
            jevDefault = FakeOpenRouter.JevScript(50, newTask)
        }
        val core = CascadeCore.create(
            scope = scope,
            ports = CascadePorts(router, hermes, hermes, audio, MemoryStore(), clock, object : Network {
                override val online = this@Rig.online
            }),
            bank = bank, settings = { CascadeSettings() }, telemetry = TelemetrySink.None, zone = ZoneOffset.UTC, seed = 9,
        )
        val actor get() = core.actor

        fun say(text: String) {
            actor.speechStarted()
            actor.final(text)
            actor.speechEnded()
            ts.runCurrent()
        }
    }

    /**
     * Speech ended while offline (no Jev, so the turn commits at pause + 500 ms and is held), but
     * the network came back during that pause. The hold used to mark the call offline with the
     * port online: no network change would ever flush it. Now it retries after `offlineRetryMs`.
     */
    @Test fun aTurnHeldJustAfterTheNetworkCameBackIsSentWithoutANetworkChange() = runTest {
        val rig = Rig(this)
        rig.core.start()
        runCurrent()
        rig.online.value = false
        runCurrent()
        rig.say("check the Leeds stock")
        rig.online.value = true
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("held at the commit", 1, rig.actor.state.value.outbox.size)
        assertTrue(rig.server.prompts.isEmpty())
        advanceTimeBy(CascadeSettings().offlineRetryMs + 2_000)
        runCurrent()
        assertEquals(0, rig.actor.state.value.outbox.size)
        assertEquals(1, rig.server.prompts.count { "Leeds" in it })
        assertTrue(rig.actor.state.value.connection.online)
        rig.scope.cancel()
    }

    /** A resend that fails as offline while the port says online retries on the timer too; with the port offline it waits for the port. */
    @Test fun anOfflineResendRetriesOnlyWhileThePortSaysOnline() = runTest {
        val rig = Rig(this)
        rig.core.start()
        runCurrent()
        rig.online.value = false
        runCurrent()
        rig.say("email Safilo about the order")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, rig.actor.state.value.outbox.size)
        // Back online, but the resend's Jev fails as offline (a DNS hiccup): held, marked offline, retried later.
        rig.router.jevScripts.addLast(FakeOpenRouter.JevScript(20, OpenRouterResult.Failed(FailureKind.Offline)))
        rig.online.value = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, rig.actor.state.value.outbox.size)
        assertFalse(rig.actor.state.value.connection.online)
        assertTrue(rig.actor.state.value.connection.portOnline)
        advanceTimeBy(CascadeSettings().offlineRetryMs + 2_000)
        runCurrent()
        assertEquals(1, rig.server.prompts.count { "Safilo" in it })

        // With the port offline, no timer retries: the port's own return flushes.
        rig.online.value = false
        runCurrent()
        rig.say("draft the newsletter")
        advanceTimeBy(CascadeSettings().offlineRetryMs * 3)
        runCurrent()
        assertTrue(rig.server.prompts.none { "newsletter" in it })
        rig.online.value = true
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, rig.server.prompts.count { "newsletter" in it })
        rig.scope.cancel()
    }

    /**
     * A confirmed cut-in means the user is speaking: the next queued piece (here a finished result)
     * must not start between the cut-in and the pipeline's `SpeechStarted`, even if the stopped
     * clip's `PlaybackFinished` is handled first.
     */
    @Test fun afterACutInNothingStartsBeforeTheUserFinishes() = runTest {
        val rig = Rig(this)
        rig.core.start()
        runCurrent()
        rig.actor.send(Event.Say("social.hi"))
        rig.actor.send(Event.Say("result.back", SpeechKind.Result))
        runCurrent()
        assertTrue(rig.actor.state.value.speech.current != null)
        val playsBefore = rig.audio.played.size
        rig.actor.userSpeech(CascadeSettings().bargeInMs + 50)
        runCurrent()
        val s = rig.actor.state.value
        assertTrue("the user is speaking", s.userSpeaking)
        assertEquals("nothing else started", null, s.speech.current)
        assertEquals(playsBefore + 1, rig.audio.played.size)
        rig.actor.speechStarted()
        rig.actor.final("wait, one more thing")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(null, rig.actor.state.value.speech.current)
        rig.scope.cancel()
    }
}
