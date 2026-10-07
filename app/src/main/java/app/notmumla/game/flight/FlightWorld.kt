package app.notmumla.game.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The airspace every client simulates: a fixed field of towers and arches, so nothing about the
 * map goes over the wire. Units are metres and radians; heading 0 faces +z and grows clockwise
 * (turning right), pitch is positive nose-up, y is up.
 */
object FlightWorld {
    /** Past this (on x or z) the ship turns itself back toward the middle. */
    const val HALF = 350.0
    /** Nothing ever goes past this; also the wire bound. */
    const val LIMIT = 420.0
    /** Where a ship touches the ground. */
    const val FLOOR = 1.5
    const val CEILING = 180.0
    /** Below this a cushion pushes ships up, harder the lower they are... */
    const val HOVER = 14.0
    /** ...reaching this climb rate (m/s) at the ground: a steep dive at cruise just gets through. */
    const val CUSHION = 32.0
    /** Sink rate (m/s) the ground forgives; above it, each m/s costs [SLAM_DAMAGE] shield. */
    const val SAFE_SINK = 4.0
    const val SLAM_DAMAGE = 2.5
    /** Nose-up pitch a ship bounces to off the ground. */
    const val BOUNCE_PITCH = 0.25

    const val CRUISE = 42.0             // m/s
    const val BOOST_SPEED = 75.0
    const val BRAKE_SPEED = 22.0
    const val ACCEL = 45.0              // m/s²
    const val MAX_YAW = 1.3             // rad/s
    const val MAX_PITCH_RATE = 1.2      // rad/s
    const val MAX_PITCH = 1.2           // rad, about 69°

    const val SHIP_RADIUS = 3.6         // generous: other ships are only ever where we guess
    const val MUZZLE = 4.0
    const val LASER_SPEED = 240.0       // on top of the ship's own speed
    const val LASER_LIFE_MS = 1_100L
    const val FIRE_INTERVAL_MS = 160L
    /** Five hits down a full shield, which only refills on respawn. */
    const val LASER_DAMAGE = 20
    const val SHIELD = 100
    const val RESPAWN_MS = 3_000L
    const val ROLL_MS = 600L
    const val ROLL_COOLDOWN_MS = 1_500L
    /** Boost meter (0..1) use and recovery per second. */
    const val BOOST_DRAIN = 0.6
    const val BOOST_REFILL = 0.25

    enum class Kind { TOWER, ARCH }

    /** An axis-aligned solid; arches are three of these (two legs and a beam). */
    data class Box(
        val x0: Double, val x1: Double,
        val y0: Double, val y1: Double,
        val z0: Double, val z1: Double,
        val kind: Kind,
    ) {
        fun contains(x: Double, y: Double, z: Double, m: Double = 0.0) =
            x > x0 - m && x < x1 + m && y > y0 - m && y < y1 + m && z > z0 - m && z < z1 + m
    }

    private fun tower(x: Double, z: Double, half: Double, height: Double) =
        Box(x - half, x + half, 0.0, height, z - half, z + half, Kind.TOWER)

    /** An arch to fly through; [alongX] = the legs stand apart on x (you fly through along z). */
    private fun arch(x: Double, z: Double, alongX: Boolean): List<Box> {
        val span = 16.0
        val leg = 4.0
        val top = 30.0
        fun box(cx: Double, cz: Double, hx: Double, hz: Double, y0: Double, y1: Double) =
            Box(cx - hx, cx + hx, y0, y1, cz - hz, cz + hz, Kind.ARCH)
        return if (alongX) listOf(
            box(x - span, z, leg, leg, 0.0, top), box(x + span, z, leg, leg, 0.0, top),
            box(x, z, span + leg, leg, top, top + 6),
        ) else listOf(
            box(x, z - span, leg, leg, 0.0, top), box(x, z + span, leg, leg, 0.0, top),
            box(x, z, leg, span + leg, top, top + 6),
        )
    }

    val boxes: List<Box> = listOf(
        tower(-120.0, 80.0, 10.0, 90.0),
        tower(60.0, 140.0, 8.0, 60.0),
        tower(150.0, -40.0, 12.0, 110.0),
        tower(-60.0, -130.0, 9.0, 70.0),
        tower(0.0, 0.0, 14.0, 50.0),
        tower(200.0, 180.0, 8.0, 80.0),
        tower(-200.0, -60.0, 10.0, 100.0),
        tower(-170.0, 200.0, 8.0, 55.0),
        tower(110.0, -180.0, 10.0, 75.0),
        tower(-20.0, 240.0, 7.0, 65.0),
        tower(240.0, 40.0, 9.0, 45.0),
        tower(-250.0, -220.0, 10.0, 85.0),
        tower(-300.0, 50.0, 9.0, 70.0),
        tower(300.0, -250.0, 10.0, 95.0),
        tower(60.0, -280.0, 8.0, 60.0),
        tower(-100.0, 300.0, 9.0, 80.0),
        tower(180.0, 300.0, 8.0, 50.0),
        tower(-290.0, 260.0, 10.0, 90.0),
        tower(90.0, 30.0, 6.0, 35.0),
        tower(-40.0, 90.0, 7.0, 45.0),
        tower(300.0, 120.0, 8.0, 65.0),
        tower(30.0, -150.0, 7.0, 55.0),
        tower(-230.0, 120.0, 6.0, 40.0),
        tower(170.0, -120.0, 6.0, 30.0),
    ) + arch(0.0, -80.0, alongX = true) + arch(-90.0, -20.0, alongX = false) + arch(120.0, 90.0, alongX = true) +
        // Rows of arches to thread at speed.
        listOf(-240.0, -190.0, -140.0, -90.0).flatMap { arch(-150.0, it, alongX = true) } +
        listOf(-110.0, -60.0, -10.0, 40.0).flatMap { arch(it, 170.0, alongX = false) } +
        listOf(-160.0, -110.0, -60.0).flatMap { arch(260.0, it, alongX = true) }

