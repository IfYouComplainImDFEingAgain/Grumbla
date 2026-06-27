package app.notmumla.audio

/** Audio pipeline constants shared by capture/codec/playback. */
object AudioConstants {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    /** 10 ms Opus frame at 48 kHz = 480 samples. */
    const val FRAME_SAMPLES = 480
}
