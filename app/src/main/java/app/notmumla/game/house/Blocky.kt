package app.notmumla.game.house

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Things a character can do on its own; [durationMs] 0 = until it moves or picks another. */
enum class Emote(val durationMs: Long) { NONE(0), WAVE(2_600), CHEER(2_000), DANCE(0), SIT(0) }

/** What a character holds: a bat swings harder and further than a hand; a plant gets thrown. */
enum class Weapon { NONE, BAT, PLANT }

/**
 * A blocky figure, six boxes in the old brick-game proportions: head, torso, two arms, two legs.
 * Both the animated pose and the ragdoll come out as the same eleven joints (world coordinates, x/y/z
 * interleaved), so one renderer draws either.
 */
object Blocky {
    const val HEAD = 0
    const val NECK = 1
    const val SHOULDER_L = 2
    const val SHOULDER_R = 3
    const val HIP_L = 4
    const val HIP_R = 5
    const val HAND_L = 6
    const val HAND_R = 7
    const val FOOT_L = 8
    const val FOOT_R = 9
    /** The front of the chest: gives the ragdoll's torso depth, so it can't stay balanced on edge. */
    const val CHEST = 10
    const val JOINTS = 11

    /** Half a limb's thickness; limbs are square in section. */
    const val LIMB = 0.18
    const val LEG = 0.72
    /** Shoulder pivot to the bottom of the arm; the arm also reaches [ARM_ABOVE] above the pivot. */
    const val ARM = 0.58
    const val ARM_ABOVE = 0.14
    const val HIP_Y = 0.72
    const val HIP_X = 0.18
    const val NECK_Y = 1.44
    const val SHOULDER_Y = 1.30
    const val SHOULDER_X = 0.54
    const val HEAD_HALF = 0.22
    /** The torso's half-width and half-depth (its height runs hip to neck). */
    const val TORSO_X = 0.36
    const val TORSO_Z = 0.18

    /** How long the slap swing lasts, start to follow-through. */
    const val SWING_MS = 380L

    /**
     * Joints for a standing figure at (x, y, z) facing [h], walking through [walk] (radians of
     * stride cycle) at [stride] (0 = still, 1 = full speed), doing [emote] for [emoteMs] and, if
     * [swingMs] is in 0..[SWING_MS], swinging: a slap, a bat, or a throw, by what [swing] says was in
     * hand. [held] sets the arms for carrying; [airborne] tucks the legs mid-jump.
     */
    fun pose(
        x: Double, y: Double, z: Double, h: Double,
        walk: Double = 0.0, stride: Double = 0.0,
        emote: Emote = Emote.NONE, emoteMs: Long = 0, swingMs: Long = -1,
        held: Weapon = Weapon.NONE, swing: Weapon = Weapon.NONE, airborne: Boolean = false,
    ): DoubleArray {
        val t = emoteMs / 1000.0
        var lift = 0.0
        var sway = 0.0
        var yaw = h
        val s = sin(walk) * stride
        var legL = 0.65 * s
        var legR = -0.65 * s
        var armL = arm(-0.55 * s, 0.05, -1)
        var armR = arm(0.55 * s, 0.05, 1)
        when (emote) {
            Emote.NONE -> {}
            Emote.WAVE -> {
                armR = arm(0.0, 2.55 + 0.4 * sin(2 * PI * 2.4 * t), 1)
            }
            Emote.CHEER -> {
                lift = 0.14 * max(0.0, sin(2 * PI * 1.6 * t))
                val b = 2.75 + 0.15 * sin(2 * PI * 3.2 * t)
                armL = arm(0.0, b, -1)
                armR = arm(0.0, b, 1)
            }
            Emote.DANCE -> {
                val beat = 2 * PI * 2.0 * t
                lift = 0.07 * abs(sin(beat))
                sway = 0.08 * sin(beat / 2)
                yaw = h + 0.35 * sin(beat / 2)
                armL = arm(0.3, 1.6 + 1.0 * sin(beat), -1)
                armR = arm(0.3, 1.6 - 1.0 * sin(beat), 1)
                legL = 0.3 * sin(beat)
                legR = -0.3 * sin(beat)
            }
            Emote.SIT -> {
                lift = -(HIP_Y - LIMB)
                legL = PI / 2
                legR = PI / 2
                armL = arm(0.45, 0.15, -1)
                armR = arm(0.45, 0.15, 1)
            }
        }
        when (held) {
            Weapon.NONE -> {}
            Weapon.BAT -> armR = arm(0.45, 0.05, 1)
            // Hugged in front at chest height, hands together under it.
            Weapon.PLANT -> {
                armL = arm(1.15, -0.4, -1)
                armR = arm(1.15, -0.4, 1)
            }
        }
        if (airborne && emote == Emote.NONE) {
            legL = 0.55
            legR = -0.15
            if (held == Weapon.NONE) {
                armL = arm(0.2, 0.9, -1)
                armR = arm(0.2, 0.9, 1)
            }
        }
        if (swingMs in 0..SWING_MS) {
            val u = swingMs.toDouble() / SWING_MS
            if (swing == Weapon.PLANT) {
                // Overarm: both hands from up behind the head to out in front.
                val th = -2.7 + 3.9 * u
                armL = norm(-0.12, -cos(th), sin(th))
                armR = norm(0.12, -cos(th), sin(th))
            } else {
                // A wide forehand: the right arm comes round from out to the side to across the body.
                val phi = 1.5 - 2.6 * u
                armR = norm(sin(phi), 0.12, cos(phi))
                val twist = if (swing == Weapon.BAT) 0.7 else 0.45
                yaw = h + twist - 2 * twist * u
            }
        }

        val local = DoubleArray(JOINTS * 3)
        fun set(j: Int, lx: Double, ly: Double, lz: Double) {
            local[j * 3] = lx; local[j * 3 + 1] = ly; local[j * 3 + 2] = lz
        }
        set(HIP_L, -HIP_X + sway, HIP_Y + lift, 0.0)
        set(HIP_R, HIP_X + sway, HIP_Y + lift, 0.0)
        set(NECK, sway, NECK_Y + lift, 0.0)
        set(HEAD, sway, NECK_Y + HEAD_HALF + lift, 0.0)
        set(SHOULDER_L, -SHOULDER_X + sway, SHOULDER_Y + lift, 0.0)
        set(SHOULDER_R, SHOULDER_X + sway, SHOULDER_Y + lift, 0.0)
        set(CHEST, sway, (HIP_Y + NECK_Y) / 2 + lift, TORSO_Z)
        fun limb(end: Int, from: Int, d: DoubleArray, len: Double) =
            set(end, local[from * 3] + d[0] * len, local[from * 3 + 1] + d[1] * len, local[from * 3 + 2] + d[2] * len)
        limb(HAND_L, SHOULDER_L, armL, ARM)
        limb(HAND_R, SHOULDER_R, armR, ARM)
        limb(FOOT_L, HIP_L, leg(legL), LEG)
        limb(FOOT_R, HIP_R, leg(legR), LEG)

        // Local (x right, y up, z forward) → world, as everywhere else: right = (cos h, 0, −sin h).
        val c = cos(yaw)
        val sn = sin(yaw)
        return DoubleArray(JOINTS * 3) { i ->
            val j = i - i % 3
            val lx = local[j]
            val ly = local[j + 1]
            val lz = local[j + 2]
            when (i % 3) {
                0 -> x + lx * c + lz * sn
                1 -> y + ly
                else -> z - lx * sn + lz * c
            }
        }
    }

