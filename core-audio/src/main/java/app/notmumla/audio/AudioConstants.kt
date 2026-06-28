package app.notmumla.audio

/** Audio pipeline constants shared by capture/codec/playback. */
object AudioConstants {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    /** 10 ms Opus frame at 48 kHz = 480 samples. */
    const val FRAME_SAMPLES = 480

    /** Largest Opus frame (60 ms at 48 kHz) — decode buffers must hold any incoming frame size. */
    const val MAX_FRAME_SAMPLES = 2880
}
