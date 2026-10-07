package app.notmumla.game.house

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The open-top car in the garage, which anyone can drive off in: a box [HALF_W] by [HALF_L]
 * (half-sizes) around (x, z), nose toward heading h, always on the ground. Only its driver runs
 * [drive]; everyone else follows the driver's states with [coast], the way walkers are dead-reckoned.
 */
object Car {
    const val HALF_W = 1.2
    const val HALF_L = 2.5
    /** Top of the body; the driver sits in it up to the chest, behind the windscreen. */
    const val BODY_TOP = 1.1
    /** Walkers and pots below this bump into it. */
    const val HEIGHT = 1.6
    const val HOME_X = 6.0
    const val HOME_Z = -0.1
    /** Nose toward the garage door. */
    const val HOME_H = PI

    const val MAX_HEALTH = 100.0
    /** Below this it smokes; below [BURNING] it's on fire too; at 0 it blows up. */
    const val SMOKING = 40.0
    const val BURNING = 15.0
    const val MAX_SPEED = 11.0
    const val REVERSE_SPEED = 4.5
    /** Hitting something faster than this dents it, by [CRASH_DAMAGE] per m/s over. */
    const val CRASH_SPEED = 3.0
    const val CRASH_DAMAGE = 9.0
    /** Slowest it knocks someone over at. */
    const val RUN_OVER_SPEED = 2.5
    const val BLAST_RADIUS = 4.5
    /** How long the burnt-out wreck stays before a new car is back in the garage. */
    const val WRECK_MS = 8_000L
    /** How near its side you have to stand to get in. */
    const val REACH = 1.2

    private const val ACCEL = 7.0
    private const val BRAKE = 16.0
    private const val COAST = 4.0
    /** Tightest turn, as a radius: the most it turns per second is speed / this. */
    private const val TURN_RADIUS = 4.0
    /** How quickly it steers toward where the stick points, per radian off. */
    private const val STEER = 3.0
    private const val DEAD_ZONE = 0.15
    private const val BOUNCE = 0.2
    /** Speed lost per second scraping along a wall. */
    private const val SCRAPE = 2.0
    private const val DT = 1.0 / 120
    private const val COAST_DT = 1.0 / 30
    /** The driver's seat, from the middle: left of centre, a little back. */
    private const val SEAT_RIGHT = -0.5
    private const val SEAT_FWD = -0.3
    /** Feet height that puts a sitting figure's hips on the seat. */
    private const val SEAT_Y = 0.57

    /** What it can run into: everything solid it's tall enough to meet (not the leaves overhead). */
    private val obstacles: List<HouseWorld.Box> by lazy { HouseWorld.solids.filter { it.y0 < HEIGHT && it.y1 > 0.15 } }

    /** A car being driven: where it is, which way it points, its speed along that (negative backing up). */
    class Motion(var x: Double, var z: Double, var h: Double) {
        var v = 0.0
        /** How fast it's turning, rad/s: sent along so others can follow the curve. */
        var turn = 0.0
        /** Up against something since the last step: a hit only counts on the way in. */
        var contact = false

        fun home() {
            x = HOME_X; z = HOME_Z; h = HOME_H
            v = 0.0; turn = 0.0; contact = false
        }
    }

    /** World (x, z) of the point [right] and [fwd] from the middle of a car at (x, z, h). */
    fun local(x: Double, z: Double, h: Double, right: Double, fwd: Double) =
        doubleArrayOf(x + cos(h) * right + sin(h) * fwd, z - sin(h) * right + cos(h) * fwd)

    /** Whether (px, pz) is within [r] of a car at (x, z, h), seen from above. */
    fun contains(x: Double, z: Double, h: Double, px: Double, pz: Double, r: Double = 0.0): Boolean {
        val dx = px - x
        val dz = pz - z
        return abs(dx * sin(h) + dz * cos(h)) < HALF_L + r && abs(dx * cos(h) - dz * sin(h)) < HALF_W + r
    }

    /** How far (px, pz) is from the side of a car at (x, z, h); 0 inside it. */
    fun distance(x: Double, z: Double, h: Double, px: Double, pz: Double): Double {
        val dx = px - x
        val dz = pz - z
        val f = dx * sin(h) + dz * cos(h)
        val s = dx * cos(h) - dz * sin(h)
        return hypot(max(0.0, abs(f) - HALF_L), max(0.0, abs(s) - HALF_W))
    }

    /** The point on the outline of a car at (x, z, h) nearest (px, pz). */
    fun nearest(x: Double, z: Double, h: Double, px: Double, pz: Double): DoubleArray {
        val dx = px - x
        val dz = pz - z
        val f = (dx * sin(h) + dz * cos(h)).coerceIn(-HALF_L, HALF_L)
        val s = (dx * cos(h) - dz * sin(h)).coerceIn(-HALF_W, HALF_W)
        return local(x, z, h, s, f)
    }

    /** Whether a car at (x, z, h) would be in a wall, the fence, a tree or the furniture. */
    fun blocked(x: Double, z: Double, h: Double): Boolean {
        if (!HouseWorld.inYard(x, z)) return true
        val fx = sin(h)
        val fz = cos(h)
        val rx = cos(h)
        val rz = -sin(h)
        // Separating axes: the world's two, then the car's two.
        val ex = abs(rx) * HALF_W + abs(fx) * HALF_L
        val ez = abs(rz) * HALF_W + abs(fz) * HALF_L
        for (b in obstacles) {
            val hx = (b.x1 - b.x0) / 2
            val hz = (b.z1 - b.z0) / 2
            val dx = (b.x0 + b.x1) / 2 - x
            val dz = (b.z0 + b.z1) / 2 - z
            if (abs(dx) >= ex + hx || abs(dz) >= ez + hz) continue
            if (abs(dx * fx + dz * fz) >= HALF_L + hx * abs(fx) + hz * abs(fz)) continue
            if (abs(dx * rx + dz * rz) >= HALF_W + hx * abs(rx) + hz * abs(rz)) continue
            return true
        }
        return false
    }

