package com.folio.hermeslive.memo

import com.folio.hermes.cascade.ports.MemoryStore
import com.folio.hermeslive.meeting.Events
import com.folio.hermeslive.meeting.FakeTasks
import com.folio.hermeslive.tasks.HeldTasks
import com.folio.hermeslive.tasks.HeldSubmit
import com.folio.hermeslive.tasks.TaskSubmitter
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
        private val task = object : TaskSubmitter {
            override suspend fun prepare() = this@Rig.prepare()
            override suspend fun submit(brief: String, label: String, key: String) = tasks.submit(brief, label, key)
        }

        fun sender() = MemoSender(
            store, HeldTasks { task }, { transcriber }, { ZoneId.of("Europe/London") }, { now }, events, notices, scheduler,
            recording = { it in live },
            // The fake task table finishes every task at once.
            taskStatus = { "done" },
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
        val failed = ArrayList<String>()
        override fun sent(record: MemoRecord) { sent += record.id }
        override fun empty(record: MemoRecord) { empty += record.id }
        override fun failed(record: MemoRecord) { failed += record.id }
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
        assertTrue(rig.tasks.calls.isEmpty())
        assertEquals(1, rig.scheduler.scheduled)
        assertTrue("the audio stays until Hermes has the text", rig.store.chunk("m1", 0).exists())

        rig.transcriber.reply = { MemoText.Text("Book the dentist for Thursday") }
        val second = rig.sender().flush()
        assertEquals(1, second.sent)
        val sent = rig.store.get("m1")!!
        assertEquals(MemoState.Sent, sent.state)
        assertEquals("task-memo-m1", sent.taskId)
        // Review round 2: a sent memo keeps no words, only what says what became of it.
        assertNull(sent.transcript)
        assertTrue(sent.parts.isEmpty())
        assertEquals("Voice memo · 10:15", sent.label)
        assertEquals(T0, sent.doneAt)
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
        rig.tasks.reply = { HeldSubmit.NotAvailable("no_reply") }
        rig.sender().flush()
        assertEquals(MemoState.Transcribed, rig.store.get("m1")!!.state)
        assertEquals("part 1 part 2 part 3", rig.store.get("m1")!!.transcript)
        assertEquals(3, rig.transcriber.sizes.size)
        // A new process: the stored transcript goes again under the same key, and the task API answers `existing`.
        rig.tasks.reply = { HeldSubmit.Sent("t-1", existing = true) }
        rig.sender().flush()
        assertEquals(true, rig.events.all.last { it.first == "memo.send" }.second["existing"])
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
        // The next try carries on from the chunk that failed (the job's own backoff spaces the tries).
        rig.transcriber.reply = { MemoText.Text("part ${it + 1}") }
        rig.sender().flush()
        assertEquals(4, rig.transcriber.sizes.size)
        assertEquals(MemoState.Sent, rig.store.get("m1")!!.state)
        assertEquals("part 1 part 3 part 4", rig.tasks.calls.single().brief.substringAfter("\n\n"))
    }

    @Test fun theTranscribeTimeoutScalesWithTheClip() {
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
        assertTrue(rig.store.chunk("m1", 0).exists())
    }

    @Test fun tooLargeFailsForGoodAndIsNotRetried() = runTest {
        val rig = Rig()
        rig.recorded()
        rig.transcriber.reply = { MemoText.TooLarge }
        rig.sender().flush()
        assertEquals(MemoState.Failed, rig.store.get("m1")!!.state)
        assertEquals("too_large", rig.store.get("m1")!!.heldReason)
        assertFalse("a memo that can never go keeps no audio", rig.store.audioDir("m1").exists())
        assertEquals(listOf("m1"), rig.notices.failed)
        rig.sender().flush()
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
        val c = async { sender.flush() }
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

    @Test fun aTextMemoIsSentAsItIsWithoutTranscription() = runTest {
        val rig = Rig()
        rig.store.put(MemoRecord("t1", T0, MemoState.Transcribed, transcript = "Book the dentist", typed = true))
        rig.sender().flush()
        assertTrue(rig.transcriber.sizes.isEmpty())
        val call = rig.tasks.calls.single()
        assertEquals("memo-t1", call.key)
        assertEquals("Text memo · 10:15", call.label)
        assertTrue(call.brief.startsWith("Request saved offline at 10:15 on Tue 20 Oct 2026 (London time). Treat it as the user's request(s):"))
        assertTrue(call.brief.endsWith("Book the dentist"))
        assertEquals(MemoState.Sent, rig.store.get("t1")!!.state)
    }

    @Test fun twoMemosAreIndependentAndOneHeldDoesNotBlockTheOther() = runTest {
        val rig = Rig()
        rig.recorded("m1", seconds = 2.0, createdAt = T0)
        rig.recorded("m2", seconds = 3.0, createdAt = T0 + 1)
        rig.transcriber.reply = { MemoText.Text("words") }
        val held = 44 + 2 * RATE * 2
        val transcriber = rig.transcriber
        val picky = object : MemoTranscriber {
            override suspend fun transcribe(wav: ByteArray, timeoutMs: Long): MemoText =
                if (wav.size == held) MemoText.Hold("timeout") else transcriber.transcribe(wav, timeoutMs)
        }
        val sender = MemoSender(
            rig.store, HeldTasks { rig.tasks }, { picky }, { ZoneId.of("Europe/London") }, { rig.now }, rig.events, rig.notices, rig.scheduler,
        )
        val r = sender.flush()
        assertEquals(1, r.sent)
        assertEquals(1, r.held)
        assertEquals(listOf("memo-m2"), rig.tasks.calls.map { it.key })
        assertEquals("timeout", rig.store.get("m1")!!.heldReason)
        assertEquals(MemoState.Sent, rig.store.get("m2")!!.state)
    }

    /** Review round 2: finished memos are pruned after 7 days (as meetings are by their setting); waiting ones never. */
    @Test fun finishedMemosArePrunedAfterSevenDays() = runTest {
        val rig = Rig()
        val day = MemoRetention.DAY_MS
        rig.now = T0 + 30 * day
        rig.store.put(MemoRecord("old-sent", T0, MemoState.Sent, taskId = "t", doneAt = rig.now - 8 * day, taskDone = true))
        // Sent long ago but its task not known to be finished: it still needs the runtime, so it stays.
        rig.store.put(MemoRecord("old-open", T0, MemoState.Sent, taskId = "t2", doneAt = rig.now - 8 * day))
        rig.store.put(MemoRecord("old-empty", T0, MemoState.Empty, doneAt = rig.now - 8 * day))
        rig.store.put(MemoRecord("old-failed", T0, MemoState.Failed, doneAt = rig.now - 9 * day))
        rig.store.put(MemoRecord("new-sent", T0, MemoState.Sent, taskId = "t", doneAt = rig.now - 6 * day, taskDone = true))
        rig.store.put(MemoRecord("old-text", T0, MemoState.Transcribed, transcript = "still to go", typed = true))
        rig.tasks.reply = { HeldSubmit.Offline }
        MemoSender(
            rig.store, HeldTasks { rig.tasks }, { rig.transcriber }, { ZoneId.of("Europe/London") }, { rig.now }, rig.events, rig.notices, rig.scheduler,
            taskStatus = { null },
        ).flush()
        assertEquals(setOf("new-sent", "old-open", "old-text"), rig.store.all().map { it.id }.toSet())
        assertEquals(3, rig.events.all.single { it.first == "memo.deleted" }.second["count"])
        assertEquals(0, MemoRetention.prune(rig.store, rig.now, rig.events))
    }

    /** Final review: a sent memo stays open until its task is finished in the task table; a not_sent one is resent. */
    @Test fun aSentMemoStaysOpenUntilItsTaskIsFinished() = runTest {
        val rig = Rig()
        rig.recorded()
        var status: String? = "queued"
        val retried = ArrayList<String>()
        val sender = MemoSender(
            rig.store, HeldTasks { rig.tasks }, { rig.transcriber }, { ZoneId.of("Europe/London") }, { rig.now }, rig.events, rig.notices, rig.scheduler,
            taskStatus = { status }, retryTask = { retried += it },
        )
        assertEquals(1, sender.flush().open)
        assertTrue("it still needs the runtime", rig.store.get("m1")!!.needsRuntime)
        status = "not_sent"
        assertEquals(1, sender.flush().open)
        assertEquals(listOf("task-memo-m1"), retried)
        status = null
        assertEquals("unknown (no runtime yet) is not finished", 1, sender.flush().open)
        status = "done"
        val r = sender.flush()
        assertEquals(0, r.open)
        assertEquals(0, r.wake)
        assertTrue(rig.store.get("m1")!!.taskDone)
        assertFalse(rig.store.get("m1")!!.needsRuntime)
        assertEquals("one task, never started again", 1, rig.tasks.calls.size)
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
