# Hermes Live — notes for coding agents

Standalone Android 12+ (API 31) voice app for the user's own Hermes server. Package `com.folio.hermeslive`, label Hermes Live, Gradle module `:live`. It works without Folio. Folio ([folio-launcher](https://github.com/mattstyles333/folio-launcher)) reaches it through the connection provider and the CALL intent. The names in that interface (package, authority, actions, extras, Bundle keys, permission) are a contract with Folio: don't rename them.

Voice is OpenAI GPT-Live (`gpt-live-1`) over WebRTC. Hermes's server holds the OpenAI key; the app sends only its SDP offer. Real work is delegated to Hermes as `prompt.submit {surface:"voice-live"}` turns through the `:hermes` library. Don't reimplement it: use `HermesAuth`, `HermesGateway`, `HermesConversation`, `VoiceLiveApi`, `DelegationBridge` and `StopPhrases`.

## Build

```bash
export JAVA_HOME=... ANDROID_HOME=...
./gradlew :live:testDebugUnitTest :live:lintDebug :live:assembleDebug
./gradlew :live:assembleRelease   # needs the Folio release key: keystore.properties or FOLIO_* env
```

- **SDK levels:** minSdk 31, targetSdk 35, compileSdk 37. versionName is independent of Folio's.
- **Release build:** R8 minify and resource shrink are on. The release APK is arm64-v8a only (the S23). Debug also carries x86_64 for the emulator.
- **Keep rules** in `proguard-rules.pro`:
  - `org.webrtc.**`: JNI looks those classes up by name.
  - The kotlinx-serialization `$$serializer` classes.
  - Keep every decode on the compile-time serializer.
