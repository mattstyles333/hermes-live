package com.folio.hermes.telemetry

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * `filesDir/telemetry/pending/`: crash records and exit reports waiting to be sent. One
 * [PendingBatch] per file, written as temp file + rename so a half-written crash never reads
 * back. At most [maxFiles] (oldest dropped first); files older than [maxAgeMs] are given up on.
 * Never throws: telemetry must not take the app down.
 */
class PendingStore(
    val dir: File,
    private val maxFiles: Int = MAX_FILES,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    /** Write [batch] now (synchronously: the crash handler calls this on a dying thread). */
    fun write(batch: PendingBatch, nowMs: Long): File? = try {
        dir.mkdirs()
        val existing = list()
        if (existing.size >= maxFiles) existing.take(existing.size - maxFiles + 1).forEach { it.delete() }
        val name = String.format(java.util.Locale.ROOT, "%013d-%s", nowMs, UUID.randomUUID().toString().take(8))
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(PendingBatch.serializer(), batch))
        val target = File(dir, "$name$SUFFIX")
        if (tmp.renameTo(target)) target else null.also { tmp.delete() }
    } catch (t: Throwable) {
        null
    }

    /** Pending files, oldest first. */
    fun list(): List<File> = try {
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.sortedBy { it.name }.orEmpty()
    } catch (t: Throwable) {
        emptyList()
    }

    /** The batch in [file]; an unreadable file is deleted and reads as null. */
    fun read(file: File): PendingBatch? = try {
        json.decodeFromString<PendingBatch>(file.readText())
    } catch (t: Throwable) {
        file.delete()
        null
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    /** Drop expired files, stray temp files and anything over the cap. */
    fun prune(nowMs: Long) {
        try {
            dir.listFiles { f -> f.name.endsWith(".tmp") && nowMs - f.lastModified() > 60_000L }?.forEach { it.delete() }
            val files = list()
            files.filter { nowMs - timeOf(it) > maxAgeMs }.forEach { it.delete() }
            val left = list()
            if (left.size > maxFiles) left.take(left.size - maxFiles).forEach { it.delete() }
        } catch (t: Throwable) {
            // nothing to do
        }
    }

    private fun timeOf(file: File): Long = file.name.substringBefore('-').toLongOrNull() ?: file.lastModified()

    companion object {
        const val MAX_FILES = 50
        const val MAX_AGE_MS = 3 * 24 * 60 * 60_000L
        const val SUFFIX = ".json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
