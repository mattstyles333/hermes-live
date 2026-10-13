package com.folio.hermeslive.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermeslive.ui.BodyStyle
import com.folio.hermeslive.ui.Ember
import com.folio.hermeslive.ui.EndRed
import com.folio.hermeslive.ui.HeadingStyle
import com.folio.hermeslive.ui.MonoStyle
import com.folio.hermeslive.ui.PrintInk
import com.folio.hermeslive.ui.PrintMute
import com.folio.hermeslive.ui.SmallStyle
import com.folio.hermeslive.ui.SurfaceHigh
import com.folio.hermeslive.ui.VoidBlack
import java.util.Locale

private val Unsure = Color(0xFFE0B040)
private val Doubt = Color(0xFFE06050)
private val DiffBg = Color(0x553C6CB4)

@Composable
fun LabScreen(lab: LabController, onStart: () -> Unit, onEcho: (Boolean) -> Unit, onShare: () -> Unit) {
    val job by lab.job.collectAsState()
    val talkNow by lab.talkNow.collectAsState()
    Box(Modifier.fillMaxSize().background(VoidBlack).statusBarsPadding()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("STT Lab", style = HeadingStyle.copy(fontSize = 24.sp), color = PrintInk, modifier = Modifier.padding(top = 10.dp))
                Text(
                    "Debug build only. Audio stays in memory on this phone; the only network use is model downloads.",
                    style = SmallStyle,
                    color = PrintMute,
                )
                job?.let { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = BodyStyle, color = Ember, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = lab::cancel) { Text("Cancel") }
                } }
            }
            item { ModelsSection(lab) }
            item { SettingsSection(lab) }
            item { LiveSection(lab, onStart) }
            item { HotwordsSection(lab) }
            item { EchoSection(lab, onEcho) }
            item { BenchSection(lab) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 40.dp)) {
                    Button(onClick = onShare) { Text("Share report (numbers only)") }
                    OutlinedButton(onClick = lab::clearResults) { Text("Clear results") }
                }
            }
        }
        if (talkNow) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Text("TALK NOW", style = HeadingStyle.copy(fontSize = 56.sp), color = Ember)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceHigh).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = HeadingStyle, color = PrintInk)
        content()
    }
}

@Composable
private fun ModelsSection(lab: LabController) {
    val status by lab.store.status.collectAsState()
    Section("Models (download on tap)") {
        ModelCatalog.all.forEach { m ->
            val st = status[m.id] ?: ModelStore.Status.Missing
            Column {
                Text(m.title, style = BodyStyle, color = PrintInk)
                Text("${mb(m.bytes)} download · ${m.license}", style = SmallStyle, color = PrintMute)
                when (st) {
                    is ModelStore.Status.Downloading -> {
                        LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Downloading ${mb(st.done)} of ${mb(st.total)}", style = SmallStyle, color = PrintMute, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { lab.cancelDownload(m) }) { Text("Cancel") }
                        }
                    }
                    ModelStore.Status.Queued -> Text("Queued (two downloads at a time)", style = SmallStyle, color = PrintMute)
                    ModelStore.Status.Verifying -> Text("Checking sha256…", style = SmallStyle, color = PrintMute)
                    is ModelStore.Status.Extracting -> {
                        LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("Unpacking (sha256 OK)", style = SmallStyle, color = PrintMute)
                    }
                    is ModelStore.Status.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ready · sha256 OK · ${mb(st.bytesOnDisk)} on disk", style = SmallStyle, color = Ember, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { lab.delete(m) }) { Text("Delete") }
                    }
                    is ModelStore.Status.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Failed: ${st.reason}", style = SmallStyle, color = Doubt, modifier = Modifier.weight(1f))
                        Button(onClick = { lab.download(m) }) { Text("Retry") }
                    }
                    ModelStore.Status.Missing -> Button(onClick = { lab.download(m) }) { Text("Download") }
                }
            }
        }
    }
}

@Composable
private fun <T> Choices(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Text(label, style = SmallStyle, color = PrintMute)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(name(o)) }) }
    }
}

