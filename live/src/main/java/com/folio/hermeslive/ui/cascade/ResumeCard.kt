package com.folio.hermeslive.ui.cascade

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermes.cascade.ResumeChoice
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.Hairline
import com.folio.hermeslive.ui.LabelStyle
import com.folio.hermeslive.ui.Pill
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.Surface

/** Most past conversations listed under the Resume card. */
internal const val HISTORY_ROWS = 5

/**
 * The Resume card (R-CONV-2): ▶ Play (mic off), Continue, New; the default choice is filled.
 * Below it, the recent conversations.
 */
@Composable
internal fun ResumeCard(resume: State<ResumeModel>, history: List<CascadeHistoryItem>, wall: State<Long>, intents: CascadeIntents) {
    Probe("resume")
    val r = resume.value.resume ?: return
    val nowWallMs = wall.value
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Surface).border(1.dp, Hairline, shape).padding(16.dp).testTag("resume-card"),
    ) {
        Text("PICK UP WHERE YOU LEFT OFF", style = LabelStyle.copy(fontSize = 11.sp), color = Ember, modifier = Modifier.semantics { heading() })
        Text(resumeLine(r, nowWallMs), style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 6.dp).testTag("resume-line"))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.unheardResults > 0) {
                Pill("▶ Play", filled = r.defaultChoice == ResumeChoice.Play, tag = "resume-play") { intents.resume(ResumeChoice.Play) }
            }
            Pill("Continue", filled = r.defaultChoice == ResumeChoice.Continue, tag = "resume-continue") { intents.resume(ResumeChoice.Continue) }
            Pill("New", filled = r.defaultChoice == ResumeChoice.New, tag = "resume-new") { intents.resume(ResumeChoice.New) }
        }
        if (r.unheardResults > 0) {
            Text("Play reads the results with the mic off.", style = SmallStyle.copy(fontSize = 11.sp), color = PrintMute, modifier = Modifier.padding(top = 8.dp))
        }
        if (history.isNotEmpty()) {
            Text("EARLIER", style = LabelStyle.copy(fontSize = 10.sp), color = PrintMute, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp).semantics { heading() })
            for (item in history.take(HISTORY_ROWS)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open conversation") { intents.openConversation(item.conversationId) }
                        .padding(vertical = 7.dp, horizontal = 4.dp)
                        .testTag("history-${item.conversationId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.title, style = SmallStyle, color = PrintInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val tasks = if (item.tasks == 1) "1 task" else "${item.tasks} tasks"
                    Text("$tasks · ${ago(nowWallMs, item.endedWallMs)}", style = SmallStyle.copy(fontSize = 11.sp), color = PrintMute, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
