package app.notmumla.game.house

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class HouseMessageTest {
    private fun dec(s: String) = HouseMessage.decode(s.toByteArray())

    @Test fun stateRoundTrips() {
        val m = HouseMessage.State(42, -12.34, 9.87, 1, 1.5, -3.2, 4.1, Emote.DANCE, true, 7, 5)
        val bytes = HouseMessage.encode(m)
        val d = HouseMessage.decode(bytes) as HouseMessage.State
        assertEquals(42, d.seq)
        assertEquals(-12.34, d.x, 0.005)
        assertEquals(9.87, d.z, 0.005)
        assertEquals(1, d.level)
        assertEquals(1.5, d.h, 0.002)
        assertEquals(-3.2, d.vx, 0.005)
        assertEquals(4.1, d.vz, 0.005)
        assertEquals(Emote.DANCE, d.emote)
        assertTrue(d.down)
        assertEquals(7, d.swings)
        assertEquals(5, d.shirt)
        assertTrue(bytes.size < 64)
    }

    @Test fun simpleMessages() {
        assertEquals(HouseMessage.Hello, dec("hi"))
        assertEquals(HouseMessage.Bye, dec("bye"))
        assertEquals(HouseMessage.Slap(12, 3), dec("slap 12 3"))
        val ow = dec("ow 12 3 900") as HouseMessage.Ow
        assertEquals(12, ow.slapper)
        assertEquals(PI / 2, ow.dir, 1e-9)
    }

    @Test fun rejectsAnythingOffShape() {
        for (bad in listOf(
            "", "hi ", "HI", "slap 1", "slap 1 0", "slap -1 2", "slap 01 2", "ow 1 2", "ow 1 2 3600",
            "s 1 0 0 0 0 0 0 0 0 0", // too few fields
            "s 1 0 0 2 0 0 0 0 0 0 0", // no third floor
            "s 1 1701 0 0 0 0 0 0 0 0 0", // outside the yard
            "s 1 0 0 0 3600 0 0 0 0 0 0", // heading out of range
            "s 1 0 0 0 0 9999 0 0 0 0 0", // faster than a walker
            "s 1 0 0 0 0 0 0 5 0 0 0", // no such emote
            "s 1 0 0 0 0 0 0 0 2 0 0", // down not 0/1
            "s 1 0 0 0 0 0 0 0 0 0 8", // no such shirt
            "s 1 -0 0 0 0 0 0 0 0 0 0", "s 1 +5 0 0 0 0 0 0 0 0 0",
        )) assertNull(bad, dec(bad))
        assertNull(HouseMessage.decode(ByteArray(200) { 'h'.code.toByte() }))
    }
}

class HouseWorldTest {
    /** Walk toward (tx, tz) in small steps; returns where we ended up. */
    private fun walkTo(start: DoubleArray, tx: Double, tz: Double, steps: Int = 400): DoubleArray {
        var p = start
        repeat(steps) {
            val dx = tx - p[0]
            val dz = tz - p[2]
            val d = hypot(dx, dz)
            if (d < 0.05) return p
            val k = minOf(0.08, d) / d
            p = HouseWorld.move(p[0], p[1], p[2], dx * k, dz * k)
        }
        return p
    }

    @Test fun stairsLeadUpstairsAndNowhereElse() {
        // Front door → hallway → bottom of the stairs → up → landing → the bonus room over the garage.
        var p = doubleArrayOf(0.0, 0.0, -8.0)
        p = walkTo(p, 0.0, -3.0)
        p = walkTo(p, 2.25, -2.0)
        p = walkTo(p, 2.25, 4.2)
        assertEquals(HouseWorld.STORY, p[1], 1e-9)
        p = walkTo(p, 0.5, 4.2)
        p = walkTo(p, 0.5, -3.7)
        p = walkTo(p, 2.25, -3.7)
        p = walkTo(p, 6.0, -3.7)
        assertEquals(6.0, p[0], 0.06)
        assertEquals(1, HouseWorld.levelOf(p[1]))
    }

    @Test fun cantStepOffTheLandingIntoTheStairwell() {
        val p = doubleArrayOf(2.25, HouseWorld.STORY, -2.5)
        val after = HouseWorld.move(p[0], p[1], p[2], 0.0, 1.5)
        assertEquals(-2.5, after[2], 1e-9)
    }

