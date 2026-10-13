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

## Debug hooks for the end-to-end tests (S-F2b)

Debug builds only (`src/debug/.../hooks/BuildHooks.kt`); release and staging have a fixed
`BuildHooks` with no overrides. The instrumentation runs in the app's process, so a test sets
them directly and calls `BuildHooks.reset()` in its teardown. Nothing is committed: no key, no host.

- **Fake Hermes.** Run it on the host, then
  `BuildHooks.hosts = mapOf(BuildHooks.FAKE_HERMES_HOST to "10.0.2.2")` and sign in with
  `context.graph.account.signIn("http://fake-hermes.ts.net:<port>", "<user>", "<password>")`.
  The `*.ts.net` name passes `HermesEndpoint` and the cleartext rule, and the app's OkHttp DNS
  maps it to the emulator's host. Sign out (`graph.account.signOut()`) at the end.
- **OpenRouter key at test time.** Pass it as an instrumentation argument (never in a file the
  repo sees): `am instrument -e openrouterKey "$OPENROUTER_API_KEY" …`, read it with
  `InstrumentationRegistry.getArguments().getString("openrouterKey")`, then
  `VoiceShell.of(context).keys.save(key, KeyCheck.Valid)` and
  `context.graph.settings.update { it.copy(voiceEngine = VoiceEngine.Cascade) }`; at the end
  `keys.remove()` and the engine back to `GptLive`. The key is sealed in the app's Keystore
  prefs on the emulator only.
- **Corpus in, capture out.** Before the call starts:
  ```kotlin
  val corpus = CorpusAudioSource(pace = 1.0)   // src/hooks: debug and staging only
  val out = CapturingAudioOut()                // real-time, a PlaybackMonitor, records each clip
  BuildHooks.cascadeAudio = CascadeAudioFactory { _, onLevel ->
      CascadeAudioIo(source = corpus, out = out, playback = out)
  }
  corpus.enqueue(File(corpusDir, "e01-bill.wav"))
  ```
  The cascade engine asks `BuildHooks.cascadeAudio` once per call (else the mic and an
  `AudioTrack`). `out.played` has each clip's start, end, length, `playedMs` and `stopped`
  (a cut-in) on `System.nanoTime()` ms, the actor's clock. Pass `onLevel` to your out
  (`CapturingAudioOut(onLevel)`) if a test checks the orb level.
- **Start a call** from the visible `LiveActivity` (the microphone service's while-in-use rule
  applies to the corpus source too): launch it with `ActivityScenario`, grant `RECORD_AUDIO`
  with `uiAutomation.grantRuntimePermission`, then `graph.calls.start(CallRequest(entry = "test"))`
  on the main thread, or send `LiveIntents.ACTION_CALL`. `graph.calls.cascade` is the
  `CascadeCallEngine` once a cascade call ran (`ui`, `events`, `core`).
