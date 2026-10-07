package app.notmumla.game.house

import kotlin.math.cos
import kotlin.math.sin

/**
 * A thrown potted plant: a plain ballistic arc from where it left the hands, checked against the
 * house in fixed steps counted from the throw, so every client flies it the same way and it breaks
 * on the same wall. Figures are checked by whoever owns this pot's [advance] call: only the victim's
 * own check of itself counts (see [HouseArena]).
 */
class Pot(
    val owner: Int,
    val n: Int,
    private val x0: Double,
    private val y0: Double,
    private val z0: Double,
    val heading: Double,
    private val vy: Double,
) {
    private var steps = 0L
    var ageMs = 0L
        private set
    /** Where it broke, once it has; null while it's still flying. */
    var smashedAt: DoubleArray? = null
        private set

    fun at(t: Double) = doubleArrayOf(
        x0 + sin(heading) * SPEED * t,
        y0 + vy * t - HouseWorld.GRAVITY * t * t / 2,
        z0 + cos(heading) * SPEED * t,
    )

    fun position(): DoubleArray = smashedAt ?: at(steps * DT)

    /** Tumble angle, for drawing. */
    fun spin() = steps * DT * 9.0

    /**
     * Fly on by [ms]. [hits] says whether a figure is in the way at a point; returns true when this
     * call ended the flight against one.
     */
    fun advance(ms: Long, hits: (DoubleArray) -> Boolean): Boolean {
        if (smashedAt != null) return false
        ageMs += ms
        val due = ageMs * RATE / 1000
        while (steps < due) {
            steps++
            val p = at(steps * DT)
            if (hits(p)) { smashedAt = p; return true }
            if (blocked(p) || steps * DT > MAX_FLIGHT_S) { smashedAt = at((steps - 1) * DT); return false }
        }
        return false
    }

    /** Break it now (someone said it hit them). */
    fun smash() {
        if (smashedAt == null) smashedAt = position()
    }

    private fun blocked(p: DoubleArray): Boolean {
        val (x, y, z) = Triple(p[0], p[1], p[2])
        if (!HouseWorld.inYard(x, z) || y < R) return true
        if (HouseWorld.inHouse(x, z)) {
            if (HouseWorld.onStairs(x, z)) {
                if (y < HouseWorld.stairY(z) + R) return true
            } else if (y0 < HouseWorld.STORY) {
                // Thrown downstairs: the upstairs floor is a ceiling.
                if (y > HouseWorld.STORY - R) return true
            } else if (y < HouseWorld.STORY + R) {
                return true
            }
        }
        for (b in HouseWorld.solids) {
            if (x > b.x0 - R && x < b.x1 + R && y > b.y0 - R && y < b.y1 + R && z > b.z0 - R && z < b.z1 + R) return true
        }
        return false
    }

    companion object {
        const val SPEED = 9.0
        /** Close enough to a figure's middle to hit it. */
        const val HIT_RADIUS = 0.45
        private const val R = 0.15
        private const val RATE = 120L
        private const val DT = 1.0 / RATE
        private const val MAX_FLIGHT_S = 3.0
        const val SHARDS_MS = 700L

        /** Whether [p] touches a standing figure with feet at (x, y, z). */
        fun touches(p: DoubleArray, x: Double, y: Double, z: Double): Boolean {
            if (p[1] < y - 0.1 || p[1] > y + HouseWorld.HEIGHT + 0.1) return false
            val dx = p[0] - x
            val dz = p[2] - z
            return dx * dx + dz * dz < HIT_RADIUS * HIT_RADIUS
        }
    }
}
