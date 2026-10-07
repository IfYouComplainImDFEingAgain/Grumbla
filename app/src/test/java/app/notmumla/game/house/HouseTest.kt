package app.notmumla.game.house

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class HouseMessageTest {
    private fun dec(s: String) = HouseMessage.decode(s.toByteArray())

    @Test fun stateRoundTrips() {
        val m = HouseMessage.State(42, -12.34, 3.5, 9.87, 1.5, -3.2, 4.1, -6.5, Emote.DANCE, true, 7, 5, Weapon.PLANT)
        val bytes = HouseMessage.encode(m)
        val d = HouseMessage.decode(bytes) as HouseMessage.State
        assertEquals(42, d.seq)
        assertEquals(-12.34, d.x, 0.005)
        assertEquals(3.5, d.y, 0.005)
        assertEquals(9.87, d.z, 0.005)
        assertEquals(1.5, d.h, 0.002)
        assertEquals(-3.2, d.vx, 0.005)
        assertEquals(4.1, d.vz, 0.005)
        assertEquals(-6.5, d.vy, 0.005)
        assertEquals(Emote.DANCE, d.emote)
        assertTrue(d.down)
        assertEquals(7, d.swings)
        assertEquals(5, d.shirt)
        assertEquals(Weapon.PLANT, d.held)
        assertEquals(-1, d.car)
        assertTrue(bytes.size < 80)
        val driving = HouseMessage.decode(HouseMessage.encode(m.copy(held = Weapon.NONE, car = 63, turn = -1.2, vx = 10.5))) as HouseMessage.State
        assertEquals(63, driving.car)
        assertEquals(-1.2, driving.turn, 0.002)
        assertEquals(10.5, driving.vx, 0.005)
    }

    @Test fun carMessagesRoundTrip() {
        val p = HouseMessage.decode(HouseMessage.encode(HouseMessage.Parked(6.25, -9.5, 3.0, 41))) as HouseMessage.Parked
        assertEquals(6.25, p.x, 0.005)
        assertEquals(-9.5, p.z, 0.005)
        assertEquals(3.0, p.h, 0.002)
        assertEquals(41, p.health)
        assertEquals(HouseMessage.Boom(-1.5, 2.0), dec("boom -150 200"))
        assertEquals(Weapon.CAR, (dec("ow 2 1 0 3") as HouseMessage.Ow).weapon)
    }

    @Test fun throwsRoundTrip() {
        val t = HouseMessage.decode(HouseMessage.encode(HouseMessage.Toss(3, 1.25, 4.45, -7.5, 0.5, 6.2))) as HouseMessage.Toss
        assertEquals(3, t.n)
        assertEquals(1.25, t.x, 0.005)
        assertEquals(4.45, t.y, 0.005)
        assertEquals(-7.5, t.z, 0.005)
        assertEquals(0.5, t.h, 0.002)
        assertEquals(6.2, t.vy, 0.005)
        assertEquals(HouseMessage.Got(4), dec("got 4"))
    }

    @Test fun simpleMessages() {
        assertEquals(HouseMessage.Hello, dec("hi"))
        assertEquals(HouseMessage.Bye, dec("bye"))
        assertEquals(HouseMessage.Slap(12, 3, Weapon.BAT), dec("slap 12 3 1"))
        val ow = dec("ow 12 3 900 2") as HouseMessage.Ow
        assertEquals(12, ow.slapper)
        assertEquals(PI / 2, ow.dir, 1e-9)
        assertEquals(Weapon.PLANT, ow.weapon)
    }

    @Test fun rejectsAnythingOffShape() {
        for (bad in listOf(
            "", "hi ", "HI", "slap 1 2", "slap 1 0 0", "slap -1 2 0", "slap 01 2 0", "slap 1 2 3",
            "ow 1 2 0", "ow 1 2 3600 0", "ow 1 2 0 5", "got 8", "got -1", "toss 0 0 0 0 0 0", "toss 1 0 -5 0 0 0",
            "s 1 0 0 0 0 0 0 0 0 0 0 0 0 -1", // too few fields
            "s 1 1701 0 0 0 0 0 0 0 0 0 0 0 -1 0", // outside the yard
            "s 1 0 701 0 0 0 0 0 0 0 0 0 0 -1 0", // above the roof
            "s 1 0 0 0 3600 0 0 0 0 0 0 0 0 -1 0", // heading out of range
            "s 1 0 0 0 0 9999 0 0 0 0 0 0 0 -1 0", // faster than the car
            "s 1 0 0 0 0 0 0 0 5 0 0 0 0 -1 0", // no such emote
            "s 1 0 0 0 0 0 0 0 0 2 0 0 0 -1 0", // down not 0/1
            "s 1 0 0 0 0 0 0 0 0 0 0 8 0 -1 0", // no such shirt
            "s 1 0 0 0 0 0 0 0 0 0 0 0 3 -1 0", // a car isn't something to hold
            "s 1 0 0 0 0 0 0 0 0 0 0 0 0 101 0", // healthier than new
            "s 1 0 0 0 0 0 0 0 0 0 0 0 0 -1 2001", // turning too fast
            "s 1 -0 0 0 0 0 0 0 0 0 0 0 0 -1 0", "s 1 +5 0 0 0 0 0 0 0 0 0 0 0 -1 0",
            "car 0 0 0", "car 0 0 3600 50", "car 0 0 0 101", "boom 0", "boom 1701 0",
        )) assertNull(bad, dec(bad))
        assertTrue(dec("s 1 0 0 0 0 0 0 0 0 0 0 0 0 -1 0") is HouseMessage.State)
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
        val p = walkTo(doubleArrayOf(2.25, HouseWorld.STORY, -2.5), 2.25, 0.5)
        assertTrue(p[2] < HouseWorld.SZ0)
        assertEquals(HouseWorld.STORY, p[1], 1e-9)
    }

    /** Hold a direction for [seconds], jumping at the start if [jump]. */
    private fun run(b: DoubleArray, vx: Double, vz: Double, seconds: Double, jump: Boolean = false): DoubleArray {
        if (jump) b[3] = HouseWorld.JUMP_SPEED
        repeat((seconds * 60).toInt()) { HouseWorld.simulate(b, vx, vz, 1.0 / 60) }
        return b
    }

    @Test fun theCouchTakesAJump() {
        // In the living room, south of the coffee table, walking toward the couch along the front wall.
        val walked = run(doubleArrayOf(-5.0, 0.0, -2.0, 0.0), 0.0, -3.0, 1.0)
        assertEquals(0.0, walked[1], 1e-9)
        assertTrue(walked[2] > -3.9)
        val jumped = run(doubleArrayOf(-5.0, 0.0, -3.5, 0.0), 0.0, -2.0, 1.0, jump = true)
        assertEquals(0.45, jumped[1], 1e-9)
        assertEquals(0.0, jumped[3], 1e-9)
    }

    @Test fun jumpsComeDownAndHitTheCeiling() {
        val out = run(doubleArrayOf(0.0, 0.0, -9.0, 0.0), 0.0, 0.0, 0.25, jump = true)
        assertTrue(out[1] > 0.5)
        run(out, 0.0, 0.0, 1.0)
        assertEquals(0.0, out[1], 1e-9)
        // On the kitchen table, a jump stops where the head meets the ceiling.
        val b = doubleArrayOf(-4.4, 0.75, 2.5, HouseWorld.JUMP_SPEED)
        var top = 0.0
        repeat(60) { HouseWorld.simulate(b, 0.0, 0.0, 1.0 / 60); top = maxOf(top, b[1]) }
        assertEquals(HouseWorld.STORY - HouseWorld.HEIGHT, top, 1e-6)
        assertEquals(0.75, b[1], 1e-9)
    }

    @Test fun walkingOffTheStairsSideFalls() {
        // Partway up, step off the open side into the hallway.
        val b = doubleArrayOf(2.2, HouseWorld.stairY(0.0), 0.0, 0.0)
        run(b, -3.0, 0.0, 1.0)
        assertTrue(b[0] < HouseWorld.SX0)
        assertEquals(0.0, b[1], 1e-9)
    }

    @Test fun windowsAreTooSmallToClimbThrough() {
        // At the living room's side window, jumping out toward the yard.
        val b = run(doubleArrayOf(-8.0, 0.0, -1.9, 0.0), -3.0, 0.0, 1.0, jump = true)
        assertEquals(HouseWorld.HX0 + HouseWorld.WALL_T + HouseWorld.RADIUS, b[0], 0.1)
        assertEquals(0.0, b[1], 1e-9)
    }

    /** Where a pot thrown from hands at (x, feet y, z) toward [heading] breaks. */
    private fun throwFrom(x: Double, y: Double, z: Double, heading: Double): DoubleArray {
        val pot = Pot(1, 1, x + sin(heading) * 0.45, y + 1.45, z + cos(heading) * 0.45, heading, 2.5)
        repeat(400) { pot.advance(10) { false } }
        return pot.smashedAt!!
    }

    @Test fun potsFlyOutOfWindowsAndBreakOnWalls() {
        // From the bonus room, out of the front window: it comes down in the front yard.
        val out = throwFrom(5.6, HouseWorld.STORY, -4.3, PI)
        assertTrue(out[2] < HouseWorld.HZ0 - 3)
        // It went through that window's pane, once.
        val panes = ArrayList<Int>()
        val pot = Pot(1, 1, 5.6, HouseWorld.STORY + 1.45, -4.75, PI, 2.5)
        repeat(400) { pot.advance(10, glass = { k, _ -> panes += k }) { false } }
        assertEquals(1, panes.size)
        val pane = HouseWorld.boxes[panes[0]]
        assertEquals(HouseWorld.Kind.GLASS, pane.kind)
        assertTrue(pane.x0 < 5.6 && pane.x1 > 5.6 && pane.z1 < HouseWorld.HZ0 + HouseWorld.WALL_T)
        // A step to the side, it hits the wall between the windows.
        val wall = throwFrom(4.0, HouseWorld.STORY, -4.3, PI)
        assertTrue(wall[2] > HouseWorld.HZ0 && wall[1] > HouseWorld.STORY)
        // Over the kitchen counter and out of the back window.
        val back = throwFrom(-3.1, 0.0, 3.9, 0.0)
        assertTrue(back[2] > HouseWorld.HZ1 + 2)
    }

    @Test fun wallsBlock() {
        // From the hallway straight west into the living room wall (not at a door).
        val after = HouseWorld.move(-0.5, 0.0, 0.0, -0.5, 0.0)
        assertEquals(-0.9 + HouseWorld.RADIUS, after[0], 1e-9)
    }
}

