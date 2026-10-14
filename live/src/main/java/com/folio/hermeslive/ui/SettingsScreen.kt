package com.folio.hermeslive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import android.content.ClipboardManager
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.folio.hermes.live.VoiceLiveStatus
import com.folio.hermes.openrouter.CloudSttModel
import com.folio.hermes.openrouter.CloudSttModels
import com.folio.hermeslive.data.EngineState
import com.folio.hermeslive.data.EngineStatus
import com.folio.hermeslive.data.KeyCheck
import com.folio.hermeslive.data.ModelState
import com.folio.hermeslive.data.PrepPhase
import com.folio.hermeslive.data.SpeechModelChoice
import com.folio.hermeslive.data.SpeechRecognition
import com.folio.hermeslive.stt.SttModel
import com.folio.hermeslive.data.VoiceEngine
import com.folio.hermeslive.data.VoiceSettingsController
import com.folio.hermeslive.data.VoiceShell
import com.folio.hermeslive.data.VoiceUi
import com.folio.hermeslive.service.MemoEntry
import kotlinx.coroutines.launch
import com.folio.hermes.telemetry.DiagnosticsEndpoint
import com.folio.hermeslive.call.FallbackRoute
import com.folio.hermeslive.data.AccountState
import com.folio.hermeslive.data.LiveSettings

