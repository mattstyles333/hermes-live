package com.folio.hermeslive.ui.cascade

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import com.folio.hermes.cascade.CascadeEvent
import com.folio.hermes.cascade.CascadeUiState
import com.folio.hermes.cascade.ResumeUi
import com.folio.hermes.cascade.TaskUi
import com.folio.hermes.cascade.TaskUiStatus
import com.folio.hermes.cascade.TurnPhase
import com.folio.hermes.cascade.Word

/*
 * :hermes is not compiled with Compose, so its types are unstable here. Each section of the
 * screen gets an @Immutable model derived with structural equality: a section recomposes only
 * when what it shows changes, not on every actor event (levels arrive at audio rate).
 */

/** One task card. */
@Immutable
data class TaskCard(val task: TaskUi) {
    val id: String get() = task.id
    val finished: Boolean get() = task.status == TaskUiStatus.Done || task.status == TaskUiStatus.Cancelled
    val cancellable: Boolean get() = task.status.live
}

@Immutable
data class TaskCards(val list: List<TaskCard>)

@Immutable
internal data class JevModel(val chips: List<JevChip>, val inFlight: Boolean, val latencyMs: Long?)

@Immutable
internal data class CaptionModel(
    val partial: String,
    val heldPieces: List<String>,
    val words: List<Word>,
    val billText: String?,
    val phaseLabel: String,
    val committed: Boolean,
)

@Immutable
internal data class TopModel(val connection: String, val online: Boolean, val hermesUp: Boolean, val memo: Boolean)

@Immutable
internal data class BannerModel(val text: String?, val memo: Boolean)

@Immutable
internal data class ResumeModel(val resume: ResumeUi?)

/** A past conversation for the history list under the Resume card (not in `CascadeUiState`; S-F supplies it). */
@Immutable
data class CascadeHistoryItem(val conversationId: String, val title: String, val endedWallMs: Long, val tasks: Int)

/** App-level facts the cascade state does not carry. */
@Immutable
data class CascadeChrome(
    /** Call start on the same clock as the screen's `clock` (null: no call timer). */
    val callStartedAtMs: Long? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val engine: String = "BILL · CASCADE",
)

/** The derived sections of one state stream. */
internal class Sections(ui: State<CascadeUiState>) {
    val tasks: State<TaskCards> = derivedStateOf(structuralEqualityPolicy()) { TaskCards(ui.value.tasks.map(::TaskCard)) }
    val jev: State<JevModel> = derivedStateOf(structuralEqualityPolicy()) {
        val s = ui.value
        JevModel(jevChips(s.jev, s.tasks), s.jev.inFlight, s.jev.latencyMs)
    }
    val caption: State<CaptionModel> = derivedStateOf(structuralEqualityPolicy()) {
        val s = ui.value
        CaptionModel(
            partial = s.turn.partial,
            heldPieces = s.turn.heldPieces,
            words = s.turn.words,
            billText = s.bill.text,
            phaseLabel = phaseLabel(s),
            committed = s.turn.partial.isEmpty() && s.turn.phase != TurnPhase.UserSpeaking,
        )
    }
    val top: State<TopModel> = derivedStateOf(structuralEqualityPolicy()) {
        val s = ui.value
        TopModel(connectionWord(s), s.connection.online, s.connection.hermesUp, s.connection.memo)
    }
    val banner: State<BannerModel> = derivedStateOf(structuralEqualityPolicy()) {
        val s = ui.value
        BannerModel(connectionBanner(s), s.connection.memo)
    }
    val resume: State<ResumeModel> = derivedStateOf(structuralEqualityPolicy()) { ResumeModel(ui.value.resume) }
    val holding: State<Boolean> = derivedStateOf(structuralEqualityPolicy()) { ui.value.turn.holdToTalk }
}

/** An open word choice. Valid only while `words[index].text == text` (the accurate pass may replace the words). */
@Immutable
internal data class SelectedWord(val index: Int, val text: String)

/** A held turn flying out of the offline pill on reconnect. */
@Immutable
internal data class HeldFlyout(val turnId: String, val text: String)

/**
 * The screen's own memory: what events taught it (the last committed turn id, the chip
 * stagger), and the user's open sheets. Everything here is decoration or local UI; the state
 * stays the truth.
 */
internal class ScreenMemory {
    /** From the last `TurnCommitted`: word choices need it (`TurnUi` carries no turn id). */
    var lastTurnId by mutableStateOf<String?>(null)

    /** Bumped on each `JevAnswered`: the chips drop in again with a stagger. 0 = never seen, show at once. */
    var chipEpoch by mutableIntStateOf(0)

    var sheetTaskId by mutableStateOf<String?>(null)
    var decisionsOpen by mutableStateOf(false)
    /** The uncertain word whose alternatives are open: its index in `TurnUi.words` and its text. */
    var selectedWord by mutableStateOf<SelectedWord?>(null)
    var doneExpanded by mutableStateOf(true)

    /** Swiped cards waiting out the Undo window: id → label. */
    val pendingCancels = mutableStateMapOf<String, String>()

    val flyouts = mutableStateListOf<HeldFlyout>()
    private val heldText = HashMap<String, String>()

    /** The orb is held (hold-to-talk). */
    var holding by mutableStateOf(false)

    /** The End button was tapped in this call (the end is then [EndReason.EndButton]). */
    var endTapped by mutableStateOf(false)

    /** The running call's start on the screen's clock, kept for the end (the chrome drops it then). */
    var callStartMs: Long? = null

    fun observe(s: CascadeUiState) {
        for (h in s.ceo.heldTurns) heldText[h.turnId] = h.text
    }

    fun onEvent(e: CascadeEvent) {
        when (e) {
            is CascadeEvent.TurnCommitted -> {
                lastTurnId = e.turnId
                selectedWord = null
            }
            is CascadeEvent.JevAnswered -> chipEpoch++
            is CascadeEvent.HeldSent -> {
                flyouts += HeldFlyout(e.turnId, heldText.remove(e.turnId) ?: "held turn")
                if (flyouts.size > 4) flyouts.removeAt(0)
            }
            else -> Unit
        }
    }
}

/** Counts recompositions per section (tests only; null in the app). */
fun interface RecomposeProbe {
    fun hit(section: String)
}

val LocalRecomposeProbe = staticCompositionLocalOf<RecomposeProbe?> { null }

/** Records one recomposition of the calling scope (inline: it adds no scope of its own). */
@Suppress("NOTHING_TO_INLINE")
@Composable
@NonRestartableComposable
internal inline fun Probe(section: String) {
    val p = LocalRecomposeProbe.current
    if (p != null) SideEffect { p.hit(section) }
}
