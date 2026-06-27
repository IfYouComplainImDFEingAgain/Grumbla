package app.notmumla.protocol

/** Mumble protocol constants. The control channel listens on TCP/UDP 64738 by default. */
object Mumble {
    const val DEFAULT_PORT = 64738

    /** Opus operates at 48 kHz; Mumble frames audio in 10 ms chunks (480 samples). */
    const val SAMPLE_RATE = 48_000
    const val FRAME_MS = 10
}