/** What the settings screen can do. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onChange: ((LiveSettings) -> LiveSettings) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onResetConversation: () -> Unit = {},
    val onRefreshVoice: () -> Unit = {},
    /** Queue a test event and send it now; the result comes back as `diagnosticsStatus`. */
    val onSendTestEvent: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    settings: LiveSettings,
    account: AccountState,
    voice: VoiceLiveStatus?,
    version: String,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    /** Built-in diagnostics endpoint present (release builds); false in local builds. */
    diagnosticsBuiltIn: Boolean = false,
    /** Result of the last "Send test event": "✓ Sent (HTTP 200)", "Failed: HTTP 503", "Off"… */
    diagnosticsStatus: String? = null,
    /** Settings › Voice (the cascade). The app's shell by default; null hides the section. */
    voiceSettings: VoiceSettingsController? = rememberVoiceSettings(),
) {
    Column(modifier.fillMaxSize().background(VoidBlack).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onBack)
                    .semantics { contentDescription = "Back" }.testTag("back"),
                contentAlignment = Alignment.Center,
            ) { Glyph(GlyphKind.Back, PrintInk, size = 22.dp) }
            Text("Settings", style = TitleStyle, color = PrintInk, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Section("Account") {
                when (account) {
                    is AccountState.SignedIn -> {
                        Line("Server", account.server)
                        Line("Signed in as", account.username)
                    }
                    is AccountState.NeedsSignIn -> {
                        Line("Server", account.server)
                        Line("Session", account.reason)
                    }
                    AccountState.SignedOut -> Line("Not signed in", "Hermes Live connects to nothing until you sign in.")
                }
                if (account !is AccountState.SignedOut) {
                    Line("Voice", voiceText(voice), Modifier.clickable(onClick = actions.onRefreshVoice).testTag("voice-status"))
                    Spacer(Modifier.height(8.dp))
                    Pill("Sign out", filled = false, tag = "signout", onClick = actions.onSignOut)
                    Text("Removes the session, the saved password and the conversation from this phone.", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp))
                }
            }
            voiceSettings?.let { VoiceSettingsWired(it) }
            Section("Calls") {
                Toggle("Start talking when opened", "Opening Hermes Live starts a call.", settings.startOnOpen, "start-on-open") { v -> actions.onChange { it.copy(startOnOpen = v) } }
                Toggle("Keep the screen on", "During a call.", settings.keepScreenOn, "keep-screen-on") { v -> actions.onChange { it.copy(keepScreenOn = v) } }
                Choice(
                    "Hang up when quiet", if (settings.idleTimeoutMinutes == 0) "Never" else "After ${settings.idleTimeoutMinutes} min with nobody talking",
                    "idle",
                ) { actions.onChange { it.copy(idleTimeoutMinutes = next(LiveSettings.IDLE_CHOICES, it.idleTimeoutMinutes)) } }
                Choice(
                    "Call limit", if (settings.callCapMinutes == 0) "None" else "${settings.callCapMinutes} min (about ${money(settings.callCapMinutes * 0.05)})",
                    "cap",
                ) { actions.onChange { it.copy(callCapMinutes = next(LiveSettings.CAP_CHOICES, it.callCapMinutes)) } }
            }
            Section("Sound") {
                Toggle("Music in the gaps", "Spotify and other players pause while you or Hermes talk, and play while Hermes works.", settings.musicInGaps, "music") { v -> actions.onChange { it.copy(musicInGaps = v) } }
                Toggle("Duck on the speaker", "Without a headset, keep music quiet in the gaps instead of full volume, so less of it reaches the mic.", settings.duckOnSpeaker, "duck", enabled = settings.musicInGaps) { v -> actions.onChange { it.copy(duckOnSpeaker = v) } }
                Choice("Without a headset", if (settings.fallbackRoute == FallbackRoute.Speaker) "Speaker" else "Phone (earpiece)", "fallback") {
                    actions.onChange { it.copy(fallbackRoute = if (it.fallbackRoute == FallbackRoute.Speaker) FallbackRoute.Earpiece else FallbackRoute.Speaker) }
                }
                Toggle("Call sounds", "Soft tones when the call connects, mutes and ends.", settings.earcons, "earcons") { v -> actions.onChange { it.copy(earcons = v) } }
                Toggle("Haptics", "A light tap with call sounds and approvals.", settings.haptics, "haptics") { v -> actions.onChange { it.copy(haptics = v) } }
            }
            Section("Approvals") {
                Toggle(
                    "Spoken approvals",
                    "Off: Hermes waits for a tap on Approve or Deny. On: saying just \"approve\" or \"deny\" also answers.",
                    settings.spokenApprovals, "spoken-approvals",
                ) { v -> actions.onChange { it.copy(spokenApprovals = v) } }
            }
            Section("Conversation") {
                Text("Calls within 30 minutes carry on the same Hermes conversation.", style = SmallStyle, color = PrintMute)
                Spacer(Modifier.height(8.dp))
                Pill("Start a new conversation", filled = false, tag = "reset-conversation", onClick = actions.onResetConversation)
            }
            Section("Diagnostics") {
                Toggle(
                    "Send crash reports and errors",
                    DIAGNOSTICS_PRIVACY + if (!diagnosticsBuiltIn && settings.diagnosticsUrl.isBlank()) " This build has no endpoint: add one below." else "",
                    settings.diagnostics, "diagnostics",
                ) { v -> actions.onChange { it.copy(diagnostics = v) } }
                DiagnosticsUrlField(settings, enabled = settings.diagnostics, onChange = actions.onChange)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("Send test event", filled = false, tag = "diagnostics-test", onClick = actions.onSendTestEvent)
                    diagnosticsStatus?.let {
                        Text(it, style = SmallStyle, color = PrintMute, modifier = Modifier.padding(start = 12.dp).testTag("diagnostics-status"))
                    }
                }
            }
            Section("About") {
                Line("Hermes Live", version)
                Text(
                    "Talks only to your own Hermes server. The voice is OpenAI's GPT-Live, reached through Hermes; it costs about \$0.05 a minute. " +
                        "Headset button: press to mute, hold to end. Say \"goodbye\" or \"that's all\" to hang up.",
                    style = SmallStyle, color = PrintMute,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

fun voiceText(voice: VoiceLiveStatus?): String = when {
    voice == null -> "Unknown (tap to check)"
    voice.available -> listOf(voice.model, voice.voice.takeIf { it.isNotBlank() }?.let { "voice $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Available" }
    else -> "Unavailable" + (voice.reason?.let { ": $it" } ?: "")
}

const val DIAGNOSTICS_PRIVACY = "Crash reports and errors only. Never what you say, Hermes's answers, or sign-in details."

/** The optional override: saved only when it is blank or usable; a problem shows under the field. */
@Composable
private fun DiagnosticsUrlField(settings: LiveSettings, enabled: Boolean, onChange: ((LiveSettings) -> LiveSettings) -> Unit) {
    var text by rememberSaveable { mutableStateOf(settings.diagnosticsUrl) }
    val problem = DiagnosticsEndpoint.problem(text)
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            if (DiagnosticsEndpoint.problem(v) == null) onChange { it.copy(diagnosticsUrl = v.trim()) }
        },
        label = { Text("Send crash reports and errors to") },
        placeholder = { Text(LiveSettings.DIAGNOSTICS_HINT, style = BodyStyle, color = PrintMute) },
        singleLine = true, enabled = enabled, colors = fieldColors(), textStyle = BodyStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("diagnostics-url"),
    )
    Text(
        problem ?: "Optional. OTLP/HTTP endpoint, e.g. your SigNoz; replaces the built-in one. Never includes what you say or Hermes's answers.",
        style = SmallStyle, color = if (problem != null) EndRed else PrintMute, modifier = Modifier.padding(top = 6.dp),
    )
}

private fun next(choices: List<Int>, current: Int): Int {
    val i = choices.indexOf(current)
    return choices[(i + 1) % choices.size]
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = HeadingStyle, color = PrintInk, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    HorizontalDivider(color = Hairline)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Line(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = SmallStyle, color = PrintMute)
        Text(value, style = BodyStyle, color = PrintInk)
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = BodyStyle, color = if (enabled) PrintInk else PrintMute)
            Text(detail, style = SmallStyle, color = PrintMute)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VoidBlack, checkedTrackColor = PrintInk,
                uncheckedThumbColor = PrintMute, uncheckedTrackColor = Surface, uncheckedBorderColor = Hairline,
            ),
        )
    }
}

