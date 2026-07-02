package app.notmumla.audio

import kotlin.math.log10
import kotlin.math.max

/**
 * Voice-activity detector. A peak-follow envelope smooths the frame level; it's then compared to
 * either a fixed [manualThreshold] or, when that's null, a threshold that continuously adapts to the
 * running **noise floor** and **speech level** — so sensitivity tracks the room/mic automatically.
 *
 * Not thread-safe; use from the capture thread only.
 */
class AdaptiveVad {
    private var envelope = 0f
    private var noiseFloor = 0.003f
    private var speechLevel = 0.03f

    fun reset() {
        envelope = 0f
        noiseFloor = 0.003f
        speechLevel = 0.03f
    }

    /** @param manualThreshold fixed RMS threshold, or null for automatic. Returns whether to transmit. */
    fun shouldTransmit(level: Float, manualThreshold: Float?): Boolean {
        envelope = max(level, envelope * ENV_DECAY)
        return envelope >= (manualThreshold ?: adapt(level))
    }

    private fun adapt(level: Float): Float {
        // Noise floor: fall toward quiet quickly, rise very slowly, so brief speech doesn't inflate it.
        val rate = if (level < noiseFloor) NOISE_FALL else NOISE_RISE
        noiseFloor = (noiseFloor + (level - noiseFloor) * rate).coerceIn(1e-4f, 0.1f)
        // Speech level: peak-follow while clearly above the floor; otherwise decay back toward it.
        speechLevel = if (level > noiseFloor * SPEECH_TRIGGER) max(level, speechLevel * SPEECH_DECAY)
        else speechLevel * SPEECH_DECAY
        speechLevel = speechLevel.coerceIn(noiseFloor * 1.5f, 1f)

        val nDb = 20f * log10(max(noiseFloor, 1e-5f))
        val sDb = 20f * log10(max(speechLevel, 1e-5f))
        // Sit ~1/3 up from the noise floor toward speech; if no clear speech yet, 9 dB above the floor.
        val tDb = if (sDb - nDb > 8f) nDb + (sDb - nDb) * 0.35f else nDb + 9f
        return Math.pow(10.0, (tDb / 20f).toDouble()).toFloat().coerceIn(0.0008f, 0.15f)
    }

    private companion object {
        const val ENV_DECAY = 0.96f      // envelope hangover (~matches VAD_ENV_DECAY)
        const val NOISE_FALL = 0.3f      // track down to quiet quickly
        const val NOISE_RISE = 0.0005f   // rise very slowly (don't chase speech)
        const val SPEECH_TRIGGER = 2f    // level > 2x noise floor counts as speech
        const val SPEECH_DECAY = 0.999f  // speech-level estimate decays slowly
    }
}
