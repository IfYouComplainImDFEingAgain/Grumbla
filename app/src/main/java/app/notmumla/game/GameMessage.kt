package app.notmumla.game

/**
 * Wire messages for the Four-in-a-Row easter egg, carried as `PluginDataTransmission` data under
 * [DATA_ID]. The body is short ASCII (`move 1a2b3c4d 5 3`), well under the server's 1000-byte cap.
 *
 * Anyone on the server can send us plugin data, so [decode] is strict: one exact shape per verb,
 * bounded numbers, and anything else is null (dropped), never an exception.
 */
sealed interface GameMessage {
    val gameId: String

    /** Start a game; the sender plays red and moves first. */
    data class Invite(override val gameId: String) : GameMessage
    data class Accept(override val gameId: String) : GameMessage
    data class Decline(override val gameId: String) : GameMessage
    /** The [ply]th move (0-based) of the game drops into [col]; [ply] catches a desync. */
    data class Move(override val gameId: String, val ply: Int, val col: Int) : GameMessage
    /** The sender left the game (or saw something it could not accept). */
    data class Quit(override val gameId: String) : GameMessage

    companion object {
        /** Versioned: an incompatible change bumps it, and older clients simply ignore the new id. */
        const val DATA_ID = "notmumla/four/1"

        private val GAME_ID = Regex("[0-9a-f]{8}")
        private const val MAX_LEN = 64

        fun encode(m: GameMessage): ByteArray = when (m) {
            is Invite -> "invite ${m.gameId}"
            is Accept -> "accept ${m.gameId}"
            is Decline -> "decline ${m.gameId}"
            is Move -> "move ${m.gameId} ${m.ply} ${m.col}"
            is Quit -> "quit ${m.gameId}"
        }.toByteArray(Charsets.US_ASCII)

        fun decode(data: ByteArray): GameMessage? {
            if (data.isEmpty() || data.size > MAX_LEN) return null
            if (data.any { it < 0x20 || it > 0x7E }) return null
            val parts = String(data, Charsets.US_ASCII).split(' ')
            val id = parts.getOrNull(1)?.takeIf { GAME_ID.matches(it) } ?: return null
            return when (parts[0]) {
                "invite" -> Invite(id).takeIf { parts.size == 2 }
                "accept" -> Accept(id).takeIf { parts.size == 2 }
                "decline" -> Decline(id).takeIf { parts.size == 2 }
                "quit" -> Quit(id).takeIf { parts.size == 2 }
                "move" -> {
                    if (parts.size != 4) return null
                    val ply = parts[2].smallInt(FourBoard.COLS * FourBoard.ROWS) ?: return null
                    val col = parts[3].smallInt(FourBoard.COLS) ?: return null
                    Move(id, ply, col)
                }
                else -> null
            }
        }

        /** A plain decimal in 0 until [bound] (no signs, no leading zeros beyond "0"). */
        private fun String.smallInt(bound: Int): Int? {
            if (isEmpty() || length > 2 || !all { it in '0'..'9' } || (length > 1 && this[0] == '0')) return null
            return toInt().takeIf { it < bound }
        }
    }
}
