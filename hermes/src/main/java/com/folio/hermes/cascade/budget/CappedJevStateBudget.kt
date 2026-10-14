package com.folio.hermes.cascade.budget

import com.folio.hermes.cascade.CascadeSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The real R-CTX-1 budget: Jev's `state` never serialises to more than
 * `CascadeSettings.jevStateCapChars` characters, whatever the input (quotes and control
 * characters grow when escaped, so the serialised JSON is what is measured).
 *
 * Items go in by the v1 §9 priority order until the cap:
 * 1. `latest` (over [LATEST_MAX] characters: the first [LATEST_HEAD], "…[N words]…", the last
 *    [LATEST_TAIL]) and `utterance` (the held part over [HELD_MAX] is cut the same way, or
 *    replaced by `held_summary` when the writer made one);
 * 2. `uncertain` spans;
 * 3. `tasks`: running, waiting and queued first, then up to [DONE_TASKS] others (newest first), at most [MAX_TASKS],
 *    each label ≤ [LABEL_CHARS] and `last_event` ≤ 80;
 * 4. `held_summary`;
 * 5. `recent`: the last `recentExchanges`, each ≤ [EXCHANGE_CHARS];
 * 6. `conversation_summary` ≤ `conversationSummaryChars`.
 *
 * Over the cap, items are dropped from the lowest priority up, then the turn's own text is cut
 * further, so the cap holds for any input. This is Jev's view only: Hermes always gets the full
 * verbatim dictation (R-CTX-3). Keys keep the order they came in.
 */
class CappedJevStateBudget : JevStateBudget {
    override fun fit(state: JsonObject, settings: CascadeSettings): JsonObject {
        val cap = settings.jevStateCapChars
        val latestRaw = state.string("latest")
        val utteranceRaw = state.string("utterance")
        val heldRaw = utteranceRaw.removeSuffix(latestRaw).trim().takeIf { utteranceRaw.endsWith(latestRaw) } ?: ""
        val heldSummary = state.string("held_summary").takeIf { it.isNotBlank() }

        var latestMax = LATEST_MAX
        var latestHead = LATEST_HEAD
        var latestTail = LATEST_TAIL
        var heldMax = HELD_MAX
        var heldHead = HELD_HEAD
        var heldTail = HELD_TAIL
        var tasks = tasks(state["tasks"] as? JsonArray)
        var recent = (state["recent"] as? JsonArray)?.takeLast(settings.recentExchanges.coerceAtLeast(0))?.map { exchange(it) }.orEmpty()
        var uncertain = (state["uncertain"] as? JsonArray)?.take(MAX_UNCERTAIN)?.map { clip(it, UNCERTAIN_CHARS) }.orEmpty()
        var summary: String? = state.string("conversation_summary").takeIf { it.isNotBlank() }?.let { cut(it, settings.conversationSummaryChars) }
        var held: String? = heldSummary?.let { cut(it, HELD_MAX) }

        fun build(): JsonObject {
            val latest = squeeze(latestRaw, latestMax, latestHead, latestTail, "words")
            val heldPart = when {
                heldRaw.isEmpty() -> ""
                // The summary carries the gist; the utterance keeps only the edges (Jev judges how it ends).
                held != null && heldRaw.length > HELD_MAX -> squeeze(heldRaw, 0, heldHead.coerceAtMost(HELD_SHORT), heldTail.coerceAtMost(HELD_SHORT), "words held")
                else -> squeeze(heldRaw, heldMax, heldHead, heldTail, "words held")
            }
            val utterance = if (state.containsKey("utterance")) (heldPart + " " + latest).trim() else null
            val out = LinkedHashMap<String, JsonElement>()
            for ((key, value) in state) {
                when (key) {
                    "utterance" -> out[key] = JsonPrimitive(utterance)
                    "latest" -> out[key] = JsonPrimitive(latest)
                    "tasks" -> out[key] = JsonArray(tasks)
                    "recent" -> out[key] = JsonArray(recent)
                    "uncertain" -> if (uncertain.isNotEmpty()) out[key] = JsonArray(uncertain)
                    "held_summary" -> held?.let { out[key] = JsonPrimitive(it) }
                    "conversation_summary" -> summary?.let { out[key] = JsonPrimitive(it) }
                    else -> out[key] = small(value)
                }
            }
            return JsonObject(out)
        }

        var fitted = build()
        // Drop from the lowest priority up until it fits.
        while (fitted.size() > cap) {
            when {
                summary != null -> summary = null
                recent.isNotEmpty() -> recent = recent.drop(1)
                held != null -> held = null
                tasks.size > 1 -> tasks = tasks.dropLast(1)
                uncertain.isNotEmpty() -> uncertain = uncertain.dropLast(1)
                tasks.isNotEmpty() -> tasks = emptyList()
                heldRaw.isNotEmpty() && heldHead + heldTail > 0 -> {
                    heldHead /= 2
                    heldTail /= 2
                    heldMax = heldHead + heldTail
                }
                latestRaw.isNotEmpty() && latestHead + latestTail > 0 -> {
                    latestHead /= 2
                    latestTail /= 2
                    latestMax = latestHead + latestTail
                }
                else -> return fitted // only the skeleton is left: nothing more can go
            }
            fitted = build()
        }
        return fitted
    }