class CarTest {
    private fun drive(m: Car.Motion, mx: Double, mz: Double, seconds: Double): Double {
        var hit = 0.0
        repeat((seconds * 60).toInt()) { hit = maxOf(hit, Car.drive(m, mx, mz, 1.0 / 60)) }
        return hit
    }

    @Test fun drivesOutOfTheGarageIntoTheFrontYard() {
        assertFalse(Car.blocked(Car.HOME_X, Car.HOME_Z, Car.HOME_H))
        val m = Car.Motion(Car.HOME_X, Car.HOME_Z, Car.HOME_H)
        // Stick toward the front of the house: −z.
        val hit = drive(m, 0.0, -1.0, 1.5)
        assertEquals(0.0, hit, 0.0)
        assertTrue("z ${m.z}", m.z < HouseWorld.HZ0 - Car.HALF_L)
        assertTrue(m.v > 5)
    }

    @Test fun turnsTowardTheStickAndBacksUpWhenItsBehind() {
        // Along the front lawn toward +x, with the stick on the forward-left diagonal: it swings left.
        val m = Car.Motion(-9.0, -9.5, PI / 2)
        assertFalse(Car.blocked(m.x, m.z, m.h))
        assertEquals(0.0, drive(m, sin(PI / 4), cos(PI / 4), 0.6), 0.0)
        assertTrue("heading ${m.h}", m.h < PI / 2 - 0.3 && m.h > PI / 4 - 0.05)
        val r = Car.Motion(0.0, -9.0, 0.0)
        drive(r, 0.0, -1.0, 0.5)
        assertTrue("backing up", r.v < -1)
        assertTrue(r.z < -9.0)
    }

