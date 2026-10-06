package app.notmumla.game.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FlightMessageTest {
    private fun enc(m: FlightMessage) = FlightMessage.encode(m)
    private fun dec(s: String) = FlightMessage.decode(s.toByteArray())

    @Test fun stateRoundTrips() {
        val pose = Pose3(-412.3, 179.9, 399.9, 1.5, -1.1, 74.9, -1.25, 1.15)
        val m = FlightMessage.State(123456, true, pose, 7, 999_999, 42, 7)
        val d = FlightMessage.decode(enc(m)) as FlightMessage.State
        assertEquals(123456, d.seq)
        assertTrue(d.alive)
        assertEquals(pose.x, d.pose.x, 0.05)
        assertEquals(pose.y, d.pose.y, 0.05)
        assertEquals(pose.z, d.pose.z, 0.05)
        assertEquals(pose.h, d.pose.h, 0.002)
        assertEquals(pose.p, d.pose.p, 0.002)
        assertEquals(pose.v, d.pose.v, 0.05)
        assertEquals(pose.w, d.pose.w, 0.002)
        assertEquals(pose.q, d.pose.q, 0.002)
        assertEquals(listOf(7, 999_999, 42, 7), listOf(d.fx, d.shots, d.kills, d.deaths))
        assertTrue(enc(m).size <= 96)
    }

    @Test fun simpleMessages() {
        assertEquals(FlightMessage.Hello, dec("hi"))
        assertEquals(FlightMessage.Bye, dec("bye"))
        assertEquals(FlightMessage.Hit(12, 5), dec("hit 12 5"))
    }

    @Test fun rejectsAnythingOffShape() {
        val ok = "s 1 1 0 100 0 0 0 420 0 0 0 0 0 0"
        assertTrue(dec(ok) is FlightMessage.State)
        for (bad in listOf(
            "", "hi ", "HI", "hit 1", "hit -1 2", "hit 01 2",
            "s 1 1 0 100 0 0 0 420 0 0 0 0 0", // too few fields
            "s 1 1 0 100 0 0 0 420 0 0 0 0 0 0 0", // too many
            "s 1 2 0 100 0 0 0 420 0 0 0 0 0 0", // alive not 0/1
            "s 1 1 4201 100 0 0 0 420 0 0 0 0 0 0", // past the limit
            "s 1 1 0 1801 0 0 0 420 0 0 0 0 0 0", // above the ceiling
            "s 1 1 0 100 0 3600 0 420 0 0 0 0 0 0", // heading out of range
            "s 1 1 0 100 0 0 900 420 0 0 0 0 0 0", // pitched past the limit
            "s 1 1 0 100 0 0 0 999 0 0 0 0 0 0", // too fast
            "s 1 1 0 100 0 0 0 420 0 0 8 0 0 0", // unknown effect bits
            "s 1 1 -0 100 0 0 0 420 0 0 0 0 0 0", "s 1 1 +5 100 0 0 0 420 0 0 0 0 0 0",
        )) assertNull(bad, dec(bad))
        assertNull(FlightMessage.decode(ByteArray(200) { 'h'.code.toByte() }))
    }
}

class Pose3Test {
    @Test fun levelFlightGoesStraight() {
        val p = Pose3(0.0, 50.0, 0.0, 0.0, 0.0, 40.0).extrapolate(1.0)
        assertEquals(0.0, p.x, 1e-9)
        assertEquals(50.0, p.y, 1e-9)
        assertEquals(40.0, p.z, 1e-9)
    }

    @Test fun levelTurnFollowsTheArc() {
        val p = Pose3(0.0, 50.0, 0.0, 0.3, 0.0, 40.0, 1.0).extrapolate(1.0)
        // Exact circle: radius v/w, from heading 0.3 to 1.3.
        assertEquals(40.0 * (cos(0.3) - cos(1.3)), p.x, 0.05)
        assertEquals(40.0 * (sin(1.3) - sin(0.3)), p.z, 0.05)
    }

