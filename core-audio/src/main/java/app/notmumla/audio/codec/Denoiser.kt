package app.notmumla.audio.codec

/** JNI bindings to RNNoise (see src/main/cpp/rnnoise_jni.cpp). */
internal object RnnoiseNative {
    init { System.loadLibrary("rnnoisejni") }
    external fun create(): Long
    external fun frameSize(): Int
    external fun process(handle: Long, frame: ShortArray): Float
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

    /** Denoise [frame] in place; returns the voice-activity probability (0..1). */
    fun process(frame: ShortArray): Float =
        if (handle != 0L) RnnoiseNative.process(handle, frame) else 0f

    fun release() {
        if (handle != 0L) {
            RnnoiseNative.destroy(handle)
            handle = 0L
        }
    }
}
