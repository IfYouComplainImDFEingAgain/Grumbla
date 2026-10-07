package app.notmumla.game.house

import kotlin.math.abs

/**
 * The block house every client simulates: a two-storey house in a fenced yard, with the garage on
 * the right (+x) and a bonus room above it. Fixed, so nothing about the map goes over the wire.
 *
 * Units are metres; y is up. Seen from the front yard, +x is right and +z is into the house.
 * Heading 0 faces +z and grows clockwise (turning right), as in the other arenas.
 */
object HouseWorld {
    const val STORY = 3.0
    const val WALL_T = 0.2
    /** A character's footprint radius and height, for collisions. */
    const val RADIUS = 0.3
    const val HEIGHT = 1.9
    /** Most a walker climbs or drops in one move: stairs yes, the stairwell's edge no. */
    const val STEP = 0.5
    const val WALK_SPEED = 4.5

    // The yard, inside the fence.
    const val YARD_X = 16.0
    const val YARD_Z0 = -13.0
    const val YARD_Z1 = 11.0

    // The house's outer walls.
    const val HX0 = -9.0
    const val HX1 = 9.0
    const val HZ0 = -5.0
    const val HZ1 = 5.0
    /** The wall between the hallway and the garage (and, upstairs, the bonus room). */
    const val GARAGE_X = 3.0

    // The stairs run along the garage wall, climbing toward +z.
    const val SX0 = 1.6
    const val SX1 = GARAGE_X - WALL_T / 2
    const val SZ0 = -1.4
    const val SZ1 = 3.6
    const val STEPS = 12

    enum class Kind { EXTERIOR, WALL, RAIL, FENCE, STAIR, FURNITURE, TRUNK, LEAVES }

    /** An axis-aligned block. [color]/[top] are ARGB, used for furniture; walls are coloured by kind. */
    class Box(
        val x0: Double, val x1: Double,
        val y0: Double, val y1: Double,
        val z0: Double, val z1: Double,
        val kind: Kind,
        val level: Int,
        val color: Long = 0,
        val top: Long = color,
    ) {
        val cx get() = (x0 + x1) / 2
        val cz get() = (z0 + z1) / 2
        /** Inside the house's walls (exterior walls themselves count as outside). */
        val inside: Boolean = kind != Kind.EXTERIOR && inHouse(cx, cz)
        /** Walkers bump into it. Stairs are walked on; leaves are overhead. */
        val solid: Boolean = kind != Kind.STAIR && kind != Kind.LEAVES
    }

    /** A floor area; [checker] tiles it in two shades of [color]. */
    class Floor(val x0: Double, val x1: Double, val z0: Double, val z1: Double, val level: Int, val color: Long, val checker: Boolean = false)

    fun inHouse(x: Double, z: Double) = x > HX0 && x < HX1 && z > HZ0 && z < HZ1

    fun onStairs(x: Double, z: Double) = x >= SX0 && x <= SX1 && z >= SZ0 && z <= SZ1

    /** Height of the stairs' walking surface: one smooth ramp (the steps are drawn, not walked). */
    fun stairY(z: Double) = ((z - SZ0) / (SZ1 - SZ0)).coerceIn(0.0, 1.0) * STORY

    fun levelOf(y: Double) = if (y > STORY / 2) 1 else 0

    /** The floor under (x, z) for someone on [level]: the stairs belong to both floors. */
    fun floorHeight(x: Double, z: Double, level: Int): Double = when {
        onStairs(x, z) -> stairY(z)
        level >= 1 && inHouse(x, z) -> STORY
        else -> 0.0
    }

    fun inYard(x: Double, z: Double, margin: Double = 0.0) =
        abs(x) <= YARD_X - margin && z >= YARD_Z0 + margin && z <= YARD_Z1 - margin

    /** Whether a walker standing at height [y] fits at (x, z). */
    fun free(x: Double, z: Double, y: Double): Boolean {
        if (!inYard(x, z, RADIUS)) return false
        for (b in solids) {
            if (b.y0 >= y + HEIGHT || b.y1 <= y + 0.05) continue
            if (x > b.x0 - RADIUS && x < b.x1 + RADIUS && z > b.z0 - RADIUS && z < b.z1 + RADIUS) return false
        }
        return true
    }