    /** The ground cushion's climb rate at height [y]. */
    fun cushion(y: Double): Double {
        if (y >= HOVER) return 0.0
        val k = ((HOVER - y) / (HOVER - FLOOR)).coerceAtMost(1.0)
        return CUSHION * k * k
    }

    fun solid(x: Double, y: Double, z: Double, margin: Double = 0.0) = boxes.any { it.contains(x, y, z, margin) }

    fun wrap(h: Double): Double {
        var a = h % (2 * PI)
        if (a < 0) a += 2 * PI
        return a
    }

    /** a − b, wrapped to (−π, π]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d <= -PI) d += 2 * PI
        return d
    }
}

/**
 * A ship's position and attitude, plus the rates it was moving at (for dead reckoning): speed [v]
 * along the nose, yaw rate [w], pitch rate [q]. Roll is only ever drawn (banking into turns).
 */
data class Pose3(
    val x: Double, val y: Double, val z: Double,
    val h: Double, val p: Double,
    val v: Double = 0.0, val w: Double = 0.0, val q: Double = 0.0,
) {
    /**
     * Where this pose will be after [sec] seconds of the same controls. Integrated in small steps
     * (the pitch limit and ground cushion make a closed form messy); our own ship flies with the
     * same code, so a peer holding the stick still is drawn exactly where they are, hovering
     * included.
     */
    fun extrapolate(sec: Double): Pose3 {
        var cur = this
        var left = sec
        while (left > 1e-9) {
            val dt = min(left, STEP)
            cur = cur.advance(dt)
            left -= dt
        }
        return cur
    }

    private fun advance(dt: Double): Pose3 {
        val p1 = (p + q * dt).coerceIn(-FlightWorld.MAX_PITCH, FlightWorld.MAX_PITCH)
        val hm = h + w * dt / 2
        val pm = (p + p1) / 2
        val d = v * dt
        val lim = FlightWorld.LIMIT
        return copy(
            x = (x + sin(hm) * cos(pm) * d).coerceIn(-lim, lim),
            y = (y + sin(pm) * d + FlightWorld.cushion(y) * dt).coerceIn(FlightWorld.FLOOR, FlightWorld.CEILING),
            z = (z + cos(hm) * cos(pm) * d).coerceIn(-lim, lim),
            h = FlightWorld.wrap(h + w * dt),
            p = p1,
        )
    }

    private companion object {
        const val STEP = 0.05
    }
}

/** A laser bolt: a straight line from the muzzle until it hits something or burns out. */
data class Bolt(
    val owner: Int,
    val shot: Int,
    val x: Double, val y: Double, val z: Double,
    /** Unit direction. */
    val dx: Double, val dy: Double, val dz: Double,
    val speed: Double,
    val born: Long,
) {
    fun at(now: Long): DoubleArray {
        val d = speed * (now - born) / 1000.0
        return doubleArrayOf(x + dx * d, y + dy * d, z + dz * d)
    }

    fun expired(now: Long): Boolean {
        if (now - born > FlightWorld.LASER_LIFE_MS) return true
        val (px, py, pz) = at(now)
        return py < 0 || abs(px) > FlightWorld.LIMIT || abs(pz) > FlightWorld.LIMIT || FlightWorld.solid(px, py, pz)
    }

    companion object {
        fun fromMuzzle(owner: Int, shot: Int, p: Pose3, now: Long): Bolt {
            val cp = cos(p.p)
            val dx = sin(p.h) * cp
            val dy = sin(p.p)
            val dz = cos(p.h) * cp
            val m = FlightWorld.MUZZLE
            return Bolt(owner, shot, p.x + dx * m, p.y + dy * m, p.z + dz * m, dx, dy, dz,
                FlightWorld.LASER_SPEED + p.v, now)
        }
    }
}

/** Whether the segment a→b passes within [r] of c. */
internal fun segmentHitsSphere(a: DoubleArray, b: DoubleArray, cx: Double, cy: Double, cz: Double, r: Double): Boolean {
    val dx = b[0] - a[0]
    val dy = b[1] - a[1]
    val dz = b[2] - a[2]
    val len2 = dx * dx + dy * dy + dz * dz
    val t = if (len2 == 0.0) 0.0 else (((cx - a[0]) * dx + (cy - a[1]) * dy + (cz - a[2]) * dz) / len2).coerceIn(0.0, 1.0)
    val px = a[0] + t * dx - cx
    val py = a[1] + t * dy - cy
    val pz = a[2] + t * dz - cz
    return px * px + py * py + pz * pz <= r * r
}