@Composable
private fun Choice(title: String, value: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp).testTag(tag)) {
        Text(title, style = BodyStyle, color = PrintInk)
        Text(value, style = SmallStyle, color = Ember)
    }
}

// ── Settings › Voice (spec v2: engine, key, pause, tasks, voice, vocabulary, decision log, Prepare) ──

/** The app's Voice settings, or null in previews and when the app shell is not there. */
@Composable
fun rememberVoiceSettings(): VoiceSettingsController? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { runCatching { VoiceSettingsController(VoiceShell.of(context), scope) }.getOrNull() }
}

/** What Settings › Voice can do. */
data class VoiceActions(
    val onChange: ((LiveSettings) -> LiveSettings) -> Unit = {},
    val onPasteKey: () -> Unit = {},
    val onCheckKey: () -> Unit = {},
    val onRemoveKey: () -> Unit = {},
    val onPrepare: () -> Unit = {},
    val onCancelPrepare: () -> Unit = {},
    val onShareLog: () -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onForgetCorrection: (String) -> Unit = {},
    val onChooseLive: (String) -> Unit = {},
    val onChooseAccurate: (String) -> Unit = {},
    val onDownloadModel: (String) -> Unit = {},
    val onCancelModel: (String) -> Unit = {},
    val onDeleteModel: (String) -> Unit = {},
    val onDismissModelsCard: () -> Unit = {},
)

/** [VoiceSettingsSection] on the controller: clipboard, share sheet and the memo shortcut. */
@Composable
private fun VoiceSettingsWired(controller: VoiceSettingsController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by controller.ui.collectAsState()
    LaunchedEffect(Unit) { controller.refresh() }
    LaunchedEffect(ui.cascadeActive) { MemoEntry.syncShortcut(context, ui.cascadeActive) }
    VoiceSettingsSection(
        ui,
        VoiceActions(
            onChange = controller::update,
            onPasteKey = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                // A pasted key should not sit on the clipboard once it is sealed.
                controller.pasteKey(text) { runCatching { clipboard?.clearPrimaryClip() } }
            },
            onCheckKey = controller::recheckKey,
            onRemoveKey = controller::removeKey,
            onPrepare = controller::prepare,
            onCancelPrepare = controller::cancelPrepare,
            onShareLog = { scope.launch { controller.shareDecisionLog()?.let { runCatching { context.startActivity(it) } } } },
            onClearLog = controller::clearDecisionLog,
            onForgetCorrection = controller::forgetCorrection,
            onChooseLive = controller::chooseLive,
            onChooseAccurate = controller::chooseAccurate,
            onDownloadModel = controller::downloadModel,
            onCancelModel = controller::cancelModel,
            onDeleteModel = controller::deleteModel,
            onDismissModelsCard = controller::dismissModelsCard,
        ),
    )
}

