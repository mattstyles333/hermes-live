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
        /** The wall clock may jump (NTP); the cap must not. */
        var wallJump = 0L
        val call = MemoCall(
            scope = CoroutineScope(test.backgroundScope.coroutineContext),
            io = StandardTestDispatcher(test.testScheduler),
            store = { store },
            audio = audio,
            effects = effects,
            speech = { speech },
            mic = { mic },
            settings = { LiveSettings() },
            clock = { T0 + test.testScheduler.currentTime + wallJump },
            elapsed = { test.testScheduler.currentTime },
            telemetry = events,
            limits = limits,
            onSaved = { saved += it },
            newId = { "m1" },
        )

        fun callWith(mic: AudioSource = this.mic, store: MemoStore = this.store, newId: () -> String = { "m1" }) = MemoCall(
            CoroutineScope(test.backgroundScope.coroutineContext), StandardTestDispatcher(test.testScheduler), { store }, audio, effects,
            { speech }, { mic }, { LiveSettings() }, { T0 + test.testScheduler.currentTime }, { test.testScheduler.currentTime }, events, MemoSettings(chunkMs = 1_000),
            onSaved = { saved += it }, newId = newId,
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

    @Test fun mutedWritesNothingAndTheCapStillCountsWallTime() = runTest {
        val rig = Rig(this, MemoSettings(chunkMs = 1_000, capMs = 3_000))
        rig.call.start(CallRequest(memo = true, muted = true))
        advanceTimeBy(2_000 + 2_000)
        assertEquals("no zeros on disk while muted", 0L, rig.store.chunk("m1", 0).let { if (it.exists()) it.length() else 0L })
        rig.call.toggleMute()
        advanceTimeBy(20_000)
        val memo = rig.store.get("m1")!!
        assertTrue("1 s heard before the 3 s cap (wall time): ${memo.durationMs}", memo.durationMs in 1_000L..1_020L)
        assertEquals("cap", rig.events.all.single { it.first == "memo.end" }.second["reason"])
    }

    @Test fun anotherCallWritesNothingUntilItIsOver() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 1_000)
        rig.call.onOtherCall(true)
        advanceTimeBy(5_000)
        rig.call.onOtherCall(false)
        advanceTimeBy(1_000)
        rig.call.end()
        advanceTimeBy(10_000)
        assertEquals(2_000L, rig.store.get("m1")!!.durationMs)
    }

    @Test fun aMuteOnlyMemoIsTooShort() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true, muted = true))
        advanceTimeBy(2_000 + 5_000)
        rig.call.end()
        advanceTimeBy(10_000)
        assertNull(rig.store.get("m1"))
    }

    /** Review round 1: a mic error at minute 2 keeps what was recorded. */
    @Test fun aMicErrorMidwayKeepsTheFinishedChunks() = runTest {
        val rig = Rig(this)
        val failing = object : AudioSource {
            val paced = PacedMic { testScheduler.currentTime }
            override var onRouteChanged: (() -> Unit)? = null
            override fun open() = paced.open()
            override fun read(buffer: ShortArray): Int {
                if (testScheduler.currentTime >= 2_000 + 2_500) throw IllegalStateException("ERROR_DEAD_OBJECT")
                return paced.read(buffer)
            }
            override fun close() = paced.close()
        }
        val call = rig.callWith(mic = failing)
        call.start(CallRequest(memo = true))
        advanceTimeBy(20_000)
        val memo = rig.store.get("m1")!!
        assertEquals(MemoState.Recorded, memo.state)
        assertEquals(3, memo.chunks)
        assertTrue(memo.durationMs in 2_400L..2_500L)
        assertEquals(listOf(memo), rig.saved)
        assertTrue(call.state.value.error == null)
        assertFalse(call.state.value.notice!!.contains("microphone"))
        assertEquals("mic_failed", rig.events.all.single { it.first == "memo.end" }.second["reason"])
    }

    @Test fun aStorageFailureIsNotTheMicrophone() = runTest {
        val rig = Rig(this)
        val broken = object : com.folio.hermes.cascade.ports.Store by rig.kv {
            override fun put(key: String, value: String?) = throw java.io.IOException("ENOSPC")
        }
        val call = rig.callWith(store = MemoStore({ broken }, rig.dir))
        call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 3_000)
        call.end()
        advanceTimeBy(10_000)
        val shown = call.state.value.error!!
        assertFalse(shown.contains("microphone"))
        assertTrue(shown.contains("save"))
        assertTrue(rig.saved.isEmpty())
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
            { rig.speech }, { broken }, { LiveSettings() }, { T0 }, { testScheduler.currentTime }, rig.events, newId = { "m2" },
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

    /** Review round 1: offline, shared text (or Folio's prompt) is a text memo: no audio, no transcription. */
    @Test fun offlineSharedTextIsATextMemoWithNoAudio() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(prompt = "  Book the dentist for Thursday ", memo = true, memoCause = MemoCause.Offline, entry = "share"))
        runCurrent()
        val memo = rig.store.get("m1")!!
        assertEquals(MemoState.Transcribed, memo.state)
        assertEquals("Book the dentist for Thursday", memo.transcript)
        assertTrue(memo.typed)
        assertEquals(0, memo.chunks)
        assertFalse(rig.store.audioDir("m1").exists())
        assertNull(rig.mic.openedAt)
        assertEquals(0, rig.audio.began)
        assertTrue(rig.speech.said.isEmpty())
        assertFalse(rig.call.state.value.inCall)
        assertEquals(listOf(memo), rig.saved)
        assertEquals("text", rig.events.all.single { it.first == "memo.start" }.second["kind"])
    }

    @Test fun textArrivingDuringARecordingIsItsOwnMemoAndTheRecordingGoesOn() = runTest {
        val rig = Rig(this)
        var n = 0
        val call = rig.callWith(newId = { "m${++n}" })
        call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 1_500)
        call.start(CallRequest(prompt = "Also call the bank"))
        runCurrent()
        assertTrue(call.state.value.inCall)
        assertEquals("Also call the bank", rig.store.get("m2")!!.transcript)
        call.end()
        advanceTimeBy(10_000)
        assertEquals(MemoState.Recorded, rig.store.get("m1")!!.state)
        assertEquals(listOf("m2", "m1"), rig.saved.map { it.id })
    }

    /** Review round 2: sign-out while recording wipes the memo, and nothing brings it back. */
    @Test fun signOutMidRecordingLeavesNothingBehind() = runTest {
        val rig = Rig(this)
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 1_500)
        assertTrue(rig.store.chunk("m1", 1).exists())
        // CascadeSignOut.wipe: every cascade.* key and the memo directory.
        rig.kv.keys("cascade.").forEach { rig.kv.put(it, null) }
        rig.dir.deleteRecursively()
        advanceTimeBy(3_000)
        assertTrue("capture stopped at the next chunk", rig.mic.closed)
        rig.call.end()
        advanceTimeBy(10_000)
        assertTrue(rig.kv.keys("cascade.").isEmpty())
        assertFalse(rig.dir.exists())
        assertTrue(rig.saved.isEmpty())
        assertEquals("no end line", 1, rig.speech.said.size)
        assertNull(rig.call.state.value.notice)
        assertNull(rig.call.state.value.error)
        assertFalse(rig.call.state.value.inCall)
    }

    @Test fun aWallClockJumpNeitherEndsNorExtendsTheCap() = runTest {
        val rig = Rig(this, MemoSettings(chunkMs = 1_000, capMs = 5_000))
        rig.call.start(CallRequest(memo = true))
        advanceTimeBy(2_000 + 1_000)
        rig.wallJump = 3_600_000L
        advanceTimeBy(1_000)
        assertTrue("an hour's NTP jump did not end it", rig.call.state.value.inCall)
        rig.wallJump = -3_600_000L
        advanceTimeBy(5_000)
        assertEquals("cap", rig.events.all.single { it.first == "memo.end" }.second["reason"])
        assertTrue(rig.store.get("m1")!!.durationMs in 4_900L..5_000L)
        advanceTimeBy(10_000)
    }

    @Test fun submitTextIsATextMemoFromShareAndIgnoresBlanks() = runTest {
        val rig = Rig(this)
        rig.call.submitText("   ")
        assertTrue(rig.kv.keys("cascade.").isEmpty())
        rig.call.submitText(" Pay the invoice ")
        val memo = rig.store.get("m1")!!
        assertEquals("Pay the invoice", memo.transcript)
        assertTrue(memo.typed)
        assertEquals("share", rig.events.all.single { it.first == "memo.start" }.second["entry"])
    }

    private companion object {
        const val T0 = 1_792_487_700_000L
    }
}
