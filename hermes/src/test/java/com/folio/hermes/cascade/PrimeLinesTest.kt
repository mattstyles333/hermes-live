package com.folio.hermes.cascade

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermes.telemetry.TelemetrySink
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speed: the first stock line of a call is a cache hit. `CascadeCore.start` opens the OpenRouter
 * connection and primes the likely slots: each gets its next line fixed now and fetched ahead.
 */
class PrimeLinesTest {
    private val bank = PhraseBank.load()
    private val reducer = CascadeReducer(CascadeEnv(bank))
    private val started = reducer.reduce(State(), Event.Start()).first

    @Test fun aPrimedSlotPlaysTheLineThatWasFetchedAheadWhateverPlaysFirst() {
        val (primed, effects) = reducer.reduce(started, Event.PrimeLines(listOf("start.lookup", "ack.do")))
        val ahead = effects.filterIsInstance<Effect.Prerender>().single().lines
        assertEquals(2, ahead.size)
        assertTrue(ahead[0] in bank["start.lookup"]!!.lines && ahead[1] in bank["ack.do"]!!.lines)
        assertEquals("both rotations saved", setOf("start.lookup", "ack.do"), effects.filterIsInstance<Effect.SaveRotation>().map { it.slotId }.toSet())

        // Another slot plays first (the seed moves on); the primed slots still play what was fetched.
        val tx = Tx(primed, CascadeEnv(bank))
        tx.nextLine("social.hi")
        assertEquals(ahead[1], tx.nextLine("ack.do"))
        assertEquals(ahead[0], tx.nextLine("start.lookup"))
    }

    @Test fun primingAgainChangesNothingAndUnknownSlotsAreSkipped() {
        val (once, _) = reducer.reduce(started, Event.PrimeLines(listOf("start.one")))
        val (twice, effects) = reducer.reduce(once, Event.PrimeLines(listOf("start.one", "no.such.slot")))
        assertEquals(once.rotation.rotations, twice.rotation.rotations)
        assertTrue(effects.none { it is Effect.SaveRotation })
        assertEquals(1, effects.filterIsInstance<Effect.Prerender>().single().lines.size)
    }

    @Test fun anExhaustedPassIsReshuffledNotRepeatingTheLastLine() {
        val lines = bank["status.working"]!!.lines
        val done = Rotation(lines, lines.size, lines.last())
        val primed = Rotations.prime(lines, done, Random(5))
        assertEquals(0, primed.cursor)
        assertFalse(primed.order.first() == lines.last())
        assertEquals(primed.order.first(), Rotations.next(lines, primed, Random(99)).first)
    }

    @Test fun offlineNothingIsFetchedAndBeforeStartNothingHappens() {
        val offline = started.copy(connection = started.connection.copy(online = false))
        assertTrue(reducer.reduce(offline, Event.PrimeLines(listOf("start.one"))).second.none { it is Effect.Prerender })
        val (s, effects) = reducer.reduce(State(), Event.PrimeLines(listOf("start.one")))
        assertTrue("off state (R-Q-1)", effects.isEmpty())
        assertNull(s.rotation.rotations["start.one"])
    }

    /** Through the factory: a call start fetches the likely lines that are not cached, once each, and warms the connection. */
    @Test fun aCallStartFetchesTheLikelyLinesAheadAndWarms() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val clock = TestClock(this)
        var warmed = 0
        val fake = FakeOpenRouter(clock).apply { cached += bank["start.one"]!!.lines }
        val router = object : com.folio.hermes.cascade.ports.OpenRouterPort by fake {
            override suspend fun warm() {
                warmed++
            }
        }
        val hermes = com.folio.hermes.cascade.registry.ClientHermes(com.folio.hermes.cascade.registry.FakeServer())
        val core = CascadeCore.create(
            scope = scope,
            ports = CascadePorts(router, hermes, hermes, FakeAudioOut(clock), MemoryStore(), clock),
            bank = bank, settings = { CascadeSettings() }, telemetry = TelemetrySink.None, zone = ZoneOffset.UTC, seed = 4,
        )
        core.start()
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, warmed)
        val fetched = fake.clips.toList()
        assertEquals("one fetch per likely slot not cached", CascadeCore.LIKELY_SLOTS.size - 1, fetched.size)
        assertEquals(fetched.distinct(), fetched)
        assertTrue("start.one was cached already", fetched.none { it in bank["start.one"]!!.lines })
        scope.cancel()
    }
}