@Composable
fun VoiceSettingsSection(ui: VoiceUi, actions: VoiceActions) {
    val s = ui.settings
    val cascade = s.voiceEngine == VoiceEngine.Cascade
    Section("Voice") {
        Line("Engine", ui.engine.text, Modifier.testTag("engine-status"))
        Choice(
            "Voice engine",
            if (cascade) "Bill: on-phone speech, Jev and Hermes agents (OpenRouter)" else "GPT-Live, through Hermes (default)",
            "engine",
        ) { actions.onChange { it.copy(voiceEngine = if (it.voiceEngine == VoiceEngine.Cascade) VoiceEngine.GptLive else VoiceEngine.Cascade) } }

        KeyBlock(ui, actions)

        if (cascade) {
            PauseSlider(s.pauseMs) { v -> actions.onChange { it.copy(pauseMs = v) } }
            Choice("Tasks at once", "${s.maxTasks} (more wait their turn)", "max-tasks") {
                actions.onChange { it.copy(maxTasks = if (it.maxTasks >= LiveSettings.MAX_TASKS) LiveSettings.MIN_TASKS else it.maxTasks + 1) }
            }
            TextSetting("Voice", s.billVoice, "bill-voice", valid = { it.isNotBlank() && it.none(Char::isWhitespace) }) { v -> actions.onChange { it.copy(billVoice = v) } }
            TextSetting("Speech model", s.ttsModel, "tts-model", valid = ::isModelId) { v -> actions.onChange { it.copy(ttsModel = v) } }
            Text("Changing the voice or model means preparing Bill's voice again.", style = SmallStyle, color = PrintMute)
            Toggle("Small listening noises", "Bill says \"mm-hm\" while you talk. Off by default: it can talk over you.", s.backchannel, "backchannel") { v ->
                actions.onChange { it.copy(backchannel = v) }
            }
            Toggle("Hold to talk", "Hold the orb or the headset button while you speak, instead of Bill waiting for a pause.", s.holdToTalk, "hold-to-talk") { v ->
                actions.onChange { it.copy(holdToTalk = v) }
            }
            Toggle("Call audio processing", "Use the phone's echo canceller on the mic (recommended on the speaker).", s.echoCancel, "echo-cancel") { v ->
                actions.onChange { it.copy(echoCancel = v) }
            }
            SpeechModelsBlock(ui, actions)
            SpeechRecognitionBlock(ui, actions)
            VocabularyBlock(ui, actions)
            DecisionLogBlock(ui, actions)
            PrepareBlock(ui, actions)
        }
        ui.message?.let { Text(it, style = SmallStyle, color = Ember, modifier = Modifier.padding(top = 6.dp).testTag("voice-message")) }
    }
}

