package app.notmumla.protocol.udp

import java.io.ByteArrayOutputStream

/**
 * Mumble's custom variable-length integer encoding (reference src/PacketDataStream.h), used inside
 * the legacy voice-packet format for session id, sequence number and Opus length. Only the
 * non-negative encodings are produced (our values are unsigned counters/lengths); the decoder
 * handles the full range a server may send.
 */
object MumbleVarint {

    fun encode(out: ByteArrayOutputStream, value: Long) {
        val i = value
        when {
            i < 0x80 -> out.write(i.toInt())
            i < 0x4000 -> {
                out.write(((i shr 8) or 0x80).toInt())
                out.write((i and 0xFF).toInt())
            }
            i < 0x200000 -> {
                out.write(((i shr 16) or 0xC0).toInt())
                out.write(((i shr 8) and 0xFF).toInt())
                out.write((i and 0xFF).toInt())
            }
            i < 0x10000000 -> {
                out.write(((i shr 24) or 0xE0).toInt())
                out.write(((i shr 16) and 0xFF).toInt())
                out.write(((i shr 8) and 0xFF).toInt())
                out.write((i and 0xFF).toInt())
            }
            i < 0x100000000L -> {
                out.write(0xF0)
                out.write(((i shr 24) and 0xFF).toInt())
                out.write(((i shr 16) and 0xFF).toInt())
                out.write(((i shr 8) and 0xFF).toInt())
                out.write((i and 0xFF).toInt())
            }
            else -> {
                out.write(0xF4)
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    out.write(((i shr shift) and 0xFF).toInt())
                }
            }
        }
    }

    /** Cursor-based reader over a byte array. */
    class Reader(private val data: ByteArray, var offset: Int = 0) {
        val remaining: Int get() = data.size - offset
        private fun next(): Long = (data[offset++].toLong() and 0xFF)

        fun readVarint(): Long {
            val v = next()
            return when {
                v and 0x80 == 0L -> v and 0x7F
                v and 0xC0 == 0x80L -> (v and 0x3F shl 8) or next()
                v and 0xF0 == 0xF0L -> when (v and 0xFC) {
                    0xF0L -> (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
                    0xF4L -> (next() shl 56) or (next() shl 48) or (next() shl 40) or (next() shl 32) or
                        (next() shl 24) or (next() shl 16) or (next() shl 8) or next()
                    0xF8L -> readVarint().inv()
                    0xFCL -> (v and 0x03).inv()
                    else -> 0L
                }
                v and 0xF0 == 0xE0L -> (v and 0x0F shl 24) or (next() shl 16) or (next() shl 8) or next()
                v and 0xE0 == 0xC0L -> (v and 0x1F shl 16) or (next() shl 8) or next()
                else -> 0L
            }
        }

        fun readBytes(len: Int): ByteArray {
            val n = len.coerceAtMost(remaining)
            val slice = data.copyOfRange(offset, offset + n)
            offset += n
            return slice
        }
    }
}
