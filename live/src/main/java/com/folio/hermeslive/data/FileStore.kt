package com.folio.hermeslive.data

import com.folio.hermes.cascade.ports.Store
import com.folio.hermes.telemetry.TelemetrySink
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * The cascade's durable [Store] (R-DUR, R-CONV) in app-private files under [dir]:
 *
 * - **Key-value:** one file per key in `kv/`. A write goes to a temp file, is fsynced (for a
 *   [durable] key), then renamed over the old file, so a reader sees the old value or the new
 *   one, never half of either. Each file carries its key and a CRC32 of key and value.
 * - **Logs:** one file per log in `logs/`, one line per entry with its own CRC32. An append is
 *   fsynced for a [durable] log. A torn last line (power cut mid-write) is skipped and the next
 *   append starts on a fresh line.
 * - **Corruption:** a value or line that fails its check reads as absent. A bad value file is
 *   moved to `corrupt/` (the last [KEEP_CORRUPT] kept, for a bug report) and reported as
 *   `store.corrupt` with the kind and a count only. Left-over temp files are deleted on open.
 *
 * Writes return once durable, as the port requires: the actor persists before any network
 * effect of the same step (R-DUR-1). Thread-safe (one lock; the actor is the main writer).
 */
class FileStore(
    private val dir: File,
    private val durable: (String) -> Boolean = { true },
    private val telemetry: TelemetrySink = TelemetrySink.Default,
) : Store {
    private val kvDir = File(dir, "kv")
    private val logDir = File(dir, "logs")
    private val corruptDir = File(dir, "corrupt")
    private val reportedLogs = mutableSetOf<String>()

    init {
        kvDir.mkdirs()
        logDir.mkdirs()
        listOf(kvDir, logDir).forEach { d -> d.listFiles { f -> f.name.contains(TMP) }?.forEach { it.delete() } }
    }

    @Synchronized
    override fun get(key: String): String? = readValue(kvFile(key), key)?.second

    @Synchronized
    override fun put(key: String, value: String?) {
        val file = kvFile(key)
        if (value == null) {
            if (file.delete()) syncDir(kvDir, durable(key))
            return
        }
        writeAtomically(file, encodeValue(key, value), durable(key))
    }

    @Synchronized
    override fun keys(prefix: String): List<String> = kvDir.listFiles { f -> f.isFile && f.name.endsWith(KV) }.orEmpty()
        .mapNotNull { f -> readValue(f, null)?.first }
        .filter { it.startsWith(prefix) }
        .sorted()

    @Synchronized
    override fun append(log: String, line: String) {
        val file = logFile(log)
        val bytes = (if (endsMidLine(file)) "\n" else "") + encodeLine(line) + "\n"
        FileOutputStream(file, true).use { out ->
            out.write(bytes.toByteArray(Charsets.UTF_8))
            out.flush()
            if (durable(log)) out.fd.sync()
        }
    }

    @Synchronized
    override fun read(log: String): List<String> = readLines(log).first

    @Synchronized
    override fun trim(log: String, keep: Int) {
        val file = logFile(log)
        if (!file.exists()) return
        val (lines, bad) = readLines(log)
        val kept = if (keep <= 0) emptyList() else lines.takeLast(keep)
        if (kept.isEmpty()) {
            file.delete()
            syncDir(logDir, durable(log))
            return
        }
        if (kept.size == lines.size && bad == 0) return
        writeAtomically(file, kept.joinToString("") { encodeLine(it) + "\n" }.toByteArray(Charsets.UTF_8), durable(log))
    }

    // ── key-value files ─────────────────────────────────────────────────────────────────────

    private fun kvFile(key: String) = File(kvDir, fileName(key) + KV)

    private fun logFile(log: String) = File(logDir, fileName(log) + LOG)

    /** (key, value), or null when absent or corrupt (a corrupt file is quarantined). */
    private fun readValue(file: File, expectedKey: String?): Pair<String, String>? {
        if (!file.isFile) return null
        val decoded = runCatching { decodeValue(file.readBytes()) }.getOrNull()
        if (decoded == null || (expectedKey != null && decoded.first != expectedKey)) {
            quarantine(file)
            return null
        }
        return decoded
    }

    private fun quarantine(file: File) {
        corruptDir.mkdirs()
        val target = File(corruptDir, "${System.currentTimeMillis()}-${file.name}")
        if (!file.renameTo(target)) file.delete()
        corruptDir.listFiles().orEmpty().sortedBy { it.name }.dropLast(KEEP_CORRUPT).forEach { it.delete() }
        report("kv", 1)
    }

    private fun writeAtomically(target: File, bytes: ByteArray, sync: Boolean) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP + System.nanoTime())
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                if (sync) out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDir(target.parentFile!!, sync)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    // ── logs ────────────────────────────────────────────────────────────────────────────────

    /** The good lines and how many were damaged. */
    private fun readLines(log: String): Pair<List<String>, Int> {
        val file = logFile(log)
        if (!file.isFile) return emptyList<String>() to 0
        val raw = file.readText(Charsets.UTF_8)
        val parts = raw.split('\n')
        // The last part is "" after a complete final line, or a torn line without its newline.
        val torn = parts.last().isNotEmpty()
        var bad = if (torn) 1 else 0
        val lines = parts.dropLast(1).mapNotNull { encoded ->
            if (encoded.isEmpty()) return@mapNotNull null
            decodeLine(encoded) ?: run { bad++; null }
        }
        if (bad > 0 && reportedLogs.add(log)) report("log", bad)
        return lines to bad
    }

    private fun endsMidLine(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { f ->
            f.seek(f.length() - 1)
            f.read() != '\n'.code
        }
    }

    private fun report(kind: String, count: Int) = runCatching {
        telemetry.event("store.corrupt", mapOf("kind" to kind, "count" to count), true)
    }

    companion object {
        private const val KV = ".kv"
        private const val LOG = ".log"
        private const val TMP = ".tmp-"
        private const val MAGIC = 0x484C5331 // "HLS1"
        private const val KEEP_CORRUPT = 20
        private const val MAX_PLAIN_NAME = 120

        /**
         * A file name for [key]: letters, digits, `.`, `_` and `-` as they are, anything else
         * `%XX` (UTF-8). Names that would be long (or start with `.`) become `h_` + SHA-256; the
         * file holds the real key either way.
         */
        internal fun fileName(key: String): String {
            val sb = StringBuilder()
            key.toByteArray(Charsets.UTF_8).forEach { b ->
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 0x80 || ch == '_' || ch == '-' || ch == '.') sb.append(ch)
                else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
            val plain = sb.toString()
            if (plain.isNotEmpty() && plain.length <= MAX_PLAIN_NAME && !plain.startsWith(".")) return plain
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return "h_" + digest.joinToString("") { "%02x".format(it) }
        }

        private const val HEX = "0123456789abcdef"

        internal fun encodeValue(key: String, value: String): ByteArray {
            val k = key.toByteArray(Charsets.UTF_8)
            val v = value.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream(16 + k.size + v.size)
            DataOutputStream(out).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(crc(k, v))
                d.writeInt(k.size)
                d.write(k)
                d.writeInt(v.size)
                d.write(v)
            }
            return out.toByteArray()
        }

        internal fun decodeValue(bytes: ByteArray): Pair<String, String>? {
            val d = DataInputStream(bytes.inputStream())
            if (bytes.size < 16 || d.readInt() != MAGIC) return null
            val crc = d.readInt()
            val kLen = d.readInt()
            if (kLen < 0 || kLen > bytes.size - 16) return null
            val k = ByteArray(kLen).also(d::readFully)
            val vLen = d.readInt()
            if (vLen != bytes.size - 16 - kLen) return null
            val v = ByteArray(vLen).also(d::readFully)
            if (crc(k, v) != crc) return null
            return String(k, Charsets.UTF_8) to String(v, Charsets.UTF_8)
        }

        private fun crc(vararg parts: ByteArray): Int = CRC32().apply { parts.forEach { update(it) } }.value.toInt()

        /** `<crc32 hex> <line>` with `\`, newline and CR escaped. */
        internal fun encodeLine(line: String): String {
            val escaped = line.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
            return "%08x".format(crc(escaped.toByteArray(Charsets.UTF_8))) + " " + escaped
        }

        internal fun decodeLine(encoded: String): String? {
            if (encoded.length < 9 || encoded[8] != ' ') return null
            val crc = encoded.substring(0, 8).toLongOrNull(16)?.toInt() ?: return null
            val escaped = encoded.substring(9)
            if (crc(escaped.toByteArray(Charsets.UTF_8)) != crc) return null
            return unescape(escaped)
        }

        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(
                        when (s[i + 1]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> s[i + 1]
                        },
                    )
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        /** Best effort: make a rename or delete in [dir] durable. Not every platform lets a directory be opened. */
        private fun syncDir(dir: File, sync: Boolean) {
            if (!sync) return
            runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        }
    }
}
