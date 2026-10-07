package app.notmumla.game.house

import app.notmumla.game.arena.ChannelArena
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Where the stick points, already turned into world space (x, z); length ≤ 1. */
data class HouseInput(val mx: Double = 0.0, val mz: Double = 0.0)

enum class HouseSound { SWISH, SLAP, BONK, SMASH, JUMP, PICKUP, SHATTER, HONK, CRASH, BOOM }

/** A sound at a spot in the world; the screen works out volume and pan from the camera. */
data class HouseSoundEvent(val sound: HouseSound, val x: Double, val z: Double)

/** Everything the renderer needs for one frame; immutable, so it can be drawn off the lock. */
data class HouseView(
    val me: Figure,
    val others: List<Figure>,
    val down: Boolean,
    val slappedBy: String?,
    /** What knocked us down: a hand, a bat or a plant. */
    val slappedWith: Weapon,
    val emote: Emote,
    val shirt: Int,
    val held: Weapon,
    val feed: List<String>,
    val players: List<String>,
    val sounds: List<HouseSoundEvent>,
    /** Weapons waiting at their spots: indexes into [HouseWorld.spots]. */
    val spots: List<Int>,
    val pots: List<FlyingPot>,
    val shards: List<Shards>,
    /** Window panes that are broken just now: indexes into [HouseWorld.boxes]. */
    val brokenPanes: Set<Int> = emptySet(),
    val car: CarView? = null,
    /** We're at the wheel. */
    val driving: Boolean = false,
    /** We're standing by the car, and it's free: we could get in. */
    val canDrive: Boolean = false,
) {
    /**
     * The car at (x, z) facing [h], with [health] out of [app.notmumla.game.house.Car.MAX_HEALTH];
     * [wreckMs] since it blew up, or −1 while it's whole. [driver] sits in it; [timeMs] runs the smoke.
     */
    class CarView(val x: Double, val z: Double, val h: Double, val health: Double, val wreckMs: Long, val driver: Figure?, val timeMs: Long)

    class FlyingPot(val x: Double, val y: Double, val z: Double, val heading: Double, val spin: Double)

    /** A pot (or, if [glass], a window) breaking at (x, y, z), [ageMs] ago; [seed] varies the pieces. */
    class Shards(val x: Double, val y: Double, val z: Double, val ageMs: Long, val seed: Int, val glass: Boolean = false)

    /** A figure to draw: its [joints] (see [Blocky]) and where it stands, for sorting and floors. */
    class Figure(
        val session: Int,
        val name: String,
        val joints: DoubleArray,
        val x: Double,
        val y: Double,
        val z: Double,
        val shirt: Int,
        val down: Boolean,
        val held: Weapon = Weapon.NONE,
        /** Sitting in the car: drawn with it, not on its own. */
        val inCar: Boolean = false,
    ) {
        val level get() = HouseWorld.levelOf(y)
    }
}

/**
 * A two-storey block house for everyone in the channel who opens it: walk around as a blocky
 * figure, jump on the furniture, wave, cheer, dance, sit, and knock each other over with a slap, a
 * bat or a thrown potted plant. Joining, invites and the message budget: [ChannelArena].
 *
 * Like the other arenas there's no host. Each client owns its figure and broadcasts its state ~3×/s
 * while walking; everyone dead-reckons the rest through the same map (and the same jump physics)
 * in between.
 *
 * **The victim decides a hit.** For a swing, the swinger names who it hit; the victim checks the
 * swinger is actually within reach on its own screen, isn't lying down, and that it isn't still
 * shaking off the last one, then falls over and announces it. A thrown plant names nobody: every
 * client flies it from the same throw, and each one checks it only against its own figure. A
 * hostile client can refuse to fall, but it can only knock someone over from a bat's length or with
 * a plant it picked up, at most once per [IMMUNE_MS] after they get up.
 *
 * Weapon spots are tracked per client: taking one tells everyone ([HouseMessage.Got]) so it vanishes
 * for [HouseWorld.RESPAWN_MS]; if that message is lost, two people may hold the same bat, which is
 * harmless.
 */