    /**
     * Drive [m] for [seconds] with the stick pointing at world direction (mx, mz), length ≤ 1: it
     * speeds up and steers toward that, or, from (nearly) standing, backs up toward it if it's
     * behind; pulling back while rolling brakes. Returns the speed it hit something at (0 if it didn't).
     */
    fun drive(m: Motion, mx: Double, mz: Double, seconds: Double): Double {
        if (seconds <= 0) return 0.0
        val n = max(1, ceil(seconds / DT).toInt())
        var impact = 0.0
        repeat(n) { impact = max(impact, step(m, mx, mz, seconds / n)) }
        return impact
    }

    private fun step(m: Motion, mx: Double, mz: Double, dt: Double): Double {
        val mag = min(1.0, hypot(mx, mz))
        var target = 0.0
        var steer = 0.0
        if (mag > DEAD_ZONE) {
            val want = atan2(mx, mz)
            val ahead = HouseArena.angleDiff(want, m.h)
            val behind = HouseArena.angleDiff(want, m.h + PI)
            val forward = when {
                m.v > 0.5 -> true
                m.v < -0.5 -> false
                else -> abs(ahead) <= 2.0
            }
            // Turning the rear toward [want] turns the heading the same way, so both steer alike.
            if (forward && abs(ahead) <= 2.2) {
                target = mag * MAX_SPEED * (0.45 + 0.55 * max(0.0, cos(ahead)))
                steer = ahead
            } else if (!forward && abs(behind) <= 2.2) {
                target = -mag * REVERSE_SPEED
                steer = behind
            }
        }
        val rate = when {
            mag <= DEAD_ZONE -> COAST
            m.v * target >= 0 && abs(target) > abs(m.v) -> ACCEL
            else -> BRAKE
        }
        m.v = if (m.v < target) min(target, m.v + rate * dt) else max(target, m.v - rate * dt)
        val maxTurn = abs(m.v) / TURN_RADIUS
        m.turn = (steer * STEER).coerceIn(-maxTurn, maxTurn)

        val nh = m.h + m.turn * dt
        val nx = m.x + sin(nh) * m.v * dt
        val nz = m.z + cos(nh) * m.v * dt
        if (!blocked(nx, nz, nh)) {
            m.x = nx; m.z = nz; m.h = nh
            m.contact = false
            return 0.0
        }
        val speed = abs(m.v)
        // A glancing blow keeps the part of the motion along the wall; the rest is the hit.
        val slide = listOf(
            Triple(nx, m.z, abs(cos(nh))),
            Triple(m.x, nz, abs(sin(nh))),
        ).filter { !blocked(it.first, it.second, nh) }.minByOrNull { it.third }
        val hit = if (slide != null && slide.third < 0.9) {
            m.x = slide.first; m.z = slide.second; m.h = nh
            m.v *= 1 - SCRAPE * dt
            speed * slide.third
        } else {
            m.v = -m.v * BOUNCE
            m.turn = 0.0
            speed
        }
        val first = !m.contact
        m.contact = true
        return if (first) hit else 0.0
    }

    /**
     * Where a car seen at (x, z, h) doing [v] and turning at [turn] is [seconds] later, if it keeps
     * on: along the curve, stopping short of whatever it would hit. Returns (x, z, h).
     */
    fun coast(x: Double, z: Double, h: Double, v: Double, turn: Double, seconds: Double): DoubleArray {
        var cx = x
        var cz = z
        var ch = h
        if (seconds > 0 && (v != 0.0 || turn != 0.0)) {
            val n = max(1, ceil(seconds / COAST_DT).toInt())
            val dt = seconds / n
            for (i in 0 until n) {
                val nh = ch + turn * dt
                val nx = cx + sin(nh) * v * dt
                val nz = cz + cos(nh) * v * dt
                if (blocked(nx, nz, nh)) break
                cx = nx; cz = nz; ch = nh
            }
        }
        return doubleArrayOf(cx, cz, ch)
    }

    /** The driver sitting in a car at (x, z, h), hands on the wheel: joints as [Blocky.pose] gives them. */
    fun seat(x: Double, z: Double, h: Double): DoubleArray {
        val p = local(x, z, h, SEAT_RIGHT, SEAT_FWD)
        return Blocky.pose(p[0], SEAT_Y, p[1], h, emote = Emote.SIT)
    }

    /** Spots just outside a car at (x, z, h) to get out at: the driver's door first, then round it. */
    fun doors(x: Double, z: Double, h: Double): List<DoubleArray> {
        val out = HouseWorld.RADIUS + 0.25
        return listOf(
            -(HALF_W + out) to SEAT_FWD, (HALF_W + out) to SEAT_FWD,
            -(HALF_W + out) to 1.5, -(HALF_W + out) to -1.5, (HALF_W + out) to 1.5, (HALF_W + out) to -1.5,
            0.0 to -(HALF_L + out), 0.0 to (HALF_L + out),
            -(HALF_W + out + 1.0) to SEAT_FWD, (HALF_W + out + 1.0) to SEAT_FWD,
            0.0 to -(HALF_L + out + 1.0), 0.0 to (HALF_L + out + 1.0),
        ).map { (r, f) -> local(x, z, h, r, f) }
    }
}
