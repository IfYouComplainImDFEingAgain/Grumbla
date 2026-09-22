package app.notmumla.protocol

import app.notmumla.protocol.udp.OpusPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusPacketTest {
    private fun toc(config: Int, code: Int) = ((config shl 3) or code).toByte()

    @Test fun celtFrameSizes() {
        assertEquals(1, OpusPacket.tenMsUnits(byteArrayOf(toc(30, 0))))  // 10 ms
        assertEquals(2, OpusPacket.tenMsUnits(byteArrayOf(toc(31, 0))))  // 20 ms
        assertEquals(1, OpusPacket.tenMsUnits(byteArrayOf(toc(28, 0))))  // 2.5 ms → at least 1
    }

    @Test fun multiFramePackets() {
        assertEquals(4, OpusPacket.tenMsUnits(byteArrayOf(toc(31, 1))))              // 2×20 ms
        assertEquals(6, OpusPacket.tenMsUnits(byteArrayOf(toc(31, 3), 3)))           // 3×20 ms
        assertEquals(6, OpusPacket.tenMsUnits(byteArrayOf(toc(3, 0))))               // SILK 60 ms
        assertEquals(2, OpusPacket.tenMsUnits(byteArrayOf(toc(13, 0))))              // hybrid 20 ms
    }

    @Test fun malformed() {
        assertEquals(1, OpusPacket.tenMsUnits(ByteArray(0)))
        assertEquals(1, OpusPacket.tenMsUnits(byteArrayOf(toc(31, 3))))
    }
}
