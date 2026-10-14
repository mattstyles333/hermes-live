package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.BillUi
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.CeoUi
import com.folio.hermes.cascade.ConnectionUi
import com.folio.hermes.cascade.HeldTurnUi
import com.folio.hermes.cascade.JevPick
import com.folio.hermes.cascade.JevUi
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermes.cascade.ResumeUi
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.ToolUi
import com.folio.hermes.cascade.TranscriptPass
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.cascade.TurnUi
import com.folio.hermes.cascade.Word
import com.folio.hermes.cascade.routing.CascadeBrain
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scripted call: the prototype's scenario (`hermes-cascade-call-prototype.html`) as a stream
 * of `CascadeUiState`s and `CascadeEvent`s, made-up data only. Deterministic: [stateAt] and
 * [eventsBetween] replay any moment (the prototype's `?at=`); [play] runs it in time for a
 * live screen. Task times are on the script clock ([nowMs]), so pass `clock = { fake.nowMs }`.
 *
 * Reusable by the integration slice (S-F) for scenario screens and screen recordings.
 */
class FakeCascadeUi(val pauseMs: Int = 1000) {
    private val _state = MutableStateFlow(CascadeUiState(connection = ConnectionUi(hermesUp = true)))
    private val _events = MutableSharedFlow<CascadeEvent>(extraBufferCapacity = 64)

    val state: StateFlow<CascadeUiState> = _state.asStateFlow()
    val events: SharedFlow<CascadeEvent> = _events.asSharedFlow()

    /** Script time of the current [state]. */
    @Volatile
    var nowMs: Long = 0L
        private set

    /** Jumps to [ms] (no events are emitted). */
    fun seek(ms: Long) {
        nowMs = ms
        _state.value = stateAt(ms)
    }

    /** Plays the script in real time from [fromMs], emitting its events; [loop] restarts at the end. */
    suspend fun play(fromMs: Long = 0L, frameMs: Long = 33L, loop: Boolean = false) {
        do {
            var t = fromMs
            var last = fromMs - 1
            while (t <= DURATION_MS) {
                for ((_, e) in eventsBetween(last, t)) _events.emit(e)
                nowMs = t
                _state.value = stateAt(t)
                last = t
                delay(frameMs)
                t += frameMs
            }
        } while (loop)
    }

    /** The state at script time [ms]. Pure. */
    fun stateAt(ms: Long): CascadeUiState {
        val sim = Sim(pauseMs)
        for (step in STEPS) {
            if (step.atMs > ms) break
            step.apply(sim, step.atMs)
        }
        return sim.state(ms)
    }

    /** The events in (fromMs, toMs], in order with their times. */
    fun eventsBetween(fromMs: Long, toMs: Long): List<Pair<Long, CascadeEvent>> =
        STEPS.filter { it.atMs in (fromMs + 1)..toMs }.flatMap { s -> s.events.map { s.atMs to it } }

    private class Step(val atMs: Long, val events: List<CascadeEvent>, val apply: Sim.(Long) -> Unit)

    /** The script's mutable working state (rebuilt for each [stateAt]). */
    private class Sim(val pauseMs: Int) {
        var phase = TurnPhase.Idle
        var partial = ""
        var words: List<Word> = emptyList()
        var bill: String? = null
        var pauseStart: Long? = null
        var jevInFlight = false
        var answers: Map<String, JevPick> = emptyMap()
        var latency: Long? = null
        val tasks = LinkedHashMap<String, TaskUi>()

        fun say(text: String?) {
            bill = text
            if (text != null) phase = TurnPhase.BillSpeaking else if (phase == TurnPhase.BillSpeaking) phase = TurnPhase.Idle
        }

        fun commit(heard: List<Word>) {
            pauseStart = null
            partial = ""
            words = heard
        }

        fun addTask(id: String, label: String, at: Long, brief: String) {
            tasks[id] = TaskUi(
                id = id, label = label, status = TaskUiStatus.Starting, startedAtMs = at, finishedAtMs = null, steps = 0,
                currentTool = null, recentTools = emptyList(), lastEventAtMs = at, partialText = "", resultPreview = null,
                pendingUpdates = 0, brief = brief, summary = label, send = SendState.Sending,
            )
        }

        fun tool(id: String, name: String, at: Long, partial: String = "") {
            val t = tasks[id] ?: return
            val closed = t.recentTools.map { if (it.endedAtMs == null) it.copy(endedAtMs = at) else it }
            tasks[id] = t.copy(
                status = TaskUiStatus.Running, steps = t.steps + 1, currentTool = name,
                recentTools = (closed + ToolUi(name, at, null)).takeLast(5), lastEventAtMs = at,
                partialText = if (partial.isEmpty()) t.partialText else (t.partialText + partial).takeLast(200),
                send = SendState.Accepted,
            )
        }

        fun finish(id: String, at: Long, result: String) {
            val t = tasks[id] ?: return
            tasks[id] = t.copy(
                status = TaskUiStatus.Done, finishedAtMs = at, currentTool = null,
                recentTools = t.recentTools.map { if (it.endedAtMs == null) it.copy(endedAtMs = at) else it },
                resultPreview = result.take(280), result = result, unread = true, lastEventAtMs = at,
            )
        }

        fun heard(id: String) {
            tasks[id]?.let { tasks[id] = it.copy(unread = false) }
        }

        fun state(ms: Long): CascadeUiState {
            val t = ms / 1000f
            val pause = pauseStart?.let { min(1f, (ms - it).toFloat() / pauseMs) } ?: 0f
            val ph = when {
                pauseStart != null && pause >= 1f && jevInFlight -> TurnPhase.Deciding
                pauseStart != null -> TurnPhase.Pausing
                else -> phase
            }
            val level = if (bill != null) 0.55f + 0.35f * abs(sin(t * 9f) * sin(t * 3.3f)) else 0f
            return CascadeUiState(
                turn = TurnUi(phase = ph, partial = partial, pauseProgress = pause, words = words, pass = TranscriptPass.Accurate),
                jev = JevUi(inFlight = jevInFlight, answers = answers, latencyMs = latency),
                bill = BillUi(speaking = bill != null, level = level, text = bill),
                tasks = tasks.values.toList(),
                connection = ConnectionUi(hermesUp = true),
                ceo = CeoUi(unreadResults = tasks.values.filter { it.unread }.map { it.id }),
            )
        }
    }

    companion object {
        const val DURATION_MS = 26_000L

        /** Made-up Jev pick. */
        fun pick(choice: String, confidence: Double, vararg others: Pair<String, Double>): JevPick =
            JevPick(choice, confidence, (listOf(choice to confidence) + others).sortedByDescending { it.second }.take(3))

        /** Words of [text]; entries of [unsure] are uncertain, with their alternatives. */
        fun words(text: String, unsure: Map<String, List<String>> = emptyMap()): List<Word> = text.split(' ').filter { it.isNotBlank() }.map { w ->
            val alts = unsure[w]
            if (alts == null) Word(w, 0.97) else Word(w, 0.41, alts, uncertain = true)
        }

        private const val FIRST = "check stock on the Ray-Ban 2140 and draft a reorder email to the supplier"

        private val STEPS: List<Step> = listOf(
            Step(300, listOf(CascadeEvent.LinePlayed("social.hi"))) { say("Hey. What's up?") },
            Step(1_600, emptyList()) { say(null) },
            Step(2_200, emptyList()) { phase = TurnPhase.UserSpeaking; partial = "check stock on the Ray-Ban 2140" },
            Step(3_400, emptyList()) { partial = "check stock on the Ray-Ban 2140… and draft a reorder email to the supplier" },
            Step(4_600, emptyList()) { at -> pauseStart = at },
            Step(5_100, listOf(CascadeEvent.JevFired(early = true))) { jevInFlight = true },
            Step(5_450, listOf(CascadeEvent.JevAnswered(350))) {
                jevInFlight = false
                latency = 350
                answers = mapOf(
                    CascadeBrain.TURN to pick("done", 0.94, "keep_listening" to 0.05, "ignore" to 0.01),
                    CascadeBrain.ACTION to pick("new_task", 0.91, "reply" to 0.06, "status" to 0.02),
                    CascadeBrain.SPLIT to pick("several", 0.88),
                    CascadeBrain.LINE to pick("start.parallel", 0.8),
                )
            },
            Step(
                5_600,
                listOf(
                    CascadeEvent.TurnCommitted("t1"), CascadeEvent.TaskStarted("a"), CascadeEvent.TaskStarted("b"),
                    CascadeEvent.LinePlayed("start.parallel"),
                ),
            ) { at ->
                commit(words(FIRST, mapOf("Ray-Ban" to listOf("Rayban", "Ray Ben"), "2140" to listOf("2104", "2014"))))
                phase = TurnPhase.BillSpeaking
                say("Running those in parallel.")
                addTask("a", "Stock check · RB2140", at, "Check stock levels for Ray-Ban RB2140 across all warehouses.")
                addTask("b", "Reorder email draft", at, "Draft a reorder email to the RB2140 supplier.")
            },
            Step(6_600, listOf(CascadeEvent.TaskAccepted("a"), CascadeEvent.ToolStarted("a", "search_skus"))) { at -> tool("a", "search_skus", at, "Looking up RB2140 frames. ") },
            Step(7_000, listOf(CascadeEvent.TaskAccepted("b"), CascadeEvent.ToolStarted("b", "get_supplier"))) { at -> say(null); tool("b", "get_supplier", at) },
            Step(7_800, listOf(CascadeEvent.ToolStarted("a", "get_stock_levels"))) { at -> tool("a", "get_stock_levels", at, "Found 3 variants. ") },
            Step(8_600, listOf(CascadeEvent.ToolStarted("b", "read_last_orders"))) { at -> tool("b", "read_last_orders", at, "Reading the last two orders. ") },
            Step(9_400, emptyList()) { phase = TurnPhase.UserSpeaking; partial = "how's the stock one going" },
            Step(10_300, emptyList()) { at -> pauseStart = at },
            Step(10_800, listOf(CascadeEvent.JevFired(early = true))) { jevInFlight = true },
            Step(11_100, listOf(CascadeEvent.JevAnswered(300))) {
                jevInFlight = false
                latency = 300
                answers = mapOf(
                    CascadeBrain.TURN to pick("done", 0.97, "keep_listening" to 0.03),
                    CascadeBrain.ACTION to pick("status", 0.95, "reply" to 0.04),
                    CascadeBrain.TASK to pick("a", 0.93, "b" to 0.07),
                    CascadeBrain.ENOUGH to pick("writer", 0.81),
                )
            },
            Step(11_300, listOf(CascadeEvent.TurnCommitted("t2"))) {
                commit(words("how's the stock one going"))
                phase = TurnPhase.BillSpeaking
                say("Here's where it's at. It's found the frame and is reading stock across three warehouses.")
            },
            Step(12_200, listOf(CascadeEvent.ToolStarted("a", "list_stock_receipts"))) { at -> tool("a", "list_stock_receipts", at, "Warehouse A: 9. Warehouse B: 5. ") },
            Step(13_200, listOf(CascadeEvent.ToolStarted("b", "compose_email"))) { at -> tool("b", "compose_email", at, "Dear supplier, we'd like to reorder ") },
            Step(14_400, emptyList()) { say(null) },
            Step(15_000, listOf(CascadeEvent.ToolStarted("a", "summarise"))) { at -> tool("a", "summarise", at) },
            Step(16_000, listOf(CascadeEvent.TaskFinished("a", ok = true), CascadeEvent.ResultQueued("a"))) { at ->
                finish("a", at, "14 in stock across three warehouses: 6 in the black 52, 5 in tortoise 50, 3 in gold 54. Warehouse C has none.")
            },
            Step(16_800, listOf(CascadeEvent.LinePlayed("result.one_of"))) {
                answers = mapOf(CascadeBrain.LINE to pick("result.one_of", 0.9))
                say("One of those is back. 14 in stock, 6 in the black 52.")
            },
            Step(19_400, listOf(CascadeEvent.ResultSpoken("a"))) { say(null); heard("a") },
            Step(19_600, listOf(CascadeEvent.ToolStarted("b", "check_tone"))) { at -> tool("b", "check_tone", at) },
            Step(20_800, listOf(CascadeEvent.TaskFinished("b", ok = true), CascadeEvent.ResultQueued("b"))) { at ->
                finish("b", at, "Draft ready: a short reorder email for 20 RB2140 in black 52 and 10 in tortoise 50, asking for delivery dates.")
            },
            Step(21_500, listOf(CascadeEvent.LinePlayed("result.back"))) {
                answers = mapOf(CascadeBrain.LINE to pick("result.back", 0.9))
                say("That's back. Draft's on screen, ready for you to send.")
            },
            Step(24_500, listOf(CascadeEvent.ResultSpoken("b"))) { say(null); heard("b") },
        )
    }

    /** Single moments the script does not reach (made-up data), for renders and tests. */
    object Moments {
        private fun task(
            id: String, label: String, status: TaskUiStatus, startedAtMs: Long, steps: Int, tool: String?,
            send: SendState = SendState.Accepted, unread: Boolean = false, finishedAtMs: Long? = null, result: String? = null,
        ) = TaskUi(
            id = id, label = label, status = status, startedAtMs = startedAtMs, finishedAtMs = finishedAtMs, steps = steps,
            currentTool = tool, recentTools = tool?.let { listOf(ToolUi(it, startedAtMs + 2_000, null)) }.orEmpty(),
            lastEventAtMs = startedAtMs, partialText = "", resultPreview = result?.take(280), pendingUpdates = 0,
            send = send, unread = unread, result = result,
        )

        /** Three agents running (at script time 14 s). */
        fun threeAgents(): CascadeUiState = FakeCascadeUi().stateAt(14_000).let { s ->
            s.copy(tasks = s.tasks + task("c", "Supplier call notes", TaskUiStatus.Running, 9_000, 2, "read_calendar", send = SendState.Accepted))
        }

        /** One agent waiting for an approval. */
        fun waiting(): CascadeUiState = FakeCascadeUi().stateAt(9_000).let { s ->
            s.copy(tasks = s.tasks.map { if (it.id == "b") it.copy(status = TaskUiStatus.Waiting, currentTool = "send_email") else it })
        }

        /** A task whose two submits failed ("Not sent · tap to retry"). */
        fun notSent(): CascadeUiState = FakeCascadeUi().stateAt(9_000).let { s ->
            s.copy(tasks = s.tasks + task("c", "Book the courier", TaskUiStatus.Starting, 8_000, 0, null, send = SendState.NotSent))
        }

        /** Offline: memo mode with three held turns and a faint held stack. */
        fun offlineMemo(): CascadeUiState {
            val held = listOf(
                HeldTurnUi("h1", "remind me to call the lab about the Oakley order", 0),
                HeldTurnUi("h2", "and check the returns from last week", 0),
                HeldTurnUi("h3", "add tortoise 50 to the reorder", 0),
            )
            val base = FakeCascadeUi().stateAt(9_000)
            return base.copy(
                turn = base.turn.copy(
                    phase = TurnPhase.UserSpeaking, partial = "and put the Leeds shop on the list for Friday",
                    heldPieces = listOf("remind me to call the lab", "and check the returns from last week"), offline = true,
                ),
                connection = ConnectionUi(hermesUp = false, outbox = 3, online = false, memo = true),
                ceo = base.ceo.copy(heldTurns = held),
            )
        }

        /** The Resume card after a call that ended 12 minutes ago, with a history. */
        fun resume(nowWallMs: Long): CascadeUiState = CascadeUiState(
            connection = ConnectionUi(hermesUp = true),
            resume = ResumeUi("c-7", nowWallMs - 12 * 60_000, unheardResults = 2, heldTurns = 1, waitingTasks = 1, defaultChoice = ResumeChoice.Continue),
        )

        fun history(nowWallMs: Long): List<CascadeHistoryItem> = listOf(
            CascadeHistoryItem("c-6", "Stock check and reorder email", nowWallMs - 3 * 3_600_000L, 2),
            CascadeHistoryItem("c-5", "Weekly returns summary", nowWallMs - 26 * 3_600_000L, 1),
            CascadeHistoryItem("c-4", "Lab orders for the Leeds shop", nowWallMs - 3 * 86_400_000L, 3),
        )

        /** After a commit with two uncertain words. */
        fun uncertainWords(): CascadeUiState = FakeCascadeUi().stateAt(5_900)
    }
}