class HouseArena(
    send: (receivers: List<Int>, message: HouseMessage) -> Unit,
    self: () -> Int?,
    channelPeers: () -> Map<Int, String>,
    enabled: () -> Boolean,
    onInvite: (name: String) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : ChannelArena<HouseMessage, HouseArena.Peer>(
    send, self, channelPeers, enabled, onInvite, clock,
    HouseMessage.Hello, HouseMessage.Bye, HouseMessage::decode,
) {
    class Peer(name: String) : ChannelArena.Peer(name) {
        var state: HouseMessage.State? = null
        var stateAt = 0L
        var lastSeq = -1
        var dx = 0.0
        var dz = 0.0
        var dh = 0.0
        /** The dead-reckoned body (x, y, z, vy), run [bodyT] seconds past the last state. */
        private val body = DoubleArray(4)
        private var bodyT = 0.0
        /** Where we draw them this frame: x, y, z, heading, and 1 if in the air. */
        var pos: DoubleArray? = null
        var held = Weapon.NONE
        var emote = Emote.NONE
        var emoteSince = 0L
        var swings = -1
        var swingAt = Long.MIN_VALUE / 2
        var swingKind = Weapon.NONE
        var tosses = 0
        var walk = 0.0
        var stride = 0.0
        var lastX = Double.NaN
        var lastZ = 0.0
        var ragdoll: Ragdoll? = null
        /** Their highest swing / throw that knocked us down: each one counts once. */
        var slapTaken = 0
        var tossTaken = 0
        /** Our highest swing / throw they said knocked them down. */
        var credited = 0
        var tossCredited = 0

        /** At the wheel, as their last state says. */
        val inCar get() = state?.let { it.car >= 0 && !it.down } == true

        /** The car's speed along its heading, from their last state. */
        val carSpeed get() = state?.let { it.vx * sin(it.h) + it.vz * cos(it.h) } ?: 0.0

        fun reset(s: HouseMessage.State) {
            body[0] = s.x; body[1] = s.y; body[2] = s.z; body[3] = s.vy
            bodyT = 0.0
        }

        /** Where to draw them at [now]; on foot, the [car] (x, z, h) is in their way. */
        fun display(now: Long, car: DoubleArray?): DoubleArray? {
            val s = state ?: return null
            val t = (now - stateAt) / 1000.0
            val k = exp(-(now - stateAt) / SMOOTH_MS)
            if (inCar) {
                val c = Car.coast(s.x, s.z, s.h, carSpeed, s.turn, min(t, MAX_EXTRAPOLATE_MS / 1000.0))
                return doubleArrayOf(c[0] + dx * k, 0.0, c[1] + dz * k, c[2] + dh * k, 0.0)
            }
            if (!s.down && t > bodyT) {
                // Walk the guess through the map, so it slides along walls, climbs stairs and lands
                // jumps like they do; past [MAX_EXTRAPOLATE_MS] it stops walking but still falls.
                val walkEnd = MAX_EXTRAPOLATE_MS / 1000.0
                if (bodyT < walkEnd) {
                    val e = min(t, walkEnd)
                    HouseWorld.simulate(body, s.vx, s.vz, e - bodyT, car)
                    bodyT = e
                }
                val e = min(t, MAX_FALL_S)
                if (e > bodyT) {
                    HouseWorld.simulate(body, 0.0, 0.0, e - bodyT, car)
                    bodyT = e
                }
            }
            val air = body[3] != 0.0 || !HouseWorld.grounded(body[0], body[1], body[2])
            return doubleArrayOf(body[0] + dx * k, body[1], body[2] + dz * k, s.h + dh * k, if (air) 1.0 else 0.0)
        }
    }

    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var vy = 0.0
    private var h = 0.0
    private var vx = 0.0
    private var vz = 0.0
    private var walk = 0.0
    private var stride = 0.0
    private var emote = Emote.NONE
    private var emoteSince = 0L
    private var held = Weapon.NONE
    private var swings = 0
    private var tosses = 0
    private var lastSwing = Long.MIN_VALUE / 2
    private var swingKind = Weapon.NONE
    private var ragdoll: Ragdoll? = null
    private var slappedBy: String? = null
    private var slappedWith = Weapon.NONE
    private var immuneUntil = 0L
    private var shirt = 0
    private var seq = 0
    private var lastSent = 0L
    private var sendNow = false
    private var wasMoving = false
    private var wasAir = false
    private var lastStep = 0L
    private val sounds = ArrayList<HouseSoundEvent>()
    /** When each spot's weapon is back (0 = there now). */
    private val spotBack = LongArray(HouseWorld.spots.size)
    /** Spots we were standing on when we let go of something: not picked from until we step off. */
    private val steppedOff = BooleanArray(HouseWorld.spots.size) { true }
    private val pots = ArrayList<Pot>()
    private val shards = ArrayList<Pair<DoubleArray, Long>>()
    private val glassShards = ArrayList<Pair<DoubleArray, Long>>()
    /**
     * When each broken pane is whole again, by box index. Not sent: every client flies the same
     * pots through the same windows, so they break the same glass.
     */
    private val paneBack = HashMap<Int, Long>()

    // The car. Its driver runs it and says where it is; everyone else follows. Parked, it stays
    // where it was last seen.
    private val car = Car.Motion(Car.HOME_X, Car.HOME_Z, Car.HOME_H)
    private var carHealth = Car.MAX_HEALTH
    /** Who's at the wheel: our own session while [driving], a peer's, or null when it's parked. */
    private var driver: Int? = null
    private var driving = false
    /** When it blew up: a wreck until [Car.WRECK_MS] later, then a new one is in the garage. */
    private var wreckedAt: Long? = null
    /** We parked it last, so we tell anyone who turns up where it is. */
    private var parkedIt = false
    /** Blown up at the wheel: we get up back at the start, not in the wreck. */
    private var killed = false

    override fun newPeer(name: String) = Peer(name)

    override fun begin(now: Long) {
        lastStep = now
        shirt = shirtChoice ?: random.nextInt(HouseMessage.SHIRTS).also { shirtChoice = it }
        spawn()
        sendNow = true
    }

    override fun end() {
        ragdoll = null; slappedBy = null
        emote = Emote.NONE
        held = Weapon.NONE
        swings = 0; tosses = 0; seq = 0
        vx = 0.0; vz = 0.0; vy = 0.0
        sendNow = false; wasMoving = false; wasAir = false
        spotBack.fill(0)
        steppedOff.fill(true)
        pots.clear(); shards.clear(); glassShards.clear(); paneBack.clear()
        sounds.clear()
        car.home(); carHealth = Car.MAX_HEALTH
        driver = null; driving = false; wreckedAt = null; parkedIt = false; killed = false
    }

    override fun onHello(peer: Peer, now: Long) {
        // They may be rejoining: fresh counters start from scratch.
        peer.lastSeq = -1; peer.swings = -1; peer.tosses = 0
        peer.slapTaken = 0; peer.credited = 0; peer.tossTaken = 0; peer.tossCredited = 0
        sendNow = true
        // Nobody else knows where we left the car.
        if (parkedIt && driver == null && wreckedAt == null && take()) broadcast(parkedMessage())
    }

    override fun onMessage(sender: Int, name: String, msg: HouseMessage, now: Long) {
        when (msg) {
            is HouseMessage.State -> onState(sender, name, msg, now)
            is HouseMessage.Slap -> onSlap(sender, msg, now)
            is HouseMessage.Ow -> onOw(sender, msg, now)
            is HouseMessage.Toss -> onToss(sender, msg, now)
            is HouseMessage.Got -> if (peers.containsKey(sender)) spotBack[msg.spot] = now + HouseWorld.RESPAWN_MS
            is HouseMessage.Parked -> onParked(sender, msg)
            is HouseMessage.Boom -> onBoom(sender, msg, now)
            else -> {}
        }
    }

    private fun onState(sender: Int, name: String, m: HouseMessage.State, now: Long) {
        val p = peer(sender, name) ?: return
        if (m.seq <= p.lastSeq) return
        p.lastSeq = m.seq
        p.lastHeard = now
        val before = p.display(now, carBlock())
        val prev = p.state
        val wasInCar = p.inCar
        val inCar = m.car >= 0 && !m.down
        // Blend from where we drew them, unless they got up, changed floors, got in or out of the
        // car, or jumped too far.
        if (before != null && prev != null && !prev.down && !m.down && wasInCar == inCar &&
            HouseWorld.levelOf(before[1]) == HouseWorld.levelOf(m.y) &&
            hypot(before[0] - m.x, before[2] - m.z) < (if (inCar) 6.0 else 2.5)
        ) {
            p.dx = before[0] - m.x
            p.dz = before[2] - m.z
            p.dh = angleDiff(before[3], m.h)
        } else {
            p.dx = 0.0; p.dz = 0.0; p.dh = 0.0
        }
        // A hard stop at speed: they hit something.
        if (wasInCar && inCar && abs(p.carSpeed) > 4.5 && abs(p.carSpeed - (m.vx * sin(m.h) + m.vz * cos(m.h))) > 4.5) {
            sounds += HouseSoundEvent(HouseSound.CRASH, m.x, m.z)
        }
        p.state = m
        p.stateAt = now
        p.reset(m)
        p.held = m.held
        if (m.emote != p.emote) { p.emote = m.emote; p.emoteSince = now }
        if (inCar) tookTheWheel(sender, m) else if (driver == sender) driver = null
        p.pos = p.display(now, carBlock())
        if (m.swings > p.swings) {
            if (p.swings >= 0) swung(p, now, m.held)
            p.swings = m.swings
        }
        // Down without our having heard the "ow": they just fall backward.
        if (m.down && p.ragdoll == null) fall(p, m.h + PI, now, Weapon.NONE, seated = wasInCar)
        if (!m.down) p.ragdoll = null
    }

    private fun swung(p: Peer, now: Long, with: Weapon) {
        p.swingAt = now
        p.swingKind = with
        p.pos?.let { sounds += HouseSoundEvent(if (p.inCar) HouseSound.HONK else HouseSound.SWISH, it[0], it[2]) }
    }

    /**
     * [sender] says they're driving. One car, so if two people jumped in at once the lower session
     * keeps it, the same on every client, and the other gets out.
     */
    private fun tookTheWheel(sender: Int, m: HouseMessage.State) {
        val d = driver
        if (driving) {
            if (sender > (self() ?: Int.MAX_VALUE)) return
            driving = false
            getOut(push = false)
        } else if (d != null && d != sender && sender > d && peers[d]?.inCar == true) {
            return
        }
        driver = sender
        carHealth = m.car.toDouble()
        wreckedAt = null
        parkedIt = false
    }

    private fun onParked(sender: Int, m: HouseMessage.Parked) {
        if (!peers.containsKey(sender) || driving || (driver != null && driver != sender)) return
        if (Car.blocked(m.x, m.z, m.h)) return
        car.x = m.x; car.z = m.z; car.h = m.h; car.v = 0.0; car.turn = 0.0
        carHealth = m.health.toDouble()
        driver = null
        wreckedAt = null
        parkedIt = false
    }

    private fun onBoom(sender: Int, m: HouseMessage.Boom, now: Long) {
        val p = peers[sender] ?: return
        // Only the car they're driving, and about where we see it.
        if (driver != sender || hypot(m.x - car.x, m.z - car.z) > 6.0) return
        car.x = m.x; car.z = m.z
        blewUp(now)
        addFeed("${p.name}'s car blew up", now)
        if (p.ragdoll == null) fall(p, random.nextDouble(0.0, 2 * PI), now, Weapon.BLAST, seated = true)
        blast(sender, p.name, now)
    }

    /** The car is a wreck from [now]: nobody's in it, and it burns for a while. */
    private fun blewUp(now: Long) {
        wreckedAt = now
        carHealth = 0.0
        driver = null
        car.v = 0.0; car.turn = 0.0
        parkedIt = false
        sounds += HouseSoundEvent(HouseSound.BOOM, car.x, car.z)
    }

    /** Knocked flying if we're near the car when it goes up (we judge it, like any hit). */
    private fun blast(owner: Int, ownerName: String, now: Long) {
        if (driving || ragdoll != null || now < immuneUntil || y > 2.0) return
        val d = hypot(x - car.x, z - car.z)
        if (d > Car.BLAST_RADIUS + Car.HALF_W) return
        val dir = if (d < 0.05) h + PI else atan2(x - car.x, z - car.z)
        knockDown(dir, ownerName, Weapon.BLAST)
        addFeed("$ownerName's car blew up on you", now)
        take(force = true)
        broadcast(HouseMessage.Ow(owner, 1, dir, Weapon.BLAST))
    }

    private fun onSlap(sender: Int, m: HouseMessage.Slap, now: Long) {
        val p = peers[sender] ?: return
        // A bat only counts if their last state showed one in their hands.
        val with = if (m.weapon == Weapon.BAT && p.held == Weapon.BAT) Weapon.BAT else Weapon.NONE
        if (m.swing > p.swings) { swung(p, now, with); p.swings = m.swing }
        if (m.victim != self()) return
        if (driving) return dented(p, m.swing, with, now)
        if (ragdoll != null || now < immuneUntil || m.swing <= p.slapTaken || p.ragdoll != null) return
        val sp = p.pos ?: return
        val d = hypot(x - sp[0], z - sp[2])
        val tolerance = if (with == Weapon.BAT) BAT_REACH_TOLERANCE else REACH_TOLERANCE
        if (d > tolerance || abs(sp[1] - y) > 1.2) return
        p.slapTaken = m.swing
        val dir = if (d < 0.05) sp[3] else atan2(x - sp[0], z - sp[2])
        knockDown(dir, p.name, with)
        addFeed(if (with == Weapon.BAT) "${p.name} bonked you with a bat" else "${p.name} slapped you", now)
        take(force = true)
        broadcast(HouseMessage.Ow(sender, m.swing, dir, with))
    }

    /** [p] swung at the car we're driving: a dent, if they were near enough to its side. */
    private fun dented(p: Peer, swing: Int, with: Weapon, now: Long) {
        if (swing <= p.slapTaken) return
        val sp = p.pos ?: return
        val reach = if (with == Weapon.BAT) BAT_REACH_TOLERANCE else REACH_TOLERANCE
        if (sp[1] > 1.2 || Car.distance(car.x, car.z, car.h, sp[0], sp[2]) > reach - 0.6) return
        p.slapTaken = swing
        sounds += HouseSoundEvent(HouseSound.BONK, car.x, car.z)
        damageCar(if (with == Weapon.BAT) BAT_DENT else HAND_DENT, now)
    }

    private fun onToss(sender: Int, m: HouseMessage.Toss, now: Long) {
        val p = peers[sender] ?: return
        if (m.n <= p.tosses) return
        p.tosses = m.n
        // It has to leave from about where we see them.
        val sp = p.pos ?: return
        if (hypot(sp[0] - m.x, sp[2] - m.z) > TOSS_TOLERANCE || abs(sp[1] - m.y) > TOSS_TOLERANCE) return
        pots += Pot(sender, m.n, m.x, m.y, m.z, m.h, m.vy)
        p.held = Weapon.NONE
        swung(p, now, Weapon.PLANT)
    }

    private fun onOw(victim: Int, m: HouseMessage.Ow, now: Long) {
        val v = peers[victim] ?: return
        if (m.weapon == Weapon.PLANT) pots.firstOrNull { it.owner == m.slapper && it.n == m.swing }?.smash()
        if (v.ragdoll != null) return
        fall(v, m.dir, now, m.weapon)
        v.pos?.let { sounds += HouseSoundEvent(hitSound(m.weapon), it[0], it[2]) }
        val mine = m.slapper == self()
        if (m.weapon == Weapon.CAR || m.weapon == Weapon.BLAST) {
            // No counters to check: they fell over, and they say why.
            val by = if (mine) "You" else peers[m.slapper]?.name
            val what = if (m.weapon == Weapon.CAR) "ran over" else "blew up"
            addFeed(if (by != null) "$by $what ${v.name}" else "${v.name} got run over", now)
        } else if (mine && m.weapon == Weapon.PLANT) {
            // Credit only a throw or swing we actually made, once.
            if (m.swing in 1..tosses && m.swing > v.tossCredited) {
                v.tossCredited = m.swing
                addFeed("Your plant beaned ${v.name}", now)
            }
        } else if (mine) {
            if (m.swing in 1..swings && m.swing > v.credited) {
                v.credited = m.swing
                addFeed(if (m.weapon == Weapon.BAT) "You bonked ${v.name}" else "You slapped ${v.name}", now)
            }
        } else {
            val by = peers[m.slapper]?.name
            val verb = when (m.weapon) {
                Weapon.NONE -> "slapped"
                Weapon.BAT -> "bonked"
                Weapon.PLANT -> "beaned"
                Weapon.CAR, Weapon.BLAST -> "hit"
            }
            addFeed(if (by != null) "$by $verb ${v.name}" else "${v.name} got $verb", now)
        }
    }

    /** Knock [p] over toward [dir]; [seated] if they were in the car, so they fly out of the seat. */
    private fun fall(p: Peer, dir: Double, now: Long, with: Weapon, seated: Boolean = p.inCar) {
        val pos = p.pos ?: return
        val joints = if (seated) Car.seat(pos[0], pos[2], pos[3])
        else Blocky.pose(pos[0], pos[1], pos[2], pos[3], p.walk, p.stride, p.emoteAt(now), now - p.emoteSince)
        p.ragdoll = Ragdoll(joints, dir, HouseWorld.levelOf(pos[1]), power(with))
        p.held = Weapon.NONE
    }

    private fun Peer.emoteAt(now: Long): Emote =
        if (emote.durationMs > 0 && now - emoteSince > emote.durationMs) Emote.NONE else emote

    private fun knockDown(dir: Double, by: String, with: Weapon) {
        val now = clock()
        ragdoll = Ragdoll(myJoints(now), dir, HouseWorld.levelOf(y), power(with))
        slappedBy = by
        slappedWith = with
        emote = Emote.NONE
        held = Weapon.NONE
        vx = 0.0; vz = 0.0; vy = 0.0
        sounds += HouseSoundEvent(hitSound(with), x, z)
        sendNow = true
    }

    /** Swing at whoever is in front of us and within reach, or throw the plant we're holding. */
    @Synchronized
    fun attack() {
        if (!active.value || ragdoll != null) return
        val now = clock()
        if (now - lastSwing < SWING_COOLDOWN_MS) return
        if (driving) {
            // At the wheel the button honks: the swing counter carries it.
            lastSwing = now
            swings++
            sounds += HouseSoundEvent(HouseSound.HONK, x, z)
            sendNow = true
            return
        }
        if (held == Weapon.PLANT) return throwPlant(now)
        val with = held
        lastSwing = now
        swingKind = with
        swings++
        emote = Emote.NONE
        sounds += HouseSoundEvent(HouseSound.SWISH, x, z)
        val reach = if (with == Weapon.BAT) BAT_REACH else REACH
        val target = nearestInFront(reach, REACH_ANGLE, 1.0)
        if (target == null && hitCar(reach, now, with)) return
        if (target != null) {
            val q = peers.getValue(target).pos!!
            h = atan2(q[0] - x, q[2] - z)
            // The slap itself says we swung; without a token, the next state's counter will.
            if (take()) {
                broadcast(HouseMessage.Slap(target, swings, with))
                return
            }
        }
        sendNow = true
    }

    /**
     * Swing at the car if its side is in reach in front of us: a dent for whoever's driving (they
     * judge it), and a clang either way. Returns whether we hit it.
     */
    private fun hitCar(reach: Double, now: Long, with: Weapon): Boolean {
        if (wreckedAt != null || y > 1.0 || Car.distance(car.x, car.z, car.h, x, z) > reach - 0.6) return false
        val q = Car.nearest(car.x, car.z, car.h, x, z)
        val a = if (hypot(q[0] - x, q[1] - z) < 0.05) h else atan2(q[0] - x, q[1] - z)
        if (abs(angleDiff(a, h)) > REACH_ANGLE) return false
        h = a
        sounds += HouseSoundEvent(HouseSound.BONK, q[0], q[1])
        val d = driver
        if (d != null && d != self() && take()) {
            broadcast(HouseMessage.Slap(d, swings, with))
            return true
        }
        sendNow = true
        return true
    }

    /** Whoever's nearest within [range] and [angle] of straight ahead; drivers only if [cars]. */
    private fun nearestInFront(range: Double, angle: Double, dy: Double, cars: Boolean = false): Int? = peers.entries
        .mapNotNull { (s, p) -> if (p.ragdoll != null || (p.inCar && !cars)) null else p.pos?.let { s to it } }
        .filter { (_, q) ->
            abs(q[1] - y) < dy && hypot(q[0] - x, q[2] - z) <= range &&
                abs(angleDiff(atan2(q[0] - x, q[2] - z), h)) < angle
        }
        .minByOrNull { (_, q) -> hypot(q[0] - x, q[2] - z) }?.first

    private fun throwPlant(now: Long) {
        lastSwing = now
        swingKind = Weapon.PLANT
        tosses++
        letGo()
        emote = Emote.NONE
        // Aim at whoever's ahead: face them, and loft it so it comes down on their middle.
        var rise = THROW_RISE
        nearestInFront(THROW_RANGE, THROW_ANGLE, 3.5, cars = true)?.let { t ->
            val q = peers.getValue(t).pos!!
            h = atan2(q[0] - x, q[2] - z)
            val d = max(0.5, hypot(q[0] - x, q[2] - z) - 0.45)
            val time = d / Pot.SPEED
            rise = ((q[1] + 1.0 - (y + 1.45)) / time + HouseWorld.GRAVITY * time / 2).coerceIn(-2.0, 8.0)
        }
        val sx = x + sin(h) * 0.45
        val sz = z + cos(h) * 0.45
        val sy = y + 1.45
        pots += Pot(self() ?: -1, tosses, sx, sy, sz, h, rise)
        sounds += HouseSoundEvent(HouseSound.SWISH, x, z)
        // Rare (one per plant picked up), and a thrown plant must not go missing: spend into debt.
        take(force = true)
        broadcast(HouseMessage.Toss(tosses, sx, sy, sz, h, rise))
        sendNow = true
    }

    /** Hop, if we're standing on something. */
    @Synchronized
    fun jump() {
        if (!active.value || ragdoll != null || driving) return
        if (vy != 0.0 || !HouseWorld.grounded(x, y, z)) return
        vy = HouseWorld.JUMP_SPEED
        if (emote != Emote.NONE) emote = Emote.NONE
        sounds += HouseSoundEvent(HouseSound.JUMP, x, z)
        sendNow = true
    }

    /** Put down what we're holding (it doesn't go back to its spot; that refills on its own). */
    @Synchronized
    fun drop() {
        if (!active.value || held == Weapon.NONE) return
        letGo()
        sendNow = true
    }

    /** Start [e], or stop it if it's already going. */
    @Synchronized
    fun emote(e: Emote) {
        if (!active.value || ragdoll != null || driving) return
        emote = if (emote == e) Emote.NONE else e
        emoteSince = clock()
        sendNow = true
    }

    /** Get in the car if we're beside it and it's free, or out if we're driving. */
    @Synchronized
    fun car() {
        if (!active.value || ragdoll != null) return
        if (driving) {
            driving = false
            getOut(push = true)
            return
        }
        if (!canGetIn()) return
        letGo()
        emote = Emote.NONE
        driving = true
        driver = self()
        parkedIt = false
        x = car.x; z = car.z; y = 0.0; h = car.h; vy = 0.0
        car.v = 0.0; car.turn = 0.0
        sounds += HouseSoundEvent(HouseSound.PICKUP, x, z)
        sendNow = true
    }

    private fun canGetIn() = !driving && ragdoll == null && driver == null && wreckedAt == null &&
        y < 0.5 && vy == 0.0 && Car.distance(car.x, car.z, car.h, x, z) < Car.REACH

    /** Climb out beside the car (it stops dead), and if [push], tell everyone where it's parked. */
    private fun getOut(push: Boolean) {
        if (driver == self()) driver = null
        car.v = 0.0; car.turn = 0.0
        vx = 0.0; vz = 0.0; vy = 0.0
        val door = Car.doors(car.x, car.z, car.h).firstOrNull { roomFor(it[0], it[1], 0.0) }
        // Boxed in on every side: out over the top, wherever that lands us.
        val at = door ?: Car.local(car.x, car.z, car.h, -(Car.HALF_W + 0.6), 0.0)
        x = at[0]; z = at[1]; y = HouseWorld.groundAt(x, z, 0.0)
        stride = 0.0
        sendNow = true
        if (push) {
            parkedIt = true
            take(force = true)
            broadcast(parkedMessage())
        }
    }

    private fun parkedMessage() = HouseMessage.Parked(car.x, car.z, car.h, carHealth.roundToInt().coerceIn(0, 100))

    /** Whether a walker fits at (x, z) standing at [y], car and all. */
    private fun roomFor(x: Double, z: Double, y: Double): Boolean =
        HouseWorld.free(x, z, y) && (y >= Car.HEIGHT || !Car.contains(car.x, car.z, car.h, x, z, HouseWorld.RADIUS))

    /** The car's footprint for walkers to bump into, unless we're in it. */
    private fun carBlock(): DoubleArray? = if (driving) null else doubleArrayOf(car.x, car.z, car.h)

    private fun damageCar(amount: Double, now: Long) {
        if (!driving || amount <= 0) return
        carHealth -= amount
        sendNow = true
        if (carHealth <= 0) explode(now)
    }

    /** Our car's health ran out: it goes up, and takes us with it. */
    private fun explode(now: Long) {
        // Thrown out of the seat, so the ragdoll starts there.
        knockDown(random.nextDouble(0.0, 2 * PI), "", Weapon.BLAST)
        slappedBy = null
        driving = false
        killed = true
        blewUp(now)
        addFeed("Your car blew up", now)
        take(force = true)
        broadcast(HouseMessage.Boom(car.x, car.z))
    }

    @Synchronized
    fun cycleShirt() {
        if (!active.value) return
        shirt = (shirt + 1) % HouseMessage.SHIRTS
        shirtChoice = shirt
        sendNow = true
    }

    /** Advance one frame and return what to draw, or null if the house isn't open. */
    @Synchronized
    fun step(input: HouseInput): HouseView? {
        if (!active.value) return null
        val now = clock()
        val dtMs = (now - lastStep).coerceIn(0, 100)
        lastStep = now
        housekeep(now)

        val rd = ragdoll
        if (rd != null) {
            rd.advance(dtMs)
            if (rd.ageMs >= Ragdoll.DOWN_MS) getUp(rd, now)
        } else if (driving) {
            drive(input, dtMs / 1000.0, now)
        } else {
            walk(input, dtMs / 1000.0)
            pickUp(now)
        }
        if (emote.durationMs > 0 && now - emoteSince > emote.durationMs) emote = Emote.NONE
        for (p in peers.values) tickPeer(p, dtMs, now)
        followCar(now)
        runOver(now)
        flyPots(dtMs, now)

        val moving = vx != 0.0 || vz != 0.0
        if (moving != wasMoving) { sendNow = true; wasMoving = moving }
        val air = vy != 0.0
        // Landing says where we came down; taking off was already sent by [jump].
        if (air != wasAir) { if (!air && ragdoll == null) sendNow = true; wasAir = air }
        val interval = if (moving) STATE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (peers.isNotEmpty() && (sendNow || now - lastSent >= interval) && take()) sendState(now)
        greetStrangers(now)
        return view(now)
    }

    private fun drive(input: HouseInput, dt: Double, now: Long) {
        if (dt <= 0) return
        val hit = Car.drive(car, input.mx, input.mz, dt)
        x = car.x; z = car.z; y = 0.0; h = car.h
        vx = sin(car.h) * car.v
        vz = cos(car.h) * car.v
        if (abs(car.v) < 0.05 && abs(input.mx) + abs(input.mz) < 0.1) { vx = 0.0; vz = 0.0 }
        if (hit > 1.5) {
            sounds += HouseSoundEvent(HouseSound.CRASH, x, z)
            sendNow = true
        }
        if (hit > Car.CRASH_SPEED) damageCar((hit - Car.CRASH_SPEED) * Car.CRASH_DAMAGE, now)
    }

    /** Keep the car under whoever's driving it, and bring a new one once the wreck has burnt out. */
    private fun followCar(now: Long) {
        val d = driver
        if (d != null && d != self()) {
            val p = peers[d]
            val pos = p?.pos
            if (p == null || !p.inCar || pos == null) {
                driver = null
                car.v = 0.0
            } else {
                car.x = pos[0]; car.z = pos[2]; car.h = pos[3]
                car.v = p.carSpeed
            }
        }
        val w = wreckedAt
        if (w != null && now - w >= Car.WRECK_MS) {
            wreckedAt = null
            car.home()
            carHealth = Car.MAX_HEALTH
        }
    }

    /** Someone else's car going fast enough runs us over. We judge it, as with any hit. */
    private fun runOver(now: Long) {
        val d = driver ?: return
        val p = peers[d] ?: return
        if (driving || ragdoll != null || now < immuneUntil || y > 1.0 || wreckedAt != null) return
        val v = p.carSpeed
        if (abs(v) < Car.RUN_OVER_SPEED || !Car.contains(car.x, car.z, car.h, x, z, HouseWorld.RADIUS)) return
        // Thrown the way it's going, and out to the side we're on.
        val side = (x - car.x) * cos(car.h) - (z - car.z) * sin(car.h)
        val dir = (if (v > 0) car.h else car.h + PI) + (if (side > 0) 0.5 else -0.5) * (if (v > 0) 1 else -1)
        knockDown(dir, p.name, Weapon.CAR)
        addFeed("${p.name} ran you over", now)
        take(force = true)
        broadcast(HouseMessage.Ow(d, 1, dir, Weapon.CAR))
    }

    private fun walk(input: HouseInput, dt: Double) {
        if (dt <= 0) return
        var mx = input.mx
        var mz = input.mz
        val m = hypot(mx, mz)
        if (m > 1) { mx /= m; mz /= m }
        val body = doubleArrayOf(x, y, z, vy)
        val block = carBlock()
        if (m < DEAD_ZONE) {
            HouseWorld.simulate(body, 0.0, 0.0, dt, block)
            stride = max(0.0, stride - dt * 6)
        } else {
            if (emote != Emote.NONE) { emote = Emote.NONE; sendNow = true }
            h = turn(h, atan2(mx, mz), TURN_RATE * dt)
            HouseWorld.simulate(body, mx * HouseWorld.WALK_SPEED, mz * HouseWorld.WALK_SPEED, dt, block)
            val moved = hypot(body[0] - x, body[2] - z)
            walk += moved * STRIDE_K
            stride = approach(stride, (moved / dt / HouseWorld.WALK_SPEED).coerceIn(0.0, 1.0), dt * 8)
        }
        vx = (body[0] - x) / dt
        vz = (body[2] - z) / dt
        if (abs(vx) < 1e-9 && abs(vz) < 1e-9) { vx = 0.0; vz = 0.0 }
        x = body[0]; y = body[1]; z = body[2]; vy = body[3]
    }

    private fun letGo() {
        held = Weapon.NONE
        for (k in HouseWorld.spots.indices) if (onSpot(k)) steppedOff[k] = false
    }

    private fun onSpot(k: Int): Boolean {
        val spot = HouseWorld.spots[k]
        return abs(spot.y - y) <= 0.7 && hypot(spot.x - x, spot.z - z) <= PICKUP_RADIUS
    }

    private fun pickUp(now: Long) {
        for (k in HouseWorld.spots.indices) if (!steppedOff[k] && !onSpot(k)) steppedOff[k] = true
        if (held != Weapon.NONE || vy != 0.0) return
        for ((k, spot) in HouseWorld.spots.withIndex()) {
            if (now < spotBack[k] || !steppedOff[k] || !onSpot(k)) continue
            held = spot.weapon
            spotBack[k] = now + HouseWorld.RESPAWN_MS
            sounds += HouseSoundEvent(HouseSound.PICKUP, x, z)
            if (take()) broadcast(HouseMessage.Got(k))
            sendNow = true
            return
        }
    }

    private fun tickPeer(p: Peer, dtMs: Long, now: Long) {
        p.pos = p.display(now, carBlock())
        p.ragdoll?.let {
            it.advance(dtMs)
            if (it.ageMs > Ragdoll.DOWN_MS + LOST_MS) p.ragdoll = null
        }
        val pos = p.pos ?: return
        if (!p.lastX.isNaN() && dtMs > 0) {
            val d = hypot(pos[0] - p.lastX, pos[2] - p.lastZ)
            p.walk += d * STRIDE_K
            p.stride = approach(p.stride, (d / (dtMs / 1000.0) / HouseWorld.WALK_SPEED).coerceIn(0.0, 1.0), dtMs / 1000.0 * 8)
        }
        p.lastX = pos[0]; p.lastZ = pos[2]
    }

    private fun flyPots(dtMs: Long, now: Long) {
        val me = self()
        for (pot in pots) {
            if (pot.smashedAt != null) continue
            var hitMe = false
            var hitCar = false
            pot.advance(dtMs, glass = { k, pt ->
                if ((paneBack[k] ?: 0L) <= now) {
                    paneBack[k] = now + HouseWorld.PANE_RESPAWN_MS
                    glassShards += pt to now
                    sounds += HouseSoundEvent(HouseSound.SHATTER, pt[0], pt[2])
                }
            }) { pt ->
                if (pt[1] < Car.HEIGHT && Car.contains(car.x, car.z, car.h, pt[0], pt[2], 0.15)) {
                    // Parked or driven, the car stops it; only the driver's dent counts.
                    hitCar = driving && pot.owner != me
                    true
                } else if (pot.owner != me && ragdoll == null && !driving && Pot.touches(pt, x, y, z)) {
                    hitMe = true
                    true
                } else {
                    // Others are judged by their own clients; here it just breaks on whoever we see.
                    peers.any { (s, p) -> s != pot.owner && p.ragdoll == null && !p.inCar && p.pos?.let { Pot.touches(pt, it[0], it[1], it[2]) } == true }
                }
            }
            if (hitMe) beaned(pot, now)
            if (hitCar) damageCar(PLANT_DENT, now)
        }
        pots.removeAll { pot ->
            val at = pot.smashedAt ?: return@removeAll false
            shards += at to now
            sounds += HouseSoundEvent(HouseSound.SMASH, at[0], at[2])
            true
        }
        shards.removeAll { now - it.second > Pot.SHARDS_MS }
        glassShards.removeAll { now - it.second > Pot.SHARDS_MS }
        paneBack.values.removeAll { it <= now }
    }

    private fun beaned(pot: Pot, now: Long) {
        val p = peers[pot.owner] ?: return
        if (now < immuneUntil || pot.n <= p.tossTaken) return
        p.tossTaken = pot.n
        knockDown(pot.heading, p.name, Weapon.PLANT)
        addFeed("${p.name} beaned you with a plant", now)
        take(force = true)
        broadcast(HouseMessage.Ow(pot.owner, pot.n, pot.heading, Weapon.PLANT))
    }

    /** Stand up where the ragdoll came to rest, or as near it as there's room. */
    private fun getUp(rd: Ragdoll, now: Long) {
        val pel = rd.pelvis()
        var spot: DoubleArray? = null
        search@ for (r in listOf(0.0, 0.4, 0.8, 1.2, 1.6)) {
            for (k in 0 until if (r == 0.0) 1 else 8) {
                val a = k * PI / 4
                val cx = pel[0] + r * sin(a)
                val cz = pel[2] + r * cos(a)
                val cy = HouseWorld.groundAt(cx, cz, pel[1])
                if (roomFor(cx, cz, cy)) { spot = doubleArrayOf(cx, cy, cz); break@search }
            }
        }
        spot?.let { x = it[0]; y = it[1]; z = it[2] }
        if (killed) {
            // Blown up: back to the start.
            killed = false
            spawn()
        }
        vy = 0.0
        ragdoll = null
        slappedBy = null
        immuneUntil = now + IMMUNE_MS
        stride = 0.0
        sendNow = true
    }

    private fun spawn() {
        // In the front yard, facing the house, as far from everyone as a few tries find.
        val others = peers.values.mapNotNull { it.pos }
        var best: DoubleArray? = null
        var bestDist = -1.0
        repeat(16) {
            val cx = random.nextDouble(-4.0, 4.0)
            val cz = random.nextDouble(-11.0, -7.0)
            if (!roomFor(cx, cz, 0.0)) return@repeat
            val d = others.minOfOrNull { hypot(it[0] - cx, it[2] - cz) } ?: Double.MAX_VALUE
            if (d > bestDist) { bestDist = d; best = doubleArrayOf(cx, cz) }
        }
        val b = best ?: doubleArrayOf(2.0, -9.0)
        x = b[0]; z = b[1]; y = 0.0; h = 0.0; vy = 0.0
        walk = 0.0; stride = 0.0
        immuneUntil = 0L
    }

    private fun sendState(now: Long) {
        seq++
        val c = if (driving) carHealth.roundToInt().coerceIn(0, 100) else -1
        broadcast(HouseMessage.State(seq, x, y, z, h, vx, vz, vy, emote, ragdoll != null, swings, shirt, held, c, if (driving) car.turn else 0.0))
        lastSent = now
        sendNow = false
    }

    private fun myJoints(now: Long) =
        if (driving) Car.seat(car.x, car.z, car.h)
        else Blocky.pose(x, y, z, h, walk, stride, emote, now - emoteSince, now - lastSwing, held, swingKind, vy != 0.0)

    private fun figure(session: Int, name: String, rd: Ragdoll?, standing: () -> DoubleArray, at: DoubleArray, shirt: Int, held: Weapon, inCar: Boolean = false): HouseView.Figure {
        if (rd != null) {
            val pel = rd.pelvis()
            return HouseView.Figure(session, name, rd.joints(), pel[0], pel[1] - 0.3, pel[2], shirt, down = true)
        }
        if (inCar) return HouseView.Figure(session, name, Car.seat(at[0], at[2], at[3]), at[0], 0.0, at[2], shirt, down = false, inCar = true)
        return HouseView.Figure(session, name, standing(), at[0], at[1], at[2], shirt, down = false, held = held)
    }

    private fun view(now: Long): HouseView {
        val me = figure(self() ?: -1, "You", ragdoll, { myJoints(now) }, doubleArrayOf(x, y, z, h), shirt, held, inCar = driving)
        val others = peers.mapNotNull { (session, p) ->
            val pos = p.pos ?: return@mapNotNull null
            val st = p.state ?: return@mapNotNull null
            figure(session, p.name, p.ragdoll, {
                Blocky.pose(
                    pos[0], pos[1], pos[2], pos[3], p.walk, p.stride, p.emoteAt(now), now - p.emoteSince,
                    now - p.swingAt, p.held, p.swingKind, pos[4] != 0.0,
                )
            }, pos, st.shirt, p.held, inCar = p.inCar)
        }
        val out = sounds.toList()
        sounds.clear()
        return HouseView(
            me = me,
            others = others,
            down = ragdoll != null,
            slappedBy = slappedBy,
            slappedWith = slappedWith,
            emote = emote,
            shirt = shirt,
            held = held,
            feed = feedLines(),
            players = listOf("You") + peers.values.map { it.name },
            sounds = out,
            spots = HouseWorld.spots.indices.filter { now >= spotBack[it] },
            pots = pots.map { val p = it.position(); HouseView.FlyingPot(p[0], p[1], p[2], it.heading, it.spin()) },
            shards = shards.map { (at, t) -> HouseView.Shards(at[0], at[1], at[2], now - t, (at[0] * 1000 + at[2] * 37).toInt()) } +
                glassShards.map { (at, t) -> HouseView.Shards(at[0], at[1], at[2], now - t, (at[0] * 1000 + at[2] * 37).toInt(), glass = true) },
            brokenPanes = paneBack.keys.toSet(),
            car = HouseView.CarView(
                car.x, car.z, car.h, carHealth, wreckedAt?.let { now - it } ?: -1,
                (listOf(me) + others).firstOrNull { it.inCar }, now,
            ),
            driving = driving,
            canDrive = canGetIn(),
        )
    }

    companion object {
        const val STATE_INTERVAL_MS = 333L
        const val IDLE_INTERVAL_MS = 1_000L
        const val SWING_COOLDOWN_MS = 600L
        /** After getting up, hits bounce off for this long. */
        const val IMMUNE_MS = 1_500L
        /** Arm's length, and a bat's, measured between the two figures' centres. */
        const val REACH = 1.5
        const val BAT_REACH = 2.2
        const val REACH_ANGLE = 1.3
        /** What the victim accepts: reach plus slack for where its screen and ours disagree. */
        const val REACH_TOLERANCE = 2.8
        const val BAT_REACH_TOLERANCE = 3.5
        /** How far from where we see the thrower a throw may start. */
        const val TOSS_TOLERANCE = 3.0
        const val PICKUP_RADIUS = 0.7
        /** Aim assist for throws: who counts as "ahead". */
        const val THROW_RANGE = 12.0
        const val THROW_ANGLE = 0.6
        /** Upward speed of a throw at nobody in particular. */
        const val THROW_RISE = 2.5
        /** What a hit does to the car's health. */
        const val HAND_DENT = 4.0
        const val BAT_DENT = 12.0
        const val PLANT_DENT = 15.0
        private const val LOST_MS = 3_000L
        private const val MAX_EXTRAPOLATE_MS = 600L
        private const val MAX_FALL_S = 2.0
        private const val SMOOTH_MS = 150.0
        private const val DEAD_ZONE = 0.15
        private const val TURN_RATE = 14.0
        /** Stride cycle per metre walked. */
        private const val STRIDE_K = 2 * PI / 1.5

        /** The last shirt picked, kept for the next time the house opens. */
        @Volatile private var shirtChoice: Int? = null

        /** How hard each weapon throws a ragdoll. */
        private fun power(w: Weapon) = when (w) {
            Weapon.NONE -> 1.0
            Weapon.BAT -> 1.7
            Weapon.PLANT -> 1.3
            Weapon.CAR -> 2.2
            Weapon.BLAST -> 2.8
        }

        private fun hitSound(w: Weapon) = when (w) {
            Weapon.NONE -> HouseSound.SLAP
            Weapon.BAT -> HouseSound.BONK
            // The pot's own smash covers the crash; this is the thump.
            Weapon.PLANT -> HouseSound.SLAP
            Weapon.CAR -> HouseSound.CRASH
            // The boom covers it.
            Weapon.BLAST -> HouseSound.SLAP
        }

        /** a − b, wrapped to (−π, π]. */
        internal fun angleDiff(a: Double, b: Double): Double {
            var d = (a - b) % (2 * PI)
            if (d > PI) d -= 2 * PI
            if (d <= -PI) d += 2 * PI
            return d
        }

        private fun turn(from: Double, to: Double, maxStep: Double): Double {
            val d = angleDiff(to, from)
            return from + d.coerceIn(-maxStep, maxStep)
        }

        private fun approach(v: Double, target: Double, step: Double) =
            if (v < target) min(target, v + step) else max(target, v - step)
    }
}
