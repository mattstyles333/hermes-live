package com.folio.hermeslive.memo

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermeslive.meeting.Events
import com.folio.hermeslive.meeting.FakeTasks
import com.folio.hermeslive.meeting.HeldTasks
import com.folio.hermeslive.meeting.MeetingSubmit
import com.folio.hermeslive.meeting.MeetingTasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import java.io.RandomAccessFile
import java.time.ZoneId

/** Held when it can't go, transcribed once, sent once with one key; audio deleted after its text is stored. */
class MemoSenderTest {
    @get:Rule val tmp = TemporaryFolder()

    private inner class Rig(val kv: MemoryStore = MemoryStore(), dir: File = tmp.newFolder("memo")) {
        val store = MemoStore({ kv }, dir)
        val tasks = FakeTasks()
        val transcriber = FakeTranscriber()
        val events = Events()
        val notices = RecordingNotices()
        val scheduler = CountingScheduler()
        var now = T0
        var live = setOf<String>()
        var prepare: suspend () -> Unit = {}
        private val task = object : MeetingTasks {
            override suspend fun prepare() = this@Rig.prepare()
            override suspend fun submit(brief: String, label: String, key: String) = tasks.submit(brief, label, key)
        }

        fun sender() = MemoSender(
            store, HeldTasks { task }, { transcriber }, { ZoneId.of("Europe/London") }, { now }, events, notices, scheduler,
            recording = { it in live },
        )

        /** A recorded memo of [seconds], in 60 s chunks (the last one partial). */
        fun recorded(id: String = "m1", seconds: Double = 2.0, createdAt: Long = T0): MemoRecord {
            val samples = (seconds * RATE).toLong()
            var left = samples
            var n = 0
            while (left > 0) {
                val take = minOf(left, 60L * RATE)
                wav(store.chunk(id, n), take)
                left -= take
                n++
            }
            return MemoRecord(id, createdAt, MemoState.Recorded, chunks = n, durationMs = samples * 1000 / RATE).also(store::put)
        }
    }

    class FakeTranscriber : MemoTranscriber {
        val sizes = ArrayList<Int>()
        var reply: (Int) -> MemoText = { MemoText.Text("part ${it + 1}") }
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun transcribe(wav: ByteArray, timeoutMs: Long): MemoText {
            val i = sizes.size
            sizes += wav.size
            gate?.await()
            return reply(i)
        }
    }

    class RecordingNotices : MemoNotices {
        val sent = ArrayList<String>()
        val empty = ArrayList<String>()
        override fun sent(record: MemoRecord) { sent += record.id }
        override fun empty(record: MemoRecord) { empty += record.id }
    }

