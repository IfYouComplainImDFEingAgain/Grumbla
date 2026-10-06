package app.notmumla.game

import app.notmumla.data.UserRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

sealed interface GameState {
    data object Idle : GameState
    /** We challenged [peer] and are waiting for them to accept. */
    data class Inviting(val peer: UserRef, val gameId: String, val deadline: Long) : GameState
    /** [peer] challenged us; the UI asks whether to accept. */
    data class Invited(val peer: UserRef, val gameId: String, val deadline: Long) : GameState
    data class Playing(val peer: UserRef, val gameId: String, val me: Disc, val board: FourBoard) : GameState {
        val myTurn: Boolean get() = board.toMove == me
    }
    /** Shown until dismissed; [board] is null when no game was played (declined, no answer). */
    data class Ended(val peer: UserRef, val message: String, val me: Disc? = null, val board: FourBoard? = null) : GameState
}

/**
 * Four-in-a-Row between two clients over the server's plugin-data relay (see docs/SIDE_CHANNELS.md).
 *
 * Every inbound message is untrusted: it is acted on only when it comes from the current peer's
 * session (stamped by the server, so not spoofable), names the current game, and is legal on our own
 * copy of the board. Anything else is dropped, or ends the game if the peer is the one misbehaving.
 * Invites are ignored entirely unless [enabled] — the easter egg is invisible until unlocked, and
 * strangers can't pop dialogs on people who never opted in.
 */