@Composable
private fun SettingsSection(lab: LabController) {
    val s by lab.settings.collectAsState()
    Section("Settings") {
        Choices("Capture source", CaptureSource.entries, s.source, { it.label }) { v -> lab.update { it.copy(source = v) } }
        Choices("Streaming model", ModelCatalog.streaming, s.streaming, { it.id }) { v -> lab.update { it.copy(streamingId = v.id) } }
        Choices("Second pass", listOf(LabSettings.NONE) + ModelCatalog.offline.map { it.id }, s.offlineId, { it }) { v ->
            lab.update { it.copy(offlineId = v) }
        }
        Choices("Threads", LabSettings.THREADS, s.threads, { "$it" }) { v -> lab.update { it.copy(threads = v) } }
        Choices("Decoding", listOf(Hotwords.BEAM, Hotwords.GREEDY), s.decoding, { it }) { v -> lab.update { it.copy(decoding = v) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Call mode (in-call audio, loudspeaker)", style = BodyStyle, color = PrintInk, modifier = Modifier.weight(1f))
            Switch(checked = s.callMode, onCheckedChange = { v -> lab.update { it.copy(callMode = v) } })
        }
        if (s.streaming.kind == ModelKind.NEMOTRON_STREAMING) {
            Text("Nemotron always decodes greedy (v1.13.8). Licence: NVIDIA Open Model License.", style = SmallStyle, color = PrintMute)
        }
    }
}

@Composable
private fun LiveSection(lab: LabController, onStart: () -> Unit) {
    val live by lab.live.collectAsState()
    val loads by lab.loadMs.collectAsState()
    Section("Live captions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live.running) OutlinedButton(onClick = lab::stopCaptions) { Text("Stop") } else Button(onClick = onStart) { Text("Start") }
            Box(Modifier.size(14.dp).background(if (live.speech) Ember else PrintMute.copy(alpha = 0.3f), CircleShape))
            Text(if (live.speech) "speech" else "quiet", style = SmallStyle, color = PrintMute)
        }
        Text(live.status, style = SmallStyle, color = PrintMute)
        val aec = live.aec
        Text(
            "Source ${live.source?.name ?: "-"} · AEC available ${aec?.available ?: "-"} · effect created ${aec?.created ?: "-"} · " +
                "on by default ${aec?.enabledByDefault ?: "-"} · enabled ${aec?.enabled ?: "-"}",
            style = SmallStyle,
            color = PrintInk,
        )
        val fraction = ((live.dbfs + 90.0) / 90.0).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
        Text(
            "Input ${fmt(live.dbfs, 1)} dBFS · stream RTF ${live.streamRtf?.let { fmt(it, 3) } ?: "-"} · slow chunks ${live.slowChunks} · read errors ${live.readErrors}",
            style = MonoStyle,
            color = PrintMute,
        )
        if (loads.isNotEmpty()) Text("Load: " + loads.entries.joinToString { "${it.key} ${fmt(it.value, 0)} ms" }, style = MonoStyle, color = PrintMute)
        Text("Colour = streaming token confidence: plain ≥ 0.9, amber 0.6–0.9, red < 0.6. Hotwords on stream: ${live.hotwordsActive}", style = SmallStyle, color = PrintMute)
        Text(confidenceText(live.partial, null), style = BodyStyle.copy(fontSize = 18.sp))
        live.utterances.forEach { u -> UtteranceCard(u) }
    }
}

@Composable
private fun UtteranceCard(u: Utterance) {
    Column(Modifier.fillMaxWidth().background(VoidBlack).padding(8.dp)) {
        Text("#${u.id} · ${fmt(u.audioS, 1)} s · hotwords ${u.hotwords}", style = SmallStyle, color = PrintMute)
        Text("Streaming", style = SmallStyle, color = PrintMute)
        Text(confidenceText(u.streaming, u.diff?.left), style = BodyStyle)
        Text("Second pass", style = SmallStyle, color = PrintMute)
        when {
            u.secondText == null -> Text("…", style = BodyStyle, color = PrintMute)
            else -> Text(diffText(u.diff?.right.orEmpty()), style = BodyStyle)
        }
        u.secondMs?.let { Text("Second pass ${fmt(it, 0)} ms · RTF ${fmt(u.secondRtf ?: 0.0, 3)}", style = MonoStyle, color = Ember) }
    }
}

private fun confidenceText(words: List<TokenConfidence.WordConfidence>, diff: List<WordDiff.Word>?): AnnotatedString = buildAnnotatedString {
    val aligned = diff != null && diff.size == words.size
    words.forEachIndexed { i, w ->
        val c = w.confidence
        val color = when {
            c == null -> PrintInk
            c >= 0.9f -> PrintInk
            c >= 0.6f -> Unsure
            else -> Doubt
        }
        val differs = aligned && diff!![i].differs
        withStyle(SpanStyle(color = color, background = if (differs) DiffBg else Color.Transparent, textDecoration = if (differs) TextDecoration.Underline else null)) {
            append(w.word)
        }
        append(' ')
    }
}

