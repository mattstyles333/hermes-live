# Emulator tests (S-C audio and speech-to-text)

Instrumented tests for `audio/` and `stt/`, run on the headless Android 15 x86_64 emulator
(`~/hub-tools/android-emulator/run.sh`, `emulator-5554`). The corpus and the model archives are
never committed: push them by hand into the app's external files dir.

```bash
ADB="$ANDROID_HOME/platform-tools/adb -s emulator-5554"
D=/sdcard/Android/data/com.folio.hermeslive/files
./gradlew :live:installDebug :live:installDebugAndroidTest
$ADB shell mkdir -p $D/stt-corpus $D/stt-archives
$ADB push <corpus>/. $D/stt-corpus/            # *.wav + manifest.jsonl (+ optional hotwords.txt, one name per line)
$ADB push <archives>/*.tar.bz2 $D/stt-archives/ # the pinned k2-fsa archives (SttCatalog)
R=com.folio.hermeslive.test/androidx.test.runner.AndroidJUnitRunner
# Install the models through ModelManager.installFrom (sha256 + unpack), then delete the archives:
$ADB shell am instrument -w -e installModels true -e class com.folio.hermeslive.stt.ModelInstallDeviceTest $R
# Everyday suite (model-dependent tests are skipped when models or corpus are missing):
./gradlew :live:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
# The corpus bench (about 20-30 minutes), then pull the aggregates:
$ADB shell am instrument -w -e bench true -e class com.folio.hermeslive.stt.CorpusBenchTest $R
$ADB shell am instrument -w -e bench true -e class com.folio.hermeslive.stt.SpeechPipelineDeviceTest#echoGateOnTheRealPipeline $R
$ADB pull $D/stt-bench/ /tmp/stt-bench/
# Subsets: -e combos zipformer+p110m,latency,latency06b,dictation   Real download + resume (network): -e download true
$ADB shell am instrument -w -e download true -e class com.folio.hermeslive.stt.ModelDownloadDeviceTest $R
```

Keep `leaveApksInstalledAfterRun=true`: uninstalling wipes the installed models (about 1.3 GB).

## Bill smoke call and the debug hooks (S-F2a)

One command runs the one Bill smoke call (`smoke/BillSmokeTest`) against the fake Hermes:

```bash
OPENROUTER_API_KEY=… tools/cascade-smoke.sh [out-dir]   # the real OpenRouter (Jev, writer, Bill's voice)
DRY_RUN=1 tools/cascade-smoke.sh [out-dir]              # no key, no OpenRouter request: plumbing only
```

It starts `tools/fake-hermes/run.sh` if nothing answers on :18119 (and resets only a fake it
started), builds, `adb install -r` (the models stay), runs the one class with `am instrument`,
records 45 s of screen, and writes `bill.json` (timings, no transcripts), the fake's prompt count
and double-submit check, and a mid-task screenshot to out-dir (default
`~/reports/assets/hermes-cascade-smoke`). The key goes in as `-e openrouterKey` and is never written.

What `BillSmokeTest` does, i.e. the hooks an emulator test uses (debug builds only;
`src/debug/.../hooks/BuildHooks.kt`; release has none; call `BuildHooks.reset()` at the end):

1. **Fake Hermes:** `graph.account.signIn("http://10.0.2.2:18119", "fake", "fake-password")`.
   Debug and staging alias `10.0.2.2` to `fake-hermes.ts.net` (passes `HermesEndpoint` and the
   cleartext rule) and `BuildHooks.dns` maps it back. Sign out at the end.
2. **Key and engine:** `VoiceShell.of(context).keys.save(key, KeyCheck.Valid)`, then
   `graph.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade, startOnOpen = false) }`.
   Don't call `shell.onSettingsChanged()` while the gate is open: it would auto-prepare (about
   285 TTS requests). At the end: `keys.remove()`, engine back to `GptLive`.
3. **Corpus in, capture out:**
   `BuildHooks.cascadeAudio = CascadeAudioFactory { _, _ -> CascadeAudioIo(corpus, out, out) }`
   with `corpus = CorpusAudioSource(pace = 2.0)` and `out = CapturingAudioOut()` (both
   `src/hooks`: debug and staging only). Give the pipeline 2.5 s of quiet before the first clip
   (the noise floor learns over 2 s), then `corpus.enqueue(pcm, "request")`.
   `out.played` has each clip's start, end, length, `playedMs` and `stopped` on
   `System.nanoTime()` ms (the actor's clock).
4. **Start the call** the app's way: `ActivityScenario.launch(LiveActivity::class.java)`, grant
   `RECORD_AUDIO`, then `scenario.onActivity { CallService.start(it, CallRequest(entry = "smoke")) }`
   (the microphone service's while-in-use rule). Wait for `graph.calls.state.value.phase == Live`.
5. **Watch Bill:** `(graph.calls.cascadeEngine as CascadeCallEngine).session!!.core.actor` has
   `ui` (`CascadeUiState`) and `events` (`CascadeEvent`: `TurnCommitted`, `TurnHeld`,
   `TaskAccepted`, `TaskFinished`, `ResultSpoken`…). Follow the task the committed turn started
   (`TaskRecord.turnId`), never `tasks.first()`.
6. **Fresh Bill:** the test deletes Bill's durable store, decision log and rotations first (the
   TTS cache and the speech models stay). Otherwise what an earlier run left is sent or
   recovered in this call: the dry run's offline memo became a task that stopped at the fake's
   approval step, and the first real smoke timed out waiting for "running" on that card.
7. **Spoken input:** the request is spoken by OpenRouter TTS (24 kHz) and resampled to 16 kHz with
   a band-limited filter. Picking samples without one aliased the consonants: "stock" was heard
   as "sto", Jev waited for more (`turn` 0.48) and the turn was parked.
   End with `graph.calls.end()` on the main thread.

`DRY_RUN` adds `BuildHooks.hosts = mapOf("openrouter.ai" to "127.0.0.1")` and a placeholder key,
so Jev can't be reached and the turn is held for the network; the fake must get no prompt.

Other emulator classes (run one at a time with `am instrument -e class …`, never the whole
`connectedDebugAndroidTest`, which runs S-C's model suite):
- `service.OffCallBackgroundStartTest` with `-e offcall true` (instrumented; the process may get
  the shell's exemptions). The probe without instrumentation:
  `am start -n com.folio.hermeslive/.probe.ProbeActivity --ez probe_hold true`, Home, screen off,
  wait 15 s, `am broadcast -n com.folio.hermeslive/.probe.ProbeReceiver -a com.folio.hermeslive.probe.OFFCALL`,
  then logcat `OFFCALL_PROBE`.
- Staging (R8): `adb shell am start -n com.folio.hermeslive.staging/com.folio.hermeslive.smoke.StagingSmokeActivity --es wav e01-sarah.wav`
  after copying the two standard model folders (with `.ready`) into
  `/sdcard/Android/data/com.folio.hermeslive.staging/files/stt-models/` and one WAV plus
  `manifest.jsonl` into `…/stt-corpus/` (`chmod -R o+rX` them: shell-made files are otherwise
  unreadable to a non-instrumented app). Logcat `STAGING_SMOKE`.
