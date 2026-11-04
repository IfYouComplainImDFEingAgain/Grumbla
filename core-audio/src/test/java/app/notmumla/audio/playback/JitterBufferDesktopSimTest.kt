package app.notmumla.audio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Feeds the buffer like a desktop Mumble client: one Opus packet per 20/40/60 ms (multi-frame CELT,
 * `frame_number` advancing by the packet's 10 ms units), the last one a terminator that carries audio.
 * Arrival has small random jitter plus occasional network stalls that release a burst; order is kept
 * (UDP rarely reorders). Playback pulls on a 10 ms clock like [SpeakerMixer].
 */
class JitterBufferDesktopSimTest {
    private class Stats(val sent: Int, val played: Int, val stallTicks: Int, val maxStallMs: Int)

    /** A fake multi-frame CELT packet: 20 ms frames (config 31), code 3 with [ms]/20 frames, then a tag. */
    private fun pkt(tag: Int, ms: Int): ByteArray =
        if (ms == 20) byteArrayOf((31 shl 3).toByte(), 0, tag.toByte(), (tag shr 8).toByte())
        else byteArrayOf(((31 shl 3) or 3).toByte(), (ms / 20).toByte(), tag.toByte(), (tag shr 8).toByte())

    private fun tagOf(opus: ByteArray) = (opus[2].toInt() and 0xFF) or ((opus[3].toInt() and 0xFF) shl 8)

    private fun simulate(packetMs: Int, jitterMs: Int, stalls: List<Pair<Int, Int>>, seconds: Int = 20, seed: Long = 1): Stats {
        val rnd = Random(seed)
        val units = packetMs / 10
        val count = seconds * 1000 / packetMs
        var last = 0
        val arrivals = IntArray(count) { i ->
            val send = i * packetMs
            var at = send + 5 + rnd.nextInt(jitterMs + 1)
            for ((start, len) in stalls) if (send in start until start + len) at = maxOf(at, start + len)
            at = maxOf(at, last)
            last = at
            at
        }
        val jb = JitterBuffer()
        var next = 0
        var pending = 0
        val played = HashSet<Int>()
        var started = false
        var stallTicks = 0
        var run = 0
        var maxRun = 0
        var t = 0
        while (t < arrivals.last() + 2000) {
            while (next < count && arrivals[next] <= t) {
                jb.put(next.toLong() * units, pkt(next, packetMs), terminator = next == count - 1)
                next++
            }
            if (pending == 0) {
                val p = jb.pull()
                val opus = p.opus
                val inSpurt = started && t <= arrivals.last()
                when {
                    opus != null -> {
                        started = true
                        played += tagOf(opus)
                        pending = units - p.skipUnits
                        run = 0
                    }
                    inSpurt && !p.endOfStream -> { stallTicks++; run++; maxRun = maxOf(maxRun, run) }
                }
            }
            if (pending > 0) pending--
            t += 10
        }
        return Stats(count, played.size, stallTicks, maxRun * 10)
    }

    @Test fun steadyJitterWithinOnePacketPlaysEverythingClean() {
        for (ms in listOf(20, 40, 60)) {
            val s = simulate(ms, jitterMs = 30, stalls = emptyList())
            assertEquals("${ms}ms sent=${s.sent}", s.sent, s.played)
            assertEquals("${ms}ms stall ticks", 0, s.stallTicks)
        }
    }

    @Test fun stallsNeverDropPacketsAndCostOnlyTheStall() {
        // Two 120 ms stalls 5 s apart, then a 100 ms one: a late packet must still play, and once the
        // buffer has grown for the first stall the later (not longer) ones must be absorbed.
        val stalls = listOf(3_000 to 120, 8_000 to 120, 13_000 to 100)
        for (ms in listOf(20, 40, 60)) {
            val s = simulate(ms, jitterMs = 15, stalls = stalls)
            assertEquals("${ms}ms dropped packets", s.sent, s.played)
            // Only the first stall can be heard, for no longer than itself; the old buffer dropped the
            // late packets and was heard on every stall (8/5/3 packets lost at 20/40/60 ms here).
            assertTrue("${ms}ms stalled ${s.stallTicks * 10} ms", s.stallTicks * 10 <= 120)
            assertEquals("${ms}ms longest stall is the only one", s.maxStallMs, s.stallTicks * 10)
        }
    }
}
