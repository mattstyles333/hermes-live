package com.folio.hermes.cascade.writer

import com.folio.hermes.cascade.Exchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One agent brief as the writer wrote it (R-TASK-2): the brief text, a 2–4 word label, a ≤ 12 word summary. */
data class WrittenBrief(val brief: String, val label: String, val summary: String?)

/** R-STT-5: the one word the writer thinks may be misheard, as the transcript has it, and Bill's short question about it. */
data class WrittenDoubt(val word: String, val question: String)

/**
 * What the writer returned for new work. [complete] is false when the JSON was cut short (only
 * the whole brief objects before the cut were kept). [repaired] and [notes] are the R-STT-6 repair.
 */
data class BriefSet(
    val briefs: List<WrittenBrief>,
    val repaired: String?,
    val notes: String?,
    val complete: Boolean,
    /** The writer's word fixes (`from` → `to`), applied to the transcript in code ([Briefs.applyFixes]). */
    val fixes: List<Pair<String, String>> = emptyList(),
    /** Asked for only with a sure `key_doubt` ([BriefRequest.askDoubt]). */
    val doubt: WrittenDoubt? = null,
)

/** What the writer is told about one turn of new work. */
data class BriefRequest(
    /** The full verbatim dictation (held text plus the latest part). */
    val text: String,
    /** The transcript with uncertain words marked `[word|alt]`, when there are any. */
    val marked: String? = null,
    /** Jev's split answer (`one`/`several`) and clarity answer, with confidences. */
    val split: String? = null,
    val clarity: String? = null,
    val vocabulary: List<String> = emptyList(),
    val recent: List<Exchange> = emptyList(),
    /** The user's answer to a key-doubt question (R-STT-5), if any. */
    val answer: String? = null,
    /** R-CTX-3 chunking: chunk [chunk] of [chunks], and what earlier chunks already became. */
    val chunk: Int = 1,
    val chunks: Int = 1,
    val carried: List<String> = emptyList(),
    /** Jev is sure a key word may be misheard (R-STT-5): ask the writer to name it and word Bill's question. */
    val askDoubt: Boolean = false,
)

/**
 * The new-work writer call (R-TASK-2, R-STT-6): one whole answer as compact JSON
 * `{briefs:[{brief, label, summary}], repaired, notes}`. The writer only writes the job lines;
 * the app appends the report-back line and the transcript block ([BriefTemplate]), so the
 * writer's output stays short.
 */
object Briefs {
    /** Most briefs one turn may produce. */
    const val MAX_BRIEFS = 8

    const val SYSTEM =
        "You turn what a user dictated to their voice assistant into briefs for AI agents that have tools. " +
            "Answer with JSON only, no markdown: {\"briefs\":[{\"brief\":\"…\",\"label\":\"…\",\"summary\":\"…\"}],\"fixes\":[{\"from\":\"…\",\"to\":\"…\"}],\"notes\":\"…\"}\n" +
            "- One brief per separate, independent job when split is several; otherwise one brief (steps that follow each other stay one job). At most 8.\n" +
            "- brief: short lines \"Goal: …\", \"Constraints: …\", \"Done when: …\" in the second person. Every detail the user gave; invent nothing.\n" +
            "- Copy names, numbers, amounts and dates exactly as the transcript has them.\n" +
            "- label: 2 to 4 words naming the job. summary: at most 12 words the voice says to confirm it, e.g. \"reorder email to Safilo, 40 units, for your approval\".\n" +
            "- fixes: speech-to-text slips to correct, as {from, to} word pairs. ONLY words marked [word|alternative] or words that match " +
            "the vocabulary; an empty list when nothing needs fixing.\n" +
            "- notes: one short line about any repair or doubt, else empty.\n" +
            "Be brief: the whole answer must stay short."

    fun prompt(r: BriefRequest): String = buildString {
        if (r.recent.isNotEmpty()) {
            append("Recent conversation:\n")
            r.recent.forEach { append("User: ").append(it.user).append("\nBill: ").append(it.bill).append('\n') }
        }
        if (r.vocabulary.isNotEmpty()) append("Vocabulary: ").append(r.vocabulary.joinToString(", ")).append('\n')
        r.split?.let { append("Split: ").append(it).append('\n') }
        r.clarity?.let { append("Clarity: ").append(it).append('\n') }
        if (r.chunks > 1) {
            append("This is part ").append(r.chunk).append(" of ").append(r.chunks).append(" of a long dictation. Brief only the jobs in this part.\n")
            if (r.carried.isNotEmpty()) append("Jobs already briefed from earlier parts: ").append(r.carried.joinToString("; ")).append('\n')
        }
        append("Transcript: \"").append(r.text).append("\"\n")
        r.marked?.takeIf { it != r.text }?.let { append("Uncertain words marked: \"").append(it).append("\"\n") }
        r.answer?.let { append("Asked about a doubtful word, the user answered: \"").append(it).append("\"\n") }
        if (r.askDoubt) append(DOUBT)
    }

