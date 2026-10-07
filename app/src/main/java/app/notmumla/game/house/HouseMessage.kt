package app.notmumla.game.house

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Wire messages for the block house (`PluginDataTransmission` under [DATA_ID]): short ASCII, one
 * message to every player at once (the server charges its rate limit per message, not receiver).
 *
 * Anyone on the server can send us these, so [decode] is strict: an exact shape per verb, bounded
 * integers, and anything else is null (dropped), never an exception.
 */
sealed interface HouseMessage {
    data object Hello : HouseMessage
    data object Bye : HouseMessage

    /**
     * The sender's figure: position in cm, [level] 0/1, heading, velocity in cm/s. [swings] counts
     * slaps swung (when it goes up, play one); [down] = lying slapped; [shirt] picks a colour.
     */
    data class State(
        val seq: Int,
        val x: Double,
        val z: Double,
        val level: Int,
        val h: Double,
        val vx: Double,
        val vz: Double,
        val emote: Emote,
        val down: Boolean,
        val swings: Int,
        val shirt: Int,
    ) : HouseMessage

    /** "My swing number [swing] hit [victim]": the victim decides whether it really did. */
    data class Slap(val victim: Int, val swing: Int) : HouseMessage

    /** "[slapper]'s swing [swing] knocked me over, flying toward [dir]" — from the victim. */
    data class Ow(val slapper: Int, val swing: Int, val dir: Double) : HouseMessage

    companion object {
        const val DATA_ID = "notmumla/house/1"
        const val SHIRTS = 8
        private const val MAX_LEN = 96
        private const val MAX_COUNT = 999_999_999
        private const val POS = 1_700               // cm: the yard is ±16 m
        private const val ANGLE = 3_600             // tenths of a degree
        private val SPEED = (HouseWorld.WALK_SPEED * 100 * 1.2).roundToInt()

        fun encode(m: HouseMessage): ByteArray = when (m) {
            Hello -> "hi"
            Bye -> "bye"
            is Slap -> "slap ${m.victim} ${m.swing}"
            is Ow -> "ow ${m.slapper} ${m.swing} ${angle(m.dir)}"
            is State -> buildString {
                append("s ").append(m.seq).append(' ')
                append((m.x * 100).roundToInt().coerceIn(-POS, POS)).append(' ')
                append((m.z * 100).roundToInt().coerceIn(-POS, POS)).append(' ')
                append(m.level.coerceIn(0, 1)).append(' ')
                append(angle(m.h)).append(' ')
                append((m.vx * 100).roundToInt().coerceIn(-SPEED, SPEED)).append(' ')
                append((m.vz * 100).roundToInt().coerceIn(-SPEED, SPEED)).append(' ')
                append(m.emote.ordinal).append(if (m.down) " 1 " else " 0 ")
                append(m.swings).append(' ').append(m.shirt.coerceIn(0, SHIRTS - 1))
            }
        }.toByteArray(Charsets.US_ASCII)

        private fun angle(a: Double): Int {
            var r = a % (2 * PI)
            if (r < 0) r += 2 * PI
            return (r * 1800 / PI).roundToInt() % ANGLE
        }

        fun decode(data: ByteArray): HouseMessage? {
            if (data.isEmpty() || data.size > MAX_LEN) return null
            if (data.any { it < 0x20 || it > 0x7E }) return null
            val parts = String(data, Charsets.US_ASCII).split(' ')
            return when (parts[0]) {
                "hi" -> Hello.takeIf { parts.size == 1 }
                "bye" -> Bye.takeIf { parts.size == 1 }
                "slap" -> {
                    if (parts.size != 3) return null
                    Slap(parts[1].int(0, MAX_COUNT) ?: return null, parts[2].int(1, MAX_COUNT) ?: return null)
                }
                "ow" -> {
                    if (parts.size != 4) return null
                    Ow(
                        parts[1].int(0, MAX_COUNT) ?: return null,
                        parts[2].int(1, MAX_COUNT) ?: return null,
                        (parts[3].int(0, ANGLE - 1) ?: return null) * PI / 1800,
                    )
                }
                "s" -> {
                    if (parts.size != 12) return null
                    val bounds = arrayOf(
                        0..MAX_COUNT, -POS..POS, -POS..POS, 0..1, 0 until ANGLE, -SPEED..SPEED, -SPEED..SPEED,
                        0 until Emote.entries.size, 0..1, 0..MAX_COUNT, 0 until SHIRTS,
                    )
                    val n = IntArray(11)
                    for (i in 0 until 11) n[i] = parts[i + 1].int(bounds[i].first, bounds[i].last) ?: return null
                    State(
                        seq = n[0], x = n[1] / 100.0, z = n[2] / 100.0, level = n[3], h = n[4] * PI / 1800,
                        vx = n[5] / 100.0, vz = n[6] / 100.0, emote = Emote.entries[n[7]], down = n[8] == 1,
                        swings = n[9], shirt = n[10],
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
