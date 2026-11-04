package app.notmumla.audio.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    /** A fake CELT packet: TOC byte for 10 ms (config 30) or 20 ms (config 31), then a tag byte. */
    private fun pkt(tag: Int, ms: Int = 10) = byteArrayOf(((if (ms == 20) 31 else 30) shl 3).toByte(), tag.toByte())

    private fun JitterBuffer.played(): Int? = pull().opus?.get(1)?.toInt()

    @Test fun primesOnTimeNotPacketCount() {
        val jb = JitterBuffer()
        for (i in 0 until 5) jb.put(i.toLong(), pkt(i))
        assertTrue(jb.pull().isEmpty) // 50 ms < 60 ms target
        jb.put(5, pkt(5))
        assertEquals(0, jb.played())

        // 20 ms packets: three of them already make the 60 ms target.
        val desktop = JitterBuffer()
        desktop.put(0, pkt(0, 20)); desktop.put(2, pkt(1, 20))
        assertTrue(desktop.pull().isEmpty)
        desktop.put(4, pkt(2, 20))
        assertEquals(0, desktop.played())
    }

    @Test fun terminatorPrimesShortSpurtThenEnds() {
        val jb = JitterBuffer()
        jb.put(0, pkt(0)); jb.put(1, pkt(1))
        assertTrue(jb.pull().isEmpty)
        jb.put(2, ByteArray(0), terminator = true)
        assertEquals(0, jb.played())
        assertEquals(1, jb.played())
        val end = jb.pull()
        assertTrue(end.conceal && end.fadeOut && end.endOfStream)
        assertTrue(jb.pull().isEmpty)
    }

    @Test fun nextSpurtPlaysAfterPreviousTail() {
        val jb = JitterBuffer()
        for (i in 0 until 6) jb.put(i.toLong(), pkt(i))
        assertEquals(0, jb.played())
        jb.put(6, ByteArray(0), terminator = true)
        // The next spurt arrives while 1..5 are still buffered (counter keeps running).
        for (i in 7 until 13) jb.put(i.toLong(), pkt(i))
        for (i in 1 until 6) assertEquals(i, jb.played())
        assertTrue(jb.pull().endOfStream)
        assertEquals(7, jb.played())
    }

    @Test fun senderThatRestartsAtZeroDropsOldTail() {
        val jb = JitterBuffer()
        for (i in 800 until 830) jb.put(i.toLong(), pkt(i and 0x7F))
        repeat(25) { jb.pull() }
        jb.put(830, ByteArray(0), terminator = true)
        for (i in 0 until 6) jb.put(i.toLong(), pkt(100 + i))
        val first = jb.pull()
        assertTrue(first.resetDecoder)
        assertArrayEquals(pkt(100), first.opus)
    }

    @Test fun lostPacketConcealedAndLateOneDropped() {
        val jb = JitterBuffer()
        for (i in listOf(0, 1, 3, 4, 5, 6, 7)) jb.put(i.toLong(), pkt(i))
        assertEquals(0, jb.played())
        assertEquals(1, jb.played())
        assertTrue(jb.pull().conceal) // 2 missing
        jb.put(2, pkt(2))             // arrives too late
        assertEquals(3, jb.played())
    }

    @Test fun underrunConcealsThenFadesAndReprimes() {
        val jb = JitterBuffer()
        for (i in 0 until 6) jb.put(i.toLong(), pkt(i))
        repeat(6) { jb.pull() }
        repeat(5) { assertTrue(jb.pull().let { it.conceal && !it.fadeOut }) }
        assertTrue(jb.pull().let { it.conceal && it.fadeOut && !it.endOfStream })
        assertTrue(jb.pull().isEmpty)
        // An underrun doesn't skip ahead: the packet we were waiting for was delayed, not lost.
        for (i in 6 until 17) jb.put(i.toLong(), pkt(i))
        assertEquals(6, jb.played())
    }

    @Test fun lateDesktopPacketAfterUnderrunStillPlays() {
        // A 60 ms sender: one packet arriving 10 ms late used to be dropped whole.
        val jb = JitterBuffer()
        val p60 = { tag: Int -> byteArrayOf(((31 shl 3) or 3).toByte(), 3, tag.toByte()) }
        jb.put(0, p60(0)); jb.put(6, p60(1))
        assertArrayEquals(p60(0), jb.pull().opus)
        assertArrayEquals(p60(1), jb.pull().opus)
        assertTrue(jb.pull().conceal) // ran dry: packet 12 is 10 ms late
        jb.put(12, p60(2))
        val late = jb.pull()
        assertArrayEquals(p60(2), late.opus)
        assertEquals(0, late.skipUnits)
    }

    @Test fun reorderedPacketStraddlingConcealmentPlaysTheRest() {
        val jb = JitterBuffer()
        jb.put(0, pkt(0, 20)); jb.put(4, pkt(4, 20)); jb.put(6, pkt(6, 20))
        assertEquals(0, jb.played())
        assertTrue(jb.pull().conceal) // 2..3 missing: conceal 10 ms of it
        jb.put(2, pkt(2, 20))         // arrives reordered, half already concealed
        val p = jb.pull()
        assertEquals(2, p.opus?.get(1)?.toInt())
        assertEquals(1, p.skipUnits)
        assertEquals(4, jb.played())
    }

    @Test fun burstPastCapTrimsToTarget() {
        val jb = JitterBuffer()
        for (i in 0 until 6) jb.put(i.toLong(), pkt(i))
        assertEquals(0, jb.played())
        for (i in 6 until 21) jb.put(i.toLong(), pkt(i)) // 20 units buffered: at the cap
        assertEquals(1, jb.played())
        jb.put(21, pkt(21)); jb.put(22, pkt(22))          // past it → back to 60 ms
        assertEquals(17, jb.played())
        assertTrue(!jb.pull().conceal)
    }
}
