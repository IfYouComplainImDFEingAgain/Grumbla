package app.notmumla.game.tank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TankMessageTest {
    private fun enc(m: TankMessage) = TankMessage.encode(m)
    private fun dec(s: String) = TankMessage.decode(s.toByteArray())

    @Test fun stateRoundTrips() {
        val m = TankMessage.State(42, true, Pose(-12.3, 99.9, 1.5, -6.0, -0.7), 7, 3, 2)
        val d = TankMessage.decode(enc(m)) as TankMessage.State
        assertEquals(42, d.seq)
        assertTrue(d.alive)
        assertEquals(-12.3, d.pose.x, 0.05)
        assertEquals(99.9, d.pose.z, 0.05)
        assertEquals(1.5, d.pose.h, 0.002)
        assertEquals(-6.0, d.pose.v, 0.05)
        assertEquals(-0.7, d.pose.w, 0.002)
        assertEquals(listOf(7, 3, 2), listOf(d.shots, d.kills, d.deaths))
        assertTrue(enc(m).size < 64)
    }

    @Test fun headingWrapsToRange() {
        val d = TankMessage.decode(enc(TankMessage.State(1, true, Pose(0.0, 0.0, 2 * PI - 1e-6), 0, 0, 0))) as TankMessage.State
        assertTrue(d.pose.h in 0.0..(2 * PI))
    }

    @Test fun simpleMessages() {
        assertEquals(TankMessage.Hello, dec("hi"))
        assertEquals(TankMessage.Bye, dec("bye"))
        assertEquals(TankMessage.Hit(12, 5), dec("hit 12 5"))
    }

    @Test fun rejectsAnythingOffShape() {
        for (bad in listOf(
            "", "hi ", "hi x", "HI", "hit 1", "hit -1 2", "hit 01 2", "hit 1 2 3",
            "s 1 1 0 0 0 0 0 0 0", // too few fields
            "s 1 2 0 0 0 0 0 0 0 0", // alive not 0/1
            "s 1 1 1001 0 0 0 0 0 0 0", // outside the arena
            "s 1 1 0 0 3600 0 0 0 0 0", // heading out of range
            "s 1 1 0 0 0 999 0 0 0 0", // faster than a tank
            "s 1 1 -0 0 0 0 0 0 0 0", "s 1 1 +5 0 0 0 0 0 0 0", "s 1 1 0  0 0 0 0 0 0",
            "s 9999999999 1 0 0 0 0 0 0 0 0",
        )) assertNull(bad, dec(bad))
        assertNull(TankMessage.decode(byteArrayOf('h'.code.toByte(), 0, 'i'.code.toByte())))
        assertNull(TankMessage.decode(ByteArray(200) { 'h'.code.toByte() }))
    }
}

class PoseTest {
    @Test fun straightLine() {
        val p = Pose(0.0, 0.0, 0.0, 10.0, 0.0).extrapolate(1.0)
        assertEquals(0.0, p.x, 1e-9)
        assertEquals(10.0, p.z, 1e-9)
    }

    @Test fun arcMatchesStepIntegration() {
        val start = Pose(0.0, 0.0, 0.3, 10.0, 1.0)
        var x = 0.0; var z = 0.0; var h = 0.3
        val dt = 1e-4
        repeat(10_000) { h += dt; x += sin(h) * 10 * dt; z += cos(h) * 10 * dt }
        val p = start.extrapolate(1.0)
        assertEquals(x, p.x, 0.01)
        assertEquals(z, p.z, 0.01)
    }

    @Test fun staysInsideTheArena() {
        val p = Pose(95.0, 0.0, PI / 2, 12.0, 0.0).extrapolate(1.0)
        assertTrue(p.x <= TankWorld.HALF)
    }
}

class TankArenaTest {
    private var now = 1_000_000L
    private val sent = mutableListOf<Pair<List<Int>, TankMessage>>()
    private var members = mapOf(2 to "bob", 3 to "carol")
    private val invites = mutableListOf<String>()
    private var unlocked = true

    private val arena = TankArena(
        send = { to, m -> sent += to to m },
        self = { 1 },
        channelPeers = { members },
        enabled = { unlocked },
        onInvite = { invites += it },
        clock = { now },
        random = Random(7),
    )

    private fun data(m: TankMessage) = TankMessage.encode(m)
    private fun tick(ms: Long = 16, input: TankInput = TankInput()) = run { now += ms; arena.step(input)!! }

    @Test fun joinGreetsTheChannel() {
        arena.join()
        assertEquals(listOf(2, 3) to TankMessage.Hello, sent.single())
    }

    @Test fun helloWhileClosedInvitesOnceAndOnlyIfUnlocked() {
        arena.onData(2, data(TankMessage.Hello))
        arena.onData(2, data(TankMessage.Hello))
        assertEquals(listOf("bob"), invites)
        now += TankArena.INVITE_REPEAT_MS
        arena.onData(2, data(TankMessage.Hello))
        assertEquals(2, invites.size)
        unlocked = false
        arena.onData(3, data(TankMessage.Hello))
        assertEquals(2, invites.size)
        assertTrue("never answers while closed", sent.isEmpty())
    }