    @Test fun wallsBlock() {
        // From the hallway straight west into the living room wall (not at a door).
        val after = HouseWorld.move(-0.5, 0.0, 0.0, -0.5, 0.0)
        assertEquals(-0.5, after[0], 1e-9)
    }
}

class BlockyTest {
    @Test fun standingFigureFacesItsHeading() {
        val j = Blocky.pose(1.0, 0.0, 2.0, PI / 2)
        // Facing +x: the right shoulder is toward −z.
        assertTrue(j[Blocky.SHOULDER_R * 3 + 2] < 2.0)
        assertEquals(0.0, j[Blocky.FOOT_L * 3 + 1], 1e-9)
        assertEquals(Blocky.NECK_Y + Blocky.HEAD_HALF, j[Blocky.HEAD * 3 + 1], 1e-9)
    }

    @Test fun waveRaisesTheRightHand() {
        val j = Blocky.pose(0.0, 0.0, 0.0, 0.0, emote = Emote.WAVE, emoteMs = 300)
        assertTrue(j[Blocky.HAND_R * 3 + 1] > Blocky.SHOULDER_Y)
        assertTrue(j[Blocky.HAND_L * 3 + 1] < Blocky.SHOULDER_Y)
    }

    @Test fun ragdollFallsFlatAndComesToRest() {
        // Out in the yard, slapped toward the side fence.
        val rd = Ragdoll(Blocky.pose(-6.0, 0.0, -9.0, 0.0), -PI / 2, 0)
        rd.advance(Ragdoll.DOWN_MS)
        val j = rd.joints()
        for (i in 0 until Blocky.JOINTS) assertTrue("joint $i lying down " + j.joinToString { "%.2f".format(it) }, j[i * 3 + 1] < 0.5)
        // Knocked over, not sent across the yard.
        assertTrue(rd.pelvis()[0] in -9.0..-6.5)
        val before = rd.joints()
        rd.advance(200)
        val after = rd.joints()
        for (i in before.indices) assertEquals(before[i], after[i], 0.02)
    }

    @Test fun ragdollStaysOutOfWalls() {
        // In the hallway, slapped west into the living room wall.
        val rd = Ragdoll(Blocky.pose(0.0, 0.0, 0.0, 0.0), -PI / 2, 0)
        repeat(Ragdoll.DOWN_MS.toInt() / 16) {
            rd.advance(16)
            val j = rd.joints()
            for (i in 0 until Blocky.JOINTS) {
                assertTrue("joint $i above the floor", j[i * 3 + 1] >= 0.0)
                assertTrue("joint $i under the ceiling", j[i * 3 + 1] <= HouseWorld.STORY)
                assertTrue("joint $i this side of the wall", j[i * 3] > -0.9)
            }
        }
    }

    @Test fun ragdollIsRepeatable() {
        val start = Blocky.pose(-3.0, 0.0, -10.0, 0.3)
        val a = Ragdoll(start, 1.0, 0).apply { advance(1_000) }.joints()
        val b = Ragdoll(start, 1.0, 0).apply { repeat(60) { advance(16) }; advance(40) }.joints()
        for (i in a.indices) assertEquals(a[i], b[i], 1e-9)
    }
}

class HouseArenaTest {
    private var now = 1_000_000L
    private val sent = mutableListOf<Pair<List<Int>, HouseMessage>>()
    private var members = mapOf(2 to "bob", 3 to "carol")
    private val invites = mutableListOf<String>()

    private val arena = HouseArena(
        send = { to, m -> sent += to to m },
        self = { 1 },
        channelPeers = { members },
        enabled = { true },
        onInvite = { invites += it },
        clock = { now },
        random = Random(7),
    )

    private fun data(m: HouseMessage) = HouseMessage.encode(m)
    private fun tick(ms: Long = 16, input: HouseInput = HouseInput()) = run { now += ms; arena.step(input)!! }
    private var bobSeq = 0
    private fun bobAt(x: Double, z: Double, h: Double = 0.0, swings: Int = 0, down: Boolean = false) =
        arena.onData(2, data(HouseMessage.State(++bobSeq, x, z, 0, h, 0.0, 0.0, Emote.NONE, down, swings, 0)))

    /** Join, and put Bob right in front of us. */
    private fun faceBob(gap: Double = 1.0): HouseView.Figure {
        arena.join()
        val me = tick().me
        bobAt(me.x, me.z + gap, PI)
        tick()
        return me
    }

