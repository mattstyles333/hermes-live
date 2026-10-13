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
