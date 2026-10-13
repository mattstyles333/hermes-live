package com.folio.hermes.telemetry

/**
 * Reads the parts of a native crash we want from Android's tombstone, and nothing else.
 *
 * On Android 12+ `ApplicationExitInfo.getTraceInputStream()` for `REASON_CRASH_NATIVE` is a
 * protobuf `Tombstone` (AOSP `system/core/debuggerd/proto/tombstone.proto`). Only these fields
 * are read: `tid` (6), `signal_info` (10: number 1, name 2, code 3, code_name 4,
 * has_fault_address 8, fault_address 9), `abort_message` (14), `causes` (15: human_readable 1)
 * and the crashing thread from `threads` (16, map<uint32, Thread>: name 2, current_backtrace 4
 * → BacktraceFrame rel_pc 1, function_name 4, function_offset 5, file_name 6). Registers,
 * memory dumps, log buffers, open files and memory maps are skipped: they can hold user data.
 *
 * Older text tombstones keep only the signal, abort, cause and backtrace lines.
 */
object Tombstone {
    private const val MAX_FRAMES = 64

    /** A debuggerd-style summary, or null when [bytes] is neither a readable proto nor text. */
    fun summarize(bytes: ByteArray, max: Int = Records.STACK_MAX): String? {
        val text = if (looksLikeText(bytes)) fromText(bytes) else runCatching { fromProto(bytes) }.getOrNull()
        return text?.let { Scrub.truncate(Scrub.text(it), max) }
    }

    private class Frame(val relPc: Long, val function: String?, val offset: Long, val file: String?)
    private class Thread(val name: String?, val frames: List<Frame>)

    private fun fromProto(bytes: ByteArray): String? {
        var tid = -1L
        var signal: String? = null
        var abort: String? = null
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Long, Thread>()
        val r = ProtoReader(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 6 && wire == 0 -> tid = r.varint()
                field == 10 && wire == 2 -> signal = signal(r.bytes())
                field == 14 && wire == 2 -> abort = r.string()
                field == 15 && wire == 2 -> cause(r.bytes())?.let(causes::add)
                field == 16 && wire == 2 -> threadEntry(r.bytes())?.let { (id, t) -> threads[id] = t }
                else -> r.skip(wire)
            }
        }
        if (signal == null && threads.isEmpty() && abort == null) return null
        val crashed = threads[tid] ?: threads.values.firstOrNull()
        return buildString {
            signal?.let { append(it).append('\n') }
            abort?.let { append("Abort message: '").append(Scrub.message(it)).append("'\n") }
            causes.forEach { append("Cause: ").append(Scrub.message(it)).append('\n') }
            if (crashed != null) {
                append("backtrace (thread ").append(crashed.name ?: "?").append(", tid ").append(tid).append("):\n")
                crashed.frames.take(MAX_FRAMES).forEachIndexed { i, f ->
                    append(String.format(java.util.Locale.ROOT, "  #%02d pc %016x  %s", i, f.relPc, f.file ?: "<unknown>"))
                    if (!f.function.isNullOrEmpty()) append(" (").append(f.function).append('+').append(f.offset).append(')')
                    append('\n')
                }
            }
        }
    }

    private fun signal(bytes: ByteArray): String {
        var number = 0L
        var name = ""
        var code = 0L
        var codeName = ""
        var hasFault = false
        var fault = 0L
        val r = ProtoReader(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> number = r.varint()
                field == 2 && wire == 2 -> name = r.string()
                field == 3 && wire == 0 -> code = r.varint()
                field == 4 && wire == 2 -> codeName = r.string()
                field == 8 && wire == 0 -> hasFault = r.varint() != 0L
                field == 9 && wire == 0 -> fault = r.varint()
                else -> r.skip(wire)
            }
        }
        val faultText = if (hasFault) String.format(java.util.Locale.ROOT, "0x%x", fault) else "--------"
        return "signal ${number.toInt()} ($name), code ${code.toInt()} ($codeName), fault addr $faultText"
    }

    private fun cause(bytes: ByteArray): String? {
        val r = ProtoReader(bytes)
        var text: String? = null
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 1 && wire == 2) text = r.string() else r.skip(wire)
        }
        return text
    }

    private fun threadEntry(bytes: ByteArray): Pair<Long, Thread>? {
        var key = -1L
        var thread: Thread? = null
        val r = ProtoReader(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> key = r.varint()
                field == 2 && wire == 2 -> thread = thread(r.bytes())
                else -> r.skip(wire)
            }
        }
        return thread?.let { key to it }
    }

    private fun thread(bytes: ByteArray): Thread {
        var name: String? = null
        val frames = mutableListOf<Frame>()
        val r = ProtoReader(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 2 && wire == 2 -> name = r.string()
                field == 4 && wire == 2 -> frames += frame(r.bytes())
                else -> r.skip(wire)
            }
        }
        return Thread(name, frames)
    }

    private fun frame(bytes: ByteArray): Frame {
        var relPc = 0L
        var function: String? = null
        var offset = 0L
        var file: String? = null
        val r = ProtoReader(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> relPc = r.varint()
                field == 4 && wire == 2 -> function = r.string()
                field == 5 && wire == 0 -> offset = r.varint()
                field == 6 && wire == 2 -> file = r.string()
                else -> r.skip(wire)
            }
        }
        return Frame(relPc, function, offset, file)
    }

    private val keepLine = Regex("^\\s*(signal \\d|Abort message|Cause:|backtrace:|#\\d+ pc |pid: \\d)")

    /** A text tombstone (pre-12): only the lines that say what crashed where. */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = bytes.take(4096)
        val printable = sample.count { b -> b == '\n'.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte() || b in 0x20..0x7e }
        return printable >= sample.size * 0.95
    }

    private fun fromText(bytes: ByteArray): String? {
        val lines = String(bytes, Charsets.UTF_8).lineSequence().filter { keepLine.containsMatchIn(it) }.toList()
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

/** Minimal protobuf wire-format reader: varints, fixed 32/64, length-delimited. Throws on bad input. */
internal class ProtoReader(private val buf: ByteArray) {
    private var pos = 0

    fun more(): Boolean = pos < buf.size

    fun tag(): Pair<Int, Int> {
        val t = varint()
        val field = (t ushr 3).toInt()
        require(field > 0) { "bad field" }
        return field to (t and 7).toInt()
    }

    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            require(pos < buf.size) { "truncated varint" }
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("varint too long")
    }

    fun bytes(): ByteArray {
        val len = varint()
        require(len >= 0 && pos + len <= buf.size) { "bad length" }
        val out = buf.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> advance(8)
            2 -> bytes()
            5 -> advance(4)
            else -> throw IllegalArgumentException("unsupported wire type $wire")
        }
    }

    private fun advance(n: Int) {
        require(pos + n <= buf.size) { "truncated" }
        pos += n
    }
}
