package app.notmumla.audio.playback

import app.notmumla.audio.AudioConstants
import app.notmumla.audio.codec.OpusDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes and mixes all active remote speakers into a single PCM stream. Each speaker gets its own
 * Opus decoder + jitter buffer. The playback loop calls [mixNextFrame] at the output frame cadence.
 *
 * Decoded frames are buffered per speaker so any incoming Opus frame size (10–60 ms, possibly
 * different from our 10 ms output chunk) plays back correctly without truncation.
 */
class SpeakerMixer(private val frameSamples: Int = AudioConstants.FRAME_SAMPLES) {

    private class Speaker {
        val decoder = OpusDecoder()
        val jitter = JitterBuffer()
        val decodeBuf = ShortArray(AudioConstants.MAX_FRAME_SAMPLES)
        var pendingLen = 0   // valid samples in decodeBuf
        var pendingPos = 0   // next unread sample in decodeBuf
        var silentFrames = 0
    }

    private val speakers = ConcurrentHashMap<Int, Speaker>()

    /** Currently audible speaker sessions (had audio within the last few frames). */
    val activeSessions: Set<Int> get() = speakers.keys.toSet()

    fun enqueue(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
        val speaker = speakers.getOrPut(session) { Speaker() }
        synchronized(speaker) {
            if (opus.isNotEmpty()) speaker.jitter.put(sequence, opus)
        }
    }

    /**
     * Mix one [out]-sized frame from every active speaker (16-bit PCM, summed and clipped).
     * Returns the set of sessions that produced audio this frame (for speaking indicators).
     */
    fun mixNextFrame(out: ShortArray): Set<Int> {
        java.util.Arrays.fill(out, 0.toShort())
        if (speakers.isEmpty()) return emptySet()

        val speaking = HashSet<Int>()
        val iterator = speakers.entries.iterator()
        while (iterator.hasNext()) {
            val (session, speaker) = iterator.next()
            var produced = false
            synchronized(speaker) { produced = mixSpeaker(speaker, out) }
            if (produced) speaking += session
            // Evict speakers idle for ~1.5 s to free decoders.
            if (speaker.jitter.isIdle && speaker.pendingPos >= speaker.pendingLen && speaker.silentFrames > 75) {
                speaker.decoder.release()
                iterator.remove()
            }
        }
        return speaking
    }

    /** Fill [out] (mixing) with this speaker's next [frameSamples], decoding as needed. */
    private fun mixSpeaker(speaker: Speaker, out: ShortArray): Boolean {
        var written = 0
        var produced = false
        while (written < out.size) {
            if (speaker.pendingPos >= speaker.pendingLen && !decodeNext(speaker)) break
            val avail = speaker.pendingLen - speaker.pendingPos
            if (avail <= 0) break
            val take = minOf(out.size - written, avail)
            for (i in 0 until take) {
                val sum = out[written + i] + speaker.decodeBuf[speaker.pendingPos + i]
                out[written + i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            speaker.pendingPos += take
            written += take
            produced = true
        }
        if (produced) speaker.silentFrames = 0 else speaker.silentFrames += 1
        return produced
    }

    /** Decode the next frame into the speaker's buffer; returns false when nothing was produced. */
    private fun decodeNext(speaker: Speaker): Boolean {
        val opus = speaker.jitter.pull().opus ?: return false
        // frame_size = full buffer capacity; Opus returns the actual decoded sample count, so any
        // incoming frame size (10–60 ms) decodes fully and is played out via the pending buffer.
        val n = speaker.decoder.decode(opus, speaker.decodeBuf, speaker.decodeBuf.size)
        return if (n > 0) {
            speaker.pendingLen = n
            speaker.pendingPos = 0
            true
        } else false
    }

    fun release() {
        speakers.values.forEach { it.decoder.release() }
        speakers.clear()
    }
}
