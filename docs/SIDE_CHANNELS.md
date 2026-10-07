# Client-to-client side channels

A stock Mumble server (no plugins, no config changes) relays three things that custom clients can
use to talk to each other. Official clients ignore what they don't understand, so these channels
are invisible to everyone not running not-mumla. All three were checked against the reference
server in `~/git/mumble/src/murmur/`.

| Channel | Carries | Reaches | Limits |
| --- | --- | --- | --- |
| `PluginDataTransmission` | Arbitrary bytes + a string id | Any listed sessions, server-wide | 1000 B data, 100 B id, ~4 msg/s |
| `positional_data` in voice packets | 3 floats per audio frame | Listeners with the same `plugin_context` | Only while talking |
| `UserState.comment` / `texture` | A blob stored per user | Everyone on the server | `textmessagelength` / `imagemessagelength` |

## 1. `PluginDataTransmission`: a client-to-client data pipe

TCP message type 26 (`Mumble.proto`: `senderSession`, `receiverSessions`, `data`, `dataID`). The
server handles it in `Server::msgPluginDataTransmission` (`src/murmur/Messages.cpp:2501`):

- **The sender can't be spoofed.** The server overwrites `senderSession` with the real session
  before forwarding, so a receiver can trust *who* sent a message (not *what* it says).
- **No ACL or channel check.** Any user can send to any session on the server, in any channel.
  Duplicate receivers are removed; unknown sessions are skipped.
- **Limits.** `data` ≤ 1000 bytes and `dataID` ≤ 100 bytes (`src/MumbleConstants.h`), else the
  message is dropped. Messages without `data` or `dataID` are dropped too.
- **Rate limit.** Per-user token bucket: `pluginmessagelimit` = 4/s, `pluginmessageburst` = 15
  (`src/murmur/Meta.cpp`). Excess messages are **dropped silently** (only a server log line), so
  protocols must tolerate loss or stay well under the limit.
- **Namespacing.** Receivers decide by `dataID` whether to act. We use `notmumla/<feature>/<version>`
  so other clients' plugins ignore ours and an incompatible change can bump the version.

In code: `MumbleClient.sendPluginData(receivers, dataId, data)` sends one;
`MumbleClient.Event.PluginData(sender, dataId, data)` delivers inbound ones.
`PluginDataIntegrationTest` checks delivery, sender stamping and receiver filtering against a real
server.

## 2. Positional audio with a private `plugin_context`

`MumbleUDP.Audio` carries `repeated float positional_data` (x, y, z) beside the Opus frame. The
server forwards it only when sender and receiver have the **same `plugin_context`**
(`src/murmur/AudioReceiverBuffer.cpp:64`). For everyone else, the frame arrives without it.

`plugin_context` is arbitrary bytes set via `UserState`. If our clients set one of their own (say
`notmumla-geo`), they get a private position side channel on top of normal voice. For example,
GPS mapped to local metres gives proximity and direction-aware voice. Not implemented yet. The
legacy (pre-1.5) packet format carries positions too, as trailing floats.

## 3. `UserState.comment` / `texture`

Per-user blobs the server stores and sends to everyone (`texture` is fetched on demand via
`RequestBlob`). A client can put a small machine-readable record there: a status, capabilities, a
public key. Limits come from `textmessagelength` / `imagemessagelength`. Other clients **do**
display the comment, so anything we put there must still read sensibly as text.

## The easter egg: Four in a Row

A two-player Connect-Four over channel 1.

**Unlock:** Settings → About → tap **Version** 7 times. Tapping it 7 more times hides the game again.
The flag is persisted (`AppSettings.gamesUnlocked`).

**Play:** long-press another user → **Four in a Row**. They get a prompt (if they also run
not-mumla with the game unlocked). The challenger plays red and moves first. Tap a column to drop.

**Wire protocol** — `dataID = notmumla/four/1`, data is one line of ASCII:

| Message | Meaning |
| --- | --- |
| `invite <id>` | Challenge; sender will be red |
| `accept <id>` | Start the game |
| `decline <id>` | Refuse (sent only on an explicit tap) |
| `move <id> <ply> <col>` | Drop into column `col` (0–6) as move number `ply` (0-based) |
| `quit <id>` | Forfeit, withdraw, or "your move broke the game" |

`<id>` is 8 lowercase hex chars chosen by the challenger.

**Rules both sides enforce:**

- Each side keeps its own board and replays the peer's moves. A move is applied only if it comes
  from the current opponent's session, names the current game, is the opponent's turn, has the
  expected `ply`, and is legal. Otherwise the game ends ("boards out of sync") and a `quit` is sent.