    /** A leg pitched forward by [a] (0 = straight down). */
    private fun leg(a: Double) = doubleArrayOf(0.0, -cos(a), sin(a))

    /** An arm pitched forward by [a], then raised out to its [side] (−1 left, 1 right) by [b]. */
    private fun arm(a: Double, b: Double, side: Int): DoubleArray {
        val th = side * b
        return doubleArrayOf(cos(a) * sin(th), -cos(a) * cos(th), sin(a))
    }

    private fun norm(x: Double, y: Double, z: Double): DoubleArray {
        val l = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / l, y / l, z / l)
    }
}

/**
 * A slapped figure flopping over: eleven Verlet particles at the joints, held together by stiff
 * sticks (the torso is braced in every direction so it stays a box), falling under gravity onto
 * the floors, walls and furniture of [HouseWorld]. Fixed time steps, so every client that starts
 * one from the same pose and push sees the same fall.
 */
class Ragdoll(start: DoubleArray, pushHeading: Double, private val level: Int, power: Double = 1.0) {
    private val p = start.copyOf()
    private val q = start.copyOf()
    private val sticks: List<Triple<Int, Int, Double>>
    private var steps = 0L
    private val grounded = BooleanArray(Blocky.JOINTS)
    var ageMs = 0L
        private set

    init {
        val pairs = ArrayList<Pair<Int, Int>>()
        val torso = listOf(Blocky.NECK, Blocky.SHOULDER_L, Blocky.SHOULDER_R, Blocky.HIP_L, Blocky.HIP_R, Blocky.CHEST)
        for (i in torso.indices) for (j in i + 1 until torso.size) pairs += torso[i] to torso[j]
        for (j in listOf(Blocky.NECK, Blocky.SHOULDER_L, Blocky.SHOULDER_R, Blocky.HIP_L)) pairs += Blocky.HEAD to j
        pairs += Blocky.HAND_L to Blocky.SHOULDER_L
        pairs += Blocky.HAND_R to Blocky.SHOULDER_R
        pairs += Blocky.FOOT_L to Blocky.HIP_L
        pairs += Blocky.FOOT_R to Blocky.HIP_R
        sticks = pairs.map { (a, b) -> Triple(a, b, dist(a, b)) }
        // The joints are particles with a radius: lift the feet off the floor by it, or the first
        // collision pops them up and the sticks fling the whole body into the air.
        for (j in 0 until Blocky.JOINTS) p[j * 3 + 1] += R

        // The push: hardest at the head, barely at the feet, so the figure topples rather than slides.
        val dx = sin(pushHeading)
        val dz = cos(pushHeading)
        for (j in 0 until Blocky.JOINTS) {
            val (k, up) = PUSH[j]
            q[j * 3] = p[j * 3] - dx * SPEED * power * k * DT
            q[j * 3 + 1] = p[j * 3 + 1] - up * kotlin.math.sqrt(power) * DT
            q[j * 3 + 2] = p[j * 3 + 2] - dz * SPEED * power * k * DT
        }
    }