    @Test fun crashingIntoTheHouseStopsItAndCountsOnce() {
        // Across the front lawn into the living room wall, flat out.
        val m = Car.Motion(-5.0, -10.3, 0.0)
        assertFalse(Car.blocked(m.x, m.z, m.h))
        val hit = drive(m, 0.0, 1.0, 2.5)
        assertTrue("hit at $hit", hit > Car.CRASH_SPEED)
        assertTrue(m.z + Car.HALF_L <= HouseWorld.HZ0 + 1e-6)
        assertFalse(Car.blocked(m.x, m.z, m.h))
        // Still pushing against the wall: no new hit.
        assertTrue(drive(m, 0.0, 1.0, 0.5) < Car.CRASH_SPEED)
    }

    @Test fun othersFollowItButNotThroughWalls() {
        val c = Car.coast(-5.0, -9.0, 0.0, 10.0, 0.0, 2.0)
        assertTrue(c[1] + Car.HALF_L <= HouseWorld.HZ0 + 1e-6)
        assertTrue(c[1] > -9.0 + 1.0)
    }

    @Test fun walkersBumpIntoItButCanStepOutOfIt() {
        val car = doubleArrayOf(0.0, -9.0, 0.0)
        // Walking at its side from 2 m away: stopped at the side.
        val b = HouseWorld.simulate(doubleArrayOf(3.0, 0.0, -9.0, 0.0), -HouseWorld.WALK_SPEED, 0.0, 1.0, car)
        assertEquals(Car.HALF_W + HouseWorld.RADIUS, b[0], 0.1)
        // One it rolled onto walks out.
        val inside = HouseWorld.simulate(doubleArrayOf(0.5, 0.0, -9.0, 0.0), HouseWorld.WALK_SPEED, 0.0, 1.0, car)
        assertTrue(inside[0] > 3.0)
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
    private fun bobAt(x: Double, z: Double, h: Double = 0.0, swings: Int = 0, down: Boolean = false, held: Weapon = Weapon.NONE) =
        arena.onData(2, data(HouseMessage.State(++bobSeq, x, 0.0, z, h, 0.0, 0.0, 0.0, Emote.NONE, down, swings, 0, held)))

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
        arena.attack()
        assertEquals(HouseMessage.Slap(2, 1, Weapon.NONE), sent.single().second)
    }

