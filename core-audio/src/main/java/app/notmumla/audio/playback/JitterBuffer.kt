package app.notmumla.audio.playback

import app.notmumla.protocol.udp.OpusPacket
import java.util.TreeMap

/**
 * Per-speaker jitter buffer, ordered by Mumble `frame_number` (a timestamp in 10 ms units: a 20 ms
 * packet advances it by 2). Depth is measured in **time**, not packets — counting packets gave a
 * 10 ms-per-packet sender (us) half the cushion of a desktop client's 20 ms packets.
 *
 * Playback tracks the next expected frame number so it can:
 * - drop **late** packets (already concealed/played past) instead of splicing them in out of order;
 * - **conceal** a missing packet or a brief underrun with Opus PLC rather than cutting to silence;
 * - end the stream at the sender's **terminator** with a faded PLC frame and a decoder reset, so the
 *   next talk-spurt (which a peer encodes with a fresh encoder) starts from a clean decoder.
 *
 * Not thread-safe on its own; callers synchronize via [SpeakerMixer].
 */
class JitterBuffer(
    private val targetUnits: Int = 6,
    private val maxUnits: Int = 20,
) {
    private class Entry(val opus: ByteArray, val units: Int)

    private val frames = TreeMap<Long, Entry>()
    private var bufferedUnits = 0
    private var primed = false
    /** Frame number the next played frame should carry; null before a stream's first frame. */
    private var nextSeq: Long? = null
    /** First frame number past the current stream's terminator, once one has been received. */
    private var endSeq: Long? = null
    /** Consecutive concealed frames (lost packets / underrun). */
    private var misses = 0
    /** Set when the stream restarted underneath us; the decoder must be reset before the next frame. */
    private var resetPending = false

    fun put(sequence: Long, opus: ByteArray, terminator: Boolean = false) {
        val units = if (opus.isEmpty()) 0 else OpusPacket.tenMsUnits(opus)
        val expected = nextSeq
        val end = endSeq
        val restarted = when {
            // Numbering went backwards after a terminator: a sender that restarts its counter per
            // spurt. Its old tail can't be played after the new frames in a sorted buffer; drop it.
            end != null && sequence < end - REORDER_UNITS -> true
            // A huge backwards jump without a terminator (lost over UDP): the counter was reset.
            expected != null && sequence < expected - RESYNC_UNITS -> true
            else -> false
        }
        // Frames past a received terminator open the next spurt; they're never "late".
        val nextStream = end != null && sequence >= end
        if (restarted) {
            restart()
        } else if (!nextStream && expected != null && sequence < expected) {
            // Late: its slot was already concealed/played. A late terminator still ends the stream,
            // or the next spurt's frames would be judged late against our concealed-ahead position.
            if (terminator) endSeq = sequence + units
            return
        }

        if (units > 0) {
            frames.put(sequence, Entry(opus, units))?.let { bufferedUnits -= it.units }
            bufferedUnits += units
        }
        if (terminator) endSeq = sequence + units

        // A burst (stalled TCP, clock drift) pushed us past the latency cap: drop back to the target
        // in one jump rather than sitting at the cap or dropping a frame on every arrival.
        if (bufferedUnits > maxUnits) {
            while (bufferedUnits > targetUnits && frames.size > 1) {
                bufferedUnits -= frames.pollFirstEntry().value.units
            }
            if (nextSeq != null) nextSeq = frames.firstKey()
        }
    }

    fun pull(): Pull {
        val end = endSeq
        if (end != null && (frames.isEmpty() || frames.firstKey() >= end)) {
            // Everything up to the terminator has played. Anything still buffered is the next spurt.
            val wasPlaying = nextSeq != null
            nextSeq = null
            endSeq = null
            primed = false
            misses = 0
            if (wasPlaying) return Pull.END
        }
        if (!primed) {
            if (frames.isEmpty()) return Pull.EMPTY
            // A spurt shorter than the target is primed by its terminator, not stuck until the next.
            if (endSeq == null && (bufferedUnits < targetUnits || frames.size < MIN_PACKETS)) return Pull.EMPTY
            primed = true
        }

        val first = frames.firstEntry()
        val expected = nextSeq
        if (first == null || (expected != null && first.key > expected && first.key - expected <= MAX_GAP_UNITS)) {
            // Underrun, or the next packet is missing (lost, or so late it's useless): conceal 10 ms.
            if (misses < MAX_CONCEAL) {
                misses++
                if (expected != null) nextSeq = expected + 1
                return Pull.CONCEAL
            }
            if (first == null) {
                // Stalled: fade the concealment out, then re-prime once packets arrive.
                primed = false
                return Pull.CONCEAL_FADE
            }
            // Concealed as long as we will; play what we have from here.
        }

        frames.remove(first.key)
        bufferedUnits -= first.value.units
        misses = 0
        nextSeq = first.key + first.value.units
        val reset = resetPending
        resetPending = false
        return Pull(opus = first.value.opus, resetDecoder = reset)
    }

    val isIdle: Boolean get() = frames.isEmpty()

    private fun restart() {
        frames.clear()
        bufferedUnits = 0
        primed = false
        nextSeq = null
        endSeq = null
        misses = 0
        resetPending = true
    }

    /**
     * One step of playback: decode [opus], or (when null) run packet-loss concealment for 10 ms.
     * [fadeOut]: fade this frame to silence. [resetDecoder]: reset decoder state before decoding
     * (stream restarted); [endOfStream]: after the faded frame, reset it (the stream ended).
     */
    class Pull(
        val opus: ByteArray? = null,
        val conceal: Boolean = false,
        val fadeOut: Boolean = false,
        val resetDecoder: Boolean = false,
        val endOfStream: Boolean = false,
    ) {
        val isEmpty: Boolean get() = opus == null && !conceal

        companion object {
            val EMPTY = Pull()
            val CONCEAL = Pull(conceal = true)
            val CONCEAL_FADE = Pull(conceal = true, fadeOut = true)
            val END = Pull(conceal = true, fadeOut = true, endOfStream = true)
        }
    }

    private companion object {
        const val MIN_PACKETS = 2
        /** Longest run of concealed frames (50 ms); Opus PLC turns to mush beyond this. */
        const val MAX_CONCEAL = 5
        /** Forward jumps larger than this are a new spurt (missed terminator), not loss to conceal. */
        const val MAX_GAP_UNITS = 10L
        /** Reordering tolerated around a terminator before a lower number counts as a restart. */
        const val REORDER_UNITS = 10L
        const val RESYNC_UNITS = 100L
    }
}