    private fun tasks(array: JsonArray?): List<JsonElement> {
        val all = array?.mapNotNull { it as? JsonObject }.orEmpty()
        val live = setOf("running", "waiting", "queued")
        val running = all.filter { it.string("status") in live }
        val others = all.filter { it.string("status") !in live }.reversed().take(DONE_TASKS)
        return (running + others).take(MAX_TASKS).map { t ->
            JsonObject(
                t.mapValues { (key, value) ->
                    when (key) {
                        "label" -> JsonPrimitive(cut(t.string(key), LABEL_CHARS))
                        "last_event" -> JsonPrimitive(cut(t.string(key), LAST_EVENT_CHARS))
                        "id", "status" -> JsonPrimitive(cut(t.string(key), ID_CHARS))
                        else -> small(value)
                    }
                },
            )
        }
    }

    private fun exchange(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return JsonObject(emptyMap())
        val user = o.string("user")
        val bill = o.string("bill")
        val userMax = (EXCHANGE_CHARS - minOf(bill.length, EXCHANGE_CHARS / 2)).coerceAtLeast(EXCHANGE_CHARS / 2)
        val u = cut(user, userMax)
        return JsonObject(mapOf("user" to JsonPrimitive(u), "bill" to JsonPrimitive(cut(bill, EXCHANGE_CHARS - u.length))))
    }

    private fun clip(e: JsonElement, max: Int): JsonElement = when (e) {
        is JsonPrimitive -> if (e.isString) JsonPrimitive(cut(e.content, max)) else e
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> clip(v, max) })
        is JsonArray -> JsonArray(e.take(MAX_ALTERNATIVES).map { clip(it, max) })
    }

    /** Anything unexpected keeps its place but stays small. */
    private fun small(value: JsonElement): JsonElement = clip(value, OTHER_CHARS)

    companion object {
        const val LATEST_MAX = 1_500
        const val LATEST_HEAD = 300
        const val LATEST_TAIL = 1_000
        const val HELD_MAX = 800
        const val HELD_HEAD = 200
        const val HELD_TAIL = 400
        private const val HELD_SHORT = 120
        const val MAX_TASKS = 8
        const val DONE_TASKS = 3
        const val LABEL_CHARS = 40
        const val LAST_EVENT_CHARS = 80
        private const val ID_CHARS = 16
        const val EXCHANGE_CHARS = 200
        private const val MAX_UNCERTAIN = 12
        private const val UNCERTAIN_CHARS = 60
        private const val MAX_ALTERNATIVES = 4
        private const val OTHER_CHARS = 200

        /** Serialised length, the thing the cap is about. */
        fun JsonObject.size(): Int = toString().length

        /**
         * [text] if it is at most [max] characters; otherwise its first [head] and last [tail]
         * characters around "…[N <unit>]…" (N = the words left out). Never splits a surrogate pair.
         */
        fun squeeze(text: String, max: Int, head: Int, tail: Int, unit: String): String {
            if (text.length <= max) return text
            val start = safeTake(text, head)
            val end = safeTakeLast(text, tail).takeIf { start.length + it.length < text.length } ?: ""
            val middle = text.substring(start.length, text.length - end.length)
            val words = middle.split(Regex("\\s+")).count { it.isNotEmpty() }
            return (start.trimEnd() + " …[$words $unit]… " + end.trimStart()).trim()
        }

        /** At most [max] characters, ending in "…" when cut. */
        fun cut(text: String, max: Int): String = when {
            max <= 0 -> ""
            text.length <= max -> text
            else -> safeTake(text, max - 1) + "…"
        }

        private fun safeTake(text: String, n: Int): String {
            if (n <= 0) return ""
            if (n >= text.length) return text
            val end = if (Character.isHighSurrogate(text[n - 1])) n - 1 else n
            return text.substring(0, end)
        }

        private fun safeTakeLast(text: String, n: Int): String {
            if (n <= 0) return ""
            if (n >= text.length) return text
            val start = text.length - n
            val from = if (Character.isLowSurrogate(text[start])) start + 1 else start
            return text.substring(from)
        }

        private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
    }
}
