package app.notmumla.game

import app.notmumla.data.UserRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FourBoardTest {
    private fun FourBoard.play(vararg cols: Int) = cols.fold(this) { b, c -> b.drop(c)!! }

    @Test fun verticalWin() {
        val b = FourBoard().play(0, 1, 0, 1, 0, 1, 0)
        assertEquals(Disc.RED, b.winner)
        assertFalse(b.canDrop(2))
    }

    @Test fun diagonalWin() {
        // Yellow builds the diagonal from (row 5, col 1) up to (row 2, col 4).
        val b = FourBoard().play(0, 1, 2, 2, 3, 3, 4, 3, 4, 4, 6, 4)
        assertEquals(Disc.YELLOW, b.winner)
    }

    @Test fun fullColumnRejected() {
        val b = FourBoard().play(0, 0, 0, 0, 0, 0)
        assertNull(b.drop(0))
        assertNull(b.drop(7))
        assertNull(b.drop(-1))
    }
}

class GameMessageTest {
    @Test fun roundTrip() {
        val msgs = listOf(
            GameMessage.Invite("0a1b2c3d"), GameMessage.Accept("0a1b2c3d"), GameMessage.Decline("0a1b2c3d"),
            GameMessage.Move("0a1b2c3d", 41, 6), GameMessage.Quit("0a1b2c3d"),
        )
        for (m in msgs) assertEquals(m, GameMessage.decode(GameMessage.encode(m)))
    }

    @Test fun rejectsMalformed() {
        val bad = listOf(
            "", "invite", "invite 0A1B2C3D", "invite 0a1b2c3", "invite 0a1b2c3d extra",
            "move 0a1b2c3d 1", "move 0a1b2c3d 1 7", "move 0a1b2c3d 42 0", "move 0a1b2c3d -1 0",
            "move 0a1b2c3d 01 0", "move 0a1b2c3d 1 +3", "launch 0a1b2c3d", "invite 0a1b2c3d\n",
            "x".repeat(200),
        )
        for (s in bad) assertNull(s, GameMessage.decode(s.toByteArray()))
    }
}

class GameControllerTest {
    private val alice = UserRef(1, "alice")
    private val bob = UserRef(2, "bob")
    private var now = 0L
    private var ids = ArrayDeque(listOf("00000001", "00000002"))
    private val names = mapOf(1 to "alice", 2 to "bob", 3 to "mallory")

    private lateinit var a: GameController
    private lateinit var b: GameController
    private var bobEnabled = true
    /** Messages each side sent, as (to, message), for asserting what went on the wire. */
    private val aSent = mutableListOf<Pair<Int, GameMessage>>()

    init {
        // Each controller's sends are delivered straight to the other, stamped with the sender's session.
        a = GameController(
            send = { to, m -> aSent += to to m; if (to == 2) b.onData(1, GameMessage.encode(m)) },
            nameOf = { names[it] }, enabled = { true }, clock = { now }, newGameId = { ids.removeFirst() },
        )
        b = GameController(
            send = { to, m -> if (to == 1) a.onData(2, GameMessage.encode(m)) },
            nameOf = { names[it] }, enabled = { bobEnabled }, clock = { now }, newGameId = { ids.removeFirst() },
        )
    }

    private fun startGame() {
        a.challenge(bob)
        b.accept()
    }

    @Test fun inviteAcceptPlayToWin() {
        startGame()
        val sa = a.state.value as GameState.Playing
        val sb = b.state.value as GameState.Playing
        assertEquals(Disc.RED, sa.me)
        assertEquals(Disc.YELLOW, sb.me)
        assertTrue(sa.myTurn)

        for (i in 0 until 3) { a.play(0); b.play(1) }
        a.play(0)
        assertEquals("You win!", (a.state.value as GameState.Ended).message)
        assertEquals("alice wins", (b.state.value as GameState.Ended).message)
    }

    @Test fun cannotPlayOutOfTurn() {
        startGame()
        b.play(3)
        assertEquals(0, (b.state.value as GameState.Playing).board.plies)
    }

    @Test fun invitesIgnoredUntilUnlocked() {
        bobEnabled = false
        a.challenge(bob)
        assertEquals(GameState.Idle, b.state.value)
        now += GameController.INVITE_TIMEOUT_MS
        a.tick()
        assertEquals("bob didn't answer", (a.state.value as GameState.Ended).message)
    }

    @Test fun decline() {
        a.challenge(bob)
        b.decline()
        assertEquals("bob declined", (a.state.value as GameState.Ended).message)
    }

    @Test fun strangerCannotMoveForPeer() {
        startGame()
        a.play(3)
        val gameId = (b.state.value as GameState.Playing).gameId
        // mallory (session 3) forges bob's move: ignored, bob's game continues untouched.
        a.onData(3, GameMessage.encode(GameMessage.Move(gameId, 1, 0)))
        val sa = a.state.value as GameState.Playing
        assertEquals(1, sa.board.plies)
        assertFalse(sa.myTurn)
    }

    @Test fun illegalMoveEndsGame() {
        startGame()
        val gameId = (b.state.value as GameState.Playing).gameId
        // bob's client claims a move while it's alice's turn.
        a.onData(2, GameMessage.encode(GameMessage.Move(gameId, 0, 0)))
        assertEquals("Game ended: boards out of sync", (a.state.value as GameState.Ended).message)
        assertTrue(b.state.value is GameState.Ended) // alice's quit reached bob
    }

    @Test fun peerLeavingEndsGame() {
        startGame()
        a.onUsersPresent(setOf(1))
        assertEquals("bob left the game", (a.state.value as GameState.Ended).message)
    }

    @Test fun lateAcceptAfterExpiryGetsQuit() {
        bobEnabled = false
        a.challenge(bob) // bob never sees it
        now += GameController.INVITE_TIMEOUT_MS
        a.tick()
        aSent.clear()
        a.onData(2, GameMessage.encode(GameMessage.Accept("00000001")))
        assertEquals(listOf(2 to GameMessage.Quit("00000001")), aSent)
        // A second, replayed accept is ignored rather than answered again.
        aSent.clear()
        a.onData(2, GameMessage.encode(GameMessage.Accept("00000001")))
        assertTrue(aSent.isEmpty())
    }

    @Test fun crossedInvitesAgree() {
        // Both challenge each other before either invite arrives: deliver them only afterwards.
        val held = mutableListOf<() -> Unit>()
        val x = arrayOfNulls<GameController>(2)
        x[0] = GameController({ _, m -> held += { x[1]!!.onData(1, GameMessage.encode(m)) } },
            { names[it] }, { true }, { now }, { "0000000a" })
        x[1] = GameController({ _, m -> held += { x[0]!!.onData(2, GameMessage.encode(m)) } },
            { names[it] }, { true }, { now }, { "0000000b" })
        x[0]!!.challenge(bob)
        x[1]!!.challenge(alice)
        while (held.isNotEmpty()) held.removeAt(0)()
        val s0 = x[0]!!.state.value as GameState.Playing
        val s1 = x[1]!!.state.value as GameState.Playing
        assertEquals("0000000a", s0.gameId)
        assertEquals(s0.gameId, s1.gameId)
        assertEquals(Disc.RED, s0.me)
        assertEquals(Disc.YELLOW, s1.me)
    }
}