    /**
     * Walk by (dx, dz) from (x, y, z), each axis on its own so walls are slid along. Returns the new
     * (x, y, z). A move that would climb or drop more than [STEP] is refused, which keeps walkers
     * off the stairs' sides and out of the stairwell.
     */
    fun move(x: Double, y: Double, z: Double, dx: Double, dz: Double): DoubleArray {
        var cx = x
        var cy = y
        var cz = z
        val level = levelOf(y)
        floorFor(cx + dx, cz, cy, level)?.let { cx += dx; cy = it }
        floorFor(cx, cz + dz, cy, level)?.let { cz += dz; cy = it }
        return doubleArrayOf(cx, cy, cz)
    }

    private fun floorFor(x: Double, z: Double, y: Double, level: Int): Double? {
        val ny = floorHeight(x, z, level)
        if (abs(ny - y) > STEP || !free(x, z, ny)) return null
        return ny
    }

    // ---- The map ---------------------------------------------------------------------------

    private const val CHUNK = 1.5

    private fun MutableList<Box>.wallAlongX(level: Int, kind: Kind, z0: Double, z1: Double, x0: Double, x1: Double, height: Double, vararg gaps: ClosedRange<Double>) =
        span(x0, x1, gaps) { a, b -> add(Box(a, b, level * STORY, level * STORY + height, z0, z1, kind, level)) }

    private fun MutableList<Box>.wallAlongZ(level: Int, kind: Kind, x0: Double, x1: Double, z0: Double, z1: Double, height: Double, vararg gaps: ClosedRange<Double>) =
        span(z0, z1, gaps) { a, b -> add(Box(x0, x1, level * STORY, level * STORY + height, a, b, kind, level)) }

    /** [from]..[to] minus the [gaps], cut into chunks no longer than [CHUNK] (short pieces sort better). */
    private fun span(from: Double, to: Double, gaps: Array<out ClosedRange<Double>>, emit: (Double, Double) -> Unit) {
        val cuts = gaps.sortedBy { it.start }
        var a = from
        for (g in cuts + listOf(to..to)) {
            val b = g.start.coerceAtMost(to)
            if (b - a > 1e-6) {
                val n = kotlin.math.ceil((b - a) / CHUNK).toInt()
                for (i in 0 until n) emit(a + (b - a) * i / n, a + (b - a) * (i + 1) / n)
            }
            a = maxOf(a, g.endInclusive)
        }
    }

    private fun MutableList<Box>.thing(level: Int, x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, color: Long, top: Long = color, kind: Kind = Kind.FURNITURE) =
        add(Box(x0, x1, level * STORY + y0, level * STORY + y1, z0, z1, kind, level, color, top))

