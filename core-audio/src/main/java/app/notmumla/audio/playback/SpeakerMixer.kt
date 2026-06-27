package app.notmumla.audio.playback

import app.notmumla.audio.AudioConstants
import app.notmumla.audio.codec.OpusDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes and mixes all active remote speakers into a single PCM stream. Each speaker gets its own
 * Opus decoder + jitter buffer. The playback loop calls [mixNextFrame] at the frame cadence.
 */
class SpeakerMixer(private val frameSamples: Int = AudioConstants.FRAME_SAMPLES) {

    private class Speaker {
        val decoder = OpusDecoder()
        val jitter = JitterBuffer()
        val pcm = ShortArray(AudioConstants.FRAME_SAMPLES)
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
     * Mix one frame from every active speaker into [out] (16-bit PCM, summed and clipped).
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
            synchronized(speaker) {
                val pull = speaker.jitter.pull()
                val n = when {
                    pull.opus != null -> speaker.decoder.decode(pull.opus, speaker.pcm, frameSamples)
                    pull.lost -> speaker.decoder.concealLoss(speaker.pcm, frameSamples)
                    else -> 0
                }
                if (n > 0) {
                    produced = true
                    speaker.silentFrames = 0
                    for (i in 0 until minOf(n, out.size)) {
                        val sum = out[i] + speaker.pcm[i]
                        out[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                } else {
                    speaker.silentFrames += 1
                }
            }
            if (produced) speaking += session
            // Evict speakers that have been idle for ~1.5s to free decoders.
            if (speaker.jitter.isIdle && speaker.silentFrames > 75) {
                speaker.decoder.release()
                iterator.remove()
            }
        }
        return speaking
    }

    fun release() {
        speakers.values.forEach { it.decoder.release() }
        speakers.clear()
    }
}