    class CountingScheduler : MemoScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun schedule() { scheduled++ }
        override fun cancel() { cancelled++ }
    }

    @Test fun offlineItIsHeldThenTranscribedAndSentOnceWithOneKey() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.Hold("offline") }
        val first = rig.sender().flush()
        assertEquals(1, first.held)
        assertEquals("offline", rig.store.get("m1")!!.heldReason)
        assertEquals("offline is retried by the network, not a timer", 0L, rig.store.get("m1")!!.nextAttemptAt)
        assertTrue(rig.tasks.calls.isEmpty())
        assertEquals(1, rig.scheduler.scheduled)
        assertTrue("the audio stays until Hermes has the text", rig.store.chunk("m1", 0).exists())

        rig.transcriber.reply = { MemoText.Text("Book the dentist for Thursday") }
        val second = rig.sender().flush()
        assertEquals(1, second.sent)
        val sent = rig.store.get("m1")!!
        assertEquals(MemoState.Sent, sent.state)
        assertEquals("task-memo-m1", sent.taskId)
        val call = rig.tasks.calls.single()
        assertEquals("memo-m1", call.key)
        assertEquals("Voice memo · 10:15", call.label)
        assertTrue(call.brief.startsWith("Offline voice memo recorded at 10:15 on Tue 20 Oct 2026 (London time). Treat it as the user's request(s):"))
        assertTrue(call.brief.endsWith("Book the dentist for Thursday"))
        assertEquals(listOf("m1"), rig.notices.sent)
        assertFalse(rig.store.audioDir("m1").exists())
        assertEquals(1, rig.scheduler.cancelled)
        // Nothing more to do.
        assertEquals(0, rig.sender().flush().sent)
        assertEquals(1, rig.tasks.calls.size)
        assertEquals(listOf("held", "sent"), rig.events.all.filter { it.first == "memo.send" }.map { it.second["outcome"] })
    }

    @Test fun aKillBetweenTranscribeAndStartSendsOnceWithTheSameKey() = runTest {
        val rig = Rig()
        rig.recorded(seconds = 130.0)
        // The start reached the task table but its reply was lost (a kill): held, transcript kept.
        rig.tasks.reply = { MeetingSubmit.NotAvailable("no_reply") }
        rig.sender().flush()
        assertEquals(MemoState.Transcribed, rig.store.get("m1")!!.state)
        assertEquals("part 1 part 2 part 3", rig.store.get("m1")!!.transcript)
        assertEquals(3, rig.transcriber.sizes.size)
        // A new process: the stored transcript goes again under the same key (the task API answers `existing`).
        rig.tasks.reply = { MeetingSubmit.Sent("t-1") }
        rig.sender().flush(force = true)
        assertEquals(listOf("memo-m1", "memo-m1"), rig.tasks.calls.map { it.key })
        assertEquals(rig.tasks.calls[0].brief, rig.tasks.calls[1].brief)
        assertEquals("never transcribed twice", 3, rig.transcriber.sizes.size)
        assertEquals(MemoState.Sent, rig.store.get("m1")!!.state)
    }

    @Test fun aPartialLastChunkIsTranscribedWithItsOwnLength() = runTest {
        val rig = Rig()
        rig.recorded(seconds = 150.0)
        rig.sender().flush()
        assertEquals(listOf(44 + 60 * RATE * 2, 44 + 60 * RATE * 2, 44 + 30 * RATE * 2), rig.transcriber.sizes)
    }

    @Test fun aChunkFailingMidwayKeepsTheEarlierTextAndDeletesOnlyItsAudio() = runTest {
        val rig = Rig()
        rig.recorded(seconds = 130.0)
        rig.transcriber.reply = { if (it == 1) MemoText.Hold("timeout") else MemoText.Text("part ${it + 1}") }
        rig.sender().flush()
        val memo = rig.store.get("m1")!!
        assertEquals(listOf("part 1"), memo.parts)
        assertFalse("its text is stored: its audio goes", rig.store.chunk("m1", 0).exists())
        assertTrue(rig.store.chunk("m1", 1).exists())
        assertEquals(1, memo.attempts)
        assertEquals(T0 + MemoSender.BACKOFF_MIN_MS, memo.nextAttemptAt)
        // Not due yet: nothing is asked.
        rig.transcriber.reply = { MemoText.Text("part ${it + 1}") }
        rig.sender().flush()
        assertEquals(2, rig.transcriber.sizes.size)
        rig.now += MemoSender.BACKOFF_MIN_MS
        rig.sender().flush()
        assertEquals("part 1 part 3 part 4", rig.store.get("m1")!!.transcript)
    }

    @Test fun backoffDoublesToAnHour() {
        assertEquals(60_000L, MemoSender.backoffMs(1))
        assertEquals(120_000L, MemoSender.backoffMs(2))
        assertEquals(3_600_000L, MemoSender.backoffMs(7))
        assertEquals(3_600_000L, MemoSender.backoffMs(40))
        assertEquals(15_000L + 15_000L, MemoSender.timeoutFor(60L * RATE))
    }

    @Test fun noSpeechIsEmptyWithAQuietNoticeAndNoTask() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.Text("  ") }
        rig.sender().flush()
        assertEquals(MemoState.Empty, rig.store.get("m1")!!.state)
        assertEquals(listOf("m1"), rig.notices.empty)
        assertTrue(rig.tasks.calls.isEmpty())
        assertFalse(rig.store.audioDir("m1").exists())
        assertEquals("empty", rig.events.all.single { it.first == "memo.send" }.second["outcome"])
    }

    @Test fun anOlderServerHoldsItAsServerOld() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.Hold("server_old") }
        rig.sender().flush()
        val memo = rig.store.get("m1")!!
        assertEquals(MemoState.Recorded, memo.state)
        assertEquals("server_old", memo.heldReason)
        assertTrue(memo.nextAttemptAt > T0)
        assertTrue(rig.store.chunk("m1", 0).exists())
    }

    @Test fun tooLargeFailsForGoodAndIsNotRetried() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.TooLarge }
        rig.sender().flush()
        assertEquals(MemoState.Failed, rig.store.get("m1")!!.state)
        assertEquals("too_large", rig.store.get("m1")!!.heldReason)
        rig.sender().flush(force = true)
        assertEquals(1, rig.transcriber.sizes.size)
        assertEquals("nothing left to wake for", 0, rig.scheduler.scheduled)
    }

    @Test fun signedOutNeverAsksTheTaskTableToStart() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.Hold("signed_out") }
        rig.sender().flush()
        assertTrue(rig.tasks.calls.isEmpty())
        assertEquals("signed_out", rig.store.get("m1")!!.heldReason)
    }

    @Test fun twoFlushesRacingTranscribeAndSendOnce() = runTest {
        val rig = Rig()
        rig.recorded(seconds = 70.0)
        val gate = CompletableDeferred<Unit>()
        rig.transcriber.gate = gate
        // The process's one sender: the network-back flush, the job's and a memo ending at once.
        val sender = rig.sender()
        val a = async { sender.flush() }
        val b = async { sender.send("m1") }
        val c = async { sender.flush(force = true) }
        runCurrent()
        gate.complete(Unit)
        a.await(); b.await(); c.await()
        assertEquals(2, rig.transcriber.sizes.size)
        assertEquals(1, rig.tasks.calls.size)
    }

    @Test fun aTornWavFromAKillIsRepairedAndSent() = runTest {
        val rig = Rig()
        // Killed mid-recording: the record still says recording, the second file's sizes are zero and it ends mid-sample.
        rig.store.put(MemoRecord("m1", T0, MemoState.Recording, chunks = 2))
        wav(rig.store.chunk("m1", 0), 60L * RATE)
        val torn = rig.store.chunk("m1", 1)
        RandomAccessFile(torn, "rw").use { f ->
            f.write(ByteArray(44).also { "RIFF".toByteArray().copyInto(it) })
            f.write(ByteArray(2 * RATE + 1) { 7 })
        }
        // One being recorded now in this process is left alone.
        rig.store.put(MemoRecord("live", T0 + 1, MemoState.Recording, chunks = 1))
        rig.live = setOf("live")
        rig.sender().flush()
        assertEquals(listOf(44 + 60 * RATE * 2, 44 + RATE * 2), rig.transcriber.sizes)
        assertEquals(MemoState.Sent, rig.store.get("m1")!!.state)
        assertEquals(61_000L, rig.store.get("m1")!!.durationMs)
        assertEquals(MemoState.Recording, rig.store.get("live")!!.state)
        assertTrue(rig.events.all.any { it.first == "memo.end" && it.second["reason"] == "recovered" })
    }

    @Test fun aKilledMemoUnderASecondIsDiscarded() = runTest {
        val rig = Rig()
        rig.store.put(MemoRecord("m1", T0, MemoState.Recording, chunks = 1))
        wav(rig.store.chunk("m1", 0), RATE / 2L)
        rig.sender().flush()
        assertNull(rig.store.get("m1"))
        assertFalse(rig.store.audioDir("m1").exists())
        assertTrue(rig.transcriber.sizes.isEmpty())
    }

    @Test fun signOutMidSendWipesAndNothingIsWrittenBackOrSent() = runTest {
        val rig = Rig()
        rig.recorded()
        val gate = CompletableDeferred<Unit>()
        rig.transcriber.gate = gate
        val flush = async { rig.sender().flush() }
        runCurrent()
        // Sign-out: every cascade.* key and the memo directory go.
        rig.kv.keys("cascade.").forEach { rig.kv.put(it, null) }
        rig.store.dir.deleteRecursively()
        gate.complete(Unit)
        flush.await()
        assertTrue(rig.kv.keys("cascade.").isEmpty())
        assertFalse(rig.store.dir.exists())
        assertTrue(rig.tasks.calls.isEmpty())
    }

    @Test fun anotherAccountsWipeBeforeReadingMeansNothingIsSent() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.prepare = {
            rig.kv.keys("cascade.").forEach { rig.kv.put(it, null) }
            rig.store.dir.deleteRecursively()
        }
        rig.sender().flush()
        assertTrue(rig.transcriber.sizes.isEmpty())
        assertTrue(rig.tasks.calls.isEmpty())
    }

    @Test fun telemetryCarriesNoTextAndNoFileNames() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.Text("SECRET-WORDS") }
        rig.sender().flush()
        val dump = rig.events.all.toString()
        assertFalse(dump.contains("SECRET"))
        assertFalse(dump.contains("chunk-"))
        assertFalse(dump.contains("m1"))
    }

    companion object {
        const val RATE = 16_000

        /** 2026-10-20 10:15 London (BST). */
        const val T0 = 1_792_487_700_000L

        fun wav(file: File, samples: Long) {
            val w = WavWriter.open(file, RATE)
            val buf = ShortArray(RATE) { (it % 100).toShort() }
            var left = samples
            while (left > 0) {
                val n = minOf(left, buf.size.toLong()).toInt()
                w.write(buf, 0, n)
                left -= n
            }
            w.close()
        }
    }
}
