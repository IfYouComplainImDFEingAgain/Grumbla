# Progress & TODO — not-mumla

Status tracker for the Android Mumble client. Milestones come from the implementation plan
(internal plan). See `CLAUDE.md` for architecture, build/test
commands, and hard-won gotchas.

Legend: ✅ done & verified · 🟡 implemented, partial verification · ⬜ not started

---

## Milestones

### M1 — Project skeleton + design system ✅
- 3-module Gradle build (`:app`, `:core-protocol`, `:core-audio`), version catalog, wrapper 8.11.1.
- Compose Material 3 theme from the design's LIGHT/DARK tokens; Connect / Channels / Settings
  screens with mock data; Tree/Speakers/Compact layouts; voice bar; quick-settings sheet.
- Wire-generated Mumble protobuf classes. Builds a debug APK.

### M2 — Identity cert + TLS connect + handshake ✅
- RSA-2048 self-signed X.509 (BouncyCastle, clientAuth, SHA-256), PKCS#12 via EncryptedFile.
- Framed TLS control channel; TOFU server pinning + fingerprints; full handshake → ServerSync.
- Live channel/user `StateFlow`; Room saved servers; Hilt DI; Connect screen wired to real connect.
- **Verified**: `HandshakeIntegrationTest` reaches ServerSync; on-device connect authenticated and
  rendered the live tree.

### M3 — Audio in/out (phone) ✅
- libopus cross-compiled (NDK, arm64/x86_64) + JNI; per-speaker jitter buffer + mixer (PLC).
- `AudioEngine`: capture→Opus→send (PTT/VAD + mute), inbound decode→mix→AudioTrack; speaking flow.
- Audio carried as raw `UDPTunnel` body using the Mumble 1.5 protobuf `Audio` format.
- **Verified**: `VoiceLoopbackTest` + `VoiceTunnelIntegrationTest` pass against the real server;
  **user confirmed live mic transmit/receive on-device**.

### M4 — Bluetooth routing engine + foreground service ✅ (🟡 BT hardware untested)
- `AudioRouter` on `setCommunicationDevice`: A2DP-HQ (A2DP out + phone mic, MODE_NORMAL),
  HFP/SCO+LE headset (MODE_IN_COMMUNICATION), phone speaker, wired; live device-change updates.
- Route-aware engine (per-route source + preferred devices). Output picker in Settings (real devices).
- `VoiceService` foreground (`microphone|connectedDevice`) + mute/disconnect notification actions.
- Fixes shipped: PTT button no longer opens quick settings; FGS sticky-restart launch crash;
  phone-speaker route now `MODE_NORMAL` (loudspeaker) so received audio is audible.
- **Needs user**: A2DP↔SCO switching can only be validated with real Bluetooth headphones.

### M5 — Text chat + polish ✅
- [x] Inbound chat: `Event.Text` → chat list, actor→name, HTML-stripped; unread badge; own
      messages added locally. `TextMessageTest` verifies routing through the real server.
- [x] Settings persisted via DataStore + applied: theme, transmission mode, mic gain, VAD,
      noise suppression, echo cancellation (hardware AudioEffects), Opus bitrate, avatars,
      keep-awake; SettingsViewModel + interactive Settings screen.
- [x] Auto-reconnect with exponential backoff (1s..15s, 8 tries), rejoins last channel.
- [x] Mention notifications (sound/vibrate gated by setting) + TTS read-aloud; licenses screen.

---

## Backlog / not-yet-wired (post-M5)
- **Still visual-only settings**: master volume, priority speaker, join/leave sounds,
  channel-layout persistence (engine gets the persisted transmission mode, but the in-call
  VoiceBar/QuickSettings layout+mode toggles remain ephemeral and aren't persisted).
- **UDP + OCB2** voice path — currently audio uses only the TCP tunnel (works everywhere). Add a UDP
  socket with OCB2-AES128 (`CryptSetup` already arrives) + UDP-ping-based switch for lower latency.
  (`:core-protocol/udp/` is the home for this; port `~/git/mumble/src/crypto/CryptStateOCB2.cpp`.)
- **Server-cert TOFU prompt UI** — pinning works in code; no user-facing "trust this fingerprint?"
  dialog yet (first connect auto-accepts and pins).
- **Profile editing** (display name/avatar/comment), blocked users, local mute list (designed, stubbed).
- **DNS SRV resolution** (`_mumble._tcp`) — connect is host:port only so far.
- **PermissionDenied / CodecVersion / ServerConfig** handling in `MumbleClient` (currently ignored).
- **Wired/SCO route audibility** — verify on hardware; earpiece-vs-loudspeaker toggle may be wanted.

## Known issues / watch-outs
- On-device UI automation is unreliable on the test phone (auto-locks to PIN, multi-display). Live
  driving needs the user to keep it unlocked. See CLAUDE.md "On-device notes".
- Integration tests need a running server on `127.0.0.1:64738` or they skip.

## Verification quick-reference
- Unit/integration: `./gradlew :core-protocol:test` (with the docker test server up).
- Build: `./gradlew :app:assembleDebug`; install: `adb install -r <apk>`.
- Live two-client audio: `ListenForAudioTest` (env-gated `NOTMUMLA_LISTEN=1`) receives device audio.
