package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.Tx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * R-OFF-4 step 1: the writer segments each offline dictation session into instructions. The
 * memo is numbered sentence by sentence and the writer only groups the numbers, so every
 * instruction is the user's own words, in order, and nothing can be dropped: a sentence the
 * writer leaves out becomes its own instruction. A failed or unusable answer falls back to one
 * instruction per held turn. Sessions over `writerChunkAboveChars` go in chunks (R-CTX-3).
 */
class MemoSegmentHandler(private val memo: MemoRouting) : RouteHandler {
    /** One sentence of the memo: its session and held turn (for the fallback). */
    data class Sentence(val session: Int, val turn: Int, val text: String)

    data class Plan(val sessions: List<MemoBatchSession>, val chunks: List<List<Sentence>>)

    override fun playsSlotFirst(tx: Tx): Boolean = false

    override fun start(tx: Tx): Next = write(tx, 0)

    override fun resume(tx: Tx, result: ActionResult): Next {
        if (result !is ActionResult.Wrote) return Next.Done
        val plan = decode(tx.action.data[PLAN]) ?: return Next.Done
        val index = tx.action.data[CHUNK]?.toIntOrNull() ?: 0
        val chunk = plan.chunks[index]
        val instructions = groups(result.result.valueOrNull(), chunk.size)?.map { g -> g.joinToString(" ") { chunk[it].text } }
            ?: chunk.groupBy { it.turn }.values.map { units -> units.joinToString(" ") { it.text } }
        tx.updateAction { it.copy(data = it.data + ("$SEGMENTS$index" to JsonArray(instructions.map(::JsonPrimitive)).toString())) }
        if (index + 1 < plan.chunks.size) return write(tx, index + 1)
        val perSession = plan.sessions.indices.map { si ->
            plan.chunks.indices.filter { plan.chunks[it].firstOrNull()?.session == si }.flatMap { ci ->
                tx.action.data["$SEGMENTS$ci"]?.let { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } }.orEmpty()
            }
        }
        memo.apply(tx, plan.sessions, perSession)
        memo.startFlush(tx)
        return Next.Done
    }

    private fun write(tx: Tx, index: Int): Next {
        val plan = decode(tx.action.data[PLAN]) ?: return Next.Done
        tx.updateAction { it.copy(data = it.data + (CHUNK to index.toString()), writerRan = true) }
        return tx.requestWrite(SYSTEM, prompt(plan.chunks[index]))
    }

    companion object {
        const val PLAN = "memo_plan"
        private const val CHUNK = "memo_chunk"
        private const val SEGMENTS = "memo_segments_"

        const val SYSTEM =
            "The user dictated these numbered sentences to their assistant while offline. Group them into separate instructions: " +
                "one group per instruction, sentences that belong to the same instruction together (a later sentence may continue an earlier one). " +
                "Answer with JSON only: {\"groups\":[[1,2],[3]]}. Use every number exactly once."

        fun prompt(units: List<Sentence>): String = units.mapIndexed { i, u -> "[${i + 1}] ${u.text}" }.joinToString("\n")

        /** Sentences of a held turn (by end punctuation; a turn without any is one sentence). */
        fun sentences(text: String): List<String> = text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

        fun plan(tx: Tx, sessions: List<MemoBatchSession>): Plan {
            val held = tx.s.outbox.held.associateBy { it.turnId }
            val settings = tx.s.settings
            val chunks = mutableListOf<List<Sentence>>()
            sessions.forEachIndexed { si, session ->
                val units = session.turnIds.flatMapIndexed { ti, id -> sentences(held[id]?.turn?.text.orEmpty()).map { Sentence(si, ti, it) } }
                if (units.sumOf { it.text.length } <= settings.writerChunkAboveChars) {
                    if (units.isNotEmpty()) chunks += units
                    return@forEachIndexed
                }
                var current = mutableListOf<Sentence>()
                var size = 0
                for (u in units) {
                    if (current.isNotEmpty() && size + u.text.length > settings.writerChunkChars) {
                        chunks += current
                        current = mutableListOf()
                        size = 0
                    }
                    current += u
                    size += u.text.length + 1
                }
                if (current.isNotEmpty()) chunks += current
            }
            return Plan(sessions, chunks)
        }

        /**
         * The writer's groups as 0-based unit indices, in order of their first sentence; any
         * sentence left out is its own group. Null if the answer is unusable.
         */
        fun groups(raw: String?, units: Int): List<List<Int>>? {
            if (raw.isNullOrBlank() || units == 0) return null
            val found = Regex("\\[\\s*(\\d+(?:\\s*,\\s*\\d+)*)\\s*]").findAll(raw)
                .map { m -> m.groupValues[1].split(',').map { it.trim().toInt() - 1 } }.toList()
            if (found.isEmpty()) return null
            val seen = mutableSetOf<Int>()
            val groups = found.map { g -> g.filter { it in 0 until units && seen.add(it) }.sorted() }.filter { it.isNotEmpty() }.toMutableList()
            (0 until units).filter { it !in seen }.forEach { groups += listOf(it) }
            return groups.sortedBy { it.first() }
        }

        fun encode(plan: Plan): String = buildJsonObject {
            putJsonArray("sessions") {
                plan.sessions.forEach { s ->
                    addJsonObject {
                        putJsonArray("ids") { s.turnIds.forEach { add(it) } }
                        put("start", s.startWallMs)
                        put("end", s.endWallMs)
                    }
                }
            }
            putJsonArray("chunks") {
                plan.chunks.forEach { c ->
                    add(JsonArray(c.map { u -> buildJsonObject { put("s", u.session); put("t", u.turn); put("x", u.text) } }))
                }
            }
        }.toString()

        fun decode(raw: String?): Plan? = runCatching {
            val o = Json.parseToJsonElement(raw!!).jsonObject
            val sessions = o["sessions"]!!.jsonArray.map { e ->
                val s = e.jsonObject
                MemoBatchSession(s["ids"]!!.jsonArray.map { it.jsonPrimitive.content }, s["start"]!!.jsonPrimitive.long, s["end"]!!.jsonPrimitive.long)
            }
            val chunks = o["chunks"]!!.jsonArray.map { c ->
                c.jsonArray.map { u ->
                    val x = u as JsonObject
                    Sentence(x["s"]!!.jsonPrimitive.int, x["t"]!!.jsonPrimitive.int, (x["x"] as JsonPrimitive).contentOrNull.orEmpty())
                }
            }
            Plan(sessions, chunks)
        }.getOrNull()
    }
}