    val boxes: List<Box> = buildList {
        val t = WALL_T
        val h = STORY
        val g = GARAGE_X
        for (level in 0..1) {
            // Outer walls. Downstairs: the front door, the garage door and a back door.
            if (level == 0) {
                wallAlongX(0, Kind.EXTERIOR, HZ0, HZ0 + t, HX0, HX1, h, -0.6..0.6, 3.6..8.4)
                wallAlongX(0, Kind.EXTERIOR, HZ1 - t, HZ1, HX0, HX1, h, -6.2..-5.0)
            } else {
                wallAlongX(1, Kind.EXTERIOR, HZ0, HZ0 + t, HX0, HX1, h)
                wallAlongX(1, Kind.EXTERIOR, HZ1 - t, HZ1, HX0, HX1, h)
            }
            wallAlongZ(level, Kind.EXTERIOR, HX0, HX0 + t, HZ0 + t, HZ1 - t, h)
            wallAlongZ(level, Kind.EXTERIOR, HX1 - t, HX1, HZ0 + t, HZ1 - t, h)
            // Hallway | garage (bonus room upstairs), with a door at the front end.
            wallAlongZ(level, Kind.WALL, g - t / 2, g + t / 2, HZ0 + t, HZ1 - t, h, -4.3..-3.1)
        }
        // Downstairs: living room (front left), kitchen (back left), hallway with the stairs.
        wallAlongZ(0, Kind.WALL, -1.1, -0.9, HZ0 + t, HZ1 - t, h, -3.6..-2.0, 2.4..3.8)
        wallAlongX(0, Kind.WALL, 0.9, 1.1, HX0 + t, -1.1, h, -8.4..-6.6)
        // Upstairs: two bedrooms off the landing, and a railing around the stairwell.
        wallAlongZ(1, Kind.WALL, -1.1, -0.9, HZ0 + t, HZ1 - t, h, -2.2..-1.0, 2.6..3.8)
        wallAlongX(1, Kind.WALL, 0.4, 0.6, HX0 + t, -1.1, h)
        wallAlongZ(1, Kind.RAIL, SX0 - 0.15, SX0, SZ0, SZ1, 1.0)
        wallAlongX(1, Kind.RAIL, SZ0 - 0.15, SZ0, SX0 - 0.15, SX1, 1.0)

        // The stairs: solid steps from the floor up.
        val run = (SZ1 - SZ0) / STEPS
        for (i in 0 until STEPS) {
            add(Box(SX0, SX1, 0.0, (i + 1) * STORY / STEPS, SZ0 + i * run, SZ0 + (i + 1) * run, Kind.STAIR, 0, 0xFF9C6B3F, 0xFFB98A5A))
        }

        // Living room: couch along the front wall, coffee table, TV against the kitchen wall.
        thing(0, -6.6, -3.4, 0.0, 0.45, -4.8, -3.9, 0xFFB5523B)
        thing(0, -6.6, -3.4, 0.45, 0.95, -4.8, -4.5, 0xFFA3462F)
        thing(0, -6.9, -6.6, 0.0, 0.7, -4.8, -3.9, 0xFFA3462F)
        thing(0, -3.4, -3.1, 0.0, 0.7, -4.8, -3.9, 0xFFA3462F)
        thing(0, -5.6, -4.4, 0.0, 0.4, -3.0, -2.2, 0xFF7A5230, 0xFF8D6138)
        thing(0, -6.0, -4.0, 0.0, 0.5, 0.35, 0.85, 0xFF4A3828)
        thing(0, -5.8, -4.2, 0.5, 1.4, 0.55, 0.7, 0xFF1C1C22, 0xFF2A2A33)
        // Kitchen: counters along the back wall (around the back door), fridge, table.
        thing(0, -8.8, -6.3, 0.0, 0.95, 4.2, 4.8, 0xFFE8E2D4, 0xFF6E6A66)
        thing(0, -4.9, -1.2, 0.0, 0.95, 4.2, 4.8, 0xFFE8E2D4, 0xFF6E6A66)
        thing(0, -8.8, -8.0, 0.0, 2.0, 2.4, 3.2, 0xFFDCE3E8)
        thing(0, -5.2, -3.6, 0.0, 0.75, 2.0, 3.0, 0xFFC28F5B, 0xFFD3A06A)
        // Garage: a blocky car, wheels and all, and a workbench.
        thing(0, 4.8, 7.2, 0.3, 1.1, -2.6, 2.4, 0xFFD7263D)
        thing(0, 5.0, 7.0, 1.1, 1.75, -1.2, 1.1, 0xFF9FD3F2, 0xFFC0392B)
        for ((wx, wz) in listOf(4.65 to -1.6, 7.0 to -1.6, 4.65 to 1.4, 7.0 to 1.4)) {
            thing(0, wx, wx + 0.35, 0.0, 0.6, wz - 0.35, wz + 0.35, 0xFF22222A)
        }
        thing(0, 8.1, 8.8, 0.0, 0.95, 0.5, 4.2, 0xFF8A6A4A, 0xFF9E7C58)

        // Front bedroom: bed and dresser.
        thing(1, -8.8, -6.9, 0.0, 0.5, -4.6, -2.4, 0xFFF2F2F2, 0xFF4F7CD1)
        thing(1, -8.8, -8.3, 0.5, 0.75, -4.6, -2.4, 0xFF6B4B2E)
        thing(1, -5.2, -3.6, 0.0, 1.1, -4.8, -4.3, 0xFF8B5E3C)
        // Back bedroom: bed and desk.
        thing(1, -8.8, -6.9, 0.0, 0.5, 2.6, 4.8, 0xFFF2F2F2, 0xFFE07AA8)
        thing(1, -8.8, -8.3, 0.5, 0.75, 2.6, 4.8, 0xFF6B4B2E)
        thing(1, -4.2, -2.6, 0.0, 0.75, 4.2, 4.8, 0xFFC9C9C9)
        // Bonus room over the garage: couch, ping-pong table, arcade cabinet.
        thing(1, 8.0, 8.8, 0.0, 0.45, -2.0, 2.0, 0xFF3F6C8F)
        thing(1, 8.5, 8.8, 0.45, 0.95, -2.0, 2.0, 0xFF335A78)
        thing(1, 4.4, 6.8, 0.0, 0.8, -0.5, 3.0, 0xFF2F7D4F, 0xFF1F8A4C)
        thing(1, 3.3, 4.1, 0.0, 1.9, 4.0, 4.8, 0xFF5A2D82, 0xFF2D1A44)

        // The yard: fence, a mailbox by the path, and four blocky trees.
        wallAlongX(0, Kind.FENCE, YARD_Z0 - 0.15, YARD_Z0, -YARD_X - 0.15, YARD_X + 0.15, 1.0)
        wallAlongX(0, Kind.FENCE, YARD_Z1, YARD_Z1 + 0.15, -YARD_X - 0.15, YARD_X + 0.15, 1.0)
        wallAlongZ(0, Kind.FENCE, -YARD_X - 0.15, -YARD_X, YARD_Z0, YARD_Z1, 1.0)
        wallAlongZ(0, Kind.FENCE, YARD_X, YARD_X + 0.15, YARD_Z0, YARD_Z1, 1.0)
        thing(0, -1.6, -1.45, 0.0, 1.0, -11.08, -10.92, 0xFF6B4B2E)
        thing(0, -1.75, -1.3, 1.0, 1.35, -11.3, -10.75, 0xFF2D5DA8, 0xFF3A6FC4)
        for ((tx, tz) in listOf(-12.5 to -9.0, 12.5 to -9.5, -12.0 to 8.0, 11.5 to 8.5)) {
            thing(0, tx - 0.3, tx + 0.3, 0.0, 2.2, tz - 0.3, tz + 0.3, 0xFF6B4B2E, kind = Kind.TRUNK)
            thing(0, tx - 1.4, tx + 1.4, 1.9, 4.5, tz - 1.4, tz + 1.4, 0xFF3E8E3A, 0xFF4FA84A, kind = Kind.LEAVES)
        }
    }

