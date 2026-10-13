package com.folio.hermes.telemetry

import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What one flush did. */
sealed interface FlushResult {
    /** No endpoint (switched off, or none configured): nothing was sent. */
    data object Off : FlushResult

    /** Nothing waiting. */
    data object Nothing : FlushResult

    /** A previous failure is still backing off; try later (or force). */
    data object BackingOff : FlushResult

    data class Sent(val status: Int, val records: Int) : FlushResult

    /** [status] is the HTTP status, or -1 when the request did not complete. */
    data class Failed(val status: Int, val reason: String) : FlushResult
}

/**
 * The Android-free half of [Telemetry]: the bounded queue, pending files, exit reports and the
 * OTLP sender. JVM-tested. [endpoint] is resolved at every flush (null = off: no request, no
 * exit scan). Pending files are deleted only after a 2xx. A failed send backs off (30 s
 * doubling to 30 min) instead of retrying in a loop.
 */
class TelemetryCore(
    private val store: PendingStore,
    private val resource: Map<String, String>,
    private val endpoint: () -> HttpUrl?,
    private val client: OkHttpClient,
    private val exits: ExitSource? = null,
    private val marks: ExitMarks? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxQueue: Int = MAX_QUEUE,
) {
    private val queue = ArrayDeque<TelemetryRecord>()
    private var backoffMs = 0L
    private var nextAttemptAtMs = 0L
    private var exitsChecked = false

    val pendingDir: File get() = store.dir

    fun add(record: TelemetryRecord) {
        synchronized(queue) {
            queue.addLast(record)
            while (queue.size > maxQueue) queue.removeFirst()
        }
    }

    fun queued(): List<TelemetryRecord> = synchronized(queue) { queue.toList() }

    /** Synchronously write a crash (plus the last few queued events, for context) to `pending/`, emptying the queue. Never throws. */
    fun writeCrash(threadName: String, throwable: Throwable): File? = try {
        val now = clock()
        val crash = Records.record(
            now, Severity.Fatal, "crash",
            mapOf("thread.name" to threadName, "crash.kind" to "jvm"),
            throwable, Records.STACK_MAX,
        ).let { it.copy(body = "crash: ${throwable.javaClass.name}") }
        // The queue moves into the crash file (the process is dying; sending it twice would duplicate).
        val context = synchronized(queue) { queue.toList().takeLast(CRASH_CONTEXT).also { queue.clear() } }
        store.write(PendingBatch(context + crash), now)
    } catch (t: Throwable) {
        null
    }

    /** Send what is waiting. Blocking: call off the main thread. [force] ignores the backoff. */
    @Synchronized
    fun flush(force: Boolean = false): FlushResult {
        val now = clock()
        store.prune(now)
        val url = try {
            endpoint()
        } catch (t: Throwable) {
            null
        } ?: return FlushResult.Off
        if (!force && now < nextAttemptAtMs) return FlushResult.BackingOff
        checkExits(now)

        val snapshot = synchronized(queue) { queue.toList() }
        val files = store.list()
        if (snapshot.isEmpty() && files.isEmpty()) return FlushResult.Nothing

        var sent = 0
        var lastStatus = 0
        var first = true
        val chunks = files.chunked(FILES_PER_REQUEST).ifEmpty { listOf(emptyList()) }
        for (chunk in chunks) {
            val fromFiles = chunk.flatMap { store.read(it)?.records.orEmpty() }
            val records = (if (first) snapshot else emptyList()) + fromFiles
            if (records.isEmpty()) {
                chunk.forEach(store::delete)
                first = false
                continue
            }
            val result = post(url, records)
            if (result !is FlushResult.Sent) {
                backoffMs = (backoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                nextAttemptAtMs = clock() + backoffMs
                return result
            }
            chunk.forEach(store::delete)
            if (first) removeFromQueue(snapshot)
            first = false
            sent += records.size
            lastStatus = result.status
        }
        backoffMs = 0
        nextAttemptAtMs = 0
        return if (sent == 0) FlushResult.Nothing else FlushResult.Sent(lastStatus, sent)
    }

    private fun post(url: HttpUrl, records: List<TelemetryRecord>): FlushResult {
        val body = Otlp.payload(resource, records, clock()).toString().toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) FlushResult.Sent(response.code, records.size) else FlushResult.Failed(response.code, "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            FlushResult.Failed(-1, e.javaClass.simpleName)
        }
    }

    /** Once per process, with an endpoint: write new exits to `pending/`, then mark them reported. */
    private fun checkExits(now: Long) {
        if (exitsChecked) return
        exitsChecked = true
        val source = exits ?: return
        val m = marks ?: return
        try {
            val (records, mark) = ExitReports.newRecords(source.recent(), m.lastReportedMs())
            if (records.isEmpty() || store.write(PendingBatch(records), now) != null) m.setLastReportedMs(mark)
        } catch (t: Throwable) {
            // An unreadable exit list must not stop the rest.
        }
    }

    private fun removeFromQueue(sent: List<TelemetryRecord>) {
        val ids = Collections.newSetFromMap(IdentityHashMap<TelemetryRecord, Boolean>()).apply { addAll(sent) }
        synchronized(queue) { queue.removeAll { it in ids } }
    }

    companion object {
        const val MAX_QUEUE = 200
        const val CRASH_CONTEXT = 20
        const val FILES_PER_REQUEST = 10
        const val MIN_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
        private val JSON = "application/json".toMediaType()
    }
}
