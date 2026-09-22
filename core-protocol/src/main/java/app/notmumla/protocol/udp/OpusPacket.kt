package app.notmumla.protocol.udp

/**
 * Reads an Opus packet's duration from its TOC byte (RFC 6716 §3.1) without decoding it. Mumble's
 * `frame_number` advances in 10 ms units, so a packet's duration tells us how far the next packet's
 * number should move — needed to tell real loss from peers sending 20/40/60 ms packets.
 */
object OpusPacket {
    /** Duration in 10 ms units (at least 1), or 1 if the packet is empty/malformed. */
    fun tenMsUnits(packet: ByteArray): Int {
        if (packet.isEmpty()) return 1
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        // Frame duration in units of 2.5 ms.
        val frameQuarters = when {
            config < 12 -> intArrayOf(4, 8, 16, 24)[config and 3]   // SILK: 10/20/40/60 ms
            config < 16 -> intArrayOf(4, 8)[config and 1]           // Hybrid: 10/20 ms
            else -> intArrayOf(1, 2, 4, 8)[config and 3]            // CELT: 2.5/5/10/20 ms
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else return 1
        }
        return maxOf(1, frameQuarters * frames / 4)
    }
}
