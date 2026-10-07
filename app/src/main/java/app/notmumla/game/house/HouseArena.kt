package app.notmumla.game.house

import app.notmumla.game.arena.ChannelArena
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Where the stick points, already turned into world space (x, z); length ≤ 1. */
data class HouseInput(val mx: Double = 0.0, val mz: Double = 0.0)

enum class HouseSound { SWISH, SLAP }

/** A sound at a spot in the world; the screen works out volume and pan from the camera. */
data class HouseSoundEvent(val sound: HouseSound, val x: Double, val z: Double)

/** Everything the renderer needs for one frame; immutable, so it can be drawn off the lock. */
data class HouseView(
    val me: Figure,
    val others: List<Figure>,
    val down: Boolean,
    val slappedBy: String?,
    val emote: Emote,
    val shirt: Int,
    val feed: List<String>,
    val players: List<String>,
    val sounds: List<HouseSoundEvent>,
) {
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
    ) {
        val level get() = HouseWorld.levelOf(y)
    }
}

/**
 * A two-storey block house for everyone in the channel who opens it: walk around as a blocky
 * figure, wave, cheer, dance, sit, and slap each other over. Joining, invites and the message
 * budget: [ChannelArena].
 *
 * Like the other arenas there's no host. Each client owns its figure and broadcasts its state ~3×/s
 * while walking; everyone dead-reckons the rest through the same map in between.
 *
 * **The victim decides a slap.** The slapper names who it hit; the victim checks the slapper is
 * actually within reach on its own screen, isn't lying down, and that it isn't still shaking off
 * the last one, then falls over and announces it. A hostile client can refuse to fall, but it can
 * only knock someone over from arm's length, at most once per [IMMUNE_MS] after they get up.
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
        /** Where we draw them this frame: x, y, z, heading. */
        var pos: DoubleArray? = null
        var emote = Emote.NONE
        var emoteSince = 0L
        var swings = -1
        var swingAt = Long.MIN_VALUE / 2
        var walk = 0.0
        var stride = 0.0
        var lastX = Double.NaN
        var lastZ = 0.0
        var ragdoll: Ragdoll? = null
        /** Their highest swing that knocked us down: each one counts once. */
        var slapTaken = 0
        /** Our highest swing they said knocked them down. */
        var credited = 0

        fun display(now: Long): DoubleArray? {
            val s = state ?: return null
            var x = s.x
            var z = s.z
            var y = HouseWorld.floorHeight(x, z, s.level)
            // Walk the guess through the map, so it slides along walls and climbs stairs like they do.
            if (!s.down && (s.vx != 0.0 || s.vz != 0.0)) {
                var left = min(now - stateAt, MAX_EXTRAPOLATE_MS) / 1000.0
                while (left > 1e-6) {
                    val dt = min(left, 0.05)
                    val r = HouseWorld.move(x, y, z, s.vx * dt, s.vz * dt)
                    x = r[0]; y = r[1]; z = r[2]
                    left -= dt
                }
            }
            val k = exp(-(now - stateAt) / SMOOTH_MS)
            return doubleArrayOf(x + dx * k, y, z + dz * k, s.h + dh * k)
        }
    }

    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var h = 0.0
    private var vx = 0.0
    private var vz = 0.0
    private var walk = 0.0
    private var stride = 0.0
    private var emote = Emote.NONE
    private var emoteSince = 0L
    private var swings = 0
    private var lastSwing = Long.MIN_VALUE / 2
    private var ragdoll: Ragdoll? = null
    private var slappedBy: String? = null
    private var immuneUntil = 0L
    private var shirt = 0
    private var seq = 0
    private var lastSent = 0L
    private var sendNow = false
    private var wasMoving = false
    private var lastStep = 0L
    private val sounds = ArrayList<HouseSoundEvent>()

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
        swings = 0; seq = 0
        vx = 0.0; vz = 0.0
        sendNow = false; wasMoving = false
        sounds.clear()
    }

    override fun onHello(peer: Peer, now: Long) {
        // They may be rejoining: fresh counters start from scratch.
        peer.lastSeq = -1; peer.swings = -1; peer.slapTaken = 0; peer.credited = 0
        sendNow = true
    }

    override fun onMessage(sender: Int, name: String, msg: HouseMessage, now: Long) {
        when (msg) {
            is HouseMessage.State -> onState(sender, name, msg, now)
            is HouseMessage.Slap -> onSlap(sender, msg, now)
            is HouseMessage.Ow -> onOw(sender, msg, now)
            else -> {}
        }
    }

    private fun onState(sender: Int, name: String, m: HouseMessage.State, now: Long) {
        val p = peer(sender, name) ?: return
        if (m.seq <= p.lastSeq) return
        p.lastSeq = m.seq
        p.lastHeard = now
        val before = p.display(now)
        val prev = p.state
        // Blend from where we drew them, unless they got up, changed floors or jumped too far.
        if (before != null && prev != null && !prev.down && !m.down && prev.level == m.level &&
            hypot(before[0] - m.x, before[2] - m.z) < 2.5
        ) {
            p.dx = before[0] - m.x
            p.dz = before[2] - m.z
            p.dh = angleDiff(before[3], m.h)
        } else {
            p.dx = 0.0; p.dz = 0.0; p.dh = 0.0
        }
        p.state = m
        p.stateAt = now
        if (m.emote != p.emote) { p.emote = m.emote; p.emoteSince = now }
        p.pos = p.display(now)
        if (m.swings > p.swings) {
            if (p.swings >= 0) swung(p, now)
            p.swings = m.swings
        }
        // Down without our having heard the "ow": they just fall backward.
        if (m.down && p.ragdoll == null) fall(p, m.h + PI, now)
        if (!m.down) p.ragdoll = null
    }

    private fun swung(p: Peer, now: Long) {
        p.swingAt = now
        p.pos?.let { sounds += HouseSoundEvent(HouseSound.SWISH, it[0], it[2]) }
    }

    private fun onSlap(sender: Int, m: HouseMessage.Slap, now: Long) {
        val p = peers[sender] ?: return
        if (m.swing > p.swings) { swung(p, now); p.swings = m.swing }
        if (m.victim != self()) return
        if (ragdoll != null || now < immuneUntil || m.swing <= p.slapTaken || p.ragdoll != null) return
        val sp = p.pos ?: return
        val d = hypot(x - sp[0], z - sp[2])
        if (d > REACH_TOLERANCE || abs(sp[1] - y) > 1.2) return
        p.slapTaken = m.swing
        val dir = if (d < 0.05) sp[3] else atan2(x - sp[0], z - sp[2])
        knockDown(dir, p.name)
        addFeed("${p.name} slapped you", now)
        take(force = true)
        broadcast(HouseMessage.Ow(sender, m.swing, dir))
    }

    private fun onOw(victim: Int, m: HouseMessage.Ow, now: Long) {
        val v = peers[victim] ?: return
        if (v.ragdoll != null) return
        fall(v, m.dir, now)
        v.pos?.let { sounds += HouseSoundEvent(HouseSound.SLAP, it[0], it[2]) }
        if (m.slapper == self()) {
            // Credit only a swing we actually made, once.
            if (m.swing in 1..swings && m.swing > v.credited) {
                v.credited = m.swing
                addFeed("You slapped ${v.name}", now)
            }
        } else {
            val by = peers[m.slapper]?.name
            addFeed(if (by != null) "$by slapped ${v.name}" else "${v.name} got slapped", now)
        }
    }

    private fun fall(p: Peer, dir: Double, now: Long) {
        val pos = p.pos ?: return
        val joints = Blocky.pose(pos[0], pos[1], pos[2], pos[3], p.walk, p.stride, p.emoteAt(now), now - p.emoteSince)
        p.ragdoll = Ragdoll(joints, dir, HouseWorld.levelOf(pos[1]))
    }

    private fun Peer.emoteAt(now: Long): Emote =
        if (emote.durationMs > 0 && now - emoteSince > emote.durationMs) Emote.NONE else emote

    private fun knockDown(dir: Double, by: String) {
        val now = clock()
        ragdoll = Ragdoll(myJoints(now), dir, HouseWorld.levelOf(y))
        slappedBy = by
        emote = Emote.NONE
        vx = 0.0; vz = 0.0
        sounds += HouseSoundEvent(HouseSound.SLAP, x, z)
        sendNow = true
    }

    /** Swing a slap at whoever is in front of us and within reach. */
    @Synchronized
    fun slap() {
        if (!active.value || ragdoll != null) return
        val now = clock()
        if (now - lastSwing < SWING_COOLDOWN_MS) return
        lastSwing = now
        swings++
        emote = Emote.NONE
        sounds += HouseSoundEvent(HouseSound.SWISH, x, z)
        val target = peers.entries
            .mapNotNull { (s, p) -> if (p.ragdoll != null) null else p.pos?.let { s to it } }
            .filter { (_, q) ->
                abs(q[1] - y) < 1.0 && hypot(q[0] - x, q[2] - z) <= REACH &&
                    abs(angleDiff(atan2(q[0] - x, q[2] - z), h)) < REACH_ANGLE
            }
            .minByOrNull { (_, q) -> hypot(q[0] - x, q[2] - z) }
        if (target != null) {
            val q = target.second
            h = atan2(q[0] - x, q[2] - z)
            // The slap itself says we swung; without a token, the next state's counter will.
            if (take()) {
                broadcast(HouseMessage.Slap(target.first, swings))
                return
            }
        }
        sendNow = true
    }

    /** Start [e], or stop it if it's already going. */
    @Synchronized
    fun emote(e: Emote) {
        if (!active.value || ragdoll != null) return
        emote = if (emote == e) Emote.NONE else e
        emoteSince = clock()
        sendNow = true
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
        } else {
            walk(input, dtMs / 1000.0)
        }
        if (emote.durationMs > 0 && now - emoteSince > emote.durationMs) emote = Emote.NONE
        for (p in peers.values) tickPeer(p, dtMs, now)

        val moving = vx != 0.0 || vz != 0.0
        if (moving != wasMoving) { sendNow = true; wasMoving = moving }
        val interval = if (moving) STATE_INTERVAL_MS else IDLE_INTERVAL_MS
        if (peers.isNotEmpty() && (sendNow || now - lastSent >= interval) && take()) sendState(now)
        greetStrangers(now)
        return view(now)
    }

    private fun walk(input: HouseInput, dt: Double) {
        var mx = input.mx
        var mz = input.mz
        val m = hypot(mx, mz)
        if (m > 1) { mx /= m; mz /= m }
        if (m < DEAD_ZONE || dt <= 0) {
            vx = 0.0; vz = 0.0
            stride = max(0.0, stride - dt * 6)
            return
        }
        if (emote != Emote.NONE) { emote = Emote.NONE; sendNow = true }
        h = turn(h, atan2(mx, mz), TURN_RATE * dt)
        val r = HouseWorld.move(x, y, z, mx * HouseWorld.WALK_SPEED * dt, mz * HouseWorld.WALK_SPEED * dt)
        val moved = hypot(r[0] - x, r[2] - z)
        vx = (r[0] - x) / dt
        vz = (r[2] - z) / dt
        walk += moved * STRIDE_K
        stride = approach(stride, (moved / dt / HouseWorld.WALK_SPEED).coerceIn(0.0, 1.0), dt * 8)
        x = r[0]; y = r[1]; z = r[2]
    }

    private fun tickPeer(p: Peer, dtMs: Long, now: Long) {
        p.pos = p.display(now)
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

    /** Stand up where the ragdoll came to rest, or as near it as there's room. */
    private fun getUp(rd: Ragdoll, now: Long) {
        val pel = rd.pelvis()
        val level = HouseWorld.levelOf(pel[1] - 0.3)
        var spot: DoubleArray? = null
        search@ for (r in listOf(0.0, 0.4, 0.8, 1.2, 1.6)) {
            for (k in 0 until if (r == 0.0) 1 else 8) {
                val a = k * PI / 4
                val cx = pel[0] + r * kotlin.math.sin(a)
                val cz = pel[2] + r * kotlin.math.cos(a)
                val cy = HouseWorld.floorHeight(cx, cz, level)
                if (HouseWorld.free(cx, cz, cy)) { spot = doubleArrayOf(cx, cy, cz); break@search }
            }
        }
        spot?.let { x = it[0]; y = it[1]; z = it[2] }
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
            if (!HouseWorld.free(cx, cz, 0.0)) return@repeat
            val d = others.minOfOrNull { hypot(it[0] - cx, it[2] - cz) } ?: Double.MAX_VALUE
            if (d > bestDist) { bestDist = d; best = doubleArrayOf(cx, cz) }
        }
        val b = best ?: doubleArrayOf(2.0, -9.0)
        x = b[0]; z = b[1]; y = 0.0; h = 0.0
        walk = 0.0; stride = 0.0
        immuneUntil = 0L
    }

    private fun sendState(now: Long) {
        seq++
        broadcast(HouseMessage.State(seq, x, z, HouseWorld.levelOf(y), h, vx, vz, emote, ragdoll != null, swings, shirt))
        lastSent = now
        sendNow = false
    }

    private fun myJoints(now: Long) =
        Blocky.pose(x, y, z, h, walk, stride, emote, now - emoteSince, now - lastSwing)

    private fun figure(session: Int, name: String, rd: Ragdoll?, standing: () -> DoubleArray, at: DoubleArray, shirt: Int): HouseView.Figure {
        if (rd != null) {
            val pel = rd.pelvis()
            return HouseView.Figure(session, name, rd.joints(), pel[0], pel[1] - 0.3, pel[2], shirt, down = true)
        }
        return HouseView.Figure(session, name, standing(), at[0], at[1], at[2], shirt, down = false)
    }

    private fun view(now: Long): HouseView {
        val me = figure(self() ?: -1, "You", ragdoll, { myJoints(now) }, doubleArrayOf(x, y, z), shirt)
        val others = peers.mapNotNull { (session, p) ->
            val pos = p.pos ?: return@mapNotNull null
            val st = p.state ?: return@mapNotNull null
            figure(session, p.name, p.ragdoll, {
                Blocky.pose(pos[0], pos[1], pos[2], pos[3], p.walk, p.stride, p.emoteAt(now), now - p.emoteSince, now - p.swingAt)
            }, pos, st.shirt)
        }
        val out = sounds.toList()
        sounds.clear()
        return HouseView(
            me = me,
            others = others,
            down = ragdoll != null,
            slappedBy = slappedBy,
            emote = emote,
            shirt = shirt,
            feed = feedLines(),
            players = listOf("You") + peers.values.map { it.name },
            sounds = out,
        )
    }

    companion object {
        const val STATE_INTERVAL_MS = 333L
        const val IDLE_INTERVAL_MS = 1_000L
        const val SWING_COOLDOWN_MS = 600L
        /** After getting up, slaps bounce off for this long. */
        const val IMMUNE_MS = 1_500L
        /** Arm's length, measured between the two figures' centres. */
        const val REACH = 1.5
        const val REACH_ANGLE = 1.3
        /** What the victim accepts: reach plus slack for where its screen and ours disagree. */
        const val REACH_TOLERANCE = 2.8
        private const val LOST_MS = 3_000L
        private const val MAX_EXTRAPOLATE_MS = 600L
        private const val SMOOTH_MS = 150.0
        private const val DEAD_ZONE = 0.15
        private const val TURN_RATE = 14.0
        /** Stride cycle per metre walked. */
        private const val STRIDE_K = 2 * PI / 1.5

        /** The last shirt picked, kept for the next time the house opens. */
        @Volatile private var shirtChoice: Int? = null

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
