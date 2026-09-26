# Experiment: RNNoise lets eating noises through (transient leak)

Status: **proposed**. Diagnosed from code; not yet reproduced on-device.

## Report

A user with AI noise suppression (RNNoise) at **100% strength** says listeners hear them eating at the
table: plate/cutlery clinks, crunching. They say Mumla doesn't transmit these sounds.

## Hypothesis

The noise gets through because of three stages that add up. The strength setting isn't the cause:

1. **RNNoise passes transients.** It suppresses stationary noise (fans, hum, room tone) well.
   Short broadband impulses like clinks, crunches and a cup set down mostly get through, even fully
   wet (`mix = 1.0`). Raising strength can't help, because 100% is already full RNNoise.
2. **Makeup gain amplifies the residue.** With RNNoise on we capture from the raw
   `VOICE_RECOGNITION` source (no hardware AGC). `Denoiser.level()` then applies up to **+28 dB**
   (`LEVEL_MAX_GAIN = 25`). The gain *estimate* is speech-gated (`vad > LEVEL_VAD`). The gain
   *application* is not: every frame is multiplied, including non-speech frames
   (`Denoiser.kt`, `level()`). A faint leaked crunch comes out loud.
3. **The VAD is energy-only, and its threshold collapses under RNNoise.** `AdaptiveVad` gates on RMS
   alone. In auto-sensitivity mode the threshold is based on the noise floor (≈ floor + 9 dB when no
   clear speech has been seen). RNNoise drives the floor to near-silence, so the threshold sinks
   toward its `0.0008` minimum, and any amplified transient opens it. RNNoise returns a per-frame
   speech probability, but `AudioEngine.captureLoop()` discards it
   (`denoiser?.let { ...; it.process(pcm) }`).

### Why Mumla doesn't show it

Mumla has no RNNoise. It captures with `VOICE_COMMUNICATION` (native NS/AGC) and uses a fixed,
user-set amplitude threshold. A quiet crunch below that threshold never transmits, so nothing
amplifies it first.

## Workarounds (no code change)

- Turn **Auto sensitivity off** and set a manual VAD threshold.
- Turn **AI noise suppression off**. This gives the native comms processing, the same path Mumla uses.
- Use **push-to-talk**.

## Proposed changes

### A. Gate transmission on RNNoise speech probability (primary fix)

When AI noise suppression is active, VAD opens only if the frame passes the energy VAD **and** RNNoise
reports likely speech:

- Smooth the probability, e.g. an attack-fast/release-slow envelope, or the max over the last few frames.
- Open when the smoothed probability is `> P_OPEN` (start ~0.6).
- Add a hangover (~200–300 ms) after the last speech-probable frame so word tails aren't chopped.
- Keep the existing pre-roll (`VAD_PREROLL_FRAMES`). RNNoise's VAD lags onsets by a frame or two,
  and the pre-roll recovers them.
- Plumb it through `Denoiser.process()` → `AudioEngine.shouldTransmit(level, speechProb)`.
  PTT/continuous modes are unaffected.

### B. Don't apply makeup gain to non-speech frames

In `Denoiser.level()`, ramp the applied gain toward 1× when `vad` is low, and back to `levelGain`
when speech resumes. Use smooth ramps to avoid pumping. Leaked noise then stays at its original
level instead of being boosted up to 25×. This is still worth doing even with A, because it also
covers audio sent during the hangover.

### Open question

Always on with RNNoise, or a toggle ("Speech-only gate", default on)? Lean toward always on, and add
a toggle only if testing shows it clips quiet talkers.

## Test plan

Device: Titan 2, test server per CLAUDE.md, second client listening (desktop Mumble). Settings:
VAD + auto sensitivity, AI NS on, strength 100%.

| Case | Baseline (expect) | After A+B (want) |
|---|---|---|
| Fork tapping plate, no speech | transmits bursts | stays closed |
| Crunching (chips) near phone | transmits | stays closed / rare short opens |
| Cup set down on table | transmits | stays closed |
| Normal speech | clean | clean, onsets not clipped |
| Quiet/soft speech at arm's length | transmits | still transmits (watch for clipping) |
| Speech while eating noises continue | noise audible between words | noise gated between phrases |

Measure:
- Talk-indicator/transmit state on both ends.
- `docker logs` for spurt count.
- Record the listener side and compare.

Tune `P_OPEN` and the hangover until the quiet-speech row passes without regressing the noise rows.

## Result

_TBD._