- **Where releases come from today:** folio-launcher's release workflow builds `:live` from its `hermes-live/` submodule pin and attaches `hermes-live-X.Y.Z.apk` to the Folio Release. To ship a Hermes Live change: merge here, bump the pin in folio-launcher, bump Folio's version, tag Folio.
- **Release workflow:** `.github/workflows/release.yml` runs on a `vX.Y.Z` tag that must match `:live`'s versionName. It tests, signs `hermes-live-X.Y.Z.apk` with Folio's release key, and publishes the GitHub Release plus a SHA-256. Bump `versionCode` / `versionName` in `live/build.gradle.kts` first. Don't `gh release create` by hand.
- **Signing:** Folio's key, always. Both apps declare the signature permission `com.folio.permission.HERMES`; with different keys the second install fails and Folio can't read the connection. CI secrets: `FOLIO_KEYSTORE_BASE64`, `FOLIO_STORE_PASSWORD`, `FOLIO_KEY_PASSWORD` (same values as folio-launcher's). This repo doesn't have them yet (only the owner can copy them), so don't tag here until it does.
- **WebRTC:** `io.getstream:stream-webrtc-android:1.3.10`, the latest on Maven Central in 2026-10. It is declared inline in `live/build.gradle.kts`, not in the version catalog.

## `:hermes` is shared with Folio

folio-launcher pulls this repo in as a git submodule and builds `:hermes` from it (not `:live`). So:

- `:hermes` must build with Folio's version catalog. Keep its aliases (`okhttp`, `okhttp-mockwebserver`, `kotlinx-coroutines-core`, `kotlinx-coroutines-test`, `kotlinx-serialization-json`, `junit`, plugins `android-library` and `kotlin-serialization`) the same in both repos. A new `:hermes` dependency needs the alias in Folio's catalog too.
- `:hermes` never depends on `:live` or anything Android-UI.
- Changing `:hermes`'s public API breaks Folio when it bumps the submodule. Keep changes additive, or update Folio in the same breath.
- No real hostnames, tokens or usernames anywhere: the repo and its Releases are public.

## Layout

| Area | Where |
|---|---|
| Call lifecycle (pure, JVM-tested) | `call/CallEngine.kt`: connect, live, one reconnect with history, goodbye, `session.close`, summary |
| Music in the gaps (pure) | `call/GapMusicPolicy.kt`; `call/OrderedSender.kt` (300 ms focus lead before a commentary, all writes in order) |
| Speech detection | `call/EnergyVad.kt` (mic VAD, `LevelGate`), `call/RemoteLevel.kt` (inbound-rtp `audioLevel` / energy) |
| Routes | `call/AudioRoutes.kt` (rules), `audio/AndroidCallAudio.kt` (AudioManager: mode, `setCommunicationDevice`, focus, noisy, other calls) |
| Earcons, haptics, proximity | `audio/Earcons.kt` |
| WebRTC | `webrtc/WebRtcTransport.kt` |
| Hermes link | `hermes/RealHermesLink.kt` (gateway + conversation + voice API); `SignedOutLink` fails before any request |
| Account | `data/Account.kt`, `data/SecureTokenStore.kt` (Keystore AES-GCM), `data/SettingsStore.kt` |
| Foreground call | `service/CallService.kt` (type microphone, CallStyle notification, MediaSession for headset buttons), `service/CallActionReceiver.kt`, `service/CallTileService.kt` |
| Folio interface | `provider/ConnectionProvider.kt` |
| Cascade voice (library only, dark; `:live` does not use it yet) | `:hermes` `openrouter/` (`OpenRouter`: Jev System One, writer, TTS) and `cascade/`. Spec: hub `hermes-cascade-spec-v2-2026-10-13.md` (R-ids); v1 contract is background. One state owner: `CascadeActor` (see "Cascade layout"). The phrase bank (v3) ships as a resource; no audio in the repo |
| Diagnostics (crash + error reporting) | `:hermes` `telemetry/` (`Telemetry`, `TelemetryCore`, `Scrub`, `Otlp`, `ExitReports`, `Tombstone`, `DiagnosticsEndpoint`); installed first in `LiveApp.onCreate`; Settings › Diagnostics |
| Entry points | `LiveIntents.kt`, `LiveActivity.kt`, `res/xml/shortcuts.xml` |
| UI | `ui/` (Compose; Folio's palette copied, no dependency on Folio) |

## Cascade layout (spec v2 R-ARC, §12 ownership)

- **One state owner (R-ARC-1).** `CascadeActor` takes `Event`s from one channel, runs the pure `CascadeReducer.reduce(State, Event) -> (State, List<Effect>)`, and runs the effects. Executors (`TaskRunner`, `SpeechPlayer`, timers, Jev and writer calls) post results back as events with correlation ids (timer seq, call seq, task id + run id); `reduce` drops stale ones. Nothing else holds call state, so there are no locks. `reduce` never reads a clock: the actor stamps `State.nowMs`, `wallMs` and `settings` before each event.
- **Pure rules** used by the reducer: `TurnRules` (pause, early Jev, commit, race, keep_listening), `TaskTable` (tasks, cap, queued/redirect, status from the log only), `SpeechQueueState` (priority, gate, cut-in, heard words), `OutboxState`, `Rotations`.
- **Frozen types (R-ARC-4):** `Event`, `State`, `Effect`, `CascadeUiState`, `CascadeEvent`, `Transcript`/`Word`, `CascadeSettings`, the ports. Additive changes only, with a hub change note.
- **Durable first (R-DUR-1):** the actor runs `Effect.Persist`/`Append` before every other effect of the same step.
- **Ports (R-ARC-5), `cascade/ports/`:** `Clock`, `Network`, `Store`, `SpeechToText`, `AudioOut`, `HermesPort` (open with a stored session id), `OpenRouterPort`; `MemoryStore`, `GatewayHermesPort`, `LiveOpenRouter`.
- **Seams (`CascadeEnv`):** phase 1 slices change behaviour in their own package, never in the reducer.

| Package | Owner | What |
|---|---|---|
| `cascade/*.kt` (root) | hub (S0) | actor, reducer, `Tx`, frozen types, pure rules, executors, `CascadeSettings` |
| `cascade/ports/` | hub (S0) | port interfaces and adapters |
| `cascade/routing/` | S-A | `HandlerRegistry` (one `RouteHandler` per route, `TurnHooks`, `ResultHandler`), `Router`, `CascadeBrain`, `JevQuestions` |
| `cascade/writer/`, `cascade/budget/` | S-A | `WriterPrompts`; `JevStateBudget` (pass-through in S0) |
| `cascade/store/`, `cascade/registry/`, `cascade/conversation/` | S-B | `StatePersistence`, `TaskRegistry`, `ConversationPolicy` (C1 defaults in S0) |

## Cascade app shell (S-E; spec v2 R-CONV-3, R-OFF-2, R-Q-1/2)

All in `:live`, dark: nothing below runs unless Settings › Voice › Voice engine is `cascade` **and** a usable OpenRouter key is saved.

| Area | Where |
|---|---|
| Voice settings | `data/LiveSettings.kt` (engine `gpt-live`\|`cascade`, pause 400–3000 ms, tasks 1–6, voice, TTS model, backchannel, hold-to-talk, vocabulary, decision log, echo cancel, high accuracy; `normalized()` clamps; `cascadeSettings()` maps onto S0's `CascadeSettings`), `data/SettingsStore.kt` |
| OpenRouter key | `data/OpenRouterKey.kt`: `OpenRouterKeyStore` (own Keystore alias and prefs file `hermes_live_openrouter`, so the Hermes sign-out wipe leaves it; status without decrypting), `KeyPaste`, `KeyChecker` (`GET /api/v1/key`) |
| Off-state gate | `data/CascadeGate.kt`: `CascadeGate`, `CascadeShell` (builds through `CascadeFactory` only while the gate is open, closes when it shuts) |
| Shell per process | `data/VoiceShell.kt` (`VoiceShell.of(context)`; `CascadeHost` is what `LiveApp` implements to supply the factory, `SpeechModels` and `OffCallHooks`) |
| Storage | `data/FileStore.kt` (the `Store` port: temp-then-rename, fsync, CRC per value and log line, torn lines skipped, corrupt values quarantined), `data/CascadeStorage.kt` (`noBackupFilesDir/cascade/`: `store/`, `tts/`, `rotations.json`, `decisions.jsonl`, `models/`) |
| Prepare Bill's voice | `data/VoicePrep.kt` (phrase render + model downloads, cancel/resume), `data/SpeechModels.kt` (the interface S-C's model manager is adapted to) |
| Vocabulary, decision log | `data/Vocabulary.kt` (learned corrections, hotwords), `data/DecisionLogShare.kt` (Share as one JSON file through `DecisionLogProvider`, not exported, URI grant) |
| Settings › Voice UI | `ui/SettingsScreen.kt` (`VoiceSettingsSection`, wired by `rememberVoiceSettings()` over `data/VoiceSettingsController.kt`) |
| Off-call | `service/OffCall.kt` (hooks: `afterHangUp`, `stop`, `postResult`, `playBriefing`; `OffCallHooks`, `OffCallPolicy`), `service/OffCallService.kt`, `service/ResultNotifications.kt`, `service/BriefingService.kt` |
| Memo entry | `service/MemoEntry.kt` (tile marks an offline start `extra.MEMO`; dynamic "Memo" shortcut `action.MEMO` while the cascade is on) |
| Private telemetry | `data/SafeTelemetry.kt`: names, codes and timings only; text becomes `redacted` |

**Off-call service type (R-CONV-3).** `OffCallService` keeps the process, and through `OffCallHooks.holdGateway()` the Hermes gateway, alive after hang-up while voice tasks finish. It never opens the mic. It is `specialUse` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification:
- not `microphone`: the mic is off, and the privacy indicator would stay on;
- not `dataSync`: Android 15 limits it to 6 hours a day and ends it with a timeout, and it is meant for transfers;
- not `remoteMessaging`: that type is defined for continuing text messages from one device to another, which this isn't;
- `specialUse` has no runtime limit beyond our own (all tasks done, 30 min, Stop, or the cascade switched off). Hermes Live ships outside Play, so the Play declaration does not apply; if it ever ships there, the declaration text is the manifest property.
- **Start rule.** Android 12+ refuses a foreground-service start from the background. Call `OffCall.afterHangUp` while the call is still foreground (before `CallService` drops it). If Android refuses, it returns false, reports `offcall.stop reason=refused`, and only the result notifications remain. Not yet tested on a phone.

**Hear it.** `BriefingService` is `mediaPlayback`, started from the notification action (`PendingIntent.getForegroundService`, which Android allows from a notification tap). It plays through an `AudioTrack` as media speech with a `MediaSession` and a public `MediaStyle` notification titled "Bill · result", so it can be played and stopped from the lock screen without showing the task. The result notification itself is `VISIBILITY_PRIVATE`.

**Tests:** `VoiceSettingsTest`, `OpenRouterKeyTest`, `FileStoreTest` (the same contract as `MemoryStore`, atomicity, corruption), `CascadeOffStateTest` (R-Q-1, with a control), `CascadeTelemetryPrivacyTest` (R-Q-2: sentinel key, transcript, headline and vocabulary through a raw sink), `VoicePrepTest`, `VoiceSettingsControllerTest`, `OffCallServiceTest` (lifecycle, timeout, notification intents, Hear it, declared types have no mic bit), `MemoEntryTest`, `VoiceSettingsScreenTest`.

## Rules that matter

- **Off state.**
  - Nothing connects until the user signs in.
  - When signed out, the link refuses before any request, and the provider answers `signed_in=false` and nothing else.
  - No credentials go in code, tests or CI.
  - The password is sealed with the Keystore and used only for a silent re-login. Sign-out wipes the tokens, the password, the key, the server and the conversation.
- **Approvals are never automatic.**
  - Each one appears as a card and a high-priority notification (Approve once / Deny, plus Session when the server offers it).
  - Spoken approvals are off by default. When on, only a whole utterance such as "approve" or "deny" counts.
  - With spoken approvals off, a spoken "approve" is consumed: the voice is told to ask for a tap, and the request never becomes a new Hermes turn.
  - Clarify questions can be answered by voice. The delegation is turned into the clarify answer.
- **Provider permission.** `ContentProvider.call` is not covered by `android:permission`. `ConnectionProvider.call` checks `com.folio.permission.HERMES` itself (signature level). The permission is declared in both manifests.
- **Starting a call.**
  - A call starts only from the visible activity, after RECORD_AUDIO is granted. This is Android 14's while-in-use rule for a microphone foreground service.
  - The QS tile opens the activity with `startActivityAndCollapse(PendingIntent)` on API 34 and later.
- **Data-channel writes.** Every data-channel write goes through `OrderedSender`. A close never overtakes a held commentary.
- **Threading.** `CallEngine` and `DelegationBridge` are single-threaded (Main). `WebRtcTransport` posts every callback to Main.
- **Cleartext.** Plain http is allowed only for `*.ts.net` (`res/xml/network_security_config.xml`). The config can't express `100.64.0.0/10`. `HermesEndpoint` accepts those IPs, but Android refuses them, so use the MagicDNS name.

## Diagnostics

Crash and error reporting as OTLP/HTTP JSON logs (`POST <endpoint>/v1/logs`), shared with Folio through `:hermes` (`com.folio.hermes.telemetry`). Service `hermes-live`; Folio reports as `folio`.

- **Endpoint.** The default is `BuildConfig.DIAGNOSTICS_URL`, baked into **release** builds from the env var `FOLIO_DIAGNOSTICS_URL` (or Gradle property `folio.diagnosticsUrl`). Release CI passes the repo variable `FOLIO_DIAGNOSTICS_URL`; `ci.yml` does not, and debug builds and unit tests always get `""`. Settings › Diagnostics: a switch (on by default) and an optional override URL (https, or http on `*.ts.net`). Effective endpoint = off if switched off, else the override, else the default (`DiagnosticsEndpoint.effective`). Never write the real URL in code, tests, docs or commits.
- **What is sent.** Crashes (written synchronously to `filesDir/telemetry/pending/` by the uncaught-exception handler, then the previous handler runs), previous exits from `ActivityManager.getHistoricalProcessExitReasons` (crash, native crash with a tombstone summary, ANR with the main-thread dump, low memory, excessive resource use, init failure, dependency died; each once), and `Telemetry.event/warn/error`. Resource: `service.name`, `service.version`, `deployment.environment=phone`, `device.model`, `device.manufacturer`, `os.version`, `app.install_id` (random per install).
- **No content, ever.** Event names plus safe attributes only: codes, enum reasons, durations, counts, booleans. Never transcripts, prompts, answers, typed text, tokens, cookies, passwords, user names or URLs with queries. Vendor and server messages (`BridgeEvent.LiveError.message`, `DelegationFailed.message`, `HermesAuthException` text) are not logged: log their `code` / `kind` / type. `Scrub` redacts and truncates every message, attribute and stack trace as a second line.
- **Events.** `call.start` (entry: launcher/folio/shortcut/assist/headset/tile/share/app), `call.connected` (connect_ms), `call.failed`, `call.reconnect`, `call.reconnected`, `call.stopping`, `call.end` (reason, duration_s, voice_s), `call.other_call`, `live.error` (code), `delegation.failed` (kind, code), `gateway.error`, `auth.signin` (ok + reason), `auth.refresh_failed`, `audio.route_change`, `audio.error`, `audio.focus_denied`, `webrtc.*` (ice_failed, connection_failed, disconnected, lost, datachannel_closed, ice_gather_timeout, ice_candidate_error), `keystore.*_failed`, `provider.call` (allowed/denied, answer kind), `diagnostics.test`. Cascade (only once `:live` builds it): `openrouter.call` (call jev/writer/writer_stream/tts/key, ok, kind, http_status, ms, first_ms/first_byte_ms, bytes), `cascade.turn` (outcome, jev_calls, jev_ms, to_jev_ms), `cascade.action` (route, slot id, writer, resend), `cascade.task` (event, running, how), `cascade.cut_in`, `cascade.outbox`, `cascade.render`, `cascade.tts_skipped`. Stock slot ids are not content; transcripts, lines, prompts and results never go in.
- **Sending.** In-memory queue (≤200) flushed every 30 s and when the app leaves the screen (`onTrimMemory(UI_HIDDEN)`), on one daemon thread, 10 s timeouts, backoff 30 s → 30 min on failure. Pending files: ≤50, dropped after 3 days, deleted only after a 2xx. Telemetry never throws into the app.

## Touch and hardware contracts

- **Launcher icon:** opens the call screen. It starts talking when "Start talking when opened" is on (the default) and the user is signed in.
- **Shortcuts:** Talk, Talk muted, New conversation.
- **QS tile:** tap to talk, tap again to end.
- **Intents:**
  - `ASSIST` and `VOICE_COMMAND` start a call over the lock screen.
  - Share text/plain starts a call with that text as the first request.
  - `com.folio.hermeslive.action.CALL` takes the optional extras `com.folio.hermeslive.extra.PROMPT` (String) and `com.folio.hermeslive.extra.MUTED` (Boolean, or the string "true").
- **Headset button during a call:**
  - A single press of the hook or play/pause button toggles mute.
  - A long press ends the call.
  - Starting a call from the headset uses the system's long-press `VOICE_COMMAND`, when Hermes Live is the chosen voice app.
- **Ending a call:**
  - End button, notification End, tile, headset long press.
  - Stop phrases ("goodbye", "that's all", "never mind"…, the whole utterance only): Hermes says a brief goodbye (`session.instructions.append`), then `session.close` follows once the voice is quiet, within 4 s.
  - Idle hang-up (default 3 min) and the call cap (default 30 min) both give a spoken and visual warning first.
  - A second End press skips the goodbye.
  - The app always waits up to 15 s for `session.closed`, then restores the audio mode and route.
- **Music in the gaps** (on by default):
  - Other players pause (`AUDIOFOCUS_GAIN_TRANSIENT`) while anyone talks.
  - They come back only while a delegation is pending and after 1.5 s of quiet. Once back, they keep playing for at least 4 s unless someone speaks.
  - They pause again at once on user speech, on Hermes's audio, and 300 ms before a commentary.
  - On the speaker they duck (`GAIN_TRANSIENT_MAY_DUCK`) instead of resuming, by default.
- **Another call:**
  - Another cellular call, or anything else that changes the audio mode, mutes the mic and Hermes's playback until it ends.
  - Losing focus to a media app never hangs up.
- **Routes:**
  - The choices are speaker, earpiece, wired, USB, Bluetooth and hearing aid. A newly connected headset takes over automatically.
  - On disconnect or unplug the call falls back to the setting (speaker by default).
  - On the earpiece route, the proximity sensor turns the screen off.

## Tests

`live/src/test` covers the following:

- `GapMusicPolicyTest`
- `CallEngineTest`: connect, talk, delegate, end; the 15 s close; reconnect with history; idle; cap; stop-phrase goodbye; signed out never dials; music gaps; barge-in; approvals (tap only, and spoken when on); clarify by voice; other calls; routes and proximity; mute; the start prompt; the 30-minute resume; ending while connecting.
- `CallHelpersTest`: VAD, level gate, stats parser, routes, spoken replies, headset buttons, captions, `OrderedSender`, cost.
- `SecureStoresTest` (Robolectric, with a software AES key standing in for the Keystore).
- `LiveSettingsTest` (defaults).
- `ConnectionProviderTest`: permission refused, signed-out shape, signed-in shape, `force_refresh`, state.
- `:hermes` `openrouter/`: `OpenRouterTest` (MockWebServer: no key means no request, request shapes, failure kinds, retry rules, SSE writer stream, TTS whole clip and streamed with frame alignment, no content or key in telemetry), `SentenceSplitterTest` (the 1–4 word lead is its own piece).
- `:hermes` `cascade/`: `CascadeReduceTest` (pure `reduce`: one full turn, stale results dropped, cut-in ends the writer without failing, keep_listening and recheck, new task, status never submits, one flush at a time, nothing before `Start`), `CascadeActorTest` (virtual clock, fake ports: early call at 500 ms, commit at 1000 ms, a raced slow early call, a late final, speech resuming, a task result waiting for the user, cut-in, end call, stop), `CascadeKillRestartTest` (kills the actor before every effect and restarts it on the same `Store`; never submits twice; the persist-to-submit window is left for S-B's registry), `CascadeUiTest` (phases, task cards, offline pill, a registered handler replacing a route, a commit hook gating a turn on the accurate pass). The C1 tests run on the pure rules through test drivers (`drivers/`: `TurnTaker`, `TaskBoard` on `TaskRunner` + `GatewayHermesPort`, `SpeechQueue`): `TurnTakerTest` (virtual time: early call, commit at `pause_ms`, cancelled by speech, racing second call, stale early answer, failures, keep_listening, recheck, parking), `routing/RouterTest` (confidence floors, `line_only` ≥ 0.7, queued by default), `routing/JevQuestionsTest` (probe5 wording pinned by a fingerprint of the probe; `utterance`/`latest` state; `split` and `clarity` every call), `TaskBoardTest` (real gateway against `FakeHermesServer`: status never submits, `queued: true` on the wire, cap under a race, cancel, queued update = two results, redirect = one, busy-mode fallback, never `config.set*`), `SpeechQueueTest`, `PhrasesTest` (bank v3, rotator passes, restart), `AudioCacheTest` (LRU, cached line = no request, renderer fetches only changed lines, 4 at a time), `DecisionLogTest`, `OutboxTest`; `GatewayQueuedTest`.
- `:hermes` `telemetry/`: `ScrubTest`, `OtlpTest` (exact OTLP JSON), `TelemetryCoreTest` (MockWebServer: off state sends nothing, crash file deleted only on 2xx, backoff, queue bound, pending cap/expiry, exit dedupe), `TombstoneTest` (proto fields per AOSP `tombstone.proto`), `DiagnosticsEndpointTest` (off without a URL, off when switched off, override wins). `OtlpLiveTest` sends one real record only when `TELEMETRY_LIVE_URL` is set; CI never sets it.
- `LiveIntentsTest`
- `CallScreenTest` (Robolectric Compose).

`ScreenshotRender` writes S23-sized PNGs of made-up states to `live/build/screenshots/`. Run it locally to refresh them; `screenshots/` is gitignored, so they are not committed.

What only the phone can prove (real WebRTC, AEC, routes, focus behaviour of Spotify, CallStyle chip, media buttons) is in the phone checklist in `README.md`.
