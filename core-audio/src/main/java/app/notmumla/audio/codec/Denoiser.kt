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
    // Seeded from the last session so a capture restart (route/setting change) doesn't start quiet.
    private var speechEnv = lastSpeechEnv
    private var levelGain = gainFor(lastSpeechEnv)

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
        // Track speech loudness only on frames RNNoise is confident are voice, so residual noise and
        // silence never drive the gain up. Fast rise (a loud burst is corrected within ~100 ms), and a
        // few seconds of speech to settle downward.
        if (vad > LEVEL_VAD && rms > LEVEL_SPEECH_FLOOR) {
            val k = if (rms > speechEnv) LEVEL_ATTACK else LEVEL_RELEASE
            speechEnv += (rms - speechEnv) * k
            lastSpeechEnv = speechEnv
        }
        levelGain += (gainFor(speechEnv) - levelGain) * LEVEL_RATE
        // Never clip: cap this frame's gain at the peak headroom.
        val g = minOf(levelGain, if (peak > 0) LEVEL_CEIL / peak else levelGain)
        if (g <= 1.001f) return
        for (i in frame.indices) frame[i] = (frame[i] * g).toInt().toShort()
    }

    private companion object {
        const val LEVEL_TARGET = 0.15f       // desired speech RMS (0..1 full-scale), ~-16 dBFS
        const val LEVEL_SPEECH_FLOOR = 0.0005f
        const val LEVEL_VAD = 0.5f
        const val LEVEL_ATTACK = 0.1f
        const val LEVEL_RELEASE = 0.01f
        const val LEVEL_MAX_GAIN = 25f       // +28 dB: the raw source runs ~20-30 dB below comms AGC
        const val LEVEL_RATE = 0.05f
        const val LEVEL_CEIL = 30000f
        /** Raw-mic speech level learned by the previous denoiser (process-wide). */
        @Volatile var lastSpeechEnv = 0.02f
        fun gainFor(env: Float) = (LEVEL_TARGET / maxOf(env, 1e-4f)).coerceIn(1f, LEVEL_MAX_GAIN)
    }

    fun release() {
        if (handle != 0L) {
            RnnoiseNative.destroy(handle)
            handle = 0L
        }
    }
}
