# Progress & TODO — not-mumla

Status tracker for the Android Mumble client (Grumbla). Build/test commands are in
[`BUILD.md`](BUILD.md).

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

### Audio pipeline overhaul (post-M5, extensive — supersedes earlier audio notes)
- **Capture routing (fixes "quiet" + "distorted" mic)**: phone/wired now use `VOICE_COMMUNICATION` +
  `MODE_IN_COMMUNICATION` with output explicitly routed to the built-in loudspeaker (like Mumble/Mumla)
  — clean, hardware-leveled, loud. We do **not** stack our own NS/AEC/AGC effects on the comms source
  (native already runs them; stacking over-suppressed close-mic).
- **Raw microphone toggle** (Audio·Input): switches phone/wired to the unprocessed `VOICE_RECOGNITION`
  source — natural/full-band, no native NS/AGC/echo-cancel (best with headphones; SCO/A2DP unaffected).
- **Codec quality**: `OPUS_APPLICATION_AUDIO` (VOIP only < 32k) + `OPUS_AUTO` signal + forced fullband,
  complexity 10, unconstrained VBR, **no FEC** (pointless over the reliable TCP tunnel). Default bitrate
  **128 kbit/s**; presets to 160k. Each talk-spurt starts with a **fresh encoder** (self-contained first
  frame) and ends with an **empty terminator** — fixes onset/tail transition distortion.
- **Playback**: `JitterBuffer` plays in **arrival order** (no sequence-gap concealment/FEC — Mumble
  `frame_number` is a 10 ms-unit timestamp, so a peer's 20 ms frames aren't false "loss"). `SpeakerMixer`
  mixes through a **float accumulator** with a **soft output limiter** (no hard-clip on overlap),
  optional **per-speaker leveling** (Settings→Output "Audio leveling"), and **per-user volume**.
- **Per-user volume**: long-press a user → dB slider (persisted by name, JSON in DataStore), inline
  `+10`/`-3` badge, applied as a per-session mixer multiplier. Local only.
- **VAD**: `AdaptiveVad` (shared by engine + mic test) — peak-follow envelope + **continuous automatic
  sensitivity** (tracks noise floor & speech level, sets threshold ~⅓ up in dB; toggle, default on) or
  a manual dB threshold; **pre-roll** (~60 ms) so onsets aren't clipped. Manual **Auto-set** calibration
  samples ~5 s of speech; **Test mic** records→VAD-gates→plays back so the user hears what transmits.
- **Noise suppression**: Off (default) / Standard (hardware) / AI (RNNoise `librnnoisejni.so`), with a
  **strength** wet/dry mix so RNNoise isn't over-aggressive. Live **input-level meter** on a dB scale
  with a mic **preview in Settings** (works disconnected; transmission is suppressed while Settings open).
- **16 KB page alignment** for native libs (`-Wl,-z,max-page-size=16384` + `useLegacyPackaging=false`)
  for Android 15+/Pixel 10.
- **Debug overlay** (Settings→Developer): live TX/RX packet rate + totals + est. loss on the voice screen.

### Other additions since M5
- **Identity certificate dialog**: tap shows CN + validity + SHA-256/SHA-1; **regenerate / import (.p12)
  / export** actions (crypto off-thread, SAF file pickers).
- **Voice notification**: ongoing FGS notification with **Mute / Deafen / Disconnect** actions +
  status; requests `POST_NOTIFICATIONS` at runtime (Android 13+) so it isn't silently hidden.
