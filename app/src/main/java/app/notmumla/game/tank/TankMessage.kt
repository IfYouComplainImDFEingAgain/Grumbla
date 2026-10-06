package app.notmumla.game.tank

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Wire messages for the tank arena (`PluginDataTransmission` under [DATA_ID]). Short ASCII, one
 * message to every player at once: the server charges its rate limit per message, not per receiver.
 *
 * Anyone on the server can send us these, so [decode] is strict: an exact shape per verb, bounded
 * integers, and anything else is null (dropped), never an exception.
 */
sealed interface TankMessage {
    /** "I'm in the arena": sent on entering to the channel, so players there start sending to us. */
    data object Hello : TankMessage
    /** "I left the arena." */
    data object Bye : TankMessage

    /**
     * The sender's tank. [shots] counts shells fired: when it goes up, a shell left the muzzle at
     * this pose. Scores are the sender's own claim (each client only tallies its own kills).
     */
    data class State(
        val seq: Int,
        val alive: Boolean,
        val pose: Pose,
        val shots: Int,
        val kills: Int,
        val deaths: Int,
    ) : TankMessage

    /** "Shell [shot] fired by [shooter] destroyed me" — the victim decides it was hit. */
    data class Hit(val shooter: Int, val shot: Int) : TankMessage

    companion object {
        const val DATA_ID = "notmumla/tank/1"
        private const val MAX_LEN = 96
        private const val MAX_COUNT = 999_999_999
        private const val POS = 1_000                 // decimetres: ±100 m
        private const val ANGLE = 3_600               // tenths of a degree
        private val SPEED = (TankWorld.MAX_SPEED * 10).roundToInt()
        private val TURN = (TankWorld.MAX_TURN * 1800 / PI).roundToInt()

        fun encode(m: TankMessage): ByteArray = when (m) {
            Hello -> "hi"
            Bye -> "bye"
            is Hit -> "hit ${m.shooter} ${m.shot}"
            is State -> buildString {
                val p = m.pose
                append("s ").append(m.seq).append(if (m.alive) " 1 " else " 0 ")
                append((p.x * 10).roundToInt().coerceIn(-POS, POS)).append(' ')
                append((p.z * 10).roundToInt().coerceIn(-POS, POS)).append(' ')
                append((TankWorld.wrap(p.h) * 1800 / PI).roundToInt() % ANGLE).append(' ')
                append((p.v * 10).roundToInt().coerceIn(-SPEED, SPEED)).append(' ')
                append((p.w * 1800 / PI).roundToInt().coerceIn(-TURN, TURN)).append(' ')
                append(m.shots).append(' ').append(m.kills).append(' ').append(m.deaths)
            }
        }.toByteArray(Charsets.US_ASCII)

        fun decode(data: ByteArray): TankMessage? {
            if (data.isEmpty() || data.size > MAX_LEN) return null
            if (data.any { it < 0x20 || it > 0x7E }) return null
            val parts = String(data, Charsets.US_ASCII).split(' ')
            return when (parts[0]) {
                "hi" -> Hello.takeIf { parts.size == 1 }
                "bye" -> Bye.takeIf { parts.size == 1 }
                "hit" -> {
                    if (parts.size != 3) return null
                    Hit(parts[1].int(0, MAX_COUNT) ?: return null, parts[2].int(0, MAX_COUNT) ?: return null)
                }
                "s" -> {
                    if (parts.size != 11) return null
                    val n = IntArray(10)
                    val bounds = arrayOf(
                        0..MAX_COUNT, 0..1, -POS..POS, -POS..POS, 0 until ANGLE,
                        -SPEED..SPEED, -TURN..TURN, 0..MAX_COUNT, 0..MAX_COUNT, 0..MAX_COUNT,
                    )
                    for (i in 0 until 10) n[i] = parts[i + 1].int(bounds[i].first, bounds[i].last) ?: return null
                    State(
                        seq = n[0], alive = n[1] == 1,
                        pose = Pose(n[2] / 10.0, n[3] / 10.0, n[4] * PI / 1800, n[5] / 10.0, n[6] * PI / 1800),
                        shots = n[7], kills = n[8], deaths = n[9],
                    )
                }
                else -> null
            }
        }

        /** A plain decimal in [lo]..[hi]: optional leading '-', no '+', no leading zeros. */
        private fun String.int(lo: Int, hi: Int): Int? {
            val digits = removePrefix("-")
            if (digits.isEmpty() || digits.length > 9 || !digits.all { it in '0'..'9' }) return null
            if (digits.length > 1 && digits[0] == '0') return null
            if (startsWith("-") && digits == "0") return null
            return toInt().takeIf { it in lo..hi }
        }
    }
}