- Invites expire: the challenger waits 45 s, the prompt closes after 40 s (so an accept normally
  lands before the challenger gives up). A late `accept` for our last expired invite gets one `quit`.
- **Crossed invites** (both challenge each other at once): both keep the invite with the lower id,
  so they agree on who is red without another round trip.
- A peer leaving the server, or our own disconnect, ends the game (session ids don't survive a
  reconnect).

**Code:** `app/.../game/` — `FourBoard` (rules), `GameMessage` (codec), `GameController` (state
machine); `ui/game/FourInARowDialog.kt` (UI); wired in `SessionManager.game`. Tests:
`app/src/test/.../game/GameControllerTest.kt`.

## Tank arena

A Battlezone-style free-for-all: green wireframe tanks, pyramids and cubes on a 200 m square plain,
mountains on the horizon. Everyone in **your channel** who opens it is in the same arena, and
voice keeps working while you play.

**Play:** with the games unlocked, long-press your current channel → **Tank arena**. The stick
(bottom left) drives and turns; **FIRE** shoots (1 s reload); **TALK** appears if you use
push-to-talk. Channel mates who have the games unlocked get a chat line when someone opens it.

**Why it fits the relay.** The server's limit is per *message*, not per receiver: one state update
listing every player as a receiver costs one token. So each client spends the same ~3 msg/s whether
there are 2 players or 15. Between updates everyone dead-reckons the others (constant speed + turn
rate along an arc) and eases out the correction when the next update lands. Over TCP at 3 Hz this
looks smooth for tanks; it would not for a twitch shooter.

**Wire protocol** — `dataID = notmumla/tank/1`, one line of ASCII, sent to all current players:

| Message | Meaning |
| --- | --- |
| `hi` | I opened the arena (sent to everyone in the channel; repeated to non-players every 15 s) |
| `bye` | I left |
| `s <seq> <alive> <x> <z> <h> <v> <w> <shots> <kills> <deaths>` | My tank: position in dm, heading in 0.1°, speed in dm/s, turn rate in 0.1°/s |
| `hit <shooter> <shot>` | Shell number `<shot>` from session `<shooter>` destroyed me |

A shell is not a message of its own: when `shots` goes up, a shell left the muzzle at that state's
pose, and everyone simulates it from there.

**Rules:**

- **No host; the victim decides.** Each client checks shells against its own tank only and
  announces its death. A cheater can make itself invincible, but it can't kill anyone, and nothing
  it sends does more than move a tank on our screen. The shooter counts a kill only for a shell it
  actually fired, once per victim.
- **Channel-scoped.** Messages count only from users in our current channel. Moving channels drops
  every player and greets the new channel.
- **Opt-in.** While the arena is closed, everything but `hi` is ignored, and a `hi` produces at
  most one chat line per sender every 2 minutes (only with the games unlocked). Nothing is ever sent
  back.
- **Rate.** Our own token bucket (3.5/s, burst 8) sits under the server's (4/s, burst 15), because
  the server drops excess messages silently. States go out every 333 ms while moving and every 1 s
  while still; a shot uses the next token or doesn't fire. Peers silent for 6 s are dropped.