    fun joints(): DoubleArray = p.copyOf()

    /** Midway between the hips: where the figure gets up. */
    fun pelvis(): DoubleArray = doubleArrayOf(
        (p[Blocky.HIP_L * 3] + p[Blocky.HIP_R * 3]) / 2,
        (p[Blocky.HIP_L * 3 + 1] + p[Blocky.HIP_R * 3 + 1]) / 2,
        (p[Blocky.HIP_L * 3 + 2] + p[Blocky.HIP_R * 3 + 2]) / 2,
    )

    fun advance(ms: Long) {
        ageMs += ms
        // Steps are counted from the start, so how the time arrives in frames doesn't change the fall.
        val due = ageMs * RATE / 1000
        while (steps < due) {
            steps++
            step()
        }
    }

    private fun step() {
        for (j in 0 until Blocky.JOINTS) {
            val i = j * 3
            for (a in 0..2) {
                val v = (p[i + a] - q[i + a]) * DRAG
                q[i + a] = p[i + a]
                p[i + a] += v + if (a == 1) -GRAVITY * DT * DT else 0.0
            }
        }
        grounded.fill(false)
        repeat(ITERATIONS) {
            for ((a, b, len) in sticks) {
                val ia = a * 3
                val ib = b * 3
                val dx = p[ib] - p[ia]
                val dy = p[ib + 1] - p[ia + 1]
                val dz = p[ib + 2] - p[ia + 2]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d < 1e-9) continue
                val k = (d - len) / d / 2
                p[ia] += dx * k; p[ia + 1] += dy * k; p[ia + 2] += dz * k
                p[ib] -= dx * k; p[ib + 1] -= dy * k; p[ib + 2] -= dz * k
            }
            for (j in 0 until Blocky.JOINTS) collide(j)
        }
        // Floor friction on whatever touched down this step: bleed off sliding, and don't bounce.
        for (j in 0 until Blocky.JOINTS) if (grounded[j]) {
            val i = j * 3
            q[i] = p[i] - (p[i] - q[i]) * FRICTION
            q[i + 2] = p[i + 2] - (p[i + 2] - q[i + 2]) * FRICTION
            q[i + 1] = p[i + 1]
        }
    }

    private fun collide(j: Int) {
        val i = j * 3
        var x = p[i]
        var y = p[i + 1]
        var z = p[i + 2]
        x = x.coerceIn(-HouseWorld.YARD_X + R, HouseWorld.YARD_X - R)
        z = z.coerceIn(HouseWorld.YARD_Z0 + R, HouseWorld.YARD_Z1 - R)
        val floor = HouseWorld.floorHeight(x, z, level)
        if (y < floor + R) { y = floor + R; grounded[j] = true }
        if (level == 0 && HouseWorld.inHouse(x, z) && !HouseWorld.onStairs(x, z)) y = y.coerceAtMost(HouseWorld.STORY - R)
        for (b in HouseWorld.solids) {
            if (x <= b.x0 - R || x >= b.x1 + R || z <= b.z0 - R || z >= b.z1 + R || y <= b.y0 - R || y >= b.y1 + R) continue
            // Out through the nearest side (or up onto the top).
            val west = x - (b.x0 - R)
            val east = b.x1 + R - x
            val south = z - (b.z0 - R)
            val north = b.z1 + R - z
            val top = b.y1 + R - y
            val m = minOf(minOf(west, east), minOf(south, north), top)
            when (m) {
                west -> x = b.x0 - R
                east -> x = b.x1 + R
                south -> z = b.z0 - R
                north -> z = b.z1 + R
                else -> { y = b.y1 + R; grounded[j] = true }
            }
        }
        p[i] = x; p[i + 1] = y; p[i + 2] = z
    }

    private fun dist(a: Int, b: Int) =
        sqrt((p[a * 3] - p[b * 3]).let { it * it } + (p[a * 3 + 1] - p[b * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[b * 3 + 2]).let { it * it })

    companion object {
        const val DOWN_MS = 3_000L
        private const val RATE = 120L
        private const val DT = 1.0 / RATE
        private const val ITERATIONS = 6
        private const val GRAVITY = 15.0
        private const val DRAG = 0.995
        private const val FRICTION = 0.85
        private const val SPEED = 5.5
        /** Particle radius: limbs are 0.36 thick, so a little under half. */
        private const val R = 0.15
        /** Per joint: (share of the push, upward kick in m/s). */
        private val PUSH = arrayOf(
            1.0 to 2.4, 0.9 to 2.2, 0.85 to 2.0, 0.85 to 2.0, 0.45 to 1.2, 0.45 to 1.2,
            0.95 to 2.6, 0.95 to 2.6, 0.1 to 1.8, 0.1 to 1.8, 0.7 to 1.8,
        )
    }
}
