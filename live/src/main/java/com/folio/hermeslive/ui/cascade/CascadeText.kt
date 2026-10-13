package com.folio.hermeslive.ui.cascade

import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.JevPick
import com.folio.hermes.cascade.JevUi
import com.folio.hermes.cascade.ResumeUi
import com.folio.hermes.cascade.SendState
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.cascade.routing.CascadeBrain
import java.util.Locale

/*
 * Every word the call screen shows, as pure functions (so the text twin of each animation is
 * tested without Compose).
 */

/** The orb's state in words (the TalkBack description and the line under the orb). */
fun orbStatus(s: CascadeUiState): String = when {
    s.turn.holdToTalk -> "listening while you hold"
    s.turn.phase == TurnPhase.UserSpeaking -> "listening"
    s.turn.phase == TurnPhase.Pausing -> "waiting to see if you have finished"
    s.turn.phase == TurnPhase.Deciding -> "deciding"
    s.turn.phase == TurnPhase.Writing -> "thinking"
    s.bill.speaking || s.turn.phase == TurnPhase.BillSpeaking -> "speaking"
    !s.connection.online -> "taking notes offline"
    else -> "idle"
}

/** The short line under the orb (empty while a caption says it already). */
fun phaseLabel(s: CascadeUiState): String = when {
    s.turn.holdToTalk -> "Release to send"
    s.turn.phase == TurnPhase.Pausing -> "Pause"
    s.turn.phase == TurnPhase.Deciding -> "Deciding"
    s.turn.phase == TurnPhase.Writing -> "Thinking"
    s.turn.phase == TurnPhase.Idle && s.tasks.none { it.status.live } && s.bill.text == null -> "Hold the orb to talk"
    else -> ""
}

/** `.94`, `1.0`. */
fun confidence(c: Double): String {
    val v = c.coerceIn(0.0, 1.0)
    return if (v >= 0.995) "1.0" else String.format(Locale.UK, "%.2f", v).removePrefix("0")
}

/** One Jev chip: question, choice and confidence. */
data class JevChip(val question: String, val choice: String, val confidence: String?) {
    val text: String get() = listOfNotNull(question, choice, confidence).joinToString(" ")
}

/** The chips for Jev's last answer, in the prototype's order. Task ids show as their labels. */
fun jevChips(jev: JevUi, tasks: List<TaskUi>): List<JevChip> {
    val order = listOf(
        CascadeBrain.TURN, CascadeBrain.ACTION, CascadeBrain.TASK, CascadeBrain.SPLIT,
        CascadeBrain.CLARITY, CascadeBrain.LINE, CascadeBrain.ENOUGH, CascadeBrain.UPDATE_MODE,
    )
    return order.mapNotNull { q ->
        val pick: JevPick = jev.answers[q] ?: return@mapNotNull null
        val raw = pick.choice ?: return@mapNotNull null
        if (q == CascadeBrain.CLARITY && raw == "clear") return@mapNotNull null
        if (q == CascadeBrain.SPLIT && raw == CascadeBrain.ONE) return@mapNotNull null
        if (q == CascadeBrain.ENOUGH && raw == CascadeBrain.NONE) return@mapNotNull null
        val choice = if (q == CascadeBrain.TASK) tasks.firstOrNull { it.id == raw }?.label?.let(SceneModel::shortLabel) ?: raw else raw
        val showConf = q != CascadeBrain.LINE
        JevChip(q.replace('_', ' '), choice, if (showConf) confidence(pick.confidence) else null)
    }
}

/** `0:13`, `4:05`, `1:02:03`. */
fun elapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s) else String.format(Locale.UK, "%d:%02d", m, s)
}

/** `8s`, `1m 12s`, `1h 03m` (task cards). */
fun shortElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return when {
        total < 60 -> "${total}s"
        total < 3600 -> "${total / 60}m ${String.format(Locale.UK, "%02d", total % 60)}s"
        else -> "${total / 3600}h ${String.format(Locale.UK, "%02d", (total % 3600) / 60)}m"
    }
}

/** `search_skus` → `search skus`. */
fun prettyTool(tool: String): String = tool.replace('_', ' ').replace('.', ' ').trim()