@Composable
private fun KeyBlock(ui: VoiceUi, actions: VoiceActions) {
    var confirmRemove by remember { mutableStateOf(false) }
    val key = ui.key
    val state = when {
        !key.saved -> "Not saved"
        else -> "Saved" + (key.hint?.let { " · ends …$it" } ?: "") + " · " + when (key.check) {
            KeyCheck.Valid -> "checked"
            KeyCheck.Refused -> "refused by OpenRouter"
            KeyCheck.Unchecked -> "not checked yet"
        }
    }
    Line("OpenRouter key", state, Modifier.testTag("key-status"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(if (key.saved) "Replace from clipboard" else "Paste from clipboard", filled = !key.saved, tag = "key-paste") { if (!ui.keyBusy) actions.onPasteKey() }
        if (key.saved) {
            Pill("Check", filled = false, tag = "key-check") { if (!ui.keyBusy) actions.onCheckKey() }
            Pill(if (confirmRemove) "Tap to remove" else "Remove", filled = false, tag = "key-remove") {
                if (confirmRemove) actions.onRemoveKey()
                confirmRemove = !confirmRemove
            }
        }
    }
    ui.keyMessage?.let { Text(it, style = SmallStyle, color = if (key.check == KeyCheck.Refused) EndRed else PrintMute, modifier = Modifier.padding(top = 6.dp).testTag("key-message")) }
    Text(
        "Your own OpenRouter key, sealed on this phone with the Android Keystore. It is only sent to OpenRouter, never logged.",
        style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PauseSlider(pauseMs: Int, onDone: (Int) -> Unit) {
    var value by remember(pauseMs) { mutableFloatStateOf(pauseMs.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Pause before Jev decides", style = BodyStyle, color = PrintInk)
        Text("%.1f s of quiet before Bill treats what you said as finished.".format(value / 1000f), style = SmallStyle, color = Ember)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onDone(((value / 100f).toInt() * 100)) },
            valueRange = LiveSettings.MIN_PAUSE_MS.toFloat()..LiveSettings.MAX_PAUSE_MS.toFloat(),
            steps = (LiveSettings.MAX_PAUSE_MS - LiveSettings.MIN_PAUSE_MS) / 100 - 1,
            colors = SliderDefaults.colors(thumbColor = PrintInk, activeTrackColor = PrintInk, inactiveTrackColor = Hairline),
            modifier = Modifier.testTag("pause"),
        )
    }
}

@Composable
private fun TextSetting(label: String, value: String, tag: String, valid: (String) -> Boolean, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            // Saved only once it is usable, so leaving mid-edit keeps the last good value.
            if (valid(v.trim())) onSave(v.trim())
        },
        isError = !valid(text.trim()),
        label = { Text(label) },
        singleLine = true, colors = fieldColors(), textStyle = BodyStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(tag),
    )
}

/** An OpenRouter model id: `vendor/model`, no spaces. */
fun isModelId(s: String): Boolean = LiveSettings.isModelId(s)

/** Settings › Voice › Speech models: the live and the accurate model, each with its size, download, progress and delete. */
@Composable
private fun SpeechModelsBlock(ui: VoiceUi, actions: VoiceActions) {
    val s = ui.settings
    Text("Speech models", style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 12.dp))
    if (s.speechModelsCard) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(Surface).padding(10.dp).testTag("speech-models-card")) {
            Text("Better speech models available", style = BodyStyle, color = PrintInk)
            Text(
                "Your phone still uses the models it downloaded first. Pick a newer one below; it downloads only when you tap Download.",
                style = SmallStyle, color = PrintMute,
            )
            Row(Modifier.padding(top = 6.dp)) { Pill("Not now", filled = false, tag = "speech-models-card-dismiss", onClick = actions.onDismissModelsCard) }
        }
    }
    Text("Live (while you talk)", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp))
    SpeechModelChoice.LIVE.forEach { m -> ModelRow(ui, m, SpeechModelChoice.live(s) == m, actions.onChooseLive, actions) }
    Text("Accurate (each finished request)", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 6.dp))
    SpeechModelChoice.ACCURATE.forEach { m -> ModelRow(ui, m, SpeechModelChoice.accurate(s) == m, actions.onChooseAccurate, actions) }
}

