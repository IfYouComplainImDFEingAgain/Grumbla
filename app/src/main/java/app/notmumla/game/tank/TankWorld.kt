package app.notmumla.game.tank

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The arena every client simulates: a fixed square of fixed obstacles, so nothing about the map
 * needs to go over the wire. Units are metres; heading 0 faces +z and grows clockwise (turning right).
 */
object TankWorld {
    const val HALF = 100.0
    const val TANK_RADIUS = 2.2
    const val MAX_SPEED = 12.0          // m/s
    const val MAX_TURN = PI / 2         // rad/s
    const val SHELL_SPEED = 50.0
    const val SHELL_LIFE_MS = 2_400L
    const val RELOAD_MS = 1_000L
    const val RESPAWN_MS = 3_000L
    const val MUZZLE = 3.6
    const val SHELL_Y = 1.4

    enum class Shape { CUBE, PYRAMID }

    /** An obstacle; [half] is its half-width on x and z (collision is the square footprint). */
    data class Block(val x: Double, val z: Double, val half: Double, val height: Double, val shape: Shape)

    val blocks: List<Block> = listOf(
        Block(-40.0, 35.0, 4.0, 6.0, Shape.PYRAMID),
        Block(30.0, 50.0, 3.0, 5.0, Shape.CUBE),
        Block(55.0, -20.0, 4.0, 6.0, Shape.PYRAMID),
        Block(-60.0, -45.0, 3.0, 5.0, Shape.CUBE),
        Block(0.0, 0.0, 5.0, 4.0, Shape.CUBE),
        Block(-15.0, -70.0, 4.0, 6.0, Shape.PYRAMID),
        Block(70.0, 70.0, 3.0, 5.0, Shape.CUBE),
        Block(-75.0, 75.0, 4.0, 7.0, Shape.PYRAMID),
        Block(20.0, -55.0, 3.0, 5.0, Shape.CUBE),
        Block(-30.0, 5.0, 3.0, 5.0, Shape.CUBE),
        Block(80.0, 10.0, 4.0, 6.0, Shape.PYRAMID),
        Block(10.0, 80.0, 4.0, 6.0, Shape.PYRAMID),
    )

    fun insideBlock(x: Double, z: Double, margin: Double = 0.0): Boolean =
        blocks.any { abs(x - it.x) < it.half + margin && abs(z - it.z) < it.half + margin }

    fun inArena(x: Double, z: Double, margin: Double = 0.0): Boolean =
        abs(x) <= HALF - margin && abs(z) <= HALF - margin

    /** Where a tank can stand: inside the walls and clear of every block. */
    fun free(x: Double, z: Double): Boolean =
        inArena(x, z, TANK_RADIUS) && !insideBlock(x, z, TANK_RADIUS)

    fun wrap(h: Double): Double {
        var a = h % (2 * PI)
        if (a < 0) a += 2 * PI
        return a
    }

    fun clampToArena(v: Double) = v.coerceIn(-HALF + TANK_RADIUS, HALF - TANK_RADIUS)
}

/** A tank's position, heading and the rates it was moving at (for dead reckoning). */
data class Pose(val x: Double, val z: Double, val h: Double, val v: Double = 0.0, val w: Double = 0.0) {
    /** Where this pose will be after [sec] seconds at constant speed and turn rate. */
    fun extrapolate(sec: Double): Pose {
        if (sec <= 0) return this
        val h1 = h + w * sec
        val (x1, z1) = if (abs(w) < 1e-4) {
            x + sin(h) * v * sec to z + cos(h) * v * sec
        } else {
            // Exact arc: integrate (v·sin h, v·cos h) while h turns at w.
            x + v / w * (cos(h) - cos(h1)) to z + v / w * (sin(h1) - sin(h))
        }
        return copy(x = TankWorld.clampToArena(x1), z = TankWorld.clampToArena(z1), h = TankWorld.wrap(h1))
    }
}

/** A shell in flight. Shells move in a straight line until they hit something or burn out. */
data class Shell(
    val owner: Int,
    val shot: Int,
    val x: Double,
    val z: Double,
    val h: Double,
    val born: Long,
) {
    fun at(now: Long): Pair<Double, Double> {
        val d = TankWorld.SHELL_SPEED * (now - born) / 1000.0
        return x + sin(h) * d to z + cos(h) * d
    }

    fun expired(now: Long): Boolean {
        if (now - born > TankWorld.SHELL_LIFE_MS) return true
        val (sx, sz) = at(now)
        return !TankWorld.inArena(sx, sz) || TankWorld.insideBlock(sx, sz)
    }

    companion object {
        fun fromMuzzle(owner: Int, shot: Int, p: Pose, now: Long) = Shell(
            owner, shot,
            p.x + sin(p.h) * TankWorld.MUZZLE, p.z + cos(p.h) * TankWorld.MUZZLE, p.h, now,
        )
    }
}

/** Whether the segment (ax,az)→(bx,bz) passes within [r] of (cx,cz). */
internal fun segmentHitsCircle(ax: Double, az: Double, bx: Double, bz: Double, cx: Double, cz: Double, r: Double): Boolean {
    val dx = bx - ax
    val dz = bz - az
    val len2 = dx * dx + dz * dz
    val t = if (len2 == 0.0) 0.0 else (((cx - ax) * dx + (cz - az) * dz) / len2).coerceIn(0.0, 1.0)
    val px = ax + t * dx - cx
    val pz = az + t * dz - cz
    return px * px + pz * pz <= r * r
}
