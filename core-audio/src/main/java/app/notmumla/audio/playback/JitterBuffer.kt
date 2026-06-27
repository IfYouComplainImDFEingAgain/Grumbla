package app.notmumla.audio.playback

import java.util.TreeMap

/**
 * Minimal per-speaker jitter buffer: holds a few frames ordered by sequence number to absorb
 * network reordering/jitter before playback, and reports gaps so the decoder can conceal losses.
 *
 * Not thread-safe on its own; callers synchronize via [SpeakerMixer].
 */
class JitterBuffer(private val targetDepth: Int = 3, private val maxDepth: Int = 12) {
    private val frames = TreeMap<Long, ByteArray?>()
    private var nextSequence = -1L
    private var primed = false

    /** Add a received frame. */
    fun put(sequence: Long, opus: ByteArray) {
        if (sequence < nextSequence) return // too late, already played past it
        frames[sequence] = opus
        if (frames.size > maxDepth) {
            // Drop the oldest to bound latency.
            val first = frames.firstKey()
            frames.remove(first)
            if (first <= nextSequence) nextSequence = first + 1
        }
    }

    /**
     * Pull the next frame to play. Returns:
     *  - the opus bytes when the in-order frame is available,
     *  - null with [Pull.lost] = true when a frame is missing (caller should run PLC),
     *  - [Pull.empty] when the buffer hasn't primed / is idle.
     */
    fun pull(): Pull {
        if (!primed) {
            if (frames.size < targetDepth) return Pull.EMPTY
            primed = true
            nextSequence = frames.firstKey()
        }
        if (frames.isEmpty()) {
            primed = false
            return Pull.EMPTY
        }
        val opus = frames.remove(nextSequence)
        return if (opus != null) {
            nextSequence += 1
            Pull(opus = opus)
        } else {
            // Gap: the expected sequence isn't here yet but later frames are → conceal one.
            nextSequence += 1
            Pull(lost = true)
        }
    }

    val isIdle: Boolean get() = frames.isEmpty()

    data class Pull(val opus: ByteArray? = null, val lost: Boolean = false) {
        val isEmpty: Boolean get() = opus == null && !lost
        companion object { val EMPTY = Pull() }
    }
}
