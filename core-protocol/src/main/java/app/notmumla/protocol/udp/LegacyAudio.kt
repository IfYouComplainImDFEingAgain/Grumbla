package app.notmumla.protocol.udp

import java.io.ByteArrayOutputStream

/**
 * The Mumble **legacy** voice-packet format (reference docs/dev/network-protocol/voice_data.md),
 * Opus variant — used to speak with servers older than 1.5.0 (which predate the protobuf audio
 * format). Carried as the raw body of an UDPTunnel control message, exactly like the protobuf path.
 *
 * Header byte: 3-bit codec type (Opus = 4) shifted into the top bits, OR the 5-bit target. The Opus
 * frame is prefixed with a Mumble varint whose 14th bit (0x2000) is the end-of-transmission flag.
 */
object LegacyAudio {

    const val TYPE_OPUS = 4
    private const val TERMINATOR_BIT = 0x2000L
    private const val LENGTH_MASK = 0x1FFFL

    /** Build an outgoing client→server Opus packet (no session id; the server infers it). */
    fun encodeOutgoing(sequence: Long, opus: ByteArray, terminator: Boolean, target: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(opus.size + 8)
        out.write((TYPE_OPUS shl 5) or (target and 0x1F))
        MumbleVarint.encode(out, sequence)
        MumbleVarint.encode(out, opus.size.toLong() or (if (terminator) TERMINATOR_BIT else 0L))
        out.write(opus)
        return out.toByteArray()
    }

    data class Incoming(val session: Int, val sequence: Long, val opus: ByteArray, val terminator: Boolean)

    /** True if [packet] looks like a legacy Opus voice packet (top 3 header bits == Opus). */
    fun isLegacyOpus(packet: ByteArray): Boolean =
        packet.isNotEmpty() && ((packet[0].toInt() and 0xFF) shr 5) == TYPE_OPUS

    /** Parse an incoming server→client legacy Opus packet, or null if it is not one. */
    fun decodeIncoming(packet: ByteArray): Incoming? {
        if (!isLegacyOpus(packet)) return null
        return runCatching {
            val r = MumbleVarint.Reader(packet, offset = 1)
            val session = r.readVarint()
            val sequence = r.readVarint()
            val header = r.readVarint()
            val terminator = (header and TERMINATOR_BIT) != 0L
            val opus = r.readBytes((header and LENGTH_MASK).toInt())
            Incoming(session.toInt(), sequence, opus, terminator)
        }.getOrNull()
    }
}
