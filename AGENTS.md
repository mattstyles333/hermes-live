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
- **Release workflow:** `.github/workflows/release.yml` runs on a `vX.Y.Z` tag that must match `:live`'s versionName. It tests, signs `hermes-live-X.Y.Z.apk` with Folio's release key, and publishes the GitHub Release plus a SHA-256. Bump `versionCode` / `versionName` in `live/build.gradle.kts` first. Don't `gh release create` by hand.
- **Signing:** Folio's key, always. Both apps declare the signature permission `com.folio.permission.HERMES`; with different keys the second install fails and Folio can't read the connection. CI secrets: `FOLIO_KEYSTORE_BASE64`, `FOLIO_STORE_PASSWORD`, `FOLIO_KEY_PASSWORD` (same values as folio-launcher's).
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
| Entry points | `LiveIntents.kt`, `LiveActivity.kt`, `res/xml/shortcuts.xml` |
| UI | `ui/` (Compose; Folio's palette copied, no dependency on Folio) |

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
- `LiveIntentsTest`
- `CallScreenTest` (Robolectric Compose).

`ScreenshotRender` writes S23-sized PNGs of made-up states to `live/build/screenshots/`. Run it locally to refresh them; `screenshots/` is gitignored, so they are not committed.

What only the phone can prove (real WebRTC, AEC, routes, focus behaviour of Spotify, CallStyle chip, media buttons) is in the phone checklist in `README.md`.
