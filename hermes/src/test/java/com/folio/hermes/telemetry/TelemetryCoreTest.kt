package com.folio.hermes.telemetry

import android.app.ApplicationExitInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryCoreTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private var now = 1_700_000_000_000L
    private var url: HttpUrl? = null
    private val marks = object : ExitMarks {
        var last = 0L
        override fun lastReportedMs() = last
        override fun setLastReportedMs(value: Long) { last = value }
    }
    private var exits = listOf<ExitInfo>()

    private fun core(maxQueue: Int = TelemetryCore.MAX_QUEUE) = TelemetryCore(
        store = PendingStore(dir),
        resource = mapOf("service.name" to "hermes-live"),
        endpoint = { url },
        client = OkHttpClient(),
        exits = { exits },
        marks = marks,
        clock = { now },
        maxQueue = maxQueue,
    )

    @Before fun setUp() {
        server.start()
        dir = Files.createTempDirectory("telemetry").toFile()
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun ok() = server.enqueue(MockResponse.Builder().code(200).body("{}").build())
    private fun fail(code: Int) = server.enqueue(MockResponse.Builder().code(code).body("{}").build())
    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
    private fun records(payload: JsonObject) =
        payload["resourceLogs"]!!.jsonArray[0].jsonObject["scopeLogs"]!!.jsonArray[0].jsonObject["logRecords"]!!.jsonArray

    @Test fun offStateSendsNothing() {
        val c = core()
        c.add(Records.record(now, Severity.Info, "call.start"))
        c.writeCrash("main", IllegalStateException("boom"))
        exits = listOf(ExitInfo(now - 1, ApplicationExitInfo.REASON_ANR, "anr"))
        assertEquals(FlushResult.Off, c.flush(force = true))
        assertEquals(0, server.requestCount)
        assertEquals(0L, marks.last) // no exit scan while off
        assertEquals(1, PendingStore(dir).list().size) // only the crash file
    }

    @Test fun sendsQueueAsOtlpJson() {
        url = server.url("/v1/logs")
        ok()
        val c = core()
        c.add(Records.record(now, Severity.Info, "call.start", mapOf("entry" to "tile")))
        val result = c.flush()
        assertEquals(FlushResult.Sent(200, 1), result)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/logs", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val payload = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val record = records(payload)[0].jsonObject
        assertEquals("call.start", record["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals("INFO", record["severityText"]!!.jsonPrimitive.content)
        assertTrue(c.queued().isEmpty())
        assertEquals(FlushResult.Nothing, c.flush())
    }

    @Test fun crashFileIsSentThenDeletedOnlyOn2xx() {
        url = server.url("/v1/logs")
        val c = core()
        c.add(Records.record(now, Severity.Info, "call.start"))
        assertTrue(c.writeCrash("main", IllegalStateException("boom")) != null)
        fail(503)
        assertEquals(FlushResult.Failed(503, "HTTP 503"), c.flush(force = true))
        assertEquals(1, PendingStore(dir).list().size)
        body()
        ok()
        assertEquals(FlushResult.Sent(200, 2), c.flush(force = true)) // the crash file: the queued event as context + the crash
        assertTrue(c.queued().isEmpty())
        val sent = records(body())
        val crash = sent.last().jsonObject
        assertEquals("FATAL", crash["severityText"]!!.jsonPrimitive.content)
        assertEquals("crash: java.lang.IllegalStateException", crash["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        val attrs = crash["attributes"]!!.jsonArray.associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject }
        assertEquals("java.lang.IllegalStateException", attrs["exception.type"]!!["stringValue"]!!.jsonPrimitive.content)
        assertTrue(attrs["exception.stacktrace"]!!["stringValue"]!!.jsonPrimitive.content.contains("TelemetryCoreTest"))
        assertTrue(PendingStore(dir).list().isEmpty())
    }

    @Test fun failureBacksOffInsteadOfRetrying() {
        url = server.url("/v1/logs")
        val c = core()
        c.add(Records.record(now, Severity.Warn, "x"))
        fail(500)
        assertTrue(c.flush() is FlushResult.Failed)
        assertEquals(FlushResult.BackingOff, c.flush())
        now += TelemetryCore.MIN_BACKOFF_MS + 1
        ok()
        assertTrue(c.flush() is FlushResult.Sent)
        assertEquals(2, server.requestCount)
    }

    @Test fun networkErrorKeepsTheQueue() {
        url = server.url("/v1/logs")
        server.close()
        val c = core()
        c.add(Records.record(now, Severity.Error, "x"))
        assertEquals(-1, (c.flush(force = true) as FlushResult.Failed).status)
        assertEquals(1, c.queued().size)
    }

    @Test fun queueIsBounded() {
        val c = core(maxQueue = 200)
        repeat(250) { c.add(Records.record(now, Severity.Info, "e$it")) }
        assertEquals(200, c.queued().size)
        assertEquals("e50", c.queued().first().body)
    }

    @Test fun pendingIsCappedAndExpires() {
        val store = PendingStore(dir, maxFiles = 50)
        repeat(60) { store.write(PendingBatch(listOf(Records.record(now, Severity.Fatal, "c$it"))), now + it) }
        assertEquals(50, store.list().size)
        store.prune(now + PendingStore.MAX_AGE_MS + 100)
        assertTrue(store.list().isEmpty())
    }

    @Test fun corruptPendingFileIsDropped() {
        val store = PendingStore(dir)
        File(dir, "0000000000001-bad.json").writeText("{not json")
        assertNull(store.read(store.list().single()))
        assertTrue(store.list().isEmpty())
    }

    @Test fun exitsAreReportedOnceAndOnlyWorthyReasons() {
        url = server.url("/v1/logs")
        exits = listOf(
            ExitInfo(now - 3_000, ApplicationExitInfo.REASON_USER_REQUESTED, "swiped"),
            ExitInfo(now - 2_000, ApplicationExitInfo.REASON_ANR, "Input dispatching timed out") {
                "----- pid 1 -----\nheader\n\"main\" prio=5 tid=1 Native\n  at com.folio.X.y(X.kt:1)\n".toByteArray()
            },
            ExitInfo(now - 1_000, ApplicationExitInfo.REASON_CRASH_NATIVE, "crash"),
        )
        ok()
        val c = core()
        assertEquals(FlushResult.Sent(200, 2), c.flush())
        val sent = records(body())
        assertEquals("app.exit.anr", sent[0].jsonObject["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertTrue(sent[0].toString().contains("\\\"main\\\" prio=5"))
        assertEquals("app.exit.crash_native", sent[1].jsonObject["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        assertEquals(now - 1_000, marks.last)
        // A new process sees the same exits: nothing new to send.
        assertEquals(FlushResult.Nothing, core().flush())
        assertEquals(1, server.requestCount)
    }

    @Test fun exitDedupeIsPure() {
        val list = listOf(ExitInfo(10, ApplicationExitInfo.REASON_CRASH, null), ExitInfo(20, ApplicationExitInfo.REASON_EXIT_SELF, null))
        val (first, mark) = ExitReports.newRecords(list, 0)
        assertEquals(1, first.size)
        assertEquals(20L, mark)
        val (second, mark2) = ExitReports.newRecords(list, mark)
        assertTrue(second.isEmpty())
        assertEquals(20L, mark2)
    }

    @Test fun anrKeepsHeaderAndMainThread() {
        val text = "----- pid 42 at 2026 -----\n" + "x".repeat(5000) + "\n\"main\" prio=5 tid=1 Blocked\n  at a.B.c(B.kt:3)\n"
        val out = ExitReports.anr(text)
        assertTrue(out.startsWith("----- pid 42"))
        assertTrue(out.contains("\"main\" prio=5 tid=1 Blocked"))
        assertFalse(out.length > Records.STACK_MAX)
    }
}
