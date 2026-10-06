package app.notmumla.game.tank

import app.notmumla.game.arena.ChannelArena
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** What the player is doing with the controls this frame. */
data class TankInput(val throttle: Float = 0f, val turn: Float = 0f, val fire: Boolean = false)

/** Everything the renderer needs for one frame; immutable, so it can be drawn off the lock. */
data class TankView(
    val me: Pose,
    val alive: Boolean,
    val killedBy: String?,
    val respawnInMs: Long,
    /** 0 = just fired, 1 = ready. */
    val reload: Float,
    val tanks: List<RemoteTank>,
    val shells: List<ShellView>,
    val explosions: List<Explosion>,
    val scores: List<Score>,
    val feed: List<String>,
) {
    data class RemoteTank(val session: Int, val name: String, val pose: Pose)
    data class ShellView(val x: Double, val z: Double, val mine: Boolean)
    data class Explosion(val x: Double, val z: Double, val ageMs: Long)
    data class Score(val name: String, val kills: Int, val deaths: Int, val me: Boolean)
}

/**
 * A free-for-all tank arena for everyone in the channel who opens it. There is no host: each
 * client owns its own tank and broadcasts its state ~3×/s to the other players; everyone
 * dead-reckons the rest in between. Joining, invites and the message budget: [ChannelArena].
 *
 * Hits are decided by the **victim**: every client checks incoming shells against its own tank only
 * and announces its death. A hostile client can therefore only cheat for itself (refuse to die);
 * it can't kill anyone, and nothing it sends does more than move a tank on our screen.
 */
