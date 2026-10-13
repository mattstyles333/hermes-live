package com.folio.hermes.cascade.routing

import com.folio.hermes.cascade.JevRequest
import com.folio.hermes.cascade.PhraseBank
import com.folio.hermes.cascade.TaskState
import com.folio.hermes.cascade.TurnContext
import com.folio.hermes.openrouter.JevQuestion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Builds the per-pause Jev call (R-RT-2, R-RT-4): one System One request asking `turn`,
 * `action`, `task` (when there are tasks), `split`, `clarity`, `line`, `enough` and
 * `update_mode` (while any task runs). `split` and `clarity` are asked on every call (spec v2 §0).
 * Jev picks; it never writes. Wording: [JevQuestions].
 *
 * The `state` shape is the measured probe's: `utterance` (held text plus the latest part,
 * joined), `latest`, `silence_ms`, `tasks` (`id`, `label`, `status`), `recent` and `speaking`.
 */
object CascadeBrain {
    const val TURN = "turn"
    const val ACTION = "action"
    const val TASK = "task"
    const val SPLIT = "split"
    const val CLARITY = "clarity"
    const val LINE = "line"
    const val ENOUGH = "enough"
    const val UPDATE_MODE = "update_mode"
    const val NONE = "none"

    // turn
    const val DONE = "done"
    const val KEEP_LISTENING = "keep_listening"
    const val IGNORE = "ignore"

    // action
    const val REPLY = "reply"
    const val NEW_TASK = "new_task"
    const val UPDATE_TASK = "update_task"
    const val STATUS = "status"
    const val CANCEL_TASK = "cancel_task"
    const val READ_RESULT = "read_result"
    const val END_CALL = "end_call"

    // split
    const val ONE = "one"
    const val SEVERAL = "several"

    // clarity
    const val CLEAR = "clear"
    const val MINOR_DOUBT = "minor_doubt"
    const val KEY_DOUBT = "key_doubt"
    const val GARBLED = "garbled"

    // enough
    const val LINE_ONLY = "line_only"
    const val LINE_THEN_WRITER = "line_then_writer"

    // update_mode
    const val QUEUE = "queue"
    const val REDIRECT = "redirect"

    /** The words a request is about: an early answer is reused only if these did not change. */
    fun key(heard: String, pending: List<String>): String = (pending + heard.trim()).joinToString("\u0001")

    /** `utterance`: everything said for this request so far (held pieces, then the latest part). */
    fun utterance(heard: String, pending: List<String>): String = (pending.joinToString(" ") + " " + heard.trim()).trim()

    fun state(ctx: TurnContext): JsonObject = buildJsonObject {
        put("utterance", utterance(ctx.heard, ctx.pending))
        put("latest", ctx.heard.trim())
        put("silence_ms", ctx.silenceMs)
        putJsonArray("tasks") {
            ctx.tasks.forEach { t ->
                addJsonObject {
                    put("id", t.id)
                    put("label", t.label)
                    put("status", t.state.wire)
                }
            }
        }
        putJsonArray("recent") {
            ctx.recent.forEach { e ->
                addJsonObject {
                    put("user", e.user)
                    put("bill", e.bill)
                }
            }
        }
        put("speaking", ctx.speaking)
    }

    fun questions(ctx: TurnContext, bank: PhraseBank, backchannel: Boolean): Map<String, JevQuestion> {
        val questions = linkedMapOf<String, JevQuestion>()
        questions[TURN] = JevQuestions.TURN
        questions[ACTION] = JevQuestions.ACTION
        if (ctx.tasks.isNotEmpty()) {
            questions[TASK] = JevQuestion(
                JevQuestions.TASK,
                linkedMapOf<String, String>().apply {
                    ctx.tasks.forEach { put(it.id, "${it.label} (${it.state.wire})") }
                    put(NONE, JevQuestions.TASK_NONE)
                },
            )
        }
        questions[SPLIT] = JevQuestions.SPLIT
        questions[CLARITY] = JevQuestions.CLARITY
        questions[LINE] = JevQuestion(
            JevQuestions.LINE,
            linkedMapOf<String, String>().apply {
                bank.jevSlots(backchannel).forEach { put(it.id, it.use) }
                put(NONE, JevQuestions.LINE_NONE)
            },
        )
        questions[ENOUGH] = JevQuestions.ENOUGH
        if (ctx.tasks.any { it.state == TaskState.Running }) questions[UPDATE_MODE] = JevQuestions.UPDATE_MODE
        return questions
    }

    fun request(ctx: TurnContext, bank: PhraseBank, backchannel: Boolean): JevRequest =
        JevRequest(state(ctx), questions(ctx, bank, backchannel), key(ctx.heard, ctx.pending))
}
