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
