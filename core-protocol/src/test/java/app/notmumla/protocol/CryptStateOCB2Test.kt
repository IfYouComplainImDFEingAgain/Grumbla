package app.notmumla.protocol

import app.notmumla.protocol.udp.CryptStateOCB2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for the OCB-AES128 UDP crypto. These validate the port against itself (roundtrip, IV
 * bookkeeping, tamper/replay rejection); byte-exact interop with a real server is covered by
 * [UdpVoiceIntegrationTest].
 */
class CryptStateOCB2Test {

    /** A matched sender/receiver pair: sender's encrypt IV == receiver's decrypt IV, and vice versa. */
    private fun pair(seed: Int): Pair<CryptStateOCB2, CryptStateOCB2> {
        val rnd = Random(seed)
        val key = rnd.nextBytes(16)
        val ivA = rnd.nextBytes(16)
        val ivB = rnd.nextBytes(16)
        val sender = CryptStateOCB2().apply { assertTrue(setKey(key, ivA, ivB)) }
        val receiver = CryptStateOCB2().apply { assertTrue(setKey(key, ivB, ivA)) }
        return sender to receiver
    }

    @Test
    fun roundtripAcrossLengths() {
        val (sender, receiver) = pair(seed = 1)
        // Cover sub-block, exact-block-multiple, and multi-block payloads (Opus frames are small).
        for (len in intArrayOf(1, 2, 15, 16, 17, 31, 32, 33, 64, 100, 127, 200)) {
            val plain = Random(len).nextBytes(len)
            val packet = sender.encrypt(plain)
            assertEquals("4-byte header + body", plain.size + 4, packet.size)
            val decoded = receiver.decrypt(packet)
            assertNotNull("len=$len decrypts", decoded)
            assertArrayEquals("len=$len roundtrips", plain, decoded)
        }
    }

    @Test
    fun ivHeaderByteAdvancesPerPacket() {
        val (sender, receiver) = pair(seed = 2)
        var last = -1
        repeat(10) {
            val packet = sender.encrypt(byteArrayOf(1, 2, 3, 4))
            val iv = packet[0].toInt() and 0xFF
            assertTrue("IV byte advances", iv != last)
            last = iv
            assertNotNull(receiver.decrypt(packet))
        }
    }

    @Test
    fun tamperedTagIsRejected() {
        val (sender, receiver) = pair(seed = 3)
        val packet = sender.encrypt(Random(3).nextBytes(40))
        packet[2] = (packet[2].toInt() xor 0xFF).toByte() // corrupt a tag byte
        assertNull("tampered packet rejected", receiver.decrypt(packet))
    }

    @Test
    fun replayedPacketIsRejected() {
        val (sender, receiver) = pair(seed = 4)
        // Advance a bit so the replay lands inside the history window rather than being "next".
        repeat(3) { assertNotNull(receiver.decrypt(sender.encrypt(byteArrayOf(9, 9, 9, 9)))) }
        val replay = sender.encrypt(byteArrayOf(7, 7, 7, 7))
        assertNotNull("first delivery accepted", receiver.decrypt(replay))
        assertNull("replay rejected", receiver.decrypt(replay))
    }

    @Test
    fun toleratesMildReorder() {
        val (sender, receiver) = pair(seed = 5)
        val p1 = sender.encrypt(byteArrayOf(1))
        val p2 = sender.encrypt(byteArrayOf(2))
        val p3 = sender.encrypt(byteArrayOf(3))
        // Deliver out of order: 1, 3, then the late 2 — all should still authenticate.
        assertNotNull(receiver.decrypt(p1))
        assertNotNull(receiver.decrypt(p3))
        assertNotNull("late packet still accepted", receiver.decrypt(p2))
    }

    @Test
    fun digitalSilenceStillAuthenticates() {
        // On an all-zero penultimate block, the counter-cryptanalysis path deliberately flips one
        // bit of the plaintext so the packet can't be used to forge a tag (matching the reference —
        // the flip is inaudible in Opus data). The packet must still authenticate and decode to the
        // input differing by exactly that one bit, NOT be rejected as a forgery.
        val (sender, receiver) = pair(seed = 6)
        val silence = ByteArray(48) // multiple all-zero blocks → triggers the mitigation
        val decoded = receiver.decrypt(sender.encrypt(silence))
        assertNotNull("silence authenticates (no false forgery rejection)", decoded)
        val differingBits = silence.indices.sumOf { Integer.bitCount((silence[it].toInt() xor decoded!![it].toInt()) and 0xFF) }
        assertEquals("mitigation flips exactly one bit", 1, differingBits)
    }
}
