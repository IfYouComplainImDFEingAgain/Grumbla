package app.notmumla.game.flight

import app.notmumla.game.arena.ChannelArena
import app.notmumla.game.flight.FlightMessage.Companion.BOOSTING
import app.notmumla.game.flight.FlightMessage.Companion.FIRING
import app.notmumla.game.flight.FlightMessage.Companion.ROLLING
import app.notmumla.game.flight.FlightWorld.angleDiff
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The controls this frame; stick axes are −1..1 (pitch +1 = nose up). */
data class FlightInput(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val fire: Boolean = false,
    val boost: Boolean = false,
    val brake: Boolean = false,
    val roll: Boolean = false,
)

/** Everything the renderer needs for one frame; immutable, so it can be drawn off the lock. */
data class FlightView(
    val me: Pose3,
    /** Drawn roll: banking into the turn, plus a barrel roll in progress. */
    val bank: Double,
    val alive: Boolean,
    /** Who shot us down, or null if we crashed (only meaningful while dead). */
    val killedBy: String?,
    val respawnInMs: Long,
    val shield: Float,
    val boost: Float,
    val turningBack: Boolean,
    val hurtAgoMs: Long,
    val ships: List<Ship>,
    val bolts: List<BoltView>,
    val explosions: List<Explosion>,
    val scores: List<Score>,
    val feed: List<String>,
) {
    data class Ship(val session: Int, val pose: Pose3, val bank: Double, val boosting: Boolean)
    data class BoltView(val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double, val mine: Boolean)
    data class Explosion(val x: Double, val y: Double, val z: Double, val ageMs: Long, val small: Boolean)
    data class Score(val name: String, val kills: Int, val deaths: Int, val me: Boolean)
}

/**
 * A free-for-all dogfight for everyone in the channel who opens it. Like the tank arena, there is
 * no host: each client flies its own ship and broadcasts its state ~3×/s; everyone dead-reckons the
 * rest. Lasers cost no messages: a ship's state says whether its trigger is held, and every client
 * fires that ship's bolts itself at the fixed rate. Joining, invites, budget: [ChannelArena].
 *
 * Damage is decided by the **victim**: each client checks bolts against its own ship only, keeps
 * its own shield, and announces only its death. A hostile client can refuse to die, but can't hurt
 * anyone, and nothing it sends does more than move a ship on our screen.
 */
