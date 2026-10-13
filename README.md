# Hermes Live

Talk to your own [Hermes Agent](https://github.com/NousResearch/hermes-agent) from an Android phone, the way the Hermes desktop app's GPT-Live voice mode works. You speak; OpenAI's `gpt-live-1` listens, speaks, and hands every real request to your Hermes, which does the work with its own model and tools. The answer is spoken back.

Android 12+ (built for a Galaxy S23). Works on its own, and as the voice for the [Folio](https://github.com/mattstyles333/folio-launcher) launcher.

## What it does

- **Your Hermes, your key.** Sign in with your Hermes dashboard address, user name and password. The OpenAI key stays on your Hermes server; the phone only sends its WebRTC offer. Tokens and the password are sealed with the Android Keystore.
- **Natural calls.** Cut in whenever you like. Calls within 30 minutes carry on the same Hermes conversation. A dropped line reconnects once and keeps the conversation.
- **Music in the gaps.** Your music pauses while you or Hermes talk, comes back while Hermes works, and pauses again just before the answer. The mic stays live. On the speaker it ducks instead, so the mic doesn't hear it.
- **Sound that behaves.** Speaker, earpiece, wired, USB and Bluetooth; a headset takes over when it connects; the screen goes dark at your ear; a phone call mutes Hermes until it's over.
- **Many ways to hang up.** End button, call notification (Mute / End), Quick Settings tile, hold the headset button, or say "goodbye", "that's all" or "never mind". Idle calls hang up after 3 minutes and calls stop at 30, each with a spoken warning. You see the length and cost ($0.05/min) afterwards.
- **Approvals need a tap.** When Hermes wants to run something, a card and a notification ask first. Spoken approvals are off unless you turn them on.
- **Crash reports, never content.** Release builds send crash reports and errors (event names, codes, durations, stack traces; never what you say, Hermes's answers or sign-in details) to the maintainer's diagnostics endpoint, set at build time from the CI variable `FOLIO_DIAGNOSTICS_URL`. Turn it off in Settings › Diagnostics, or point it at your own OTLP/HTTP collector (e.g. SigNoz). Builds you make yourself send nothing unless you set that variable.
- **Start it how you like.** App icon, shortcuts (Talk, Talk muted, New conversation), Quick Settings tile, the assistant gesture or headset button once Hermes Live is your assistant app, or share text to it.

## Server requirements

A Hermes Agent with the dashboard reachable over HTTPS (or plain http on a Tailscale `*.ts.net` name), dashboard login on, and `voice.voice_chat_mode: gpt-live` with an OpenAI key that can use `gpt-live-1`.

## Install

Download `hermes-live-X.Y.Z.apk` from the [Folio Releases](https://github.com/mattstyles333/folio-launcher/releases/latest): Folio's release workflow builds Hermes Live from this repo (pinned as a submodule) and signs both apps with the same key, which they need to talk to each other. This repo's own release workflow takes over once it has the signing secrets (see AGENTS.md).

## Phone checklist

What only a real call can prove:

1. Sign in; the voice shows as available.
2. Call: connect tone, two-way audio, no echo on the speaker, cutting in over Hermes.
3. With music playing: it pauses when anyone talks, returns while Hermes works, pauses before the answer; on the speaker the ducked music doesn't count as you talking.
4. Bluetooth connect / disconnect mid-call, wired unplug, earpiece screen-off.
5. Status-bar call chip, notification Mute / End, the tile.
6. Headset press (mute) and hold (end), also while music is back; starting from the lock screen.
7. A phone call mid-call mutes, then resumes.
8. "Goodbye" closes cleanly; the idle warning is spoken; a network drop reconnects.
9. An approval card and its notification.
10. Folio reads the connection (Settings → Hermes says Ready).

## Build

See [AGENTS.md](AGENTS.md). JDK 17, Android SDK 37:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

MIT licensed.