- **Bounds.** At most 15 other players and 4 live shells per player; all numbers are range-checked
  (positions inside the arena, speeds within a tank's limits).

**Code:** `app/.../game/tank/`: `TankWorld` (map, physics, `Pose.extrapolate`), `TankMessage`
(codec), `TankArena` (simulation and networking); `ui/game/TankScreen.kt` (Canvas renderer and
controls); wired in `SessionManager.tanks`. Tests: `app/src/test/.../game/tank/TankTest.kt`.
Joining, invites, peer timeouts and the token bucket live in the shared base
`game/arena/ChannelArena.kt`, which the dogfight uses too.

## Dogfight

A free-for-all in the style of early-90s console space shooters: flat-shaded polygon fighters,
towers and rows of arches over a 700 m square field, chase camera behind your ship. Same channel
rules as the tank arena (it's built on the same `ChannelArena` base); opening one game leaves the
other.

**Play:** long-press your current channel → **Dogfight**. The stick steers (yaw and pitch; the
**Y** button toggles flight-style "pull back to climb" and arcade-style); **FIRE** holds for rapid
twin lasers; **BOOST**/**BRAKE** share a meter; **ROLL** barrel-rolls, and bolts pass through you
mid-roll. Shields take 20 per bolt (five hits) and only refill on respawn; hitting a building is fatal.
Below 14 m a ground cushion pushes ships up (up to 32 m/s of climb at the ground), so you can skim
and hover low; a dive that still reaches the ground costs shield in proportion to how hard it hit
(the hardest, boosted and nose fully down, costs 29, so a full shield takes three), and bounces you
off. The cushion is part of `Pose3`'s integration, so peers dead-reckon it too. Flying past 350 m hands control to an autopilot that turns you back.
Enemies off screen show as arrows at the edge.

**Sound:** lasers, hits, taking damage, explosions and a looping afterburner roar while you
boost, synthesized at startup (`FlightSounds`, no
audio assets) and played through a `SoundPool` with game usage. Other players' lasers and
explosions fade with distance and pan by direction. Silent while self-deafened; the HUD **SFX**
button mutes it. With voice activation on speakerphone the effects can reach the mic if the phone's
echo cancellation misses them; PTT or a headset avoids that.

**Wire protocol** — `dataID = notmumla/flight/1`:

| Message | Meaning |
| --- | --- |
| `hi`, `bye` | As in the tank arena |
| `s <seq> <alive> <x> <y> <z> <h> <p> <v> <w> <q> <fx> <shots> <kills> <deaths>` | My ship: position in dm, heading and pitch in 0.1°, speed in dm/s, yaw and pitch rates in 0.1°/s, `fx` bits 1 = trigger held, 2 = boosting, 4 = rolling |
| `hit <shooter> <shot>` | Bolt `<shot>` from `<shooter>` finished me; `<shooter>` = my own session means I crashed with nobody to credit |

**Lasers cost no messages.** At ~6 bolts/s, a message per shot would blow the 4/s budget. Instead
the state carries "trigger held", sent the moment it changes, and every client fires that ship's
bolts itself at the fixed rate from where it draws the ship. A tap that starts and ends between two
states shows up as the shot counter moving, and is replayed then. Bolts fired for someone after they
let go (we hear it up to ~333 ms late) can still kill, so a kill is credited for shot numbers up to
8 past the shooter's own count.

**Rules beyond the tank arena's:** the victim keeps its own shield and decides its own death, as
before; a crash within 5 s of being hit credits whoever hit last. Dead reckoning integrates speed,
yaw and pitch rate in 50 ms steps (the same code flies our own ship) and eases corrections over
200 ms; hit spheres are a generous 3.6 m to absorb the prediction error. At most 12 live bolts per
ship.

**Code:** `app/.../game/flight/`: `FlightWorld` (map, `Pose3`, `Bolt`), `FlightMessage`,
`FlightArena`; `ui/game/FlightScreen.kt` (flat-shaded Canvas renderer, HUD) and
`ui/game/ArenaControls.kt` (stick and buttons, shared with the tanks); wired in
`SessionManager.flights`. Tests: `app/src/test/.../game/flight/FlightTest.kt`.

## Nudge

Long-press a user → **Nudge**. If they run not-mumla with **Allow nudges** on (Settings, default
on), their screen shakes, the phone buzzes for 40 ms and plays a short synthesized "boing", and a
"<name> nudged you" line lands in chat.

- **Wire:** `dataID = notmumla/nudge/1`, data is exactly `nudge`. Anything else is dropped.
- **Receive throttle** (`NudgeLimiter`): one per sender per 10 s, and one from anyone per 3 s.
  Extra nudges are dropped, not queued, so spam can't turn the phone into a siren.
- **Send cooldown:** 3 s between our own nudges (the receiver would drop them anyway).
- **Ringer switch:** silent = shake only; vibrate = shake + buzz; normal = all three. No sound
  while self-deafened. The buzz uses notification vibration usage, so turning off touch haptics
  doesn't silence it.
- **Code:** `app/.../nudge/` (`NudgeMessage`, `NudgeLimiter`, `NudgeEffects`); wired in
  `SessionManager.nudge` / `onNudge`; the shake is a `graphicsLayer` offset in `ChannelsScreen`.

## Security and privacy

- **Not private from the server.** The client→server link is TLS, but the server decrypts and
  re-sends every message. Anything sensitive needs end-to-end encryption on top (for example, keys
  published via the comment or derived from each user's certificate).
- **Every inbound message is hostile until checked.** Because there's no ACL, any user on the server
  can send our client plugin data. Rules we follow:
  - Strict parsing: a fixed shape per message, bounded numbers, size cap, printable ASCII only.
    Anything else is dropped, never thrown.
  - Act only on messages from the session we're already dealing with. The server's sender stamp
    is the only trustworthy field.
  - Nothing happens without consent. An invite shows a prompt; it never starts a game, switches
    channels, or reveals anything on its own.
  - Opt-in surface: with the game locked, invites are ignored entirely, so strangers can't pop
    dialogs on people who never enabled it.
  - Never amplify. A busy client ignores invites instead of replying, so a flood of invites can't
    make us flood back.
- **Positions are location data.** Any feature built on channel 2 must be opt-in, show clearly when
  it's active, and prefer relative coordinates over raw latitude/longitude.