    @Test fun slapAtNobodyStillShowsTheSwing() {
        faceBob(gap = 5.0)
        sent.clear()
        arena.attack()
        tick()
        val s = sent.map { it.second }.filterIsInstance<HouseMessage.State>().single()
        assertEquals(1, s.swings)
        assertTrue(sent.none { it.second is HouseMessage.Slap })
    }

    @Test fun theVictimDecides() {
        faceBob()
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 1, Weapon.NONE)))
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
        arena.onData(2, data(HouseMessage.Slap(1, 2, Weapon.NONE)))
        assertTrue(sent.none { it.second is HouseMessage.Ow })
    }

    @Test fun slapsFromAcrossTheRoomOrReplayedDontCount() {
        faceBob(gap = 6.0)
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 1, Weapon.NONE)))
        assertFalse(tick().down)
        bobAt(tick().me.x, tick().me.z + 1.0, PI)
        tick()
        arena.onData(2, data(HouseMessage.Slap(1, 1, Weapon.NONE)))
        assertTrue(tick().down)
        repeat((Ragdoll.DOWN_MS / 50 + 2).toInt() + 40) { tick(50) }
        val me = tick().me
        bobAt(me.x, me.z + 1.0, PI)
        tick()
        sent.clear()
        arena.onData(2, data(HouseMessage.Slap(1, 1, Weapon.NONE))) // the same swing again
        assertTrue(sent.none { it.second is HouseMessage.Ow })
    }

    @Test fun strangersCantSlap() {
        faceBob()
        arena.onData(9, data(HouseMessage.Slap(1, 1, Weapon.NONE)))
        assertFalse(tick().down)
    }

    @Test fun anOwKnocksThemDownAndCreditsOnlyOurRealSwings() {
        faceBob()
        arena.onData(2, data(HouseMessage.Ow(1, 1, 0.0, Weapon.NONE)))
        val v = tick()
        assertTrue(v.others.single().down)
        assertTrue(v.feed.none { it.startsWith("You slapped") }) // we never swung
        // They get up when their state says so.
        bobAt(0.0, -8.0)
        assertFalse(tick().others.single().down)
        arena.attack()
        arena.onData(2, data(HouseMessage.Ow(1, 1, 0.0, Weapon.NONE)))
        assertTrue(tick().feed.any { it == "You slapped bob" })
    }

    @Test fun swingCounterPlaysTheirSwing() {
        faceBob()
        bobAt(0.0, -8.0, swings = 1)
        val v = tick()
        assertTrue(v.sounds.any { it.sound == HouseSound.SWISH })
    }

    @Test fun aBatReachesFurtherButOnlyIfTheyHoldOne() {
        faceBob(gap = 3.2)
        arena.onData(2, data(HouseMessage.Slap(1, 1, Weapon.BAT)))
        assertFalse(tick().down)
        val me = tick().me
        bobAt(me.x, me.z + 3.2, PI, held = Weapon.BAT)
        tick()
        arena.onData(2, data(HouseMessage.Slap(1, 2, Weapon.BAT)))
        val v = tick()
        assertTrue(v.down)
        assertEquals(Weapon.BAT, v.slappedWith)
        assertEquals(Weapon.BAT, sent.map { it.second }.filterIsInstance<HouseMessage.Ow>().single().weapon)
    }

    @Test fun walkingOverASpotPicksItUp() {
        arena.join()
        bobAt(10.0, -10.0)
        tick()
        sent.clear()
        // From the front path to the plant left of the front door.
        val plant = HouseWorld.spots.indexOfFirst { it.weapon == Weapon.PLANT && it.x < 0 && it.z < HouseWorld.HZ0 }
        val sp = HouseWorld.spots[plant]
        var v = tick()
        repeat(200) {
            if (v.held != Weapon.NONE) return@repeat
            val dx = sp.x - v.me.x
            val dz = sp.z - v.me.z
            val d = hypot(dx, dz)
            v = tick(input = HouseInput(dx / d, dz / d))
        }
        assertEquals(Weapon.PLANT, v.held)
        assertFalse(plant in tick().spots)
        assertTrue(sent.any { it.second == HouseMessage.Got(plant) })
        assertEquals(Weapon.PLANT, sent.map { it.second }.filterIsInstance<HouseMessage.State>().last().held)
        // Back after a while.
        repeat((HouseWorld.RESPAWN_MS / 100).toInt() + 1) { tick(100, HouseInput(0.0, -1.0)) }
        assertTrue(plant in tick().spots)
    }

    @Test fun aPlantThrownAtUsKnocksUsDown() {
        arena.join()
        val me = tick().me
        bobAt(me.x + 5.0, me.z, -PI / 2)
        tick()
        sent.clear()
        // Bob, 5 m to our right across the front yard, lobs it at us.
        arena.onData(2, data(HouseMessage.Toss(1, me.x + 4.5, 1.2, me.z, -PI / 2, 3.0)))
        var v = tick()
        repeat(60) { if (!v.down) v = tick() }
        assertTrue(v.down)
        assertEquals(Weapon.PLANT, v.slappedWith)
        val ow = sent.map { it.second }.filterIsInstance<HouseMessage.Ow>().single()
        assertEquals(HouseMessage.Ow(2, 1, ow.dir, Weapon.PLANT), ow)
        assertTrue(v.shards.isNotEmpty())
    }

    @Test fun aThrowFromNowhereNearTheThrowerIsIgnored() {
        val me = faceBob(gap = 8.0)
        arena.onData(2, data(HouseMessage.Toss(1, me.x, 1.2, me.z + 1.0, PI, 0.0)))
        repeat(60) { assertFalse(tick().down) }
    }

    @Test fun throwingAimsAtWhoeverIsAheadAndGetsCredit() {
        faceBob(gap = 6.0)
        // Fetch a plant from the porch first.
        val plant = HouseWorld.spots.indexOfFirst { it.weapon == Weapon.PLANT && it.z < HouseWorld.HZ0 }
        val sp = HouseWorld.spots[plant]
        var v = tick()
        repeat(300) {
            if (v.held != Weapon.NONE) return@repeat
            val dx = sp.x - v.me.x
            val dz = sp.z - v.me.z
            val d = hypot(dx, dz)
            v = tick(input = HouseInput(dx / d, dz / d))
        }
        assertEquals(Weapon.PLANT, v.held)
        // Bob stands 6 m south of us; turn toward him and throw.
        bobAt(v.me.x, v.me.z - 6.0, 0.0)
        repeat(20) { v = tick(input = HouseInput(0.0, -0.2)) }
        tick()
        sent.clear()
        arena.attack()
        val toss = sent.map { it.second }.filterIsInstance<HouseMessage.Toss>().single()
        assertEquals(PI, abs(HouseArena.angleDiff(toss.h, 0.0)), 0.05)
        // The pot flies at Bob's figure on our screen too.
        repeat(60) { tick() }
        assertTrue(tick().pots.isEmpty())
        arena.onData(2, data(HouseMessage.Ow(1, 1, PI, Weapon.PLANT)))
        assertTrue(tick().feed.any { it == "Your plant beaned bob" })
    }

    @Test fun jumpingSendsTakeOffAndLanding() {
        arena.join()
        bobAt(10.0, -10.0)
        repeat(5) { tick() }
        now += 2_000
        tick()
        sent.clear()
        arena.jump()
        tick()
        val up = sent.map { it.second }.filterIsInstance<HouseMessage.State>().single()
        assertTrue(up.vy > 0)
        repeat(60) { tick() }
        val down = sent.map { it.second }.filterIsInstance<HouseMessage.State>().last()
        assertEquals(0.0, down.vy, 1e-9)
        assertEquals(0.0, down.y, 1e-9)
    }

    /** Walk to (tx, tz); returns the last view. */
    private fun walkTo(tx: Double, tz: Double, within: Double = 0.3): HouseView {
        var v = tick()
        repeat(600) {
            val dx = tx - v.me.x
            val dz = tz - v.me.z
            val d = hypot(dx, dz)
            if (d < within) return v
            v = tick(input = HouseInput(dx / d, dz / d))
        }
        return v
    }

    /** Join and get into the car in the garage. */
    private fun intoTheCar(): HouseView {
        arena.join()
        walkTo(Car.HOME_X, HouseWorld.HZ0 - 1.0)
        val v = walkTo(Car.HOME_X, Car.HOME_Z - Car.HALF_L - 0.6)
        assertTrue("by the car at ${v.me.x}, ${v.me.z}", v.canDrive)
        arena.car()
        return tick()
    }

    @Test fun getInDriveOffAndParkIt() {
        var v = intoTheCar()
        bobAt(-10.0, 8.0)
        assertTrue(v.driving)
        assertTrue(v.me.inCar)
        sent.clear()
        // Out of the garage, and stopped short of the fence.
        repeat(100) { v = tick(input = HouseInput(0.0, -1.0)) }
        assertTrue("car at ${v.car!!.z}", v.car!!.z < HouseWorld.HZ0 - 2)
        val s = sent.map { it.second }.filterIsInstance<HouseMessage.State>().last()
        assertEquals(100, s.car)
        assertTrue(s.vz < -5)
        sent.clear()
        arena.car()
        v = tick()
        assertFalse(v.driving)
        val parked = sent.map { it.second }.filterIsInstance<HouseMessage.Parked>().single()
        assertEquals(v.car!!.z, parked.z, 0.01)
        // Out beside it, not in it.
        assertFalse(Car.contains(v.car!!.x, v.car!!.z, v.car!!.h, v.me.x, v.me.z))
        // The parking spent the budget; on foot again once it's back.
        repeat(20) { tick(50) }
        assertEquals(-1, sent.map { it.second }.filterIsInstance<HouseMessage.State>().last().car)
    }

    @Test fun bobsCarRunsUsOver() {
        arena.join()
        val me = tick().me
        // Bob drives straight at us from 5 m to our left.
        val bobCar = HouseMessage.State(++bobSeq, me.x - 5.0, 0.0, me.z, PI / 2, 9.0, 0.0, 0.0, Emote.NONE, false, 0, 0, Weapon.NONE, car = 100)
        arena.onData(2, data(bobCar))
        sent.clear()
        var v = tick()
        repeat(40) { if (!v.down) v = tick() }
        assertTrue(v.down)
        assertEquals(Weapon.CAR, v.slappedWith)
        assertEquals("bob", v.slappedBy)
        val ow = sent.map { it.second }.filterIsInstance<HouseMessage.Ow>().single()
        assertEquals(2, ow.slapper)
        assertEquals(Weapon.CAR, ow.weapon)
    }

    @Test fun aParkedCarDoesntRunAnyoneOver() {
        arena.join()
        val me = tick().me
        arena.onData(2, data(HouseMessage.State(++bobSeq, me.x - 3.0, 0.0, me.z, PI / 2, 0.0, 0.0, 0.0, Emote.NONE, false, 0, 0, Weapon.NONE, car = 100)))
        repeat(60) { assertFalse(tick().down) }
    }

    @Test fun batteredCarBlowsUpAndTheDriverStartsOver() {
        intoTheCar()
        // Bob, with a bat, at the car's nose.
        bobAt(Car.HOME_X, Car.HOME_Z - Car.HALF_L - 0.8, 0.0, held = Weapon.BAT)
        tick()
        sent.clear()
        var swings = 0
        var v = tick()
        while (!v.down && swings < 20) {
            arena.onData(2, data(HouseMessage.Slap(1, ++swings, Weapon.BAT)))
            v = tick()
        }
        assertEquals(Math.ceil(Car.MAX_HEALTH / HouseArena.BAT_DENT).toInt(), swings)
        assertTrue(v.down)
        assertFalse(v.driving)
        assertEquals(Weapon.BLAST, v.slappedWith)
        assertTrue(v.car!!.wreckMs >= 0)
        assertTrue(sent.any { it.second is HouseMessage.Boom })
        // Back on our feet at the start, in the front yard.
        repeat((Ragdoll.DOWN_MS / 50 + 2).toInt()) { v = tick(50) }
        assertFalse(v.down)
        assertTrue(v.me.z < HouseWorld.HZ0 - 1)
        // And a new car in the garage once the wreck burns out.
        repeat((Car.WRECK_MS / 100).toInt()) { v = tick(100) }
        assertEquals(-1L, v.car!!.wreckMs)
        assertEquals(Car.MAX_HEALTH, v.car!!.health, 0.0)
        assertEquals(Car.HOME_X, v.car!!.x, 1e-9)
    }

    @Test fun bobsCarBlowingUpNextToUsKnocksUsOver() {
        arena.join()
        val me = tick().me
        arena.onData(2, data(HouseMessage.State(++bobSeq, me.x + 3.5, 0.0, me.z, 0.0, 0.0, 0.0, 0.0, Emote.NONE, false, 0, 0, Weapon.NONE, car = 5)))
        tick()
        sent.clear()
        arena.onData(2, data(HouseMessage.Boom(me.x + 3.5, me.z)))
        val v = tick()
        assertTrue(v.down)
        assertEquals(Weapon.BLAST, v.slappedWith)
        assertTrue(v.car!!.wreckMs >= 0)
        assertTrue(v.feed.any { it == "bob's car blew up" })
        assertEquals(Weapon.BLAST, sent.map { it.second }.filterIsInstance<HouseMessage.Ow>().single().weapon)
    }

    @Test fun aBoomFromSomeoneNotDrivingIsIgnored() {
        arena.join()
        val me = tick().me
        bobAt(me.x + 3.0, me.z)
        tick()
        arena.onData(2, data(HouseMessage.Boom(Car.HOME_X, Car.HOME_Z)))
        val v = tick()
        assertFalse(v.down)
        assertEquals(-1L, v.car!!.wreckMs)
    }

    @Test fun twoInTheCarAtOnceTheLowerSessionKeepsIt() {
        intoTheCar()
        // Bob (session 2) thinks he got in too: we keep it.
        arena.onData(2, data(HouseMessage.State(++bobSeq, Car.HOME_X, 0.0, Car.HOME_Z, PI, 0.0, 0.0, 0.0, Emote.NONE, false, 0, 0, Weapon.NONE, car = 100)))
        assertTrue(tick().driving)
    }

    @Test fun staysUnderTheServerRateLimit() {
        arena.join()
        bobAt(0.0, -8.0)
        sent.clear()
        // Ten seconds of walking in circles, slapping and emoting as fast as possible.
        repeat(600) { i ->
            val a = i * 0.05
            tick(input = HouseInput(cos(a), sin(a)))
            if (i % 3 == 0) arena.attack()
            if (i % 5 == 0) arena.emote(Emote.WAVE)
            if (i % 7 == 0) arena.jump()
        }
        assertTrue("sent ${sent.size}", sent.size <= 8 + 10 * 3.5 + 1)
    }
}
