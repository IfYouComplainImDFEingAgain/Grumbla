package app.notmumla.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Tells whether our own playback comes back through the mic, i.e. whether the device's echo
 * cancelling works in the mode we're in. Used on the loudspeaker in media mode, where that's up to
 * the audio HAL and `AcousticEchoCanceler.isAvailable()` says nothing useful.
 *
 * Both audio threads report the level of each 10 ms frame with its clock time, which puts them on
 * a shared grid of [SLOT_NS] slots without having to know the output/input latencies. Every
 * [WINDOW_SLOTS], for each delay up to [MAX_LAG_SLOTS], the mic's quiet floor while something was
 * played is compared with its floor while nothing was; echo lifts it by [ECHO_RISE_DB] or more at
 * some delay. Background noise and local speech don't follow the remote talker's on/off pattern,
 * so they lift both floors alike. Windows without enough talk and pauses to tell are skipped.
 *
 * Thread-safe: [onPlayback] and [onCapture] come from different threads.
 */
class EchoDetector(
    private val onEcho: () -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private val far = FloatArray(RING)
    private val near = FloatArray(RING)
    // Slot each entry was written for, so an entry left from a lap ago (a stalled thread) isn't read.
    private val farSlot = LongArray(RING) { -1 }
    private val nearSlot = LongArray(RING) { -1 }
    private val activeNear = FloatArray(WINDOW_SLOTS)
    private val quietNear = FloatArray(WINDOW_SLOTS)
    private var firstSlot = -1L
    private var nextCheck = -1L
    private var hits = 0
    private var hitLag = -1
    private var bestLag = -1
    private var fired = false

    /** Floor rise (dB) of the last window that could be scored, for logs. */
    @Volatile var lastScore = Float.NaN
        private set

    fun onPlayback(pcm: ShortArray, len: Int) = record(far, farSlot, pcm, len)

    fun onCapture(pcm: ShortArray, len: Int) = record(near, nearSlot, pcm, len)

    @Synchronized
    private fun record(env: FloatArray, slots: LongArray, pcm: ShortArray, len: Int) {
        if (fired) return
        val slot = clock() / SLOT_NS
        if (firstSlot < 0) {
            firstSlot = slot
            nextCheck = slot + WINDOW_SLOTS + MAX_LAG_SLOTS
        }
        val i = index(slot)
        env[i] = levelDb(pcm, len)
        slots[i] = slot
        if (slot >= nextCheck) {
            nextCheck = slot + WINDOW_SLOTS
            check(slot)
        }
    }

    /** Score the window ending a little before [now], so both threads have filled it in. */
    private fun check(now: Long) {
        val end = now - SETTLE_SLOTS
        val start = end - WINDOW_SLOTS
        val score = bestRise(start, end)
        if (score.isNaN()) return // not enough playback in this window to tell
        lastScore = score
        log("speaker echo: floor rise %.1f dB at %d ms".format(score, bestLag * 10))
        // Real echo comes back at the same delay every time; a chance overlap with local speech
        // lands wherever it happens to.
        hits = when {
            score < ECHO_RISE_DB -> 0
            hits > 0 && abs(bestLag - hitLag) <= LAG_TOLERANCE_SLOTS -> hits + 1
            else -> 1
        }
        if (hits > 0) hitLag = bestLag
        if (hits >= WINDOWS_TO_CONFIRM) {
            fired = true
            onEcho()
        }
    }

    /** Largest [floorRise] over the delays searched (its delay in [bestLag]), or NaN if unscorable. */
    private fun bestRise(start: Long, end: Long): Float {
        var best = Float.NaN
        for (lag in 0..MAX_LAG_SLOTS) {
            val r = floorRise(start, end, lag)
            if (!r.isNaN() && (best.isNaN() || r > best)) { best = r; bestLag = lag }
        }
        return best
    }

    /**
     * How much the mic's quiet floor rises (dB) while the playback from [lag] slots earlier is
     * active. A low percentile, not the mean: local speech only fills some slots, so it hardly moves
     * it, while echo lifts every slot that follows playback.
     */
    private fun floorRise(start: Long, end: Long, lag: Int): Float {
        var na = 0
        var nq = 0
        for (s in start until end) {
            val ni = index(s)
            val fi = index(s - lag)
            if (nearSlot[ni] != s || farSlot[fi] != s - lag) continue
            if (far[fi] > ACTIVE_DB) activeNear[na++] = near[ni] else quietNear[nq++] = near[ni]
        }
        if (na < WINDOW_SLOTS * MIN_ACTIVE_FRACTION || nq < WINDOW_SLOTS * MIN_QUIET_FRACTION) return Float.NaN
        return percentile(activeNear, na) - percentile(quietNear, nq)
    }

    private fun percentile(values: FloatArray, n: Int): Float {
        values.sort(0, n)
        return values[(n * FLOOR_PERCENTILE).toInt()]
    }

    private fun index(slot: Long) = slot.mod(RING.toLong()).toInt()

    private fun levelDb(pcm: ShortArray, len: Int): Float {
        var sum = 0.0
        for (k in 0 until len) { val v = pcm[k] / 32768.0; sum += v * v }
        val ms = if (len > 0) sum / len else 0.0
        return if (ms <= 1e-10) FLOOR_DB else (10 * log10(ms)).toFloat().coerceAtLeast(FLOOR_DB)
    }

    companion object {
        const val SLOT_NS = 10_000_000L
        /** 8 s scored per check. */
        const val WINDOW_SLOTS = 800
        /** Speaker-to-mic delay searched: output buffer + air + input buffer, up to 500 ms. */
        const val MAX_LAG_SLOTS = 50
        /** Wait for the slower thread before scoring a window. */
        const val SETTLE_SLOTS = 20
        private const val RING = WINDOW_SLOTS + MAX_LAG_SLOTS + SETTLE_SLOTS * 2 + 64

        /** Playback louder than this counts as someone talking. */
        const val ACTIVE_DB = -50f
        /** A window needs at least this much talking (~1.6 s of 8) to be scored... */
        const val MIN_ACTIVE_FRACTION = 0.2
        /** ...and this much quiet to compare against (so a nonstop music bot can't be scored). */
        const val MIN_QUIET_FRACTION = 0.1
        private const val FLOOR_PERCENTILE = 0.1
        /** Echo, not chance: with working cancelling the two floors stay within a few dB. */
        const val ECHO_RISE_DB = 10f
        /** Consecutive scored windows above the threshold, at about the same delay, before we call it. */
        const val WINDOWS_TO_CONFIRM = 3
        /** Delay wobble allowed between confirming windows (buffer timing jitter), 30 ms. */
        const val LAG_TOLERANCE_SLOTS = 3
        private const val FLOOR_DB = -90f
    }
}
