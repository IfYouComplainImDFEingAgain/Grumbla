package app.notmumla.audio.codec

import kotlin.math.abs
import kotlin.math.sqrt

/** JNI bindings to RNNoise (see src/main/cpp/rnnoise_jni.cpp). */
internal object RnnoiseNative {
    init { System.loadLibrary("rnnoisejni") }
    external fun create(): Long
    external fun frameSize(): Int
    external fun process(handle: Long, frame: ShortArray, mix: Float): Float
    external fun destroy(handle: Long)
}

/**
 * RNN-based noise suppression for one mic stream. Operates on 48 kHz / 480-sample frames (matching
 * the capture frame) in place. Not thread-safe; use from the capture thread only.
 */
class Denoiser {
    private var handle = RnnoiseNative.create()

    /** Frame size RNNoise expects (480 = 10 ms at 48 kHz). */
    val frameSize: Int = RnnoiseNative.frameSize()

    /** Strength: 1.0 = full RNNoise, lower blends in the original to soften over-suppression. */
    @Volatile var mix: Float = 1.0f

    /**
     * Speech-gated makeup gain after denoising. RNNoise only ever attenuates, and it runs on the raw
     * capture source, which has none of VOICE_COMMUNICATION's hardware AGC — without this the voice
     * goes out far quieter than with native processing.
     */
    @Volatile var leveling: Boolean = true
    private var levelGain = 1f
    private var speechEnv = LEVEL_TARGET

    /** Denoise [frame] in place; returns the voice-activity probability (0..1). */
    fun process(frame: ShortArray): Float {
        if (handle == 0L) return 0f
        val vad = RnnoiseNative.process(handle, frame, mix.coerceIn(0f, 1f))
        if (leveling) level(frame, vad) else levelGain = 1f
        return vad
    }

    private fun level(frame: ShortArray, vad: Float) {
        var sum = 0.0
        var peak = 0
        for (s in frame) { sum += s.toDouble() * s; val a = abs(s.toInt()); if (a > peak) peak = a }
        val rms = (sqrt(sum / frame.size) / 32768.0).toFloat()
        // Adapt only on frames RNNoise is confident are speech, so residual noise isn't pumped up.
        if (vad > LEVEL_VAD && rms > LEVEL_SPEECH_FLOOR) speechEnv = maxOf(rms, speechEnv * LEVEL_ENV_DECAY)
        val desired = (LEVEL_TARGET / speechEnv).coerceIn(1f, LEVEL_MAX_GAIN)
        levelGain += (desired - levelGain) * LEVEL_RATE
        // Never clip: cap this frame's gain at the peak headroom.
        val g = minOf(levelGain, if (peak > 0) LEVEL_CEIL / peak else levelGain)
        if (g <= 1.001f) return
        for (i in frame.indices) frame[i] = (frame[i] * g).toInt().toShort()
    }

    private companion object {
        const val LEVEL_TARGET = 0.12f       // desired speech RMS (0..1 full-scale)
        const val LEVEL_SPEECH_FLOOR = 0.003f
        const val LEVEL_VAD = 0.6f
        const val LEVEL_ENV_DECAY = 0.998f   // ~5 s to fall 10x at 100 frames/s
        const val LEVEL_MAX_GAIN = 6f        // +15.6 dB cap
        const val LEVEL_RATE = 0.02f
        const val LEVEL_CEIL = 30000f
    }

    fun release() {
        if (handle != 0L) {
            RnnoiseNative.destroy(handle)
            handle = 0L
        }
    }
}
