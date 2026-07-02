package app.notmumla.audio.playback

import app.notmumla.audio.AudioConstants
import app.notmumla.audio.codec.OpusDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decodes and mixes all active remote speakers into a single PCM stream. Each speaker gets its own
 * Opus decoder + jitter buffer. The playback loop calls [mixNextFrame] at the output frame cadence.
 *
 * Mixing goes through a float accumulator so we can (a) apply per-speaker **leveling** — makeup gain
 * toward a target loudness when [leveling] is on, so quiet and loud talkers come through evenly — and
 * (b) run a soft **output limiter** so the summed mix never hard-clips when people overlap.
 */
class SpeakerMixer(private val frameSamples: Int = AudioConstants.FRAME_SAMPLES) {

    /** Normalize each incoming speaker toward a consistent loudness. */
    @Volatile var leveling: Boolean = false

    private class Speaker {
        val decoder = OpusDecoder()
        val jitter = JitterBuffer()
        val decodeBuf = ShortArray(AudioConstants.MAX_FRAME_SAMPLES)
        var pendingLen = 0
        var pendingPos = 0
        var silentFrames = 0
        var levelGain = 1f    // current leveling makeup gain
        var speechEnv = 0.05f // smoothed speech loudness for leveling
    }

    private val speakers = ConcurrentHashMap<Int, Speaker>()
    /** Local per-user volume multipliers keyed by session (1.0 = default). */
    private val userGains = ConcurrentHashMap<Int, Float>()
    private val mixBuf = FloatArray(frameSamples)
    private var limiterGain = 1f

    val activeSessions: Set<Int> get() = speakers.keys.toSet()

    /** Set the local playback gain (multiplier) for a session; 1.0 clears it. */
    fun setUserGain(session: Int, gain: Float) {
        if (gain == 1.0f) userGains.remove(session) else userGains[session] = gain
    }

    fun enqueue(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
        val speaker = speakers.getOrPut(session) { Speaker() }
        synchronized(speaker) {
            if (opus.isNotEmpty()) speaker.jitter.put(sequence, opus)
        }
    }

    /**
     * Mix one [out]-sized frame from every active speaker. Returns the set of sessions that produced
     * audio this frame (for speaking indicators).
     */
    fun mixNextFrame(out: ShortArray): Set<Int> {
        java.util.Arrays.fill(mixBuf, 0f)
        if (speakers.isEmpty()) {
            java.util.Arrays.fill(out, 0.toShort())
            return emptySet()
        }

        val speaking = HashSet<Int>()
        val iterator = speakers.entries.iterator()
        while (iterator.hasNext()) {
            val (session, speaker) = iterator.next()
            var produced = false
            synchronized(speaker) { produced = mixSpeaker(speaker, userGains[session] ?: 1f) }
            if (produced) speaking += session
            if (speaker.jitter.isIdle && speaker.pendingPos >= speaker.pendingLen && speaker.silentFrames > 75) {
                speaker.decoder.release()
                iterator.remove()
            }
        }

        applyLimiter()
        for (i in mixBuf.indices) {
            out[i] = mixBuf[i].roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return speaking
    }

    /** Accumulate this speaker's next [frameSamples] into the float mix buffer (× leveling × user gain). */
    private fun mixSpeaker(speaker: Speaker, userGain: Float): Boolean {
        var written = 0
        var produced = false
        while (written < mixBuf.size) {
            if (speaker.pendingPos >= speaker.pendingLen && !decodeNext(speaker)) break
            val avail = speaker.pendingLen - speaker.pendingPos
            if (avail <= 0) break
            val take = minOf(mixBuf.size - written, avail)
            val g = speaker.levelGain * userGain
            for (i in 0 until take) mixBuf[written + i] += speaker.decodeBuf[speaker.pendingPos + i] * g
            speaker.pendingPos += take
            written += take
            produced = true
        }
        if (produced) speaker.silentFrames = 0 else speaker.silentFrames += 1
        return produced
    }

    private fun decodeNext(speaker: Speaker): Boolean {
        val opus = speaker.jitter.pull().opus ?: return false
        val n = speaker.decoder.decode(opus, speaker.decodeBuf, speaker.decodeBuf.size)
        if (n <= 0) return false
        speaker.pendingLen = n
        speaker.pendingPos = 0
        updateLeveling(speaker, n)
        return true
    }

    /** Track the speaker's loudness and steer its makeup gain toward the target (when leveling is on). */
    private fun updateLeveling(speaker: Speaker, n: Int) {
        if (!leveling) { speaker.levelGain = 1f; return }
        var sum = 0.0
        for (i in 0 until n) { val v = speaker.decodeBuf[i] / 32768.0; sum += v * v }
        val level = sqrt(sum / n).toFloat()
        if (level > LEVEL_SPEECH_FLOOR) speaker.speechEnv = maxOf(level, speaker.speechEnv * LEVEL_ENV_DECAY)
        val desired = (LEVEL_TARGET / maxOf(speaker.speechEnv, 1e-4f)).coerceIn(LEVEL_MIN_GAIN, LEVEL_MAX_GAIN)
        speaker.levelGain += (desired - speaker.levelGain) * LEVEL_RATE
    }

    /** Soft peak limiter: immediate attack (no clipping), slow release. */
    private fun applyLimiter() {
        var peak = 0f
        for (v in mixBuf) { val a = abs(v); if (a > peak) peak = a }
        val target = if (peak > LIMIT_CEIL) LIMIT_CEIL / peak else 1f
        limiterGain = if (target < limiterGain) target else limiterGain + (target - limiterGain) * LIMIT_RELEASE
        if (limiterGain < 0.999f) for (i in mixBuf.indices) mixBuf[i] *= limiterGain
    }

    fun release() {
        speakers.values.forEach { it.decoder.release() }
        speakers.clear()
        limiterGain = 1f
    }

    private companion object {
        const val LEVEL_TARGET = 0.14f        // desired per-speaker RMS (0..1)
        const val LEVEL_SPEECH_FLOOR = 0.01f  // only adapt above this (= actual speech)
        const val LEVEL_ENV_DECAY = 0.995f
        const val LEVEL_MIN_GAIN = 0.4f
        const val LEVEL_MAX_GAIN = 4f         // cap boost (+12 dB) so quiet+noisy isn't amplified to hiss
        const val LEVEL_RATE = 0.05f          // gain adaptation speed
        const val LIMIT_CEIL = 30000f         // ~0.92 full-scale headroom
        const val LIMIT_RELEASE = 0.05f
    }
}
