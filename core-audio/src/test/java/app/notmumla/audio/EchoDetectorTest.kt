package app.notmumla.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EchoDetectorTest {

    private val frame = AudioConstants.FRAME_SAMPLES

    /** Talk-spurt pattern: noise bursts of 0.2–1.5 s with gaps of 0.2–1.2 s and varying loudness. */
    private class Talker(seed: Int, private val level: Double = 0.2) {
        private val rnd = Random(seed)
        private var left = 0
        private var on = false
        private var amp = 0.0
        fun next(out: DoubleArray) {
            if (left <= 0) {
                on = !on
                left = if (on) rnd.nextInt(20, 150) else rnd.nextInt(20, 120)
                amp = level * rnd.nextDouble(0.3, 1.0)
            }
            left--
            for (i in out.indices) out[i] = if (on) amp * (rnd.nextDouble() * 2 - 1) else 0.0
        }
    }

    private fun DoubleArray.toPcm() = ShortArray(size) { (this[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }

    /**
     * Feed [seconds] of audio. [near] builds each mic frame from the played frame [delaySlots] ago
     * (or null before then), so tests can mix in echo, local speech and noise.
     */
    private fun run(
        seconds: Int,
        delaySlots: Int = 15,
        talker: Talker = Talker(1),
        near: (played: DoubleArray?, out: DoubleArray) -> Unit,
    ): Int {
        var now = 0L
        var fired = 0
        val det = EchoDetector(onEcho = { fired++ }, clock = { now })
        val history = ArrayDeque<DoubleArray>()
        repeat(seconds * 100) {
            val f = DoubleArray(frame)
            talker.next(f)
            history.addLast(f)
            det.onPlayback(f.toPcm(), frame)
            val played = if (history.size > delaySlots) history.removeFirst() else null
            val m = DoubleArray(frame)
            near(played, m)
            det.onCapture(m.toPcm(), frame)
            now += EchoDetector.SLOT_NS
        }
        return fired
    }

    private val noise = Random(99)
    private fun addNoise(out: DoubleArray, level: Double = 0.003) {
        for (i in out.indices) out[i] += level * (noise.nextDouble() * 2 - 1)
    }

    @Test fun `playback leaking into the mic is detected`() {
        val fired = run(40) { played, out ->
            played?.forEachIndexed { i, v -> out[i] = v * 0.3 }
            addNoise(out)
        }
        assertEquals(1, fired)
    }

    @Test fun `detected at the longest searched delay`() {
        val fired = run(40, delaySlots = EchoDetector.MAX_LAG_SLOTS - 5) { played, out ->
            played?.forEachIndexed { i, v -> out[i] = v * 0.3 }
            addNoise(out)
        }
        assertEquals(1, fired)
    }

    @Test fun `echo with the local user talking over it is still detected`() {
        val local = Talker(7, level = 0.1)
        val buf = DoubleArray(frame)
        val fired = run(60) { played, out ->
            played?.forEachIndexed { i, v -> out[i] = v * 0.3 }
            local.next(buf)
            buf.forEachIndexed { i, v -> out[i] += v }
            addNoise(out)
        }
        assertEquals(1, fired)
    }

    @Test fun `cancelled echo with only background noise is not detected`() {
        val fired = run(120) { _, out -> addNoise(out) }
        assertEquals(0, fired)
    }

    @Test fun `residual echo far below the noise floor is not detected`() {
        val fired = run(120) { played, out ->
            played?.forEachIndexed { i, v -> out[i] = v * 0.001 }
            addNoise(out, level = 0.01)
        }
        assertEquals(0, fired)
    }

    @Test fun `local speech unrelated to playback is not detected`() {
        val local = Talker(42, level = 0.2)
        val buf = DoubleArray(frame)
        val fired = run(120) { _, out ->
            local.next(buf)
            buf.copyInto(out)
            addNoise(out)
        }
        assertEquals(0, fired)
    }

    @Test fun `answering in the pauses is not detected`() {
        // Turn-taking: the local user talks exactly while nothing is coming in.
        val local = Talker(5, level = 0.3)
        val buf = DoubleArray(frame)
        val fired = run(120) { played, out ->
            local.next(buf)
            if (played == null || played.all { it == 0.0 }) buf.copyInto(out)
            addNoise(out)
        }
        assertEquals(0, fired)
    }

    @Test fun `nonstop playback has no quiet to compare against and is not scored`() {
        var now = 0L
        var fired = 0
        val det = EchoDetector(onEcho = { fired++ }, clock = { now })
        val loud = ShortArray(frame) { ((noise.nextDouble() * 2 - 1) * 8000).toInt().toShort() }
        repeat(60 * 100) {
            det.onPlayback(loud, frame)
            det.onCapture(loud, frame)
            now += EchoDetector.SLOT_NS
        }
        assertEquals(0, fired)
        assertTrue(det.lastScore.isNaN())
    }

    @Test fun `nothing played means nothing scored`() {
        var now = 0L
        var fired = 0
        val det = EchoDetector(onEcho = { fired++ }, clock = { now })
        val silence = ShortArray(frame)
        val mic = ShortArray(frame) { (noise.nextDouble() * 200).toInt().toShort() }
        repeat(60 * 100) {
            det.onPlayback(silence, frame)
            det.onCapture(mic, frame)
            now += EchoDetector.SLOT_NS
        }
        assertEquals(0, fired)
        assertTrue(det.lastScore.isNaN())
    }
}
