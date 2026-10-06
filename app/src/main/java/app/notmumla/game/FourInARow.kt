package app.notmumla.game

/** A disc colour; [RED] always moves first. */
enum class Disc { RED, YELLOW;
    val other: Disc get() = if (this == RED) YELLOW else RED
}

/**
 * Immutable 7×6 Four-in-a-Row board. [cells] is row-major with row 0 at the top. Each client keeps
 * its own copy and replays the peer's moves on it, so an illegal move from the peer is detectable
 * locally rather than trusted.
 */
class FourBoard private constructor(private val cells: Array<Disc?>, val plies: Int) {

    constructor() : this(arrayOfNulls(COLS * ROWS), 0)

    operator fun get(row: Int, col: Int): Disc? = cells[row * COLS + col]

    /** Whose turn it is: plies alternate starting with red. */
    val toMove: Disc get() = if (plies % 2 == 0) Disc.RED else Disc.YELLOW

    fun canDrop(col: Int): Boolean = col in 0 until COLS && this[0, col] == null && winner == null

    /** The board after [toMove] drops into [col], or null if that move is illegal. */
    fun drop(col: Int): FourBoard? {
        if (!canDrop(col)) return null
        val row = (ROWS - 1 downTo 0).first { this[it, col] == null }
        val next = cells.copyOf()
        next[row * COLS + col] = toMove
        return FourBoard(next, plies + 1)
    }

    /** The four winning cells (row, col), or null while nobody has four in a row. */
    val winningLine: List<Pair<Int, Int>>? by lazy { findLine() }
    val winner: Disc? get() = winningLine?.first()?.let { (r, c) -> this[r, c] }
    val isDraw: Boolean get() = winningLine == null && plies == COLS * ROWS
    val isOver: Boolean get() = winningLine != null || isDraw

    private fun findLine(): List<Pair<Int, Int>>? {
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val d = this[r, c] ?: continue
            for ((dr, dc) in dirs) {
                val line = (0 until 4).map { r + it * dr to c + it * dc }
                if (line.all { (lr, lc) -> lr in 0 until ROWS && lc in 0 until COLS && this[lr, lc] == d }) {
                    return line
                }
            }
        }
        return null
    }

    companion object {
        const val COLS = 7
        const val ROWS = 6
    }
}
