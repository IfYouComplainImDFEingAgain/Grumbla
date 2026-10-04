# Grumbla

A native Android client for [Mumble](https://www.mumble.info/) voice chat. Written from scratch in
Kotlin and Jetpack Compose. It is not a fork of Mumla or Plumble.

Requires Android 12 (API 31) or newer.

## Why this exists

The main reason is Bluetooth. When an Android app records from a Bluetooth headset's mic, the
headset switches to HFP/SCO, which is call-quality audio in both directions. For voice chat where
you mostly listen, that's a bad trade.

Grumbla can keep the headset on **A2DP** (full-quality playback) and record from the phone's own
mic instead. If you do want the headset mic, a normal **HFP/SCO** headset mode is still there.

## Features

- **Bluetooth routing**: A2DP playback with the phone mic, or HFP/SCO headset mode. Output routes
  are a priority list, with optional "Remember last output" and "Switch to Bluetooth when
  connected".
- **Voice**: full-band Opus at up to 128 kbit/s. Push-to-talk or adaptive voice activation with
  pre-roll. RNNoise noise suppression with adjustable strength. Per-speaker leveling, an output
  limiter, and per-user volume.
- **Network**: TLS control channel, direct UDP voice (OCB2-AES128) with automatic TCP fallback,
  both the Mumble 1.5+ protobuf audio format and the legacy one, auto-reconnect, and kick/ban
  reasons shown instead of hidden.
- **Identity**: self-signed client certificate generated on the phone. View, regenerate, and
  import/export it as PKCS#12. Server certificates are pinned on first use.
- **Private messages and whisper**: send one user a private text or image message, or route your
  voice to just them.
- Channel tree, speakers, and compact views. Text chat with images (swipe between channels and
  chat). Saved servers. Mute / Deafen / Disconnect in the notification. Light and dark themes.

## How to install

Download the latest `.apk` from the
[Releases](https://github.com/IfYouComplainImDFEingAgain/Grumbla/releases) tab and open it on your
phone. Android will ask you to allow installs from your browser or file manager the first time.

To get updates automatically, install [Obtainium](https://github.com/ImranR98/Obtainium) and add
`https://github.com/IfYouComplainImDFEingAgain/Grumbla` as an app. It checks the releases and
offers each new version as an update.

Building the APK yourself is optional. All releases are compiled automatically via GitHub Actions.

## Requirements

- JDK 17
- Android SDK with platform 35 and build-tools 35.0.0
- NDK r27 (`27.0.12077973`) and CMake 3.22.1
- The libopus and RNNoise sources (not committed, see below)
- Docker, if you want to run the integration tests against a local server

## Building

The native codec sources are vendored but not checked in. Fetch them once by following
[`core-audio/src/main/cpp/README.md`](core-audio/src/main/cpp/README.md). RNNoise is pinned to
commit `bad0a75`, the classic version with the model built in. Later versions split the model out
into a separate download, which the build doesn't handle.

Then:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use the wrapper (`./gradlew`), not a system Gradle. Gradle 9.x doesn't work with the Wire plugin;
the wrapper is pinned to 8.11.1. The first native build is slow because it compiles libopus for
`arm64-v8a` and `x86_64`.

Release signing, multiple phones, and connecting a phone to a local server are covered in
[`docs/BUILD.md`](docs/BUILD.md).

## Configuration

Most of it is in Settings. The ones worth knowing about:

- **Use media volume** (default on): plays voice on the media stream, so the volume buttons work
  like they do for music. Turn it off to use call mode instead. Call mode is the fallback for phones
  whose echo cancellation only works in a call.
- **Ear speaker**: plays through the earpiece instead of the loudspeaker on the phone route. Also
  toggleable from quick settings.
- **Raw microphone**: records the mic without the system's voice-call processing. Grumbla then
  applies its own noise suppression.
- **Share microphone** (default on): when another app starts recording, Grumbla lets go of the mic
  and picks it back up afterwards. Without this, Android silences the other app while Grumbla is in
  call mode.
- **Output priority**: the order routes are tried in on connect, and when the active device
  disappears mid-call.

## How it works

Three Gradle modules:

| Module | What it is |
|---|---|
| `core-protocol` | Pure Kotlin/JVM Mumble protocol: TLS control channel, handshake, UDP + OCB2 crypto, identity certificates. Unit-tested on the JVM. |
| `core-audio` | Android library + NDK: Opus/RNNoise JNI, capture/encode/playback engine, jitter buffer, mixer, audio routing. |
| `app` | Compose UI, Hilt DI, Room database, foreground voice service. |

Voice goes over UDP when it can. UDP is only used once a packet has actually decrypted, and if
nothing decrypts for about 8 seconds it falls back to tunnelling audio over the TCP control
connection while it keeps trying UDP. Lots of networks block UDP, so TCP is the floor.

The audio format is picked from the server's version: protobuf for 1.5+, legacy for anything older.
Getting this wrong doesn't error, the audio just never arrives.

## Tests

```sh
./gradlew :core-protocol:test
```

Integration tests need a Mumble server on `127.0.0.1:64738` and skip if there isn't one:

```sh
docker run -d --name mumble-test -p 64738:64738 -p 64738:64738/udp \
  -e MUMBLE_CONFIG_autobanAttempts=0 mumblevoip/mumble-server:latest
```

The `autobanAttempts=0` matters. The full suite makes more than 10 connections quickly, which trips
Mumble's brute-force auto-ban (10 per 120 s) partway through, and the remaining tests fail.

## Real-world notes

- **Aggressive battery management**: some phones kill the connection in the background.
  Set the app to Battery → Unrestricted, allow autostart in the phone's app manager, and
  lock it in Recents. The app asks for a battery-optimization exemption on connect.
- **Debug and release builds use different signing keys.** Switching a phone from one to the other
  means uninstalling, and uninstalling wipes the identity certificate. Export it in the app first.

## Troubleshooting

- **Sent audio stutters now and then**: turn off Settings → Share microphone first. Each time
  another app grabs the mic, capture restarts and leaves a gap in what you send. Frames that were
  never sent don't show up as packet loss, so the server will report almost none. Repeated
  `record source … initialized` lines in `adb logcat` confirm capture is restarting.
- **RNNoise doesn't cancel out sound**: RNNoise isn't designed for quick sounds like pops, bangs.
  Standard will use your phone's noise cancellation system which usually does a decent job.
  Pop cancellation will need another processing layer added.
- **Playback is quiet and coming from the earpiece**: Ear speaker is on. Toggle it off in quick
  settings.
- **Phone can't reach a server running on your PC**: `adb reverse tcp:64738 tcp:64738`, then connect
  to `127.0.0.1`.

## Known issues

- **32-bit ARM phones crash on connect.** The native libs are only built for `arm64-v8a` and
  `x86_64`, but some dependencies ship `armeabi-v7a` libs, so the APK installs on 32-bit phones
  (some budget Galaxy A models) and then fails with `UnsatisfiedLinkError` when it loads Opus.
- The first connection to a server pins its certificate without asking. Only a *changed*
  certificate prompts.
- No DNS SRV lookup. Connect by host and port.
- Some settings are still visual only: master volume, priority speaker, join/leave sounds.

The full backlog is in [`docs/PROGRESS.md`](docs/PROGRESS.md).

## License

MIT, see [`LICENSE`](LICENSE). Third-party components (Mumble protocol definitions, libopus,
RNNoise) are BSD-licensed; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
