# STT Lab (debug builds only)

Spike S-C0 of the cascade voice spec (v2, 2026-10-13). It measures on-device speech-to-text on the phone before the production audio and STT slice is designed. Everything lives in `live/src/debug`, so release builds don't contain it. sherpa-onnx and commons-compress are `debugImplementation` only.

## Open it

- Install the debug APK, then open the **STT Lab** launcher icon (a second icon next to Hermes Live).
- Or: `adb shell am start -n com.folio.hermeslive/.lab.SttLabActivity`
- Debug and release share the package name but not the signing key. Uninstall a release Hermes Live first, and reinstall it from the Folio Release afterwards. You will need to sign in again.
- Folio can stay installed. The debug manifest drops the `com.folio.permission.HERMES` definition, because a differently signed redefinition would be refused. Folio can't read a debug build's connection anyway.

## What it does

- **Models.** Nothing downloads until you tap it. Each model comes from the k2-fsa `asr-models` GitHub release and is checked against the sha256 pinned in `ModelCatalog.kt`. Only the needed files are unpacked into `noBackupFilesDir/stt-lab`. `bpe.vocab` for hotwords is made on the phone:
  - Zipformer: from `bpe.model` (sentencepiece protobuf);
  - Parakeet: from `tokens.txt` at equal scores, as k2-fsa's own example does.
- **Live captions.** The mic feeds Silero VAD and the streaming model at 16 kHz.
  - Choose the capture source.
  - The screen shows AEC availability and whether it is on.
  - Each word is coloured by its token `ysProbs`.
  - The input level is shown in dBFS.
  - At each endpoint the offline model re-reads the utterance (held in memory only), and the words that differ are highlighted.
- **Hotwords.** One phrase per line, optional `:score`. They are passed via `createStream(hotwords)` from the next utterance, and only where sherpa-onnx v1.13.8 can't crash (`Hotwords.support`):
  - Zipformer and Parakeet with `modified_beam_search` and a vocab: yes.
  - Nemotron: never, because the process would exit.
  - "Self-test" decodes a bundled sample with and without a probe phrase that is known to change the output.
- **Echo and cut-in.** On-device TextToSpeech is synthesised into memory and played on the loudspeaker through an `AudioTrack` with `USAGE_VOICE_COMMUNICATION`, in call mode. For each source the lab:
  - plays the passage while you stay quiet, and counts the words it hears and the VAD's false speech;
  - plays it again; when TALK NOW shows, you talk over it. Playback stops when the VAD fires, and the lab records the time from your speech onset (from the input level) to the VAD firing.
- **Benchmarks.** For every downloaded model at 1, 2 and 4 threads: the load time, and the real-time factor on the LibriSpeech WAVs bundled in the archives. The word error rate is shown where the archive has transcripts.
- **Report.** Numbers, booleans, model ids and settings only, sent through the share sheet. It never includes transcripts, hotword text or audio (`LabReportTest`).

## Rules it keeps

- Audio never touches disk and never leaves the phone.
- The model downloads are its only network calls.
- No keys.
- No telemetry events.
