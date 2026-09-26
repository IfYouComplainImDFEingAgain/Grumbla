# Plan: other apps' audio sounds bad while connected (Pixel 7 / GrapheneOS)

Status: **triage pending**. Don't implement until the reporter's answers (below) confirm the cause.

## Report
- A user on a **Pixel 7 (Tensor G2), GrapheneOS** gets "sound issues" when another app plays audio
  while Grumbla is connected.
- The same scenario on a **Pixel 10 (Tensor G5), GrapheneOS** is fine.
- Still unknown: which audio sounds wrong, what it sounds like, and which output route is active.

## Hypothesis: call mode held for the whole session
`AudioRouter.select()` sets `am.mode = MODE_IN_COMMUNICATION` (and a communication device) for the
`PHONE_SPEAKER`, `WIRED` and `BT_HEADSET_SCO` routes. It stays set for the entire connected session,
not just while someone is talking (see `AudioRouter.configFor`, `SessionManager.applyRoute`). Only
`BT_A2DP_HQ` uses `MODE_NORMAL`.

To Android, another app playing during that time is playing **during a call**. What that does is
decided by the audio HAL/policy (GrapheneOS uses the stock Pixel vendor audio stack, so G2 vs G5
differences are plausible):
1. **Other apps' audio goes through the call path.** It can get voice processing (call EQ/limiter,
   AEC reference), a sample-rate change, ducking, or be moved to the comm device. Symptoms: tinny,
   muffled, "underwater", pumping volume, wrong speaker.
2. **Our capture fights it.** `VOICE_COMMUNICATION` capture runs native AEC, which treats the other
   app's audio as echo, so our outgoing voice pumps or gates, or the music leaks into the channel.

Mumla behaves the same way (same mode/source), so this is probably not a regression.

**Falsifier:** if the problem also happens on `BT_A2DP_HQ` (MODE_NORMAL), this hypothesis is wrong.
Look at playback underruns instead (see "Alternative cause" below).

## Triage: ask the reporter
1. Which audio sounds wrong: the other app's, Mumble voices, or what others hear from them?
2. What does it sound like: quieter, muffled/tinny, crackling, dropouts, or the earpiece instead of
   the loudspeaker?
3. Which route: phone speaker, wired, BT headset (SCO), or BT A2DP HQ? Does switching to A2DP HQ fix it?
4. Does it stop immediately after disconnecting?
5. While it's happening:
   ```sh
   adb shell dumpsys audio > audio.txt
   adb shell dumpsys media.audio_policy > policy.txt
   ```
   In `audio.txt`, check the mode (`MODE_IN_COMMUNICATION`), the focus stack and the communication
   device. In `policy.txt`, check which output/profile the other app's `USAGE_MEDIA` track is attached
   to (a voice/`primary` output with call processing, vs the normal `deep_buffer` output), and its
   sample rate.

Decision:
- Confirmed call-mode cause (answer 3 says only the MODE_IN_COMMUNICATION routes, answer 4 says yes,
  the dump shows media on the voice path) → implement **Fix A**.
- Mumble voices crackle or drop out on any route → **Alternative cause**.

## Fix A (recommended): opt-in "Media-friendly audio" setting
Per-device opt-in toggle, **default off**. When on, the `PHONE_SPEAKER` and `WIRED` routes run like
`BT_A2DP_HQ`: `MODE_NORMAL`, no communication device, `USAGE_MEDIA` playback. `BT_HEADSET_SCO` is
unchanged, since SCO requires call mode.

