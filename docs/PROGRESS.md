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

### Added since M5
- **AI noise suppression (RNNoise)**: Settings → Noise suppression is now Off / Standard (hardware) /
  AI. AI runs the vendored RNNoise model (`librnnoisejni.so`, ~165KB) on each 480-sample mic frame
  before Opus encode; disables hardware NS when on. Also: automatic gain control via the platform
  AGC effect (the earlier software AGC was removed — it fought the VAD); a live VAD calibration meter.
- **Audio quality**: default Opus bitrate 40→72 kbit/s, inband FEC (encoder) + FEC-aware jitter
  buffer (lost frames recovered from the next packet), unconstrained VBR, and a mixer that buffers
  decoded PCM so any incoming frame size plays back without truncation.
- **Server-cert-changed dialog**: TOFU now surfaces a "trust new certificate?" prompt when a
  server's cert changes (instead of failing silently); auto-reconnect skips cert mismatches.
- **Background**: requests battery-optimization exemption on connect so the foreground voice
  service survives backgrounding (esp. on aggressive OEMs like Unihertz).
- **Inline image messages**: chat `+` button → photo picker → downscaled/compressed JPEG sent as a
  base64 `<img>` data URI (sized to `ServerConfig.image_message_length`); inbound images are parsed
  and rendered in the bubble. Verified by `ImageMessageTest`. (Doesn't yet act on `allow_html=false`.)
  Thumbnails are aspect-bounded (240×300dp) so they never overflow; tap opens a full-screen viewer
  with pinch-zoom + pan.
- **Legacy voice protocol** (pre-1.5 servers): auto-selects the legacy Opus packet format when
  the server (or our advertised version) predates 1.5.0, so voice works on 1.3.x/1.4.x servers;
  inbound format is auto-detected by header byte. Verified by `LegacyVoiceTest`. The 1.5 protobuf
  path is unchanged.

### Fixes since M5 (user-reported)
- **Dropped from busy servers after a few seconds** (silent EOF, no reason). The keep-alive idle
  timer (`ControlChannel.idleMs()`) was reset by **receives as well as sends**, so on a populated
  server the constant inbound traffic kept the link "non-idle" and the client **never sent a ping** →
  the server timed it out and closed the socket. (An empty backup of the same server stayed up
  because it had no inbound traffic.) Fixed: `idleMs()` now tracks time since our last *outgoing*
  message only (`readFrame()` no longer touches it), so pings go out every ~6 s of send-idle.
- **Kicks/bans were swallowed → invisible reconnect loop.** `onUserRemove` ignored removal of our own
  session, and auto-reconnect retried every failure — so a kick/ban/reject looked like a generic drop
  and silently relooped with the reason hidden. Now: self-removal surfaces "Kicked/Banned: <reason>",
  `Reject` and self-kick/ban set `ServerState.fatal`, and auto-reconnect skips fatal failures.
- **Stale auto-reconnect** → a pending reconnect to a former server could fire after switching
  servers. `connect()` now cancels any pending reconnect; the delayed retry verifies it still
  targets the current `lastServer`.
- **Chat send latency** felt delayed on the remote client (local echo masked it). Protocol send is
  ~1–3 ms (`TextLatencyTest`); the delay was the phone↔server link going idle. Now: TCP keep-alive
  on the socket, text sends dispatched off the UI thread, and an **idle-aware keep-alive** — pings
  only after ~6 s of real idle (no extra pings during a call → battery-friendly), via
  `ControlChannel.idleMs()`.

---

## Backlog / not-yet-wired (post-M5)
- **Still visual-only settings**: master volume, priority speaker, join/leave sounds,
  channel-layout persistence (engine gets the persisted transmission mode, but the in-call
  VoiceBar/QuickSettings layout+mode toggles remain ephemeral and aren't persisted).
- **UDP + OCB2** voice path — currently audio uses only the TCP tunnel (works everywhere). Add a UDP
  socket with OCB2-AES128 (`CryptSetup` already arrives) + UDP-ping-based switch for lower latency.
  (`:core-protocol/udp/` is the home for this; port `~/git/mumble/src/crypto/CryptStateOCB2.cpp`.)
- **Server-cert TOFU on first connect** still auto-accepts/pins silently; only a *changed* cert now
  prompts. A first-connect "trust this fingerprint?" prompt is still not shown.
- **Profile editing** (display name/avatar/comment), blocked users, local mute list (designed, stubbed).
- **DNS SRV resolution** (`_mumble._tcp`) — connect is host:port only so far.
- **PermissionDenied / CodecVersion / ServerConfig** handling in `MumbleClient` (currently ignored).
- **Wired/SCO route audibility** — verify on hardware; earpiece-vs-loudspeaker toggle may be wanted.

## Known issues / watch-outs
- On-device UI automation is unreliable on the test phone (auto-locks to PIN, multi-display). Live
  driving needs the user to keep it unlocked. See CLAUDE.md "On-device notes".
- Integration tests need a running server on `127.0.0.1:64738` or they skip.
- The full integration suite makes >10 rapid connections and trips Mumble's brute-force auto-ban
  (default 10/120s). Run the docker test server with `-e MUMBLE_CONFIG_autobanAttempts=0`, or
  restart it between full runs.

## Verification quick-reference
- Unit/integration: `./gradlew :core-protocol:test` (with the docker test server up).
- Build: `./gradlew :app:assembleDebug`; install: `adb install -r <apk>`.
- Live two-client audio: `ListenForAudioTest` (env-gated `NOTMUMLA_LISTEN=1`) receives device audio.