    @Test fun pitchAndAltitudeStayInBounds() {
        val up = Pose3(0.0, 170.0, 0.0, 0.0, 0.0, 70.0, 0.0, 1.2).extrapolate(3.0)
        assertEquals(FlightWorld.MAX_PITCH, up.p, 1e-9)
        assertEquals(FlightWorld.CEILING, up.y, 1e-9)
        val down = Pose3(0.0, 10.0, 0.0, 0.0, -1.0, 70.0).extrapolate(3.0)
        assertEquals(FlightWorld.FLOOR, down.y, 1e-9)
    }
}

class FlightArenaTest {
    private var now = 1_000_000L
    private val sent = mutableListOf<Pair<List<Int>, FlightMessage>>()
    private var members = mapOf(2 to "bob", 3 to "carol")
    private val invites = mutableListOf<String>()

    private val arena = FlightArena(
        send = { to, m -> sent += to to m },
        self = { 1 },
        channelPeers = { members },
        enabled = { true },
        onInvite = { invites += it },
        clock = { now },
        random = Random(7),
    )

    private fun data(m: FlightMessage) = FlightMessage.encode(m)
    private fun tick(ms: Long = 16, input: FlightInput = FlightInput()) = run { now += ms; arena.step(input)!! }
    private fun state(seq: Int, pose: Pose3, fx: Int = 0, shots: Int = 0, alive: Boolean = true) =
        data(FlightMessage.State(seq, alive, pose, fx, shots, 0, 0))

    @Test fun joinGreetsAndHelloWhileClosedOnlyInvites() {
        arena.onData(2, data(FlightMessage.Hello))
        assertEquals(listOf("bob"), invites)
        assertTrue(sent.isEmpty())
        arena.join()
        assertEquals(listOf(2, 3) to FlightMessage.Hello, sent.single())
    }

    @Test fun ignoresUsersOutsideOurChannel() {
        arena.join()
        arena.onData(9, state(1, Pose3(0.0, 50.0, 0.0, 0.0, 0.0)))
        assertTrue(tick().ships.isEmpty())
        arena.onData(2, state(1, Pose3(0.0, 50.0, 0.0, 0.0, 0.0)))
        assertEquals(listOf(2), tick().ships.map { it.session })
    }

    /** Bob hovers [d] m dead ahead of us, nose to nose, holding his trigger. */
    private fun bobFiresAtUs(d: Double = 60.0) {
        arena.join()
        val me = tick().me
        val bob = Pose3(me.x + sin(me.h) * d, me.y, me.z + cos(me.h) * d, FlightWorld.wrap(me.h + PI), 0.0)
        arena.onData(2, state(1, bob))
        arena.onData(2, state(2, bob, fx = FlightMessage.FIRING, shots = 1))
    }

    @Test fun peersTriggerFiresTheirLasersHereAndTheVictimDecides() {
        bobFiresAtUs()
        sent.clear()
        var v = tick()
        var steps = 0
        // Keep bob's state fresh so he doesn't time out while he shreds us.
        while (v.alive && steps++ < 400) {
            if (steps % 20 == 0) arena.onData(2, state(2 + steps, Pose3(v.me.x + sin(v.me.h) * 60, v.me.y,
                v.me.z + cos(v.me.h) * 60, FlightWorld.wrap(v.me.h + PI), 0.0), fx = FlightMessage.FIRING, shots = 1 + steps / 3))
            v = tick()
            assertTrue("shield only falls under fire", v.shield <= 1f)
        }
        assertFalse(v.alive)
        assertEquals("bob", v.killedBy)
        val hit = sent.map { it.second }.filterIsInstance<FlightMessage.Hit>().single()
        assertEquals(2, hit.shooter)
        assertTrue(hit.shot >= 1)
        now += FlightWorld.RESPAWN_MS
        val back = tick()
        assertTrue(back.alive)
        assertEquals(1f, back.shield, 0f)
    }