class TankArena(
    send: (receivers: List<Int>, message: TankMessage) -> Unit,
    self: () -> Int?,
    channelPeers: () -> Map<Int, String>,
    enabled: () -> Boolean,
    onInvite: (name: String) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : ChannelArena<TankMessage, TankArena.Peer>(
    send, self, channelPeers, enabled, onInvite, clock,
    TankMessage.Hello, TankMessage.Bye, TankMessage::decode,
) {
    class Peer(name: String) : ChannelArena.Peer(name) {
        var base: Pose? = null
        var baseAt = 0L
        var dx = 0.0
        var dz = 0.0
        var dh = 0.0
        var alive = false
        var lastSeq = -1
        var shots = -1
        var kills = 0
        var deaths = 0

        fun display(now: Long): Pose? {
            val b = base ?: return null
            val age = now - baseAt
            val p = b.extrapolate(min(age, MAX_EXTRAPOLATE_MS) / 1000.0)
            // Ease out the jump to the new estimate instead of snapping.
            val k = exp(-age / SMOOTH_MS)
            return p.copy(x = p.x + dx * k, z = p.z + dz * k, h = TankWorld.wrap(p.h + dh * k))
        }
    }

    private val shells = ArrayList<Shell>()
    private val explosions = ArrayList<Pair<Pose, Long>>()
    private val credited = ArrayDeque<Pair<Int, Int>>()

    private var pose = Pose(0.0, 0.0, 0.0)
    private var alive = false
    private var deadUntil = 0L
    private var killedBy: String? = null
    private var lastFire = 0L
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
        shells.clear(); explosions.clear(); credited.clear()
        shots = 0; kills = 0; deaths = 0; seq = 0
        killedBy = null; sendNow = false
    }

    override fun onChannelChanged() = shells.clear()

    override fun onHello(peer: Peer, now: Long) {
        // They may be rejoining: a fresh counter starts from scratch.
        peer.lastSeq = -1; peer.shots = -1
        sendNow = true
    }

    override fun onMessage(sender: Int, name: String, msg: TankMessage, now: Long) {
        when (msg) {
            is TankMessage.State -> onState(sender, name, msg, now)
            is TankMessage.Hit -> onHit(sender, msg, now)
            else -> {}
        }
    }

    private fun onState(sender: Int, name: String, m: TankMessage.State, now: Long) {
        val p = peer(sender, name) ?: return
        if (m.seq <= p.lastSeq) return
        p.lastSeq = m.seq
        p.lastHeard = now
        val before = p.display(now)
        if (p.alive && !m.alive) before?.let { explosions += it to now }
        // Blend from where we drew them, unless they respawned or jumped too far to be a correction.
        if (before != null && p.alive && m.alive && hypot(before.x - m.pose.x, before.z - m.pose.z) < 15) {
            p.dx = before.x - m.pose.x
            p.dz = before.z - m.pose.z
            p.dh = angleDiff(before.h, m.pose.h)
        } else {
            p.dx = 0.0; p.dz = 0.0; p.dh = 0.0
        }
        p.base = m.pose
        p.baseAt = now
        if (m.alive && p.shots >= 0 && m.shots > p.shots && shells.count { it.owner == sender } < MAX_SHELLS_PER_TANK) {
            shells += Shell.fromMuzzle(sender, m.shots, m.pose, now)
        }
        p.shots = m.shots
        p.alive = m.alive
        p.kills = m.kills
        p.deaths = m.deaths
    }

    private fun onHit(victim: Int, m: TankMessage.Hit, now: Long) {
        val v = peers[victim] ?: return
        if (v.alive) v.display(now)?.let { explosions += it to now }
        v.alive = false
        shells.removeAll { it.owner == m.shooter && it.shot == m.shot }
        if (m.shooter == self()) {
            val key = victim to m.shot
            // Each of our shells counts once per victim, and only shells we actually fired.
            if (m.shot in 1..shots && key !in credited) {
                credited.addLast(key)
                if (credited.size > 64) credited.removeFirst()
                kills++
                addFeed("You destroyed ${v.name}", now)
                sendNow = true
            }
        } else {
            val shooter = peers[m.shooter]?.name ?: return
            addFeed("$shooter destroyed ${v.name}", now)
        }
    }

    /** Advance one frame and return what to draw, or null if the arena isn't open. */
    @Synchronized
    fun step(input: TankInput): TankView? {
        if (!active.value) return null
        val now = clock()
        val dt = (now - lastStep).coerceIn(0, 100) / 1000.0
        val prevStep = lastStep
        lastStep = now
        housekeep(now)

        if (alive) drive(input, dt) else if (now >= deadUntil) respawn(now)
        // Alone, firing costs nothing; with players, a shot is a state message and needs a token.
        if (alive && input.fire && now - lastFire >= TankWorld.RELOAD_MS && (peers.isEmpty() || take())) {
            lastFire = now
            shots++
            shells += Shell.fromMuzzle(self() ?: -1, shots, pose, now)
            if (peers.isNotEmpty()) sendState(now)
        }
        moveShells(prevStep, now)

        val moving = alive && (pose.v != 0.0 || pose.w != 0.0)
        val interval = if (moving) STATE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (peers.isNotEmpty() && (sendNow || now - lastSent >= interval) && take()) sendState(now)
        greetStrangers(now)

        explosions.removeAll { now - it.second > EXPLOSION_MS }
        return view(now)
    }

    private fun drive(input: TankInput, dt: Double) {
        val v = input.throttle.coerceIn(-1f, 1f) * TankWorld.MAX_SPEED
        val w = input.turn.coerceIn(-1f, 1f) * TankWorld.MAX_TURN
        val h = TankWorld.wrap(pose.h + w * dt)
        var x = pose.x
        var z = pose.z
        // Move each axis separately so a tank slides along a wall instead of sticking to it.
        val nx = x + sin(h) * v * dt
        if (TankWorld.free(nx, z)) x = nx
        val nz = z + cos(h) * v * dt
        if (TankWorld.free(x, nz)) z = nz
        val blocked = x == pose.x && z == pose.z
        pose = Pose(x, z, h, if (blocked) 0.0 else v, w)
    }

    private fun moveShells(prev: Long, now: Long) {
        val me = self()
        val it = shells.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.expired(now)) { it.remove(); continue }
            val (ax, az) = s.at(maxOf(prev, s.born))
            val (bx, bz) = s.at(now)
            if (s.owner != me) {
                if (alive && segmentHitsCircle(ax, az, bx, bz, pose.x, pose.z, TankWorld.TANK_RADIUS)) {
                    it.remove()
                    die(s, now)
                }
            } else if (peers.values.any { p ->
                    p.alive && p.display(now)?.let { segmentHitsCircle(ax, az, bx, bz, it.x, it.z, TankWorld.TANK_RADIUS) } == true
                }
            ) {
                // Only a visual stop: the victim decides whether it counted.
                it.remove()
            }
        }
    }

    private fun die(s: Shell, now: Long) {
        alive = false
        deaths++
        deadUntil = now + TankWorld.RESPAWN_MS
        killedBy = peers[s.owner]?.name ?: "?"
        pose = pose.copy(v = 0.0, w = 0.0)
        if (peers.isNotEmpty()) {
            take(force = true)
            broadcast(TankMessage.Hit(s.owner, s.shot))
        }
        sendNow = true
    }

    private fun respawn(now: Long) {
        // Of a handful of open spots, take the one farthest from everybody else.
        val others = peers.values.mapNotNull { if (it.alive) it.display(now) else null }
        var best: Pose? = null
        var bestDist = -1.0
        repeat(12) {
            val x = random.nextDouble(-90.0, 90.0)
            val z = random.nextDouble(-90.0, 90.0)
            if (!TankWorld.free(x, z)) return@repeat
            val d = others.minOfOrNull { hypot(it.x - x, it.z - z) } ?: Double.MAX_VALUE
            if (d > bestDist) { bestDist = d; best = Pose(x, z, TankWorld.wrap(atan2(-x, -z))) }
        }
        pose = best ?: Pose(-90.0, -90.0, PI / 4)
        alive = true
        killedBy = null
        lastFire = now
        sendNow = true
    }

    private fun sendState(now: Long) {
        seq++
        broadcast(TankMessage.State(seq, alive, pose, shots, kills, deaths))
        lastSent = now
        sendNow = false
    }

    private fun view(now: Long): TankView {
        val me = self()
        val tanks = peers.mapNotNull { (session, p) ->
            if (!p.alive) null else p.display(now)?.let { TankView.RemoteTank(session, p.name, it) }
        }
        val scores = buildList {
            add(TankView.Score("You", kills, deaths, me = true))
            peers.values.forEach { add(TankView.Score(it.name, it.kills, it.deaths, me = false)) }
        }.sortedWith(compareByDescending<TankView.Score> { it.kills }.thenBy { it.deaths })
        return TankView(
            me = pose,
            alive = alive,
            killedBy = killedBy,
            respawnInMs = if (alive) 0 else (deadUntil - now).coerceAtLeast(0),
            reload = ((now - lastFire).toFloat() / TankWorld.RELOAD_MS).coerceIn(0f, 1f),
            tanks = tanks,
            shells = shells.map { s -> s.at(now).let { (x, z) -> TankView.ShellView(x, z, s.owner == me) } },
            explosions = explosions.map { (p, at) -> TankView.Explosion(p.x, p.z, now - at) },
            scores = scores,
            feed = feedLines(),
        )
    }

    companion object {
        const val STATE_INTERVAL_MS = 333L
        const val IDLE_INTERVAL_MS = 1_000L
        const val PEER_TIMEOUT_MS = ChannelArena.PEER_TIMEOUT_MS
        const val INVITE_REPEAT_MS = ChannelArena.INVITE_REPEAT_MS
        const val MAX_SHELLS_PER_TANK = 4
        const val EXPLOSION_MS = 1_500L
        private const val MAX_EXTRAPOLATE_MS = 1_000L
        private const val SMOOTH_MS = 150.0

        /** a − b, wrapped to (−π, π]. */
        internal fun angleDiff(a: Double, b: Double): Double {
            var d = (a - b) % (2 * PI)
            if (d > PI) d -= 2 * PI
            if (d <= -PI) d += 2 * PI
            return d
        }
    }
}
