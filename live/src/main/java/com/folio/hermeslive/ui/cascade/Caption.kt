package com.folio.hermeslive.ui.cascade

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermes.cascade.Word
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.CaptionStyle
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.Pill
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.PrintShadow
import com.folio.hermeslive.ui.SmallStyle

/** Most words shown for one committed turn (long dictation shows its tail). */
internal const val MAX_WORDS = 40

private val UserStyle = BodyStyle.copy(fontSize = 16.sp, lineHeight = 22.sp, shadow = PrintShadow, textAlign = TextAlign.Center)

/**
 * The caption: held keep_listening pieces as a faint stack, the user's words (PrintMute while
 * partial, PrintInk once committed, uncertain words underlined and tappable), and Bill's
 * sentence in Ember.
 */
@Composable
internal fun CaptionView(cap: State<CaptionModel>, memory: ScreenMemory, onWord: (Int, String) -> Unit, motion: CascadeMotion) {
    Probe("caption")
    val c = cap.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("caption"), horizontalAlignment = Alignment.CenterHorizontally) {
        val held = c.heldPieces.takeLast(3)
        held.forEachIndexed { i, piece ->
            val alpha = 0.22f + 0.1f * (i + 3 - held.size)
            Text(
                piece, style = SmallStyle.copy(fontSize = 13.sp, textAlign = TextAlign.Center), color = PrintMute.copy(alpha = alpha),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Held: $piece" }.testTag("held-$i"),
            )
        }
        val ink by animateColorAsState(if (c.committed) PrintInk else PrintMute, tween(if (motion.reduceMotion) 0 else 450), label = "commit")
        when {
            c.partial.isNotEmpty() -> Text(
                tail(c.partial), style = UserStyle, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("caption-partial"),
            )
            c.words.isNotEmpty() -> Words(c.words, ink, memory, onWord)
        }
        val open = memory.selectedWord
        val selected = open?.let { sel -> c.words.getOrNull(sel.index)?.takeIf { it.text == sel.text } }
        if (open != null && selected != null && c.partial.isEmpty()) {
            BackHandler { memory.selectedWord = null }
            Alternatives(open.index, selected, memory, onWord)
        }
        AnimatedContent(
            c.billText,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "bill",
        ) { line ->
            if (line != null) {
                Text(
                    line, style = CaptionStyle.copy(textAlign = TextAlign.Center, shadow = PrintShadow), color = Ember, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Bill: $line" }.testTag("caption-bill"),
                )
            }
        }
        if (c.phaseLabel.isNotEmpty()) {
            Text(c.phaseLabel.uppercase(), style = LabelStyle.copy(fontSize = 10.sp), color = PrintMute.copy(alpha = 0.8f), modifier = Modifier.padding(top = 10.dp).testTag("phase-label"))
        }
    }
}

/** The last [MAX_WORDS] words; uncertain ones underlined and tappable when they have alternatives. */
@Composable
private fun Words(words: List<Word>, color: Color, memory: ScreenMemory, onWord: (Int, String) -> Unit) {
    val start = (words.size - MAX_WORDS).coerceAtLeast(0)
    val canChoose = memory.lastTurnId != null
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp).testTag("caption-words"),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (start > 0) Text("…", style = UserStyle, color = color)
        for (i in start until words.size) {
            val w = words[i]
            if (!w.uncertain) {
                Text(w.text, style = UserStyle, color = color)
                continue
            }
            val tappable = canChoose && w.alternatives.isNotEmpty()
            Text(
                w.text,
                style = UserStyle.copy(textDecoration = TextDecoration.Underline),
                color = color,
                modifier = Modifier
                    .then(
                        if (tappable) Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClickLabel = "Show alternatives") {
                            memory.selectedWord = if (memory.selectedWord?.index == i) null else SelectedWord(i, w.text)
                        } else Modifier,
                    )
                    .semantics { contentDescription = "${w.text}, not sure I heard this right" + if (tappable) ". Tap for alternatives" else "" }
                    .testTag("word-$i"),
            )
        }
    }
}

@Composable
private fun Alternatives(index: Int, word: Word, memory: ScreenMemory, onWord: (Int, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("alternatives"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Did you mean", style = SmallStyle.copy(fontSize = 12.sp), color = PrintMute)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (alt in word.alternatives.take(3)) {
                Pill(alt, filled = true, tag = "alt-$alt") {
                    // Only for the word that is still on screen at that index.
                    if (memory.selectedWord == SelectedWord(index, word.text)) onWord(index, alt)
                    memory.selectedWord = null
                }
            }
            Pill("Keep “${word.text}”", filled = false, tag = "alt-keep") { memory.selectedWord = null }
        }
    }
}

/** A long partial shows its last ~220 characters. */
internal fun tail(text: String, max: Int = 220): String = if (text.length <= max) text else "…" + text.takeLast(max).substringAfter(' ')