    @Test fun crashingWithNobodyToBlameCreditsNoOne() {
        arena.join()
        arena.onData(2, state(1, Pose3(300.0, 150.0, 300.0, 0.0, 0.0)))
        sent.clear()
        // Dive into the floor and stay there: scraping wears the shield away.
        var v = tick()
        var steps = 0
        while (v.alive && steps++ < 2000) {
            if (steps % 50 == 0) arena.onData(2, state(1 + steps, Pose3(300.0, 150.0, 300.0, 0.0, 0.0)))
            v = tick(16, FlightInput(pitch = -1f))
        }
        assertFalse(v.alive)
        assertNull(v.killedBy)
        assertEquals(FlightMessage.Hit(1, 0), sent.map { it.second }.filterIsInstance<FlightMessage.Hit>().single())
    }

    @Test fun killsCountOnlyForBoltsWeFiredAndOnlyOnce() {
        arena.join()
        arena.onData(2, state(1, Pose3(300.0, 150.0, 300.0, 0.0, 0.0)))
        arena.onData(2, data(FlightMessage.Hit(1, 50)))
        assertEquals(0, tick().scores.first { it.me }.kills) // never fired that many
        tick(16, FlightInput(fire = true))
        arena.onData(2, state(2, Pose3(300.0, 150.0, 300.0, 0.0, 0.0)))
        arena.onData(2, data(FlightMessage.Hit(1, 1)))
        arena.onData(2, data(FlightMessage.Hit(1, 1)))
        assertEquals(1, tick().scores.first { it.me }.kills)
    }

    @Test fun staysUnderTheServerRateLimit() {
        arena.join()
        arena.onData(2, state(1, Pose3(300.0, 150.0, 300.0, 0.0, 0.0)))
        sent.clear()
        var seq = 2
        // Fly around for 20 s mashing every button while bob keeps saying hello (forcing replies).
        repeat(1250) {
            if (it % 10 == 0) {
                arena.onData(2, data(FlightMessage.Hello))
                arena.onData(2, state(seq++, Pose3(300.0, 150.0, 300.0, 0.0, 0.0)))
            }
            val mash = it % 6 < 3
            tick(16, FlightInput(yaw = 1f, pitch = 0.3f, fire = mash, boost = mash, brake = !mash, roll = mash))
        }
        assertTrue("${sent.size} messages in 20 s", sent.size <= 4 * 20)
        assertTrue("still sends regularly", sent.size >= 3 * 20)
    }

    @Test fun neverLeavesTheAirspace() {
        arena.join()
        repeat(4000) {
            val v = tick(16, FlightInput(boost = it % 400 < 100, pitch = if (it % 700 < 50) 0.5f else 0f))
            if (v.alive) {
                // Turned back well before the hard clamp at LIMIT.
                assertTrue("${v.me}", abs(v.me.x) < FlightWorld.LIMIT - 5 && abs(v.me.z) < FlightWorld.LIMIT - 5)
                assertTrue(v.me.y in FlightWorld.FLOOR..FlightWorld.CEILING)
            }
        }
    }

    @Test fun barrelRollShrugsOffBolts() {
        bobFiresAtUs(d = 30.0)
        sent.clear()
        // A roll lasts ROLL_MS; bolts take ~0.1 s to cover 30 m, so the first volley meets the roll.
        var v = tick(16, FlightInput(roll = true))
        repeat(20) { v = tick(16) }
        assertEquals(1f, v.shield, 0f)
    }

    @Test fun leavingSaysBye() {
        arena.join()
        arena.onData(2, state(1, Pose3(0.0, 50.0, 0.0, 0.0, 0.0)))
        sent.clear()
        arena.leave()
        assertEquals(listOf(2) to FlightMessage.Bye, sent.single())
        assertNull(arena.step(FlightInput()))
    }
}
