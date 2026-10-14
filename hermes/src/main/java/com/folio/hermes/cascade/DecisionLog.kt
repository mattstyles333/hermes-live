package com.folio.hermes.cascade

import com.folio.hermes.HermesJson
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** One Jev answer as logged: the pick and how sure Jev was. */
@Serializable
data class LoggedAnswer(val choice: String?, val confidence: Double, val probabilities: Map<String, Double> = emptyMap())

/**
 * One turn, for tuning the bank and the Jev questions from real calls. Holds the transcript and
 * Jev's state, so it stays on the phone: never uploaded, never in telemetry.
 */
@Serializable
data class DecisionRecord(
    val turnId: String,
    val atMs: Long,
    /** `done`, `keep_listening`, `parked`, `hold_prompt`, `ignore`, `no_answer`, `held`, `resent`. */
    val outcome: String,
    /** The Jev `state` that got the answer used (null when Jev never answered). */
    val state: JsonObject? = null,
    /** Question ids asked. */
    val questions: List<String> = emptyList(),
    val answers: Map<String, LoggedAnswer> = emptyMap(),
    /** What the router did (`reply`, `new_task`, `clarify`…). */
    val route: String? = null,
    val taskId: String? = null,
    /** Slot and line played. */
    val slot: String? = null,
    val line: String? = null,
    val writerRan: Boolean = false,
    val jevCalls: Int = 0,
    /** Failure kind when Jev gave nothing usable. */
    val failure: String? = null,
    /** Speech end → the Jev answer used. */
    val speechEndToJevMs: Long? = null,
    /** That Jev call's own duration. */
    val jevMs: Long? = null,
    /** Speech end → first audio of the response. */
    val speechEndToAudioMs: Long? = null,
    /** How a `done` turn was committed: null when Jev said done, `hold_silence` by the hold-silence rule. */
    val via: String? = null,
)

/** Lines of JSON, appended one per turn. `:live` supplies [FileDecisionStore] in app-private storage. */
interface DecisionStore {
    fun append(line: String)
    fun readAll(): List<String>
    fun replaceAll(lines: List<String>)
}

class MemoryDecisionStore : DecisionStore {
    private val lines = mutableListOf<String>()
    @Synchronized override fun append(line: String) {
        lines += line
    }
    @Synchronized override fun readAll(): List<String> = lines.toList()
    @Synchronized override fun replaceAll(lines: List<String>) {
        this.lines.clear()
        this.lines += lines
    }
}

class FileDecisionStore(private val file: File) : DecisionStore {
    @Synchronized override fun append(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }
    @Synchronized override fun readAll(): List<String> =
        if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
    @Synchronized override fun replaceAll(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(lines.joinToString("") { it + "\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

/**
 * The decision log: a ring of the last [capacity] turns (1,000), each kept at most [retentionMs]
 * (14 days). On by default ([enabled], a Settings switch); when off, nothing is recorded.
 * Share exports [exportJson]; Clear is [clear]. Not wired to telemetry in any way.
 */
class DecisionLog(
    private val store: DecisionStore,
    private val enabled: () -> Boolean = { true },
    private val capacity: Int = 1_000,
    private val retentionMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val ring: ArrayDeque<DecisionRecord> by lazy {
        val lines = store.readAll()
        ArrayDeque(lines.mapNotNull { decode(it) }).also { loaded ->
            prune(loaded)
            if (lines.size != loaded.size) store.replaceAll(loaded.map(::encode))
        }
    }
    private var appendedSinceCompact = 0

    @Synchronized
    fun record(record: DecisionRecord) {
        if (!enabled()) return
        ring.addLast(record)
        store.append(encode(record))
        val before = ring.size
        prune(ring)
        appendedSinceCompact++
        // The file is append-only; rewrite it when the ring dropped something and enough has piled up.
        if (ring.size < before && appendedSinceCompact >= COMPACT_EVERY) compact()
    }

    /** Oldest first, within retention. */
    @Synchronized
    fun records(): List<DecisionRecord> {
        prune(ring)
        return ring.toList()
    }

    @Synchronized
    fun clear() {
        ring.clear()
        store.replaceAll(emptyList())
        appendedSinceCompact = 0
    }

    /** Every record as one JSON array (for the share sheet). */
    @Synchronized
    fun exportJson(): String = HermesJson.encodeToString(ListSerializer(DecisionRecord.serializer()), records())

    private fun compact() {
        store.replaceAll(ring.map(::encode))
        appendedSinceCompact = 0
    }

    private fun prune(records: ArrayDeque<DecisionRecord>) {
        val cutoff = now() - retentionMs
        while (records.isNotEmpty() && (records.size > capacity || records.first().atMs < cutoff)) records.removeFirst()
    }

    private fun encode(record: DecisionRecord): String = HermesJson.encodeToString(DecisionRecord.serializer(), record)
    private fun decode(line: String): DecisionRecord? = runCatching { HermesJson.decodeFromString(DecisionRecord.serializer(), line) }.getOrNull()

    private companion object {
        const val COMPACT_EVERY = 100
    }
}