    /** Added to the prompt with a sure `key_doubt` (R-STT-5): the task starts anyway; Bill asks this once it has. */
    const val DOUBT =
        "A name, number or date the job depends on may be misheard. Also answer \"doubt\":{\"word\":\"…\",\"question\":\"…\"}: " +
            "word is that one word or short phrase exactly as the transcript has it; question is one short question the voice asks to check it, " +
            "e.g. \"Kestrel, right?\" or \"Forty, or fourteen?\". Leave doubt out if nothing looks misheard.\n"

    /**
     * Parse the writer's answer. Tolerates a markdown fence and an answer cut short: whole brief
     * objects before the cut are kept and [BriefSet.complete] is false. Null if nothing usable.
     */
    fun parse(raw: String?): BriefSet? {
        if (raw.isNullOrBlank()) return null
        val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = body.indexOf('{')
        if (start < 0) return null
        val json = body.substring(start)
        val whole = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        if (whole != null) {
            val briefs = (whole["briefs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::brief) }.orEmpty()
            if (briefs.isEmpty()) return null
            val complete = (whole["complete"] as? JsonPrimitive)?.contentOrNull != "false"
            return BriefSet(briefs.take(MAX_BRIEFS), whole.str("repaired")?.takeIf { it.isNotEmpty() }, whole.str("notes"), complete, fixes(whole["fixes"]), doubt(whole["doubt"]))
        }
        // Cut short: keep the whole briefs. The set is complete if the cut came after the array
        // closed (only the fixes or notes were lost, and the verbatim transcript stands).
        val (briefs, closed) = salvage(json)
        return if (briefs.isEmpty()) null else BriefSet(briefs.take(MAX_BRIEFS), null, null, complete = closed)
    }

    private fun doubt(e: JsonElement?): WrittenDoubt? {
        val o = e as? JsonObject ?: return null
        val word = o.str("word")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return WrittenDoubt(word, o.str("question")?.trim().orEmpty())
    }

    private fun fixes(e: JsonElement?): List<Pair<String, String>> =
        (e as? JsonArray)?.mapNotNull { f -> (f as? JsonObject)?.let { o -> o.str("from")?.let { from -> o.str("to")?.let { to -> from to to } } } }
            ?.filter { it.first.isNotBlank() && it.second.isNotBlank() }.orEmpty()

    /**
     * [text] with each fix applied once, whole words only, case-insensitive (null if nothing
     * changed). The result still goes through [Repair.check], so fixing a word that was neither
     * uncertain nor in the vocabulary is refused there.
     */
    fun applyFixes(text: String, fixes: List<Pair<String, String>>): String? {
        var out = text
        for ((from, to) in fixes) {
            val m = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).find(out) ?: continue
            out = out.replaceRange(m.range, to)
        }
        return out.takeIf { it != text }
    }

    private fun brief(o: JsonObject): WrittenBrief? {
        val text = o.str("brief")?.takeIf { it.isNotBlank() } ?: return null
        val label = o.str("label")?.let(::label)?.takeIf { it.isNotEmpty() } ?: return WrittenBrief(text, "", summary(o.str("summary")))
        return WrittenBrief(text, label, summary(o.str("summary")))
    }

    /** At most 4 words. */
    fun label(raw: String): String = raw.trim().trim('"', '.').split(Regex("\\s+")).filter { it.isNotEmpty() }.take(4).joinToString(" ")

    /** At most 12 words, as a spoken sentence; null when empty. */
    fun summary(raw: String?): String? {
        val words = raw?.trim()?.trim('"')?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (words.isEmpty()) return null
        val text = words.take(12).joinToString(" ").trimEnd(',', ';', ':')
        return text.replaceFirstChar { it.uppercaseChar() }.let { if (it.last() in ".!?") it else "$it." }
    }

    /** The whole `{…}` objects inside the `briefs` array of a cut-short answer, and whether the array closed. */
    private fun salvage(json: String): Pair<List<WrittenBrief>, Boolean> {
        val key = json.indexOf("\"briefs\"")
        if (key < 0) return emptyList<WrittenBrief>() to false
        val open = json.indexOf('[', key)
        if (open < 0) return emptyList<WrittenBrief>() to false
        val out = mutableListOf<WrittenBrief>()
        var depth = 0
        var inString = false
        var escaped = false
        var objectStart = -1
        var i = open + 1
        while (i < json.length) {
            val c = json[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> {
                    if (depth == 0) objectStart = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        runCatching { Json.parseToJsonElement(json.substring(objectStart, i + 1)) as? JsonObject }.getOrNull()?.let(::brief)?.let(out::add)
                        objectStart = -1
                    }
                }
                c == ']' && depth == 0 -> return out to true
            }
            i++
        }
        return out to false
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
}
