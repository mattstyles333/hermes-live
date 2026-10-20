package com.folio.hermeslive.memo

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermeslive.audio.AudioSource
import com.folio.hermeslive.call.CallPhase
import com.folio.hermeslive.call.CallRequest
import com.folio.hermeslive.call.Earcon
import com.folio.hermeslive.call.FakeAudio
import com.folio.hermeslive.call.FakeEffects
import com.folio.hermeslive.call.FocusMode
import com.folio.hermeslive.call.MemoCause
import com.folio.hermeslive.data.LiveSettings
import com.folio.hermeslive.meeting.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The memo recorder as a call, on virtual time: the line first, then the mic; chunks; the cap; too short. */
class MemoCallTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The phone's voice: ready after [readyAfterMs] (null: never), each line takes [lineMs]. */
    class FakeSpeech(private val scope: TestScope, var readyAfterMs: Long? = 0, var lineMs: Long = 2_000) : MemoSpeech {
        val said = ArrayList<Pair<String, Long>>()
        var stopped = 0
        var shut = 0
        override suspend fun awaitReady(): Boolean {
            val after = readyAfterMs ?: awaitCancellation()
            delay(after)
            return true
        }
        override suspend fun speak(text: String) {
            said += text to scope.testScheduler.currentTime
            delay(lineMs)
        }
        override fun stop() { stopped++ }
        override fun shutdown() { shut++ }
    }

    /** A mic that yields audio at real-time pace on the virtual clock (1,000 per sample). */
    class PacedMic(private val now: () -> Long) : AudioSource {
        var openedAt: Long? = null
        var closed = false
        private var produced = 0L
        override var onRouteChanged: (() -> Unit)? = null
        override fun open() { openedAt = now() }
        override fun read(buffer: ShortArray): Int {
            val from = openedAt ?: return -1
            val n = minOf(buffer.size.toLong(), (now() - from) * 16 - produced).toInt()
            buffer.fill(1_000, 0, n)
            produced += n
            return n
        }
        override fun close() { closed = true }
    }

    private inner class Rig(val test: TestScope, limits: MemoSettings = MemoSettings(chunkMs = 1_000)) {
        val kv = MemoryStore()
        val dir: File = tmp.newFolder("memo")
        val store = MemoStore({ kv }, dir)
        val audio = FakeAudio()
        val effects = FakeEffects()
        val speech = FakeSpeech(test)
        val mic = PacedMic { test.testScheduler.currentTime }
        val events = Events()
        val saved = ArrayList<MemoRecord>()
        val call = MemoCall(
            scope = CoroutineScope(test.backgroundScope.coroutineContext),
            io = StandardTestDispatcher(test.testScheduler),
            store = { store },
            audio = audio,
            effects = effects,
            speech = { speech },
            mic = { mic },
            settings = { LiveSettings() },
            clock = { T0 + test.testScheduler.currentTime },
            telemetry = events,
            limits = limits,
            onSaved = { saved += it },
            newId = { "m1" },
        )
    }

    @Test fun theStartLinePlaysFirstAndTheMicOpensOnlyAfterIt() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true, entry = "memo", memoCause = MemoCause.Offline))
        runCurrent()
        assertTrue(rig.call.state.value.inCall)
        assertTrue(rig.call.state.value.memo)
        assertEquals(1, rig.audio.began)
        assertEquals(listOf(FocusMode.Hold), rig.audio.focus)
        assertEquals(MemoCall.lines(MemoCause.Offline).start to 0L, rig.speech.said.single())
        advanceTimeBy(1_999)
        assertNull("the line is never recorded", rig.mic.openedAt)
        assertNull(rig.store.get("m1"))
        advanceTimeBy(2)
        assertEquals(2_000L, rig.mic.openedAt)
        assertEquals(MemoState.Recording, rig.store.get("m1")!!.state)
        assertEquals("m1", rig.call.recordingId)
        val start = rig.events.all.single { it.first == "memo.start" }.second
        assertEquals(mapOf("entry" to "memo", "cause" to "offline"), start)
        rig.call.end()
        advanceTimeBy(10_000)
    }

    @Test fun noVoiceReadyWithinOneAndAHalfSecondsIsAnEarconAndAHaptic() = runTest {
        val rig = Rig(this)
        rig.speech.readyAfterMs = null
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(1_499)
        assertNull(rig.mic.openedAt)
        advanceTimeBy(2)
        assertEquals(1_500L, rig.mic.openedAt)
        assertEquals(listOf(Earcon.Connected), rig.effects.earcons)
        assertEquals(1, rig.effects.haptics)
        assertTrue(rig.speech.said.isEmpty())
        rig.call.end()
        advanceTimeBy(10_000)
    }

    @Test fun aLongStartLineIsCutAtFourSeconds() = runTest {
        val rig = Rig(this)
        rig.speech.lineMs = 9_000
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(4_001)
        assertEquals(4_000L, rig.mic.openedAt)
        assertEquals(1, rig.speech.stopped)
        rig.call.end()
        advanceTimeBy(20_000)
    }

    @Test fun itRecordsInChunksAndSavesTheMemoWhenEnded() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true, memoCause = MemoCause.Offline))
        advanceTimeBy(2_000 + 2_500)
        rig.call.end()
        runCurrent()
        assertEquals(CallPhase.Ending, rig.call.state.value.phase)
        advanceTimeBy(10_000)
        val memo = rig.store.get("m1")!!
        assertEquals(MemoState.Recorded, memo.state)
        assertEquals(3, memo.chunks)
        assertEquals(2_500L, memo.durationMs)
        assertEquals(T0, memo.createdAt)
        assertEquals(44L + 16_000 * 2, rig.store.chunk("m1", 0).length())
        assertEquals(44L + 8_000 * 2, rig.store.chunk("m1", 2).length())
        assertTrue(rig.mic.closed)
        assertEquals(listOf(memo), rig.saved)
        assertEquals(MemoCall.endLine(MemoCause.Offline, capped = false), rig.speech.said.last().first)
        assertTrue("the end line plays after the mic closed", rig.speech.said.last().second >= 4_500)
        assertEquals(1, rig.speech.shut)
        assertEquals(1, rig.audio.ended)
        assertEquals(CallPhase.Ended, rig.call.state.value.phase)
        assertFalse(rig.call.state.value.inCall)
        assertEquals(MemoCall.lines(MemoCause.Offline).saved, rig.call.state.value.notice)
        assertNull(rig.call.recordingId)
        val end = rig.events.all.single { it.first == "memo.end" }.second
        assertEquals(mapOf("reason" to "ended", "duration_s" to 2L, "chunks" to 3, "saved" to true), end)
    }

    @Test fun underASecondIsDiscarded() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 500)
        rig.call.end()
        advanceTimeBy(10_000)
        assertNull(rig.store.get("m1"))
        assertFalse(rig.store.audioDir("m1").exists())
        assertTrue(rig.saved.isEmpty())
        assertEquals("Too short. Nothing was saved.", rig.call.state.value.notice)
        assertEquals("no end line", 1, rig.speech.said.size)
    }

    @Test fun endingDuringTheStartLineRecordsNothing() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(500)
        rig.call.end()
        advanceTimeBy(10_000)
        assertNull(rig.mic.openedAt)
        assertTrue(rig.kv.keys("cascade.").isEmpty())
        assertEquals(1, rig.audio.ended)
        assertFalse(rig.call.state.value.inCall)
    }

    @Test fun theCapStopsTheMemoAndSaysSo() = runTest {
        val rig = Rig(this, MemoSettings(chunkMs = 1_000, capMs = 3_000))
        rig.call.start(CallRequest(memo = true, memoCause = MemoCause.Offline))
        advanceTimeBy(2_000 + 6_000)
        advanceTimeBy(10_000)
        val memo = rig.store.get("m1")!!
        assertEquals(3_000L, memo.durationMs)
        assertEquals(MemoCall.endLine(MemoCause.Offline, capped = true), rig.speech.said.last().first)
        assertTrue(rig.speech.said.last().first.startsWith("That's ten minutes"))
        assertEquals("cap", rig.events.all.single { it.first == "memo.end" }.second["reason"])
        assertFalse(rig.call.state.value.inCall)
    }

    @Test fun mutedRecordsSilence() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true, muted = true))
        advanceTimeBy(2_000 + 1_500)
        rig.call.end()
        advanceTimeBy(10_000)
        val bytes = rig.store.chunk("m1", 0).readBytes()
        assertTrue(bytes.drop(44).all { it == 0.toByte() })
    }

    @Test fun aSecondEndCutsTheEndLine() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 1_500)
        rig.call.end()
        advanceTimeBy(100)
        assertEquals(2, rig.speech.said.size)
        rig.call.end()
        assertEquals(1, rig.speech.stopped)
        advanceTimeBy(10_000)
        assertFalse(rig.call.state.value.inCall)
    }

    @Test fun theMicFailingSavesNothingAndSaysWhy() = runTest {
        val rig = Rig(this)
        val broken = object : AudioSource {
            override var onRouteChanged: (() -> Unit)? = null
            override fun open() = throw IllegalStateException("AudioRecord not initialised")
            override fun read(buffer: ShortArray) = -1
            override fun close() = Unit
        }
        val call = MemoCall(
            CoroutineScope(backgroundScope.coroutineContext), StandardTestDispatcher(testScheduler), { rig.store }, rig.audio, rig.effects,
            { rig.speech }, { broken }, { LiveSettings() }, { T0 }, rig.events, newId = { "m2" },
        )
        call.start(CallRequest(memo = true))
        advanceTimeBy(10_000)
        assertNull(rig.store.get("m2"))
        assertEquals(CallPhase.Ended, call.state.value.phase)
        assertTrue(call.state.value.error!!.contains("microphone"))
    }

    @Test fun eachCauseHasItsOwnLinesAndTelemetry() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true, memoCause = MemoCause.CallDropped))
        runCurrent()
        val said = rig.speech.said.single().first
        assertTrue(said.startsWith("The call dropped"))
        assertEquals("The call dropped. Recording a memo for Hermes.", rig.call.state.value.notice)
        assertEquals("call_dropped", rig.events.all.single { it.first == "memo.start" }.second["cause"])
        assertEquals(MemoCause.entries.size, MemoCause.entries.map { MemoCall.lines(it) }.size)
        assertTrue(MemoCause.entries.filter { it != MemoCause.CallDropped }.none { MemoCall.lines(it).start.contains("dropped") })
        rig.call.end()
        advanceTimeBy(10_000)
    }

    private companion object {
        const val T0 = 1_792_487_700_000L
    }
}
