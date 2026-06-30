package app.notmumla.audio.playback

import java.util.TreeMap

/**
 * Minimal per-speaker jitter buffer: holds a few frames ordered by Mumble frame number to absorb
 * network reordering/jitter, then plays them back in order.
 *
 * Audio currently rides the **reliable, in-order TCP tunnel**, so frames are never lost — only
 * delayed/reordered. We therefore play strictly in arrival order and do **not** infer "gaps" from
 * non-contiguous frame numbers. That matters because Mumble's `frame_number` is a timestamp in
 * 10 ms units, so a peer using 20/40/60 ms Opus frames advances it by 2/4/6 per packet; treating
 * those jumps as losses (and concealing) would stretch and chop the audio. (A loss-concealment /
 * FEC path belongs with the future UDP transport, where packets really can go missing.)
 *
 * Not thread-safe on its own; callers synchronize via [SpeakerMixer].
 */
class JitterBuffer(private val targetDepth: Int = 3, private val maxDepth: Int = 12) {
    private val frames = TreeMap<Long, ByteArray>()
    private var primed = false

    /** Add a received frame. */
    fun put(sequence: Long, opus: ByteArray) {
        frames[sequence] = opus
        // Bound latency: if we're backing up, drop the oldest frames.
        while (frames.size > maxDepth) frames.remove(frames.firstKey())
    }

    /** Pull the next frame to play (lowest frame number), or [Pull.EMPTY] while unprimed/idle. */
    fun pull(): Pull {
        if (!primed) {
            if (frames.size < targetDepth) return Pull.EMPTY
            primed = true
        }
        val entry = frames.pollFirstEntry()
        if (entry == null) {
            primed = false
            return Pull.EMPTY
        }
        return Pull(opus = entry.value)
    }

    val isIdle: Boolean get() = frames.isEmpty()

    data class Pull(val opus: ByteArray? = null) {
        val isEmpty: Boolean get() = opus == null
        companion object { val EMPTY = Pull() }
    }
}