@Composable
private fun ModelRow(ui: VoiceUi, model: SttModel, chosen: Boolean, choose: (String) -> Unit, actions: VoiceActions) {
    val st = ui.speechModels.firstOrNull { it.id == model.id }
    val state = st?.state ?: ModelState.Missing
    val size = EngineStatus.megabytes(model.bytes)
    val detail = when (state) {
        ModelState.Ready -> "Downloaded"
        ModelState.Missing -> "$size to download"
        ModelState.Downloading -> "${((st?.fraction ?: 0f) * 100).toInt()}% of $size"
        ModelState.Paused -> "Paused at ${((st?.fraction ?: 0f) * 100).toInt()}% of $size"
        ModelState.Verifying -> "Checking"
        ModelState.Failed -> "Download failed"
    }
    Column(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { choose(model.id) }.padding(vertical = 6.dp).testTag("speech-model-${model.id}")) {
        Text((if (chosen) "● " else "○ ") + SpeechModelChoice.label(model), style = BodyStyle, color = if (chosen) PrintInk else PrintMute)
        Text(SpeechModelChoice.note(model) + " · " + detail, style = SmallStyle, color = PrintMute)
        if (state == ModelState.Downloading) {
            LinearProgressIndicator(progress = { st?.fraction ?: 0f }, color = Ember, trackColor = Hairline, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            when (state) {
                ModelState.Downloading, ModelState.Verifying -> Pill("Pause", filled = false, tag = "model-cancel-${model.id}") { actions.onCancelModel(model.id) }
                ModelState.Ready -> if (SpeechModelChoice.deletable(ui.settings, model.id)) Pill("Delete", filled = false, tag = "model-delete-${model.id}") { actions.onDeleteModel(model.id) }
                else -> Pill("Download", filled = chosen, tag = "model-download-${model.id}") { actions.onDownloadModel(model.id) }
            }
        }
    }
}

/** Settings › Voice › Speech recognition: on device (default) or the cloud when online, its model and cost. */
@Composable
private fun SpeechRecognitionBlock(ui: VoiceUi, actions: VoiceActions) {
    val s = ui.settings
    val cloud = s.speechRecognition == SpeechRecognition.Cloud
    Choice("Speech recognition", speechRecognitionText(s.speechRecognition), "speech-recognition") {
        actions.onChange { it.copy(speechRecognition = if (it.speechRecognition == SpeechRecognition.Cloud) SpeechRecognition.OnDevice else SpeechRecognition.Cloud) }
    }
    if (!cloud) return
    val model = CloudSttModels.of(s.cloudSttModel)
    Choice("Cloud model", "${model.label}. ${model.note}", "cloud-stt-model") {
        actions.onChange { it.copy(cloudSttModel = nextCloudModel(it.cloudSttModel)) }
    }
    TextSetting("Cloud model id (advanced)", s.cloudSttModel, "cloud-stt-model-id", valid = ::isModelId) { v -> actions.onChange { it.copy(cloudSttModel = v) } }
    Text(cloudSttCost(model), style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("cloud-stt-cost"))
}

/** What each choice means, in plain words (the user must know when audio leaves the phone). */
fun speechRecognitionText(choice: SpeechRecognition): String = when (choice) {
    SpeechRecognition.OnDevice -> "On device (default): your audio never leaves the phone."
    SpeechRecognition.Cloud -> "Cloud when online: an audio clip of each thing you say is sent to OpenRouter " +
        "for better words, on your OpenRouter key. Offline, or if it is slow, the phone's own pass is used."
}

/** The listed model after [current] (a custom id goes back to the first). */
fun nextCloudModel(current: String): String {
    val all = CloudSttModels.ALL
    val i = all.indexOfFirst { it.id == current }
    return all[(i + 1) % all.size].id
}

/** The estimated cost line for [model]. */
fun cloudSttCost(model: CloudSttModel): String = model.dollarsPerHour?.let { "About $" + "%.2f".format(java.util.Locale.UK, it) + " an hour of speech, billed to your OpenRouter key." }
    ?: "Billed to your OpenRouter key at this model's price on OpenRouter."

@Composable
private fun VocabularyBlock(ui: VoiceUi, actions: VoiceActions) {
    var text by remember { mutableStateOf(ui.settings.vocabulary.joinToString("\n")) }
    Text("Vocabulary", style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 12.dp))
    Text("Names and words Bill should hear right. One per line.", style = SmallStyle, color = PrintMute)
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            actions.onChange { it.copy(vocabulary = LiveSettings.cleanVocabulary(listOf(v))) }
        },
        minLines = 3, maxLines = 8, colors = fieldColors(), textStyle = BodyStyle,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("vocabulary"),
    )
    if (ui.corrections.isNotEmpty()) {
        Text("Learned from your calls", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 8.dp))
        ui.corrections.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "“${c.heard}” → ${c.meant}" + if (c.count > 1) "  ×${c.count}" else "",
                    style = BodyStyle, color = PrintInk, modifier = Modifier.weight(1f),
                )
                Pill("Forget", filled = false, tag = "forget-${c.heard}") { actions.onForgetCorrection(c.heard) }
            }
        }
    }
}