class GameController(
    private val send: (session: Int, message: GameMessage) -> Unit,
    private val nameOf: (session: Int) -> String?,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newGameId: () -> String = { "%08x".format(Random.nextInt()) },
) {
    private val _state = MutableStateFlow<GameState>(GameState.Idle)
    val state: StateFlow<GameState> = _state.asStateFlow()

    /** Our last invite that timed out, so a late accept gets a quit instead of a silent hang. */
    private var expiredInvite: Pair<Int, String>? = null

    private val free: Boolean
        get() = _state.value.let { it is GameState.Idle || it is GameState.Ended }

    @Synchronized
    fun challenge(peer: UserRef) {
        if (!free) return
        val id = newGameId()
        _state.value = GameState.Inviting(peer, id, clock() + INVITE_TIMEOUT_MS)
        send(peer.session, GameMessage.Invite(id))
    }

    @Synchronized
    fun accept() {
        val s = _state.value as? GameState.Invited ?: return
        send(s.peer.session, GameMessage.Accept(s.gameId))
        _state.value = GameState.Playing(s.peer, s.gameId, Disc.YELLOW, FourBoard())
    }

    @Synchronized
    fun decline() {
        val s = _state.value as? GameState.Invited ?: return
        send(s.peer.session, GameMessage.Decline(s.gameId))
        _state.value = GameState.Idle
    }

    @Synchronized
    fun play(col: Int) {
        val s = _state.value as? GameState.Playing ?: return
        if (!s.myTurn) return
        val next = s.board.drop(col) ?: return
        send(s.peer.session, GameMessage.Move(s.gameId, s.board.plies, col))
        _state.value = afterMove(s, next)
    }

    /** Leave whatever is in progress: withdraw an invite or forfeit a game. */
    @Synchronized
    fun quit() {
        when (val s = _state.value) {
            is GameState.Inviting -> send(s.peer.session, GameMessage.Quit(s.gameId))
            is GameState.Invited -> send(s.peer.session, GameMessage.Decline(s.gameId))
            is GameState.Playing -> send(s.peer.session, GameMessage.Quit(s.gameId))
            else -> Unit
        }
        _state.value = GameState.Idle
    }

    @Synchronized
    fun dismiss() {
        if (_state.value is GameState.Ended) _state.value = GameState.Idle
    }

    /** Session ids change on reconnect, so any game dies with the connection. */
    @Synchronized
    fun reset() {
        _state.value = GameState.Idle
        expiredInvite = null
    }

    /** Expire pending invites; call about once a second. */
    @Synchronized
    fun tick() {
        val now = clock()
        when (val s = _state.value) {
            is GameState.Inviting -> if (now >= s.deadline) {
                expiredInvite = s.peer.session to s.gameId
                _state.value = GameState.Ended(s.peer, "${s.peer.name} didn't answer")
            }
            is GameState.Invited -> if (now >= s.deadline) _state.value = GameState.Idle
            else -> Unit
        }
    }

    /** The server's user list changed: end anything involving a peer who left. */
    @Synchronized
    fun onUsersPresent(sessions: Set<Int>) {
        when (val s = _state.value) {
            is GameState.Inviting -> if (s.peer.session !in sessions) _state.value = GameState.Ended(s.peer, "${s.peer.name} left")
            is GameState.Invited -> if (s.peer.session !in sessions) _state.value = GameState.Idle
            is GameState.Playing -> if (s.peer.session !in sessions)
                _state.value = GameState.Ended(s.peer, "${s.peer.name} left the game", s.me, s.board)
            else -> Unit
        }
    }

    @Synchronized
    fun onData(sender: Int, data: ByteArray) {
        val msg = GameMessage.decode(data) ?: return
        val s = _state.value
        when (msg) {
            is GameMessage.Invite -> onInvite(sender, msg, s)
            is GameMessage.Accept -> when {
                s is GameState.Inviting && s.peer.session == sender && s.gameId == msg.gameId ->
                    _state.value = GameState.Playing(s.peer, s.gameId, Disc.RED, FourBoard())
                expiredInvite == (sender to msg.gameId) -> {
                    expiredInvite = null
                    send(sender, GameMessage.Quit(msg.gameId))
                }
            }
            is GameMessage.Decline ->
                if (s is GameState.Inviting && s.peer.session == sender && s.gameId == msg.gameId) {
                    _state.value = GameState.Ended(s.peer, "${s.peer.name} declined")
                }
            is GameMessage.Quit -> when {
                s is GameState.Invited && s.peer.session == sender && s.gameId == msg.gameId ->
                    _state.value = GameState.Idle
                s is GameState.Playing && s.peer.session == sender && s.gameId == msg.gameId ->
                    _state.value = GameState.Ended(s.peer, "${s.peer.name} left the game", s.me, s.board)
            }
            is GameMessage.Move -> {
                if (s !is GameState.Playing || s.peer.session != sender || s.gameId != msg.gameId) return
                val next = if (!s.myTurn && msg.ply == s.board.plies) s.board.drop(msg.col) else null
                if (next == null) {
                    // Out of turn, out of sync or illegal: we can't continue from a board we disagree on.
                    send(sender, GameMessage.Quit(s.gameId))
                    _state.value = GameState.Ended(s.peer, "Game ended: boards out of sync", s.me, s.board)
                    return
                }
                _state.value = afterMove(s, next)
            }
        }
    }

    private fun onInvite(sender: Int, msg: GameMessage.Invite, s: GameState) {
        if (!enabled()) return
        val name = nameOf(sender) ?: return
        val peer = UserRef(sender, name)
        when {
            // Busy: ignore rather than answer, so a flood of invites can't make us flood back.
            free -> _state.value = GameState.Invited(peer, msg.gameId, clock() + INVITED_TIMEOUT_MS)
            // Crossed invites: both sides keep the invite with the lower id, so they agree who is red.
            s is GameState.Inviting && s.peer.session == sender && msg.gameId < s.gameId -> {
                send(sender, GameMessage.Accept(msg.gameId))
                _state.value = GameState.Playing(peer, msg.gameId, Disc.YELLOW, FourBoard())
            }
        }
    }

    private fun afterMove(s: GameState.Playing, next: FourBoard): GameState = when {
        next.winner == s.me -> GameState.Ended(s.peer, "You win!", s.me, next)
        next.winner != null -> GameState.Ended(s.peer, "${s.peer.name} wins", s.me, next)
        next.isDraw -> GameState.Ended(s.peer, "Draw", s.me, next)
        else -> s.copy(board = next)
    }

    companion object {
        const val INVITE_TIMEOUT_MS = 45_000L
        /** Shorter than the inviter's wait, so an accept normally lands before they give up. */
        const val INVITED_TIMEOUT_MS = 40_000L
    }
}