    @Test fun joinGreetsTheChannel() {
        arena.join()
        assertEquals(listOf(2, 3) to HouseMessage.Hello, sent.first())
    }

    @Test fun helloWhileClosedInvitesAndNeverAnswers() {
        arena.onData(2, data(HouseMessage.Hello))
        assertEquals(listOf("bob"), invites)
        assertTrue(sent.isEmpty())
    }

    @Test fun walkingMovesAndBroadcasts() {
        arena.join()
        val start = tick().me
        sent.clear()
        bobAt(10.0, -10.0)
        repeat(30) { tick(input = HouseInput(1.0, 0.0)) }
        val me = tick(input = HouseInput(1.0, 0.0)).me
        assertTrue(me.x > start.x + 1.5)
        val state = sent.map { it.second }.filterIsInstance<HouseMessage.State>().last()
        assertTrue(state.vx > 3)
    }

    @Test fun slapNamesTheFigureInFront() {
        faceBob()
        sent.clear()
        arena.slap()
        assertEquals(HouseMessage.Slap(2, 1), sent.single().second)
    }

    @Test fun slapAtNobodyStillShowsTheSwing() {
        faceBob(gap = 5.0)
        sent.clear()
        arena.slap()
        tick()
        val s = sent.map { it.second }.filterIsInstance<HouseMessage.State>().single()
        assertEquals(1, s.swings)
        assertTrue(sent.none { it.second is HouseMessage.Slap })
    }

    @Test fun theVictimDecides() {
        faceBob()
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 1)))
        val ow = sent.map { it.second }.filterIsInstance<HouseMessage.Ow>().single()
        assertEquals(2, ow.slapper)
        // Pushed away from Bob, who stands in front of us (+z): toward −z, heading π.
        assertEquals(PI, ow.dir, 0.05)
        val v = tick()
        assertTrue(v.down)
        assertEquals("bob", v.slappedBy)
        // Up again after a few seconds, and briefly immune.
        repeat((Ragdoll.DOWN_MS / 50 + 2).toInt()) { tick(50) }
        assertFalse(tick().down)
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 2)))
        assertTrue(sent.none { it.second is HouseMessage.Ow })
    }

    @Test fun slapsFromAcrossTheRoomOrReplayedDontCount() {
        faceBob(gap = 6.0)
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 1)))
        assertFalse(tick().down)
        bobAt(tick().me.x, tick().me.z + 1.0, PI)
        tick()
        arena.onData(2, data(HouseMessage.Slap(1, 1)))
        assertTrue(tick().down)
        repeat((Ragdoll.DOWN_MS / 50 + 2).toInt() + 40) { tick(50) }
        val me = tick().me
        bobAt(me.x, me.z + 1.0, PI)
        tick()
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 1))) // the same swing again
        assertTrue(sent.none { it.second is HouseMessage.Ow })
    }

    @Test fun strangersCantSlap() {
        faceBob()
        arena.onData(9, data(HouseMessage.Slap(1, 1)))
        assertFalse(tick().down)
    }

    @Test fun anOwKnocksThemDownAndCreditsOnlyOurRealSwings() {
        faceBob()
        arena.onData(2, data(HouseMessage.Ow(1, 1, 0.0)))
        val v = tick()
        assertTrue(v.others.single().down)
        assertTrue(v.feed.none { it.startsWith("You slapped") }) // we never swung
        // They get up when their state says so.
        bobAt(0.0, -8.0)
        assertFalse(tick().others.single().down)
        arena.slap()
        arena.onData(2, data(HouseMessage.Ow(1, 1, 0.0)))
        assertTrue(tick().feed.any { it == "You slapped bob" })
    }

    @Test fun swingCounterPlaysTheirSwing() {
        faceBob()
        bobAt(0.0, -8.0, swings = 1)
        val v = tick()
        assertTrue(v.sounds.any { it.sound == HouseSound.SWISH })
    }

    @Test fun staysUnderTheServerRateLimit() {
        arena.join()
        bobAt(0.0, -8.0)
        sent.clear()
        // Ten seconds of walking in circles, slapping and emoting as fast as possible.
        repeat(600) { i ->
            val a = i * 0.05
            tick(input = HouseInput(cos(a), sin(a)))
            if (i % 3 == 0) arena.slap()
            if (i % 5 == 0) arena.emote(Emote.WAVE)
        }
        assertTrue("sent ${sent.size}", sent.size <= 8 + 10 * 3.5 + 1)
    }
}