    val solids: List<Box> = boxes.filter { it.solid }

    val floors: List<Floor> = listOf(
        // Downstairs.
        Floor(HX0, -1.0, HZ0, 1.0, 0, 0xFFC9B48F),
        Floor(HX0, -1.0, 1.0, HZ1, 0, 0xFFE9E4DA, checker = true),
        Floor(-1.0, GARAGE_X, HZ0, HZ1, 0, 0xFFB7834E),
        Floor(GARAGE_X, HX1, HZ0, HZ1, 0, 0xFF9A9A98),
        // Upstairs, around the stairwell.
        Floor(HX0, -1.0, HZ0, 0.5, 1, 0xFF7E9FD6),
        Floor(HX0, -1.0, 0.5, HZ1, 1, 0xFFD8A7C4),
        Floor(-1.0, SX0, HZ0, HZ1, 1, 0xFFB7834E),
        Floor(SX0, GARAGE_X, HZ0, SZ0, 1, 0xFFB7834E),
        Floor(SX0, GARAGE_X, SZ1, HZ1, 1, 0xFFB7834E),
        Floor(GARAGE_X, HX1, HZ0, HZ1, 1, 0xFF8DBF7A),
    )

    /** Outdoor ground markings, drawn on the lawn: the driveway and the front path. */
    val paths: List<Floor> = listOf(
        Floor(3.6, 8.4, YARD_Z0, HZ0, 0, 0xFF8E8E8C),
        Floor(-0.6, 0.6, YARD_Z0, HZ0, 0, 0xFFCDBFA6),
    )
}
