package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.ActionState
import com.folio.hermes.cascade.Transcript
import com.folio.hermes.cascade.Tx
import com.folio.hermes.cascade.writer.BriefRequest
import com.folio.hermes.cascade.writer.BriefSet
import com.folio.hermes.cascade.writer.BriefTemplate
import com.folio.hermes.cascade.writer.Briefs
import com.folio.hermes.cascade.writer.Repair
import com.folio.hermes.cascade.writer.TranscriptBlock
import com.folio.hermes.cascade.writer.Verbatim
import com.folio.hermes.cascade.writer.WrittenBrief

/**
 * Turns one committed turn of new work into [PlannedTask]s (R-TASK-2, R-STT-6, R-CTX-3): the
 * writer's briefs, checked (numbers verbatim, repair limited to uncertain or vocabulary words),
 * each ending in the transcript block that carries the full verbatim dictation.
 */
object BriefPlanner {
    /** The turn's transcript: the merged one if the speech-to-text side left it, else the text. */
    fun transcript(tx: Tx, a: ActionState): Transcript {
        val t = a.transcript ?: tx.s.transcript.byTurn[a.turnId]
        return if (t != null && t.text.trim() == a.text.trim()) t else Transcript(a.text, t?.words.orEmpty(), t?.pass ?: com.folio.hermes.cascade.TranscriptPass.Fast, t?.offline ?: false)
    }

    /** R-CTX-3: the writer sees the dictation in chunks above `writerChunkAboveChars`. */
    fun chunks(text: String, above: Int, size: Int): List<String> {
        if (text.length <= above || size <= 0) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > size) {
            val cut = rest.lastIndexOf(' ', size).takeIf { it > size / 2 } ?: size
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    fun request(tx: Tx, a: ActionState, chunk: String, index: Int, count: Int, carried: List<String>): BriefRequest {
        val t = transcript(tx, a)
        return BriefRequest(
            text = chunk,
            marked = if (count == 1) Repair.marked(t) else null,
            split = a.record.answers[CascadeBrain.SPLIT]?.let { "${it.choice} (${"%.2f".format(it.confidence)})" },
            clarity = clarity(a),
            vocabulary = tx.s.settings.vocabulary,
            recent = tx.recentExchanges(),
            answer = a.data[KeyDoubt.ANSWER],
            chunk = index + 1,
            chunks = count,
            carried = carried,
        )
    }

    fun clarity(a: ActionState): String =
        a.record.answers[CascadeBrain.CLARITY]?.let { "${it.choice} (${"%.2f".format(it.confidence)})" } ?: "not judged"

    /**
     * The tasks for [a] from the writer's [set] (null: the writer failed). Every brief keeps the
     * user's numbers or falls back to the user's own words; a set cut short gets one more task
     * for whatever the cut lost.
     */
    fun plan(tx: Tx, a: ActionState, set: BriefSet?, offline: String?): List<PlannedTask> {
        val t = transcript(tx, a)
        val repaired = Repair.check(t, set?.repaired, tx.s.settings.vocabulary)
        val notes = listOfNotNull(
            set?.notes?.takeIf { it.isNotBlank() },
            repaired.rejected.takeIf { it.isNotEmpty() }?.let { r -> "repair not applied (only uncertain or vocabulary words may change): " + r.joinToString("; ") { "\"${it.first}\" -> \"${it.second}\"" } },
            a.data[KeyDoubt.ANSWER]?.let { "asked about a doubtful word; the user answered \"$it\"" },
            if (a.data[KeyDoubt.STATE] == KeyDoubt.UNANSWERED) "asked about a doubtful word; no answer, so going with what was heard" else null,
        ).joinToString(" ")
        val block = TranscriptBlock(
            transcript = repaired.text,
            asHeard = Repair.marked(t),
            clarity = clarity(a),
            notes = notes,
            offline = offline,
        )
        val written = set?.briefs.orEmpty().map { b -> checked(b, a.text) }
        val source = written.ifEmpty { listOf(null) }
        val labels = written.map { it?.label?.takeIf { l -> l.isNotEmpty() } ?: fallbackLabel(a.text) }
        val planned = source.mapIndexed { i, b ->
            val label = labels.getOrNull(i) ?: fallbackLabel(a.text)
            PlannedTask(
                brief = BriefTemplate.build(b?.brief, block, i + 1, source.size, labels.filterIndexed { k, _ -> k != i }),
                label = label,
                summary = b?.summary,
            )
        }.toMutableList()
        if (set != null && !set.complete) {
            // The writer's answer was cut short: whatever it lost still becomes a task.
            planned += PlannedTask(
                BriefTemplate.build(
                    "Goal: Do any jobs in the transcript below that are not covered by these tasks: ${labels.joinToString("; ")}. If there are none, say so.",
                    block,
                ),
                "remaining jobs",
                null,
            )
        }
        return planned
    }

    /** A brief whose numbers the user never said is replaced by the fallback (the user's own words, below). */
    private fun checked(b: WrittenBrief, source: String): WrittenBrief? {
        val invented = Verbatim.invented(source, b.brief + " " + b.label + " " + b.summary.orEmpty())
        return if (invented.isEmpty()) b else null
    }

    /** The first three words of the request. */
    fun fallbackLabel(text: String): String =
        text.split(' ').map { it.trim(',', '.', '?', '!', '"') }.filter { it.isNotEmpty() }.take(3).joinToString(" ").ifEmpty { "task" }

    /** Parse one chunk's answer (null when the writer failed or wrote nothing usable). */
    fun parse(raw: String?): BriefSet? = Briefs.parse(raw)
}