class FlightArena(
    send: (receivers: List<Int>, message: FlightMessage) -> Unit,
    self: () -> Int?,
    channelPeers: () -> Map<Int, String>,
    enabled: () -> Boolean,
    onInvite: (name: String) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : ChannelArena<FlightMessage, FlightArena.Peer>(
    send, self, channelPeers, enabled, onInvite, clock,
    FlightMessage.Hello, FlightMessage.Bye, FlightMessage::decode,
) {
    class Peer(name: String) : ChannelArena.Peer(name) {
        var base: Pose3? = null
        var baseAt = 0L
        var dx = 0.0
        var dy = 0.0
        var dz = 0.0
        var dh = 0.0
        var dp = 0.0
        var dw = 0.0
        var alive = false
        var lastSeq = -1
        var fx = 0
        /** Shot counter as last reported (−1: not heard since they (re)joined)... */
        var shots = -1
        /** ...and as we've fired for them since. */
        var shotsSeen = 0
        var lastBolt = 0L
        var rollStart = Long.MIN_VALUE / 2
        var kills = 0
        var deaths = 0

        fun display(now: Long): Pose3? {
            val b = base ?: return null
            val age = now - baseAt
            val p = b.extrapolate(min(age, MAX_EXTRAPOLATE_MS) / 1000.0)
            // Ease out the jump to the new estimate instead of snapping.
            val k = exp(-age / SMOOTH_MS)
            return p.copy(
                x = p.x + dx * k, y = p.y + dy * k, z = p.z + dz * k,
                h = FlightWorld.wrap(p.h + dh * k), p = p.p + dp * k, w = p.w + dw * k,
            )
        }
    }

    private val bolts = ArrayList<Bolt>()
    private val explosions = ArrayList<Pair<DoubleArray, Long>>()
    private val sparks = ArrayList<Pair<DoubleArray, Long>>()
    private val credited = ArrayDeque<Pair<Int, Int>>()

    private var pose = Pose3(0.0, 60.0, 0.0, 0.0, 0.0)
    private var bank = 0.0
    private var alive = false
    private var deadUntil = 0L
    private var killedBy: String? = null
    private var shield = FlightWorld.SHIELD.toDouble()
    private var boostMeter = 1.0
    private var boosting = false
    private var firing = false
    private var turningBack = false
    private var rollStart = Long.MIN_VALUE / 2
    private var lastFire = 0L
    private var lastHurt = Long.MIN_VALUE / 2
    private var lastAttacker = -1
    private var lastAttackerShot = 0
    private var shots = 0
    private var kills = 0
    private var deaths = 0
    private var seq = 0
    private var lastSent = 0L
    private var sendNow = false
    private var lastStep = 0L

    override fun newPeer(name: String) = Peer(name)

    override fun begin(now: Long) {
        lastStep = now
        respawn(now)
    }

    override fun end() {
        bolts.clear(); explosions.clear(); sparks.clear(); credited.clear()
        shots = 0; kills = 0; deaths = 0; seq = 0
        killedBy = null; sendNow = false; firing = false
    }

    override fun onChannelChanged() = bolts.clear()

    override fun onHello(peer: Peer, now: Long) {
        // They may be rejoining: a fresh counter starts from scratch.
        peer.lastSeq = -1; peer.shots = -1
        sendNow = true
    }

    override fun onMessage(sender: Int, name: String, msg: FlightMessage, now: Long) {
        when (msg) {
            is FlightMessage.State -> onState(sender, name, msg, now)
            is FlightMessage.Hit -> onHit(sender, msg, now)
            else -> {}
        }
    }

    private fun onState(sender: Int, name: String, m: FlightMessage.State, now: Long) {
        val p = peer(sender, name) ?: return
        if (m.seq <= p.lastSeq) return
        p.lastSeq = m.seq
        p.lastHeard = now
        val before = p.display(now)
        if (p.alive && !m.alive) before?.let { explosions += doubleArrayOf(it.x, it.y, it.z) to now }
        // Blend from where we drew them, unless they respawned or jumped too far to be a correction.
        if (before != null && p.alive && m.alive &&
            hypot(hypot(before.x - m.pose.x, before.y - m.pose.y), before.z - m.pose.z) < 40
        ) {
            p.dx = before.x - m.pose.x
            p.dy = before.y - m.pose.y
            p.dz = before.z - m.pose.z
            p.dh = angleDiff(before.h, m.pose.h)
            p.dp = before.p - m.pose.p
            p.dw = before.w - m.pose.w
        } else {
            p.dx = 0.0; p.dy = 0.0; p.dz = 0.0; p.dh = 0.0; p.dp = 0.0; p.dw = 0.0
        }
        p.base = m.pose
        p.baseAt = now
        val wasFiring = p.alive && p.fx and FIRING != 0
        val nowFiring = m.alive && m.fx and FIRING != 0
        if (nowFiring && !wasFiring) p.lastBolt = now - FlightWorld.FIRE_INTERVAL_MS
        // A tap between two updates: the counter moved but the trigger never showed as held.
        if (m.alive && !nowFiring && p.shots >= 0) {
            repeat((m.shots - p.shotsSeen).coerceIn(0, 2)) { i ->
                spawnBolt(sender, p.shotsSeen + i + 1, m.pose, now)
            }
        }
        if (m.fx and ROLLING != 0 && p.fx and ROLLING == 0) p.rollStart = now
        p.shots = m.shots
        p.shotsSeen = m.shots
        p.fx = m.fx
        p.alive = m.alive
        p.kills = m.kills
        p.deaths = m.deaths
    }

    private fun onHit(victim: Int, m: FlightMessage.Hit, now: Long) {
        val v = peers[victim] ?: return
        if (v.alive) v.display(now)?.let { explosions += doubleArrayOf(it.x, it.y, it.z) to now }
        v.alive = false
        v.fx = 0
        bolts.removeAll { it.owner == m.shooter && it.shot == m.shot }
        when (m.shooter) {
            victim -> addFeed("${v.name} crashed", now)
            self() -> {
                val key = victim to m.shot
                // Each bolt counts once per victim, and only bolts we could have fired: the victim
                // may have fired a few for us after we let go (they only hear that ~3×/s).
                if (m.shot in 1..shots + PHANTOM_SHOTS && key !in credited) {
                    credited.addLast(key)
                    if (credited.size > 64) credited.removeFirst()
                    kills++
                    addFeed("You shot down ${v.name}", now)
                    sendNow = true
                }
            }
            else -> {
                val shooter = peers[m.shooter]?.name ?: return
                addFeed("$shooter shot down ${v.name}", now)
            }
        }
    }

    /** Advance one frame and return what to draw, or null if the dogfight isn't open. */
    @Synchronized
    fun step(input: FlightInput): FlightView? {
        if (!active.value) return null
        val now = clock()
        val dt = (now - lastStep).coerceIn(0, 100) / 1000.0
        val prevStep = lastStep
        lastStep = now
        housekeep(now)

        if (alive) fly(input, dt, now) else if (now >= deadUntil) respawn(now)

        val trigger = alive && input.fire
        if (trigger && now - lastFire >= FlightWorld.FIRE_INTERVAL_MS) {
            lastFire = now
            shots++
            spawnBolt(self() ?: -1, shots, pose, now)
        }
        // Others fire our bolts from the trigger state, so tell them as soon as it changes.
        if (trigger != firing) { firing = trigger; sendNow = true }
        firePeers(now)
        moveBolts(prevStep, now)

        val interval = if (alive) STATE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (peers.isNotEmpty() && (sendNow || now - lastSent >= interval) && take()) sendState(now)
        greetStrangers(now)

        explosions.removeAll { now - it.second > EXPLOSION_MS }
        sparks.removeAll { now - it.second > SPARK_MS }
        return view(now)
    }

    private fun fly(input: FlightInput, dt: Double, now: Long) {
        // Leaving the airspace hands control to the autopilot until we're pointed back in.
        val home = FlightWorld.wrap(atan2(-pose.x, -pose.z))
        if (abs(pose.x) > FlightWorld.HALF || abs(pose.z) > FlightWorld.HALF) turningBack = true
        if (turningBack && abs(angleDiff(home, pose.h)) < 0.35) turningBack = false
        val w: Double
        val q: Double
        if (turningBack) {
            w = (angleDiff(home, pose.h) * 2.5).coerceIn(-FlightWorld.MAX_YAW, FlightWorld.MAX_YAW)
            q = (-pose.p * 2).coerceIn(-FlightWorld.MAX_PITCH_RATE, FlightWorld.MAX_PITCH_RATE)
        } else {
            w = input.yaw.coerceIn(-1f, 1f) * FlightWorld.MAX_YAW
            q = input.pitch.coerceIn(-1f, 1f) * FlightWorld.MAX_PITCH_RATE
        }

        // No boosting through the autopilot's turn: it would swing far wider.
        val wantBoost = input.boost && !input.brake && !turningBack && boostMeter > 0.02
        val wantBrake = input.brake && !input.boost && boostMeter > 0.02
        val target = when {
            wantBoost -> FlightWorld.BOOST_SPEED
            wantBrake -> FlightWorld.BRAKE_SPEED
            else -> FlightWorld.CRUISE
        }
        boostMeter = if (wantBoost || wantBrake) (boostMeter - FlightWorld.BOOST_DRAIN * dt).coerceAtLeast(0.0)
        else (boostMeter + FlightWorld.BOOST_REFILL * dt).coerceAtMost(1.0)
        if (wantBoost != boosting) { boosting = wantBoost; sendNow = true }
        val dv = FlightWorld.ACCEL * dt
        val v = pose.v + (target - pose.v).coerceIn(-dv, dv)

        pose = pose.copy(v = v, w = w, q = q).extrapolate(dt)
        // Pinned to the floor or ceiling, the nose levels out instead of pushing on through.
        if (pose.y <= FlightWorld.FLOOR + 1e-6 && pose.p < 0) pose = pose.copy(p = 0.0)
        if (pose.y >= FlightWorld.CEILING - 1e-6 && pose.p > 0) pose = pose.copy(p = 0.0)

        if (input.roll && now - rollStart >= FlightWorld.ROLL_COOLDOWN_MS) { rollStart = now; sendNow = true }
        val target2 = w / FlightWorld.MAX_YAW * BANK
        bank += (target2 - bank) * min(1.0, dt * 8)

        if (FlightWorld.solid(pose.x, pose.y, pose.z, 1.0)) {
            crash(now)
            return
        }
        if (pose.y <= FlightWorld.FLOOR + 0.5) hurt(FlightWorld.SCRAPE_PER_SEC * dt, now)
        else if (now - lastHurt >= FlightWorld.REGEN_DELAY_MS) {
            shield = (shield + FlightWorld.REGEN_PER_SEC * dt).coerceAtMost(FlightWorld.SHIELD.toDouble())
        }
        if (shield <= 0) crash(now)
    }

    private fun rolling(now: Long) = now - rollStart < FlightWorld.ROLL_MS

    private fun spawnBolt(owner: Int, shot: Int, from: Pose3, now: Long) {
        if (bolts.count { it.owner == owner } < MAX_BOLTS_PER_SHIP) bolts += Bolt.fromMuzzle(owner, shot, from, now)
    }

    /** Fire for every peer whose trigger is held, from where we draw them. */
    private fun firePeers(now: Long) {
        for ((session, p) in peers) {
            if (!p.alive || p.fx and FIRING == 0 || now - p.lastBolt < FlightWorld.FIRE_INTERVAL_MS) continue
            val at = p.display(now) ?: continue
            // Catch up a missed interval or two, but never fire a burst after a stall.
            p.lastBolt = maxOf(p.lastBolt + FlightWorld.FIRE_INTERVAL_MS, now - FlightWorld.FIRE_INTERVAL_MS)
            p.shotsSeen++
            spawnBolt(session, p.shotsSeen, at, now)
        }
    }

    private fun moveBolts(prev: Long, now: Long) {
        val me = self()
        val shown = peers.mapNotNull { (s, p) -> if (p.alive) p.display(now)?.let { s to it } else null }
        val it = bolts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val a = b.at(maxOf(prev, b.born))
            val c = b.at(now)
            if (b.owner != me && alive &&
                segmentHitsSphere(a, c, pose.x, pose.y, pose.z, FlightWorld.SHIP_RADIUS)
            ) {
                // A barrel roll shrugs bolts off.
                if (!rolling(now)) {
                    it.remove()
                    sparks += doubleArrayOf(pose.x, pose.y, pose.z) to now
                    lastAttacker = b.owner
                    lastAttackerShot = b.shot
                    hurt(FlightWorld.LASER_DAMAGE.toDouble(), now)
                    if (shield <= 0) crash(now)
                    continue
                }
            }
            // Bolts that reach another ship stop there; only a visual: the victim decides.
            val target = shown.firstOrNull { (s, p) ->
                s != b.owner && segmentHitsSphere(a, c, p.x, p.y, p.z, FlightWorld.SHIP_RADIUS)
            }
            if (target != null) {
                it.remove()
                sparks += c to now
                continue
            }
            if (b.expired(now)) it.remove()
        }
    }

    private fun hurt(amount: Double, now: Long) {
        shield -= amount
        lastHurt = now
    }

    /** Our ship is gone: shot down if someone hit us lately, else just crashed. */
    private fun crash(now: Long) {
        if (!alive) return
        alive = false
        deaths++
        deadUntil = now + FlightWorld.RESPAWN_MS
        val me = self()
        val attacker = lastAttacker.takeIf { now - lastHurt <= CREDIT_MS && it in peers }
        killedBy = attacker?.let { peers[it]?.name }
        explosions += doubleArrayOf(pose.x, pose.y, pose.z) to now
        pose = pose.copy(v = 0.0, w = 0.0, q = 0.0)
        firing = false
        boosting = false
        if (peers.isNotEmpty() && me != null) {
            take(force = true)
            broadcast(
                if (attacker != null) FlightMessage.Hit(attacker, lastAttackerShot)
                else FlightMessage.Hit(me, 0),
            )
        }
        addFeed(killedBy?.let { "Shot down by $it" } ?: "You crashed", now)
        sendNow = true
    }

    private fun respawn(now: Long) {
        // Of a handful of spots near the edge, take the one farthest from everybody else.
        val others = peers.values.mapNotNull { if (it.alive) it.display(now) else null }
        var best: Pose3? = null
        var bestDist = -1.0
        repeat(12) {
            val a = random.nextDouble(0.0, 2 * PI)
            val r = random.nextDouble(220.0, 300.0)
            val x = sin(a) * r
            val z = cos(a) * r
            val y = random.nextDouble(50.0, 100.0)
            if (FlightWorld.solid(x, y, z, 10.0)) return@repeat
            val d = others.minOfOrNull { hypot(hypot(it.x - x, it.y - y), it.z - z) } ?: Double.MAX_VALUE
            if (d > bestDist) {
                bestDist = d
                best = Pose3(x, y, z, FlightWorld.wrap(atan2(-x, -z)), 0.0, FlightWorld.CRUISE)
            }
        }
        pose = best ?: Pose3(0.0, 120.0, -280.0, 0.0, 0.0, FlightWorld.CRUISE)
        alive = true
        killedBy = null
        shield = FlightWorld.SHIELD.toDouble()
        boostMeter = 1.0
        bank = 0.0
        turningBack = false
        lastHurt = Long.MIN_VALUE / 2
        lastAttacker = -1
        lastFire = now
        sendNow = true
    }

    private fun fx(now: Long) =
        (if (firing) FIRING else 0) or (if (boosting) BOOSTING else 0) or (if (rolling(now)) ROLLING else 0)

    private fun sendState(now: Long) {
        seq++
        broadcast(FlightMessage.State(seq, alive, pose, fx(now), shots, kills, deaths))
        lastSent = now
        sendNow = false
    }

    private fun rollAngle(start: Long, now: Long): Double {
        val t = (now - start).toDouble() / FlightWorld.ROLL_MS
        return if (t in 0.0..1.0) t * 2 * PI else 0.0
    }

    private fun view(now: Long): FlightView {
        val me = self()
        val ships = peers.mapNotNull { (session, p) ->
            if (!p.alive) return@mapNotNull null
            p.display(now)?.let {
                val bank = it.w / FlightWorld.MAX_YAW * BANK + rollAngle(p.rollStart, now)
                FlightView.Ship(session, it, bank, p.fx and BOOSTING != 0)
            }
        }
        val scores = buildList {
            add(FlightView.Score("You", kills, deaths, me = true))
            peers.values.forEach { add(FlightView.Score(it.name, it.kills, it.deaths, me = false)) }
        }.sortedWith(compareByDescending<FlightView.Score> { it.kills }.thenBy { it.deaths })
        return FlightView(
            me = pose,
            bank = bank + rollAngle(rollStart, now),
            alive = alive,
            killedBy = killedBy,
            respawnInMs = if (alive) 0 else (deadUntil - now).coerceAtLeast(0),
            shield = (shield / FlightWorld.SHIELD).toFloat().coerceIn(0f, 1f),
            boost = boostMeter.toFloat(),
            turningBack = alive && turningBack,
            hurtAgoMs = now - lastHurt,
            ships = ships,
            bolts = bolts.map { b ->
                val p = b.at(now)
                FlightView.BoltView(p[0], p[1], p[2], b.dx, b.dy, b.dz, b.owner == me)
            },
            explosions = explosions.map { (p, at) -> FlightView.Explosion(p[0], p[1], p[2], now - at, small = false) } +
                sparks.map { (p, at) -> FlightView.Explosion(p[0], p[1], p[2], now - at, small = true) },
            scores = scores,
            feed = feedLines(),
        )
    }

    companion object {
        const val STATE_INTERVAL_MS = 333L
        const val IDLE_INTERVAL_MS = 1_000L
        const val EXPLOSION_MS = 1_400L
        const val SPARK_MS = 250L
        /** A crash within this long of being hit is credited to whoever hit us. */
        const val CREDIT_MS = 5_000L
        const val MAX_BOLTS_PER_SHIP = 12
        private const val PHANTOM_SHOTS = 8
        private const val BANK = 0.8
        private const val MAX_EXTRAPOLATE_MS = 1_000L
        private const val SMOOTH_MS = 200.0
    }
}
