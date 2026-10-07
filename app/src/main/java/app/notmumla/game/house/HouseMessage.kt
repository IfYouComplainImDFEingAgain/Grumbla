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
     * The sender's figure: feet at (x, y, z) in cm, heading, velocity in cm/s ([vy] up, while
     * jumping or falling). [swings] counts swings of hand or bat (when it goes up, play one);
     * [down] = lying knocked over; [shirt] picks a colour; [held] is what's in its hands.
     */
    data class State(
        val seq: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val h: Double,
        val vx: Double,
        val vz: Double,
        val vy: Double,
        val emote: Emote,
        val down: Boolean,
        val swings: Int,
        val shirt: Int,
        val held: Weapon,
    ) : HouseMessage

    /** "My swing number [swing], with [weapon], hit [victim]": the victim decides whether it really did. */
    data class Slap(val victim: Int, val swing: Int, val weapon: Weapon) : HouseMessage

    /**
     * "[slapper]'s [weapon] knocked me over, flying toward [dir]" — from the victim. [swing] is the
     * swing number, or for a plant the throw number.
     */
    data class Ow(val slapper: Int, val swing: Int, val dir: Double, val weapon: Weapon) : HouseMessage

    /** "I threw plant number [n] from (x, y, z), toward [h], rising at [vy]": everyone flies it the same. */
    data class Toss(val n: Int, val x: Double, val y: Double, val z: Double, val h: Double, val vy: Double) : HouseMessage

    /** "I picked up what was at [HouseWorld.spots] index [spot]." */
    data class Got(val spot: Int) : HouseMessage

    companion object {
        const val DATA_ID = "notmumla/house/2"
        const val SHIRTS = 8
        private const val MAX_LEN = 96
        private const val MAX_COUNT = 999_999_999
        private const val POS = 1_700               // cm: the yard is ±16 m
        private const val HIGH = 700                // cm: a rail top upstairs, plus a jump
        private const val ANGLE = 3_600             // tenths of a degree
        private const val RISE = 1_500              // cm/s up or down
        private val SPEED = (HouseWorld.WALK_SPEED * 100 * 1.2).roundToInt()

        fun encode(m: HouseMessage): ByteArray = when (m) {
            Hello -> "hi"
            Bye -> "bye"
            is Slap -> "slap ${m.victim} ${m.swing} ${m.weapon.ordinal}"
            is Ow -> "ow ${m.slapper} ${m.swing} ${angle(m.dir)} ${m.weapon.ordinal}"
            is Got -> "got ${m.spot}"
            is Toss -> "toss ${m.n} ${cm(m.x, -POS, POS)} ${cm(m.y, 0, HIGH)} ${cm(m.z, -POS, POS)} ${angle(m.h)} ${cm(m.vy, -RISE, RISE)}"
            is State -> buildString {
                append("s ").append(m.seq).append(' ')
                append(cm(m.x, -POS, POS)).append(' ')
                append(cm(m.y, 0, HIGH)).append(' ')
                append(cm(m.z, -POS, POS)).append(' ')
                append(angle(m.h)).append(' ')
                append(cm(m.vx, -SPEED, SPEED)).append(' ')
                append(cm(m.vz, -SPEED, SPEED)).append(' ')
                append(cm(m.vy, -RISE, RISE)).append(' ')
                append(m.emote.ordinal).append(if (m.down) " 1 " else " 0 ")
                append(m.swings).append(' ').append(m.shirt.coerceIn(0, SHIRTS - 1)).append(' ')
                append(m.held.ordinal)
            }
        }.toByteArray(Charsets.US_ASCII)

        private fun cm(v: Double, lo: Int, hi: Int) = (v * 100).roundToInt().coerceIn(lo, hi)

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
                "got" -> {
                    if (parts.size != 2) return null
                    Got(parts[1].int(0, HouseWorld.spots.size - 1) ?: return null)
                }
                "slap" -> {
                    if (parts.size != 4) return null
                    Slap(
                        parts[1].int(0, MAX_COUNT) ?: return null,
                        parts[2].int(1, MAX_COUNT) ?: return null,
                        weapon(parts[3]) ?: return null,
                    )
                }
                "ow" -> {
                    if (parts.size != 5) return null
                    Ow(
                        parts[1].int(0, MAX_COUNT) ?: return null,
                        parts[2].int(1, MAX_COUNT) ?: return null,
                        (parts[3].int(0, ANGLE - 1) ?: return null) * PI / 1800,
                        weapon(parts[4]) ?: return null,
                    )
                }
                "toss" -> {
                    if (parts.size != 7) return null
                    val bounds = arrayOf(1..MAX_COUNT, -POS..POS, 0..HIGH, -POS..POS, 0 until ANGLE, -RISE..RISE)
                    val n = IntArray(6)
                    for (i in 0 until 6) n[i] = parts[i + 1].int(bounds[i].first, bounds[i].last) ?: return null
                    Toss(n[0], n[1] / 100.0, n[2] / 100.0, n[3] / 100.0, n[4] * PI / 1800, n[5] / 100.0)
                }
                "s" -> {
                    if (parts.size != 14) return null
                    val bounds = arrayOf(
                        0..MAX_COUNT, -POS..POS, 0..HIGH, -POS..POS, 0 until ANGLE, -SPEED..SPEED, -SPEED..SPEED,
                        -RISE..RISE, 0 until Emote.entries.size, 0..1, 0..MAX_COUNT, 0 until SHIRTS, 0 until Weapon.entries.size,
                    )
                    val n = IntArray(13)
                    for (i in 0 until 13) n[i] = parts[i + 1].int(bounds[i].first, bounds[i].last) ?: return null
                    State(
                        seq = n[0], x = n[1] / 100.0, y = n[2] / 100.0, z = n[3] / 100.0, h = n[4] * PI / 1800,
                        vx = n[5] / 100.0, vz = n[6] / 100.0, vy = n[7] / 100.0, emote = Emote.entries[n[8]],
                        down = n[9] == 1, swings = n[10], shirt = n[11], held = Weapon.entries[n[12]],
                    )
                }
                else -> null
            }
        }

        private fun weapon(s: String) = s.int(0, Weapon.entries.size - 1)?.let { Weapon.entries[it] }

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
