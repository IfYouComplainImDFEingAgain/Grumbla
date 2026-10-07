package app.notmumla.game.flight

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Wire messages for the dogfight (`PluginDataTransmission` under [DATA_ID]). Short ASCII, one
 * message to every player at once: the server charges its rate limit per message, not per receiver.
 *
 * Anyone on the server can send us these, so [decode] is strict: an exact shape per verb, bounded
 * integers, and anything else is null (dropped), never an exception.
 */
sealed interface FlightMessage {
    /** "I'm in the dogfight": sent to the channel on opening it, so players there send to us. */
    data object Hello : FlightMessage
    /** "I left the dogfight." */
    data object Bye : FlightMessage

    /**
     * The sender's ship. [shots] counts bolts fired; while [FIRING] is set in [fx], receivers fire
     * bolts for them at the fixed rate, so lasers cost no messages of their own.
     */
    data class State(
        val seq: Int,
        val alive: Boolean,
        val pose: Pose3,
        val fx: Int,
        val shots: Int,
        val kills: Int,
        val deaths: Int,
    ) : FlightMessage

    /**
     * "Bolt [shot] from [shooter] finished me" — the victim decides. [shooter] equal to the sender
     * means it crashed with nobody to credit (then [shot] is 0).
     */
    data class Hit(val shooter: Int, val shot: Int) : FlightMessage

    companion object {
        // v2: fx gained ROLL_LEFT; v3: BRAKING. Older clients would reject the new bits, so each
        // change gets a new id and old and new clients simply don't hear each other.
        const val DATA_ID = "notmumla/flight/3"
        const val FIRING = 1
        const val BOOSTING = 2
        const val ROLLING = 4
        /** With [ROLLING]: the roll goes left (counter-clockwise from behind). */
        const val ROLL_LEFT = 8
        const val BRAKING = 16
        private const val FX_MAX = 31
        private const val MAX_LEN = 96
        private const val MAX_COUNT = 999_999_999
        private val POS = (FlightWorld.LIMIT * 10).roundToInt()          // decimetres
        private val ALT = (FlightWorld.CEILING * 10).roundToInt()
        private const val ANGLE = 3_600                                  // tenths of a degree
        private val PITCH = (FlightWorld.MAX_PITCH * 1800 / PI).roundToInt()
        private val SPEED = (FlightWorld.BOOST_SPEED * 10).roundToInt()
        private val YAW_RATE = (FlightWorld.MAX_YAW * 1800 / PI).roundToInt()
        private val PITCH_RATE = (FlightWorld.MAX_PITCH_RATE * 1800 / PI).roundToInt()

        private fun deci(a: Double) = (a * 1800 / PI).roundToInt()

        fun encode(m: FlightMessage): ByteArray = when (m) {
            Hello -> "hi"
            Bye -> "bye"
            is Hit -> "hit ${m.shooter} ${m.shot}"
            is State -> buildString {
                val p = m.pose
                append("s ").append(m.seq).append(if (m.alive) " 1 " else " 0 ")
                append((p.x * 10).roundToInt().coerceIn(-POS, POS)).append(' ')
                append((p.y * 10).roundToInt().coerceIn(0, ALT)).append(' ')
                append((p.z * 10).roundToInt().coerceIn(-POS, POS)).append(' ')
                append(deci(FlightWorld.wrap(p.h)) % ANGLE).append(' ')
                append(deci(p.p).coerceIn(-PITCH, PITCH)).append(' ')
                append((p.v * 10).roundToInt().coerceIn(0, SPEED)).append(' ')
                append(deci(p.w).coerceIn(-YAW_RATE, YAW_RATE)).append(' ')
                append(deci(p.q).coerceIn(-PITCH_RATE, PITCH_RATE)).append(' ')
                append(m.fx and FX_MAX).append(' ')
                append(m.shots).append(' ').append(m.kills).append(' ').append(m.deaths)
            }
        }.toByteArray(Charsets.US_ASCII)

        fun decode(data: ByteArray): FlightMessage? {
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
                    val bounds = arrayOf(
                        0..MAX_COUNT, 0..1, -POS..POS, 0..ALT, -POS..POS, 0 until ANGLE, -PITCH..PITCH,
                        0..SPEED, -YAW_RATE..YAW_RATE, -PITCH_RATE..PITCH_RATE, 0..FX_MAX,
                        0..MAX_COUNT, 0..MAX_COUNT, 0..MAX_COUNT,
                    )
                    if (parts.size != bounds.size + 1) return null
                    val n = IntArray(bounds.size)
                    for (i in bounds.indices) n[i] = parts[i + 1].int(bounds[i].first, bounds[i].last) ?: return null
                    val r = PI / 1800
                    State(
                        seq = n[0], alive = n[1] == 1,
                        pose = Pose3(n[2] / 10.0, n[3] / 10.0, n[4] / 10.0, n[5] * r, n[6] * r,
                            n[7] / 10.0, n[8] * r, n[9] * r),
                        fx = n[10], shots = n[11], kills = n[12], deaths = n[13],
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
