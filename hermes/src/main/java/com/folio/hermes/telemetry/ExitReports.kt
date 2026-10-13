package com.folio.hermes.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import java.io.InputStream

/** One previous process exit, as Android recorded it. [trace] reads the ANR dump / tombstone lazily. */
class ExitInfo(
    val timestampMs: Long,
    val reason: Int,
    val description: String?,
    val importance: Int = 0,
    val pssKb: Long = 0,
    val rssKb: Long = 0,
    val status: Int = 0,
    val trace: () -> ByteArray? = { null },
)

/** Where previous exits come from: `ActivityManager` on the phone, a fake in tests. */
fun interface ExitSource {
    fun recent(): List<ExitInfo>
}

/** Remembers the newest exit already reported, so each one is sent once. */
interface ExitMarks {
    fun lastReportedMs(): Long
    fun setLastReportedMs(value: Long)
}

class PrefsExitMarks(private val prefs: SharedPreferences) : ExitMarks {
    override fun lastReportedMs(): Long = prefs.getLong(KEY, 0L)
    override fun setLastReportedMs(value: Long) {
        prefs.edit().putLong(KEY, value).apply()
    }

    private companion object {
        const val KEY = "last_exit_reported_ms"
    }
}

/**
 * Turns `ApplicationExitInfo` into log records: crashes (JVM and native), ANRs, low-memory and
 * resource kills, init failures and dependency deaths. Native crashes and ANRs carry a trace
 * (tombstone summary / main-thread dump, ≤16 KB); that is how a WebRTC crash in native code shows up.
 */
object ExitReports {
    /** Reasons worth a report (ApplicationExitInfo.REASON_*). User swipes, updates and self-exits are not. */
    val REPORTED: Set<Int> = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    )

    /** Records for exits newer than [lastReportedMs], oldest first, and the new mark. */
    fun newRecords(exits: List<ExitInfo>, lastReportedMs: Long): Pair<List<TelemetryRecord>, Long> {
        val fresh = exits.filter { it.timestampMs > lastReportedMs }
        val mark = (fresh.maxOfOrNull { it.timestampMs } ?: lastReportedMs).coerceAtLeast(lastReportedMs)
        val records = fresh.filter { it.reason in REPORTED }.sortedBy { it.timestampMs }.map(::record)
        return records to mark
    }

    fun record(exit: ExitInfo): TelemetryRecord {
        val severity = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Severity.Fatal
            else -> Severity.Warn
        }
        val reason = reasonName(exit.reason)
        val attrs = mutableMapOf<String, Any?>(
            "exit.reason" to reason,
            "exit.description" to exit.description,
            "exit.importance" to exit.importance,
            "exit.pss_kb" to exit.pssKb,
            "exit.rss_kb" to exit.rssKb,
            "exit.status" to exit.status,
        )
        val base = Records.record(exit.timestampMs, severity, "app.exit.$reason", attrs)
        val trace = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> runCatching { exit.trace()?.let { Tombstone.summarize(it) } }.getOrNull()
            ApplicationExitInfo.REASON_ANR -> runCatching { exit.trace()?.let { anr(String(it, Charsets.UTF_8)) } }.getOrNull()
            else -> null
        }
        return if (trace.isNullOrEmpty()) base else base.copy(attributes = base.attributes + ("exception.stacktrace" to kotlinx.serialization.json.JsonPrimitive(trace)))
    }

    /** An ANR dump: a little of the header, then the main thread onwards, scrubbed, ≤16 KB. */
    fun anr(text: String, max: Int = Records.STACK_MAX): String {
        val main = text.indexOf("\n\"main\"")
        val cut = if (main > 0) text.take(minOf(main, 1024)) + "\n…\n" + text.substring(main + 1) else text
        return Scrub.truncate(Scrub.text(cut), max)
    }

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown_$reason"
    }
}

/** [ExitSource] over `ActivityManager.getHistoricalProcessExitReasons` (API 30+), newest 5. */
class AndroidExitSource(private val context: Context) : ExitSource {
    override fun recent(): List<ExitInfo> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS).map { info ->
            ExitInfo(
                timestampMs = info.timestamp,
                reason = info.reason,
                description = info.description,
                importance = info.importance,
                pssKb = info.pss,
                rssKb = info.rss,
                status = info.status,
                trace = { info.traceInputStream?.use { readUpTo(it, MAX_TRACE_BYTES) } },
            )
        }
    }

    companion object {
        const val MAX_EXITS = 5

        /** Enough to reach the main thread in an ANR dump; the summary is cut to 16 KB. */
        const val MAX_TRACE_BYTES = 512 * 1024

        fun readUpTo(input: InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