    @Test fun ignoresUsersOutsideOurChannel() {
        arena.join()
        arena.onData(9, data(TankMessage.State(1, true, Pose(0.0, 10.0, 0.0), 0, 0, 0)))
        assertTrue(tick().tanks.isEmpty())
        arena.onData(2, data(TankMessage.State(1, true, Pose(0.0, 10.0, 0.0), 0, 0, 0)))
        assertEquals(listOf("bob"), tick().tanks.map { it.name })
    }

    @Test fun staleOrReplayedStatesAreDropped() {
        arena.join()
        arena.onData(2, data(TankMessage.State(5, true, Pose(10.0, 10.0, 0.0), 0, 0, 0)))
        arena.onData(2, data(TankMessage.State(4, true, Pose(-50.0, -50.0, 0.0), 0, 0, 0)))
        assertEquals(10.0, tick().tanks.single().pose.x, 0.5)
    }

    @Test fun peerTimesOut() {
        arena.join()
        arena.onData(2, data(TankMessage.State(1, true, Pose(0.0, 10.0, 0.0), 0, 0, 0)))
        now += TankArena.PEER_TIMEOUT_MS + 1
        assertTrue(tick().tanks.isEmpty())
    }

    /** Bob sits 12 m in front of us, facing us, and fires. */
    private fun bobShootsUs(): Pose {
        arena.join()
        val me = tick().me
        val bob = Pose(me.x + sin(me.h) * 12, me.z + cos(me.h) * 12, TankWorld.wrap(me.h + PI))
        arena.onData(2, data(TankMessage.State(1, true, bob, 0, 0, 0)))
        arena.onData(2, data(TankMessage.State(2, true, bob, 1, 0, 0)))
        return me
    }

    @Test fun theVictimDecidesAHit() {
        bobShootsUs()
        sent.clear()
        repeat(30) { tick() }
        val v = tick()
        assertFalse(v.alive)
        assertEquals("bob", v.killedBy)
        val hit = sent.first { it.second is TankMessage.Hit }
        assertEquals(TankMessage.Hit(2, 1), hit.second)
        assertEquals(listOf(2), hit.first)
        // Back after the respawn delay.
        now += TankWorld.RESPAWN_MS
        assertTrue(tick().alive)
    }

    @Test fun killsCountOnlyForShellsWeFiredAndOnlyOnce() {
        arena.join()
        arena.onData(2, data(TankMessage.State(1, true, Pose(50.0, 50.0, 0.0), 0, 0, 0)))
        arena.onData(2, data(TankMessage.Hit(1, 1)))
        assertEquals(0, tick().scores.first { it.me }.kills) // we never fired
        tick(TankWorld.RELOAD_MS, TankInput(fire = true))
        arena.onData(2, data(TankMessage.Hit(1, 1)))
        arena.onData(2, data(TankMessage.Hit(1, 1)))
        assertEquals(1, tick().scores.first { it.me }.kills)
    }

    @Test fun staysUnderTheServerRateLimit() {
        arena.join()
        arena.onData(2, data(TankMessage.State(1, true, Pose(80.0, 80.0, 0.0), 0, 0, 0)))
        sent.clear()
        var seq = 2
        // Drive in circles and hold fire for 20 s while bob keeps saying hello (forcing replies).
        repeat(1250) {
            if (it % 10 == 0) {
                arena.onData(2, data(TankMessage.Hello))
                arena.onData(2, data(TankMessage.State(seq++, true, Pose(80.0, 80.0, 0.0), 0, 0, 0)))
            }
            tick(16, TankInput(throttle = 1f, turn = 1f, fire = true))
        }
        assertTrue("${sent.size} messages in 20 s", sent.size <= 4 * 20)
        assertTrue("still sends regularly", sent.size >= 3 * 20)
    }

    @Test fun leavingSaysBye() {
        arena.join()
        arena.onData(2, data(TankMessage.State(1, true, Pose(0.0, 10.0, 0.0), 0, 0, 0)))
        sent.clear()
        arena.leave()
        assertEquals(listOf(2) to TankMessage.Bye, sent.single())
        assertNull(arena.step(TankInput()))
        assertFalse(arena.active.value)
    }

    @Test fun movingChannelsForgetsPlayersAndGreetsTheNewOne() {
        arena.join()
        arena.onUsersPresent(10)
        arena.onData(2, data(TankMessage.State(1, true, Pose(0.0, 10.0, 0.0), 0, 0, 0)))
        sent.clear()
        members = mapOf(4 to "dave")
        arena.onUsersPresent(11)
        assertTrue(tick().tanks.isEmpty())
        assertEquals(listOf(4) to TankMessage.Hello, sent.first())
    }

    @Test fun driveSlidesInsteadOfEnteringABlock() {
        arena.join()
        repeat(2000) {
            val v = tick(16, TankInput(throttle = 1f, turn = if (it % 300 < 50) 1f else 0f))
            assertFalse(TankWorld.insideBlock(v.me.x, v.me.z))
            assertTrue(TankWorld.inArena(v.me.x, v.me.z))
        }
        assertNotNull(arena.step(TankInput()))
        assertTrue(abs(TankArena.angleDiff(0.1, 2 * PI - 0.1) - 0.2) < 1e-9)
    }
}