- **Screen-off survival**: `VoiceService` holds a partial wake lock + low-latency Wi-Fi lock for the call.
- **Settings from Connect screen**; **Permissions** section (mic/notifications/bluetooth + battery
  "Run in background") that re-requests missing grants; **transmission mode is Settings-only** (removed
  the in-call VoiceBar/QuickSettings toggle so it doesn't override the persisted setting).
- **Server-cert-changed dialog**: TOFU now surfaces a "trust new certificate?" prompt when a
  server's cert changes (instead of failing silently); auto-reconnect skips cert mismatches.
- **Background**: requests battery-optimization exemption on connect so the foreground voice
  service survives backgrounding (esp. on aggressive OEMs like Unihertz).
- **Inline image messages**: chat `+` button → photo picker → downscaled/compressed JPEG sent as a
  base64 `<img>` data URI (sized to `ServerConfig.image_message_length`); inbound images are parsed
  and rendered in the bubble. Verified by `ImageMessageTest`. (Doesn't yet act on `allow_html=false`.)
  Thumbnails are aspect-bounded (240×300dp) so they never overflow; tap opens a full-screen viewer
  with pinch-zoom + pan.
- **Rich text chat**: outgoing text is Markdown → HTML via `ChatMarkdown` (port of the desktop
  client's `Markdown.cpp`; plain text is now HTML-escaped, which it wasn't before). Inbound HTML is
  rendered by `ChatHtml` (Qt rich-text subset: b/i/u/s, Qt `<span style>`, font colors, headings,
  code/pre, lists, links). Sender colors below 3:1 contrast on the bubble are dropped; font sizes and
  backgrounds ignored. Links: only http/https/mailto open, and a link whose text doesn't show its
  real host asks first. Long-press a message for Copy/Delete-for-me; long-press empty space to clear.
- **WYSIWYG composer** (Settings → Chat → Formatting toolbar, default off): B/I/U/S/code/color
  toolbar; toggles apply to the selection or to what's typed next. `RichDraft` keeps one style per
  character (edits splice styles like text; the diff is anchored on the cursor) and serializes to
  Mumble HTML itself. Markdown is not applied when it's on.
- **Media volume** (Settings → Audio output → Use media volume, default on): phone speaker and wired
  play as `USAGE_MEDIA` in `MODE_NORMAL`, because in call mode AudioService sends the volume keys to
  call volume whatever the app asks for. Capture keeps `VOICE_COMMUNICATION`. On a Pixel it still
  opens a `VOIP_TX` input with the hardware AEC + NS, but that depends on the HAL, so the toggle
  returns to call mode on phones that echo. The SCO headset always stays in call mode.
- **Ear speaker** (Settings → Audio output → "Phone speaker plays through", default Speakerphone):
  the phone route's communication device becomes `TYPE_BUILTIN_EARPIECE`. Media streams can't
  reach the earpiece, so this forces call mode (call volume) even with Media volume on; while
  sharing the mic it plays `USAGE_VOICE_COMMUNICATION` pinned to the earpiece in `MODE_NORMAL`. A
  `PROXIMITY_SCREEN_OFF_WAKE_LOCK` is held while it's in use. Hidden on devices with no earpiece.
  Also toggled from the channel screen's quick settings (⋮), shown only while the phone route is active.
- **Whisper**: user sheet → "Whisper to X" registers `VoiceTarget` 1 for that session; the target is
  locked per talk spurt and cleared when they leave or we reconnect. Verified by `WhisperIntegrationTest`.
- **Private messages**: inbound `TextMessage` with `session` set and no `channel_id`/`tree_id` is
  flagged private (same rule as the reference client); shown inline with a 🔒 label + outlined
  bubble, always notifies, TTS says "privately says". Send via user sheet → "Message X privately"
  or by tapping a private message; the composer shows a 🔒 name ✕ chip while in private mode. Target
  is re-resolved by name if its session id is stale, and cleared when the peer leaves / on reconnect.
  Verified by `TextMessageTest.privateMessageReachesOnlyTargetAndIsFlagged`.
- **Legacy voice protocol** (pre-1.5 servers): auto-selects the legacy Opus packet format when
  the server (or our advertised version) predates 1.5.0, so voice works on 1.3.x/1.4.x servers;
  inbound format is auto-detected by header byte. Verified by `LegacyVoiceTest`. The 1.5 protobuf
  path is unchanged.
- **Swipe between Channels and Chat**: a `HorizontalPager` in `ChannelsScreen` replaces the tab
  switch; the voice bar stays outside it so a swipe can't steal a PTT hold. Back on the chat page
  returns to channels (an edge swipe is the system Back gesture, which on this screen backgrounds
  the app). Paging is off while the keyboard is up. The chat page stays composed, keeping its scroll
  position and draft; unread clears on the *settled* page, including messages arriving while shown.

- **Share microphone** (Settings → Audio input, default on): other apps used to get silence from
  the mic for the whole session, because we hold `MODE_IN_COMMUNICATION` (Android silences every
  non-owner capture during a VoIP call) and `VOICE_COMMUNICATION` is privacy-sensitive by default.
  Now our `AudioRecord` is `setPrivacySensitive(false)`, and `MicContentionMonitor` watches
  `AudioRecordingCallback` for a foreign mic capture (silenced clients still show up, anonymized).
  When one appears, we stop capture and switch to `AudioRouter.sharedConfigFor` (MODE_NORMAL, no
  comm device, `USAGE_MEDIA` pinned to the route's output), so the other app gets the mic and we
  keep playing the channel. When it stops, we retake the mic and call mode. The voice bar shows
  "Mic in use by another app". Debounced 300 ms (yield) / 1 s (resume); our own record session ids
  are excluded. 🟡 Needs on-device verification. Suspect for intermittent send stutter: see
  "Known issues / watch-outs".

- **Channel listeners**: `User.listeningChannels` tracks `UserState.listening_channel_add/remove`
  (deltas). Listeners render above a channel's members (ear icon, italic `listener` blue) and are
  not counted in its user badge. Long-press a channel (Tree/Compact) → Join / Listen / Stop
  listening (`MumbleClient.setListening`). Listens are re-added after auto-reconnect (unregistered
  users lose them server-side). `PermissionDenied` is now surfaced as a toast (was silently
  dropped). Verified on-device.

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

### Direct UDP voice path + OCB2 (post-M5)
- **OCB-AES128** (`udp/CryptStateOCB2.kt`) — pure-Kotlin port of the reference `CryptStateOCB2.cpp`
  (`AES/ECB/NoPadding` block primitive, byte-wise GF(2^128) doubling, per-packet IV counter, the
  reorder/loss IV-resync window + replay history, and the 2019 counter-cryptanalysis guards). Unit
  tested (`CryptStateOCB2Test`): roundtrip across lengths, IV advance, tamper/replay rejection,
  mild-reorder tolerance, digital-silence bit-flip.
- **`udp/UdpTransport.kt`** — `DatagramSocket` to the same host:port, encrypts audio + periodic pings,
  decrypts inbound datagrams back into raw audio packets (same bytes as a UDPTunnel body). Any packet
  that decrypts marks the path **active**; if nothing decrypts for ~8 s it drops back to the TCP tunnel
  but keeps pinging so it can recover. Protobuf (≥1.5) and legacy (<1.5) ping/audio framing both handled.
- **`MumbleClient` wiring** — handles `CryptSetup` (full key+nonces → init+start UDP; lone server_nonce
  → decrypt-IV resync; empty → echo our encrypt IV), routes `sendAudio` over UDP when active (else TCP),
  feeds inbound UDP through the existing `onAudioPacket` parser. Transparent to the app/audio engine.
  Debug overlay shows **UDP/TCP**. Automatic with TCP fallback (no setting — fallback covers blocked UDP).
- **Verified**: `UdpVoiceIntegrationTest` against the real 1.5 server — the encrypted ping echo
  round-trips (proving byte-exact OCB2 both directions) and audio loops back over UDP (target 31).

### Tank arena (multiplayer easter egg) 🧪
- Battlezone-style wireframe tanks for everyone in a channel who opens it: long-press your current
  channel → **Tank arena** (needs the games unlock). Voice keeps running; a TALK button appears for
  PTT users. No host: each client owns its tank, broadcasts state ~3×/s, the victim decides hits.
  Details in [`SIDE_CHANNELS.md`](SIDE_CHANNELS.md#tank-arena).
- **Verified**: `TankTest` (codec, dead reckoning, channel scoping, invite throttle, victim-decided
  hits, kill crediting, ≤ 4 msg/s under load). Not yet played device-to-device.

### Dogfight (multiplayer easter egg) 🧪
- Flat-shaded polygon dogfight in the old console style, same channel model as the tank arena:
  long-press your channel → **Dogfight**. Chase camera, twin lasers, boost/brake meter, barrel roll,
  shields (five hits, no regeneration), synthesized sound effects, towers and arch rows, autopilot turn-back at the edge. Lasers ride on the state message's
  "trigger held" bit, so firing costs no extra messages. Details in
  [`SIDE_CHANNELS.md`](SIDE_CHANNELS.md#dogfight).
- Shared plumbing pulled out of the tank arena into `game/arena/ChannelArena` (tank tests unchanged
  and passing).
- **Verified**: `FlightTest` (codec bounds, dead reckoning, victim-decided damage and kills, crash
  crediting, barrel roll, ≤ 4 msg/s under button mashing, airspace bounds); rendering checked on
  the Pixel. Not yet played device-to-device.

### Block house (multiplayer easter egg) 🧪
- Brick-game-style blocky figures in a two-storey house: long-press your channel → **Block house**.
  Living room, kitchen and hallway with stairs downstairs; two bedrooms and a bonus room over the
  garage (on the right) upstairs; a fenced yard with trees. Dollhouse camera that cuts away walls in
  front of you and hides the upstairs while you're downstairs; rotate in 90° steps, three zooms.
- Emotes: wave, cheer, dance, sit. **SLAP** whoever's in front of you: they flop over as a Verlet
  ragdoll (walls, furniture, stairs) for 3 s, then stand up where they landed. Victim decides, with
  a reach check and 1.5 s of immunity after getting up. Shirt colour picker, name tags, synthesized
  swish/smack. Details in [`SIDE_CHANNELS.md`](SIDE_CHANNELS.md#block-house).
- **Verified**: `HouseTest` (codec bounds, walking the stairs up to the bonus room, stairwell edge
  and walls, ragdoll falls flat / stays out of walls / is frame-rate independent, victim-decided
  slaps with reach + replay + immunity checks, crediting, ≤ 4 msg/s under button mashing). Not yet
  played device-to-device.

### Nudge ✅
- Long-press a user → Nudge: their screen shakes, 40 ms buzz, synthesized "boing". Throttled on
  receive (10 s per sender, 3 s global), follows the ringer switch, opt-out in Settings.
  Details in [`SIDE_CHANNELS.md`](SIDE_CHANNELS.md#nudge). Verified Titan 2 → Pixel 10.

### Client-to-client side channel + Four in a Row easter egg ✅
- `PluginDataTransmission` (TCP type 26) send/receive in `MumbleClient`; strict, untrusted-input
  handling. Design, limits and caveats: [`SIDE_CHANNELS.md`](SIDE_CHANNELS.md).
- Four in a Row (`app/.../game/`): unlock by tapping Settings → About → Version 7×; long-press a
  user → Four in a Row.
- **Verified**: `PluginDataIntegrationTest` (real server: delivery, sender stamping, receiver
  filtering); `GameControllerTest` (rules, codec, forged/illegal moves, expiry, crossed invites).
  On-device: Titan 2 (Android 16) vs Pixel 10 (Android 17) on the local test server — unlock,
  invite, accept, a full game to a win, boards identical on both. Rematch/forfeit covered by unit
  tests only (the Pixel locked mid-check).

## Output route priority
- **Settings → Audio · Output** is a reorderable priority list (up/down arrows). A fresh connect uses
  the first route whose hardware is present (default: Wired → BT HQ → BT headset → Phone); if the
  active route's hardware vanishes mid-call it walks down the same list (was: always Phone speaker —
  and it never re-applied the engine, so playback kept targeting the dead device).
- **Remember last output**: starts on the last *manually* picked route (falls back to the priority
  list if that hardware isn't present); greys out reordering.
- **Switch to Bluetooth when connected** (default on): mid-call, a newly appearing BT route moves
  output to the highest-priority BT route. Auto-reconnects keep the current route.
- Picking a route while *not* in a call no longer sets `MODE_IN_COMMUNICATION` system-wide
  (`AudioRouter.markCurrent`); AudioManager is only touched while the engine exists.
- Not yet verified on hardware (phone was locked).

## Backlog / not-yet-wired (post-M5)
- **Still visual-only settings**: master volume, priority speaker, join/leave sounds. Channel-**layout**
  selection (VoiceBar/QuickSettings) is still ephemeral (not persisted); transmission mode **is** now
  persisted (Settings-only). Per-user volume is done; a true one-tap **local mute** entry (vs. −30 dB)
  is still a quick add.
- **UDP + OCB2** voice path — ✅ done (see the section above). Remaining polish: decode ping echoes for
  a real RTT/jitter readout, and surface UDP↔TCP transitions in the UI beyond the debug overlay.
- **Server-cert TOFU on first connect** still auto-accepts/pins silently; only a *changed* cert now
  prompts. A first-connect "trust this fingerprint?" prompt is still not shown.
- **Profile editing** (display name/avatar/comment), blocked users, local mute list (designed, stubbed).
- **DNS SRV resolution** (`_mumble._tcp`) — connect is host:port only so far.
- **PermissionDenied / CodecVersion / ServerConfig** handling in `MumbleClient` (currently ignored).
- **Wired/SCO route audibility** — verify on hardware; earpiece-vs-loudspeaker toggle may be wanted.
- **32-bit ARM (`armeabi-v7a`) native libs** — `core-audio` `abiFilters` builds only `arm64-v8a` +
  `x86_64`, but AndroidX deps ship `armeabi-v7a` libs, so the APK still *installs* on 32-bit-userspace
  phones (some budget Galaxy A models) and then crashes with `UnsatisfiedLinkError` on connect
  (`libopusjni` missing; `librnnoisejni` failure is caught). Fix: add `"armeabi-v7a"` to `abiFilters`
  (larger APK) and verify Opus/RNNoise build + run on a 32-bit device. Alternative: drop the stray
  32-bit libs so such phones refuse the install instead of crashing.

## Known issues / watch-outs
- On-device UI automation is unreliable on the test phone (auto-locks to PIN, multi-display). Live
  driving needs the user to keep it unlocked.
- Integration tests need a running server on `127.0.0.1:64738` or they skip.
- The full integration suite makes >10 rapid connections and trips Mumble's brute-force auto-ban
  (default 10/120s). Run the docker test server with `-e MUMBLE_CONFIG_autobanAttempts=0`, or
  restart it between full runs.
- **Sent audio stutters sometimes: suspect "Share microphone" first** (added 2026-09-26, commit
  `5f70ca9`). Seen on the local server and by a test user, with only ~1 packet reported lost. Each
  time `MicContentionMonitor` releases or retakes the mic, the engine restarts capture *and*
  playback, which leaves a short gap in what we send. Frames never sent don't count as lost, which
  fits the low loss figure. If something on the phone opens the mic briefly and repeatedly, we'd
  cycle and stutter.
  - **Check:** turn off Settings → Share microphone. If the stutter stops, this is the cause.
  - **Evidence:** repeated `record source … initialized` lines in `adb logcat` mean capture is
    restarting.
  - **Rollback:** revert `5f70ca9`, or change the `shareMic` default in `Settings.kt` to false.
  - **Other side effects:** while another app has the mic, playback moves to the media stream, so
    its volume changes. An app that records all the time keeps us muted, and the voice bar shows
    "Mic in use by another app".

## Verification quick-reference
- Unit/integration: `./gradlew :core-protocol:test` (with the docker test server up).
- Build: `./gradlew :app:assembleDebug`; install: `adb install -r <apk>`.
- Live two-client audio: `ListenForAudioTest` (env-gated `NOTMUMLA_LISTEN=1`) receives device audio.