**Why not the default:** this is essentially the old MODE_NORMAL approach, dropped because it was
quiet and then distorted (CLAUDE.md gotcha #5). Call mode stays the default. The toggle trades some
voice quality for better coexistence with other apps on devices like the Pixel 7.

### Steps
1. **Setting.** Add `mediaFriendlyAudio: Boolean = false` to `AppSettings` in
   `app/src/main/java/app/notmumla/data/Settings.kt` (key `MEDIA_FRIENDLY_AUDIO`, reader, `suspend fun
   setMediaFriendlyAudio`), following the `rawMic` pattern exactly.
2. **Router.** Give `AudioRouter` a `var mediaFriendly: Boolean` (or pass it into `configFor`). When it's
   true, `PHONE_SPEAKER` and `WIRED` return:
   - `audioMode = MODE_NORMAL`
   - `communicationDeviceId = null` (so `select()` calls `clearCommunicationDevice()`)
   - `trackUsage = USAGE_MEDIA`
   - `trackDeviceId` = the builtin speaker (`TYPE_BUILTIN_SPEAKER`) or the wired device, so playback
     never lands on the earpiece
   - `recordSource = MediaRecorder.AudioSource.MIC` with `recordDeviceId` = the builtin mic (phone) or
     `null` (wired headset mic)
3. **Capture effects.** `AudioEngine.captureLoop` already attaches our own NS/AEC/AGC only for
   non-`VOICE_COMMUNICATION` sources (`rawSource`), so the MIC source gets them automatically. Check
   that the raw-mic/RNNoise source override (`wantRawSource` → `VOICE_RECOGNITION`) still only
   replaces `VOICE_COMMUNICATION` and doesn't break MIC. **Do not** stack effects on
   VOICE_COMMUNICATION (gotcha #5).
4. **Wiring.** In `SessionManager`, push `settings.mediaFriendlyAudio` into the router. When it changes
   mid-call, re-run `applyRoute(router.current.value)` so the mode and engine restart take effect (same
   path as route changes, on `Dispatchers.Main`). `reset()` on disconnect is unchanged.
5. **UI.** Add a toggle in `ui/settings/SettingsScreen.kt` next to "Raw mic", wired through
   `SettingsViewModel` and `MainActivity` like `onToggleRawMic`. Label it "Media-friendly audio", with
   the subtitle "Better when other apps play sound; voice may be quieter. Not used for Bluetooth
   headset calls."
6. **Docs.** Add a line to `docs/PROGRESS.md`. If it ships, add a gotcha to CLAUDE.md saying this
   toggle is the *only* sanctioned MODE_NORMAL path for phone/wired.

### Loudness risk
MODE_NORMAL + USAGE_MEDIA playback follows the **media** volume stream, not the call stream, and
isn't hardware-leveled. Check that voices are loud enough at the same volume step. The
per-speaker leveling and output limiter in the mixer should help. If playback is still too quiet,
consider `CONTENT_TYPE_MUSIC` vs `SPEECH` (some HALs apply speech-specific processing), but measure
before changing it.

## Rejected: call mode only while voice is flowing
Toggling between MODE_IN_COMMUNICATION and MODE_NORMAL around speech re-routes on every switch. That
causes pops, gaps and route flapping, and would likely clip the first syllable of every spurt. Don't do
this.

## Alternative cause: our own playback underruns
If the problem is **Mumble voices** crackling or dropping out while another app plays (on any route),
the likely cause is AudioTrack underruns. Other apps' load plus a shared mixer can starve our track.
`AudioEngine.playbackLoop` uses `minBuf.coerceAtLeast(frame * 2 * 4)`, which is only about 4 frames.
- Fix: raise the floor (e.g. 8 frames). Optionally, log `track.underrunCount` in the debug overlay to
  confirm before and after.
- Cost: slightly more output latency.

## Test plan
- **Pixel 10:** run the existing default on all routes with music playing in another app (e.g. YouTube)
  to confirm nothing regresses. Toggle media-friendly on/off mid-call and confirm the route restarts
  cleanly, with no earpiece output and the mic still captured.
- **Reporter's Pixel 7:** send a debug/release APK with the toggle and ask for an A/B of the same
  scenario with the toggle off and on.
- **Screen off with the toggle on:** confirm that capture continues (FGS microphone type, gotcha #4).
- **Bluetooth:** confirm that `BT_HEADSET_SCO` still enters call mode with the toggle on, and that
  `BT_A2DP_HQ` is unaffected.
- `./gradlew :app:assembleDebug --no-daemon` builds.
