# Grumbla

A native Android client for [Mumble](https://www.mumble.info/) voice chat, written from scratch in
Kotlin and Jetpack Compose (not a fork of Mumla/Plumble).

## Features
- **Bluetooth routing that doesn't wreck audio quality** — play over high-quality **A2DP** while
  capturing from the phone's own mic, or use a standard **HFP/SCO** headset. Output-route priority
  list, remember-last-output, and auto-switch to Bluetooth.
- **Voice**: full-band Opus (up to 128 kbit/s), push-to-talk or adaptive voice activation with
  pre-roll, RNNoise AI noise suppression with adjustable strength, per-speaker leveling, output
  limiter, per-user volume.
- **Network**: TLS control channel, direct **UDP voice with OCB2-AES128** and automatic TCP fallback,
  both the Mumble ≥1.5 protobuf and legacy audio formats, auto-reconnect, kick/ban reporting.
- **Identity**: self-signed client certificate generated on-device; view, regenerate, and
  import/export as PKCS#12. Trust-on-first-use server certificate pinning.
- **Private messages and whisper**: send a user a private text/image message (shown inline in the
  chat, marked 🔒, always notifies), or whisper — route your voice to just one user.
- Channel tree / speakers / compact views, text chat with images, saved servers, foreground service
  with Mute / Deafen / Disconnect in the notification, light and dark themes.

Requires Android 12 (API 31) or newer.

## Building
```sh
# one-time: fetch the native codec sources (not committed)
#   see core-audio/src/main/cpp/README.md
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Needs JDK 17, the Android SDK (platform 35), NDK r27 and CMake 3.22.1. Full instructions, release
signing, and installing on a phone: [`docs/BUILD.md`](docs/BUILD.md).

## Project layout
| Module | What it is |
|---|---|
| `core-protocol` | Pure Kotlin/JVM Mumble protocol: TLS control channel, handshake, UDP + OCB2 crypto, identity certificates. Unit-tested on the JVM. |
| `core-audio` | Android library + NDK: Opus/RNNoise JNI, capture/encode/playback engine, jitter buffer, mixer, audio routing. |
| `app` | Compose UI, Hilt DI, Room database, foreground voice service. |

## Tests
```sh
./gradlew :core-protocol:test
```
Integration tests expect a Mumble server on `127.0.0.1:64738` and are skipped if none is running:
```sh
docker run -d --name mumble-test -p 64738:64738 -p 64738:64738/udp \
  -e MUMBLE_CONFIG_autobanAttempts=0 mumblevoip/mumble-server:latest
```

## Status
Usable day to day. Progress and backlog are tracked in [`docs/PROGRESS.md`](docs/PROGRESS.md).

## License
MIT — see [`LICENSE`](LICENSE). Third-party components (Mumble protocol definitions, libopus,
RNNoise) are BSD-licensed; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