@Composable
private fun DecisionLogBlock(ui: VoiceUi, actions: VoiceActions) {
    Toggle(
        "Decision log",
        "Keeps each turn's decision (what you said, what Jev chose, timings) on this phone only, for tuning. Last 1,000 turns or 14 days.",
        ui.settings.decisionLog, "decision-log",
    ) { v -> actions.onChange { it.copy(decisionLog = v) } }
    ui.decisionTurns?.let { Text(if (it == 1) "1 turn kept" else "$it turns kept", style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("log-count")) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        Pill("Share", filled = false, tag = "log-share", onClick = actions.onShareLog)
        Pill("Clear", filled = false, tag = "log-clear", onClick = actions.onClearLog)
    }
}

@Composable
private fun PrepareBlock(ui: VoiceUi, actions: VoiceActions) {
    val prep = ui.prep
    val p = prep.phrases
    Text("Prepare Bill's voice", style = BodyStyle, color = PrintInk, modifier = Modifier.padding(top = 12.dp))
    Text(
        when (prep.phase) {
            PrepPhase.Idle -> if (prep.ready) "Ready." else "Records Bill's stock lines once and downloads the speech models."
            PrepPhase.Running -> "Preparing…"
            PrepPhase.Done -> "Ready."
            PrepPhase.Cancelled -> "Paused. Resume carries on where it stopped."
            PrepPhase.Failed -> "Not finished: some lines or models failed. Try again."
        },
        style = SmallStyle, color = PrintMute, modifier = Modifier.testTag("prepare-status"),
    )
    if (p.total > 0) {
        Text("Lines: ${p.done} of ${p.total}" + if (p.failed > 0) " (${p.failed} failed)" else "", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 4.dp).testTag("prepare-lines"))
        LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, color = Ember, trackColor = Hairline, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    prep.models.forEach { m ->
        val size = if (m.bytesTotal > 0) " of ${EngineStatus.megabytes(m.bytesTotal)}" else ""
        val state = when (m.state) {
            ModelState.Ready -> "ready"
            ModelState.Missing -> "to download$size"
            ModelState.Downloading -> "${(m.fraction * 100).toInt()}%$size"
            ModelState.Paused -> "paused at ${(m.fraction * 100).toInt()}%$size"
            ModelState.Verifying -> "checking"
            ModelState.Failed -> "failed"
        }
        Text("${m.label}: $state", style = SmallStyle, color = PrintMute, modifier = Modifier.padding(top = 4.dp).testTag("model-${m.id}"))
        if (m.state == ModelState.Downloading) {
            LinearProgressIndicator(progress = { m.fraction }, color = Ember, trackColor = Hairline, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
    if (ui.metered && prep.bytesToFetch > 0) {
        Text(
            "You're on mobile data. About ${EngineStatus.megabytes(prep.bytesToFetch)} to download: Wi-Fi recommended.",
            style = SmallStyle, color = Ember, modifier = Modifier.padding(top = 6.dp).testTag("wifi-hint"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        if (prep.phase == PrepPhase.Running) {
            Pill("Cancel", filled = false, tag = "prepare-cancel", onClick = actions.onCancelPrepare)
        } else if (ui.cascadeActive) {
            val label = when {
                prep.phase == PrepPhase.Cancelled || prep.phase == PrepPhase.Failed -> "Resume"
                prep.ready && ui.engine.state == EngineState.Ready -> "Prepare again"
                else -> "Prepare"
            }
            Pill(label, filled = ui.engine.state == EngineState.NeedsVoice, tag = "prepare", onClick = actions.onPrepare)
        }
    }
}