private fun diffText(words: List<WordDiff.Word>): AnnotatedString = buildAnnotatedString {
    words.forEach { w ->
        withStyle(SpanStyle(color = PrintInk, background = if (w.differs) DiffBg else Color.Transparent, textDecoration = if (w.differs) TextDecoration.Underline else null)) {
            append(w.text)
        }
        append(' ')
    }
}

@Composable
private fun HotwordsSection(lab: LabController) {
    val h by lab.hotwords.collectAsState()
    val s by lab.settings.collectAsState()
    var text by remember { mutableStateOf(s.hotwordsText) }
    Section("Hotwords") {
        Text("One phrase per line, optional score last, e.g. \"Hermes Live :2.5\". Used from the next utterance.", style = SmallStyle, color = PrintMute)
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { lab.applyHotwords(text) }) { Text("Apply") }
            OutlinedButton(onClick = lab::runHotwordSelfTest) { Text("Self-test (built-in probe)") }
        }
        Text("${h.phrases} phrase(s)", style = SmallStyle, color = PrintMute)
        h.errors.forEach { Text(it, style = SmallStyle, color = Doubt) }
        Text("Streaming: ${h.streaming}", style = SmallStyle, color = if (h.streamingSupported) Ember else PrintMute)
        Text("Second pass: ${h.offline}", style = SmallStyle, color = if (h.offlineSupported) Ember else PrintMute)
        Text(
            "Self-test decodes a bundled sample twice, without and with a probe phrase (Zipformer: " +
                "\"${ModelCatalog.zipformer.probeHotword}\"; Parakeet: \"${ModelCatalog.parakeet110m.probeHotword}\"). Needs ${Hotwords.BEAM}.",
            style = SmallStyle,
            color = PrintMute,
        )
        h.selfTestStreaming?.let { SelfTest("Streaming", it) }
        h.selfTestOffline?.let { SelfTest("Second pass", it) }
    }
}

@Composable
private fun SelfTest(name: String, result: Pair<String, String>) {
    Text("$name self-test: output ${if (result.first != result.second) "changed" else "unchanged"}", style = SmallStyle, color = Ember)
    Text("without: ${result.first}", style = MonoStyle, color = PrintMute)
    Text("with:    ${result.second}", style = MonoStyle, color = PrintInk)
}

@Composable
private fun EchoSection(lab: LabController, onEcho: (Boolean) -> Unit) {
    val trials by lab.echoTrials.collectAsState()
    Section("Echo and cut-in") {
        Text(
            "Plays a 20 s passage on the loudspeaker (on-device TTS) while listening. First stay quiet. Then talk over it as soon as TALK NOW shows. Volume keys set the call volume.",
            style = SmallStyle,
            color = PrintMute,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEcho(false) }) { Text("This source") }
            OutlinedButton(onClick = { onEcho(true) }) { Text("All 3 sources") }
        }
        trials.forEach { t ->
            Text(
                "${t.source}: AEC ${t.aecEnabled} · echo words ${t.echoWords} (${t.echoWordsInPassage} from passage) · " +
                    "VAD false ${pct(t.vadFalseSpeechRatio)} · cut-in ${t.cutInMs?.let { "${fmt(it, 0)} ms" } ?: "-"} · " +
                    "prompt→VAD ${t.promptToVadMs?.let { "${fmt(it, 0)} ms" } ?: "-"} · stop ${t.stopPlaybackMs?.let { "${fmt(it, 1)} ms" } ?: "-"} · " +
                    "busy at prompt ${t.vadBusyAtPrompt} · ${t.playbackPath}" +
                    if (t.ttsOfflineVoice) "" else " · WARNING: no on-device TTS voice found, the engine may have used the network",
                style = MonoStyle,
                color = PrintInk,
            )
        }
    }
}

@Composable
private fun BenchSection(lab: LabController) {
    val rows by lab.bench.collectAsState()
    Section("Benchmarks") {
        Text("Every downloaded model at 1, 2 and 4 threads on its bundled LibriSpeech WAVs. Stops captions.", style = SmallStyle, color = PrintMute)
        Button(onClick = lab::runBench) { Text("Run benchmarks") }
        rows.forEach { r ->
            Text(
                "${r.model} ${r.pass} t${r.threads} ${r.decodingMethod}: load ${fmt(r.loadMs, 0)} ms · RTF ${fmt(r.rtf, 3)}" +
                    (r.wer?.let { " · WER ${pct(it)}" } ?: ""),
                style = MonoStyle,
                color = PrintInk,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