/** The card's second line. */
fun taskLine(task: TaskUi): String {
    val steps = when (task.steps) {
        0 -> ""
        1 -> " · 1 step"
        else -> " · ${task.steps} steps"
    }
    if (task.send == SendState.NotSent && task.status != TaskUiStatus.Done) return "Hermes did not get it"
    return when (task.status) {
        TaskUiStatus.Starting -> "starting…"
        TaskUiStatus.Running -> (task.currentTool?.let(::prettyTool) ?: "working") + steps +
            if (task.pendingUpdates > 0) " · ${task.pendingUpdates} update${if (task.pendingUpdates > 1) "s" else ""} queued" else ""
        TaskUiStatus.Waiting -> "needs you · approve or answer$steps"
        TaskUiStatus.Queued -> "queued · starts when a slot frees"
        TaskUiStatus.Done -> if (task.unread) "finished · result not heard yet" else "finished$steps"
        TaskUiStatus.Failed -> "failed$steps · open to retry"
        TaskUiStatus.Cancelled -> "cancelled"
    }
}

/** The send state as words (null when accepted and nothing to say). */
fun sendLine(task: TaskUi): String? = when (task.send) {
    SendState.Sending -> if (task.status == TaskUiStatus.Done || task.status == TaskUiStatus.Cancelled) null else "sending…"
    SendState.NotSent -> "Not sent · tap to retry"
    SendState.Accepted -> null
}

fun statusWord(status: TaskUiStatus): String = when (status) {
    TaskUiStatus.Starting -> "starting"
    TaskUiStatus.Running -> "running"
    TaskUiStatus.Waiting -> "waiting for your approval"
    TaskUiStatus.Queued -> "queued"
    TaskUiStatus.Done -> "finished"
    TaskUiStatus.Failed -> "failed"
    TaskUiStatus.Cancelled -> "cancelled"
}

/** The card's TalkBack description: everything the satellite shows by motion. */
fun taskDescription(task: TaskUi, elapsedMs: Long): String = buildString {
    append(task.label).append(", ").append(statusWord(task.status))
    task.currentTool?.takeIf { task.status == TaskUiStatus.Running }?.let { append(", ").append(prettyTool(it)) }
    if (task.steps > 0) append(", ").append(task.steps).append(if (task.steps == 1) " step" else " steps")
    append(", ").append(shortElapsed(elapsedMs))
    sendLine(task)?.let { append(", ").append(it) }
    if (task.unread) append(", result not heard yet")
    if (task.offline) append(", dictated offline")
}

/** Progress along the card's bottom edge, 0..1 (capped below 1 until done: true progress is unknown). */
fun taskProgress(task: TaskUi): Float = when (task.status) {
    TaskUiStatus.Done -> 1f
    TaskUiStatus.Queued -> 0f
    else -> ((8f + task.steps * 15f) / 100f).coerceAtMost(0.92f)
}

/** Elapsed for a card: running time, or the time it took. */
fun taskElapsedMs(task: TaskUi, nowMs: Long): Long = ((task.finishedAtMs ?: nowMs) - task.startedAtMs).coerceAtLeast(0)

/** "Memo · offline · 3 held" or "offline · 3 held". */
fun connectionBanner(s: CascadeUiState): String? {
    val held = s.connection.outbox
    val heldText = if (held > 0) " · $held held" else ""
    return when {
        s.connection.memo -> "Memo · offline$heldText"
        !s.connection.online -> "offline$heldText"
        held > 0 -> "sending · $held held"
        else -> null
    }
}

/** The top bar's connection state. */
fun connectionWord(s: CascadeUiState): String = when {
    s.connection.memo -> "memo"
    !s.connection.online -> "offline"
    !s.connection.hermesUp -> "connecting"
    else -> "online"
}

/** `just now`, `12 min ago`, `3 h ago`, `2 days ago`. */
fun ago(nowWallMs: Long, thenWallMs: Long): String {
    val min = ((nowWallMs - thenWallMs) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

/** The Resume card's facts line. */
fun resumeLine(r: ResumeUi, nowWallMs: Long): String {
    val parts = mutableListOf("Last call ${ago(nowWallMs, r.lastEndedWallMs)}")
    if (r.unheardResults > 0) parts += "${r.unheardResults} result${if (r.unheardResults > 1) "s" else ""} not heard"
    if (r.heldTurns > 0) parts += "${r.heldTurns} held"
    if (r.waitingTasks > 0) parts += "${r.waitingTasks} waiting for you"
    return parts.joinToString(" · ")
}
