package app.notmumla.audio.codec

import app.notmumla.audio.AudioConstants

/**
 * Mono Opus encoder for the mic path. 48 kHz, VBR, VOIP-tuned. Not thread-safe; use from one thread.
 */
class OpusEncoder(
    sampleRate: Int = AudioConstants.SAMPLE_RATE,
    channels: Int = AudioConstants.CHANNELS,
    bitrate: Int = 72_000,
) {
    private var handle = OpusNative.encoderCreate(sampleRate, channels, bitrate)
    private val scratch = ByteArray(MAX_PACKET)

    init {
        require(handle != 0L) { "Failed to create Opus encoder" }
    }

    fun setBitrate(bitrate: Int) {
        if (handle != 0L) OpusNative.encoderSetBitrate(handle, bitrate)
    }

    /** Encode one PCM frame; returns the Opus packet bytes, or null on error. */
    fun encode(pcm: ShortArray, frameSize: Int): ByteArray? {
        if (handle == 0L) return null
        val n = OpusNative.encode(handle, pcm, frameSize, scratch, scratch.size)
        return if (n > 0) scratch.copyOf(n) else null
    }

    fun release() {
        if (handle != 0L) {
            OpusNative.encoderDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        const val MAX_PACKET = 4000
    }
}

/**
 * Mono Opus decoder for one remote speaker, with packet-loss concealment.
 */
class OpusDecoder(
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val channels: Int = AudioConstants.CHANNELS,
) {
    private var handle = OpusNative.decoderCreate(sampleRate, channels)

    init {
        require(handle != 0L) { "Failed to create Opus decoder" }
    }

    /** Decode a packet into [pcmOut]; returns samples-per-channel decoded, or -1 on error. */
    fun decode(packet: ByteArray?, pcmOut: ShortArray, frameSize: Int): Int {
        if (handle == 0L) return -1
        return OpusNative.decode(handle, packet, packet?.size ?: 0, pcmOut, frameSize, false)
    }

    /**
     * Recover a lost frame from the *next* packet's embedded forward-error-correction data.
     * Falls back to packet-loss concealment internally if the packet carries no FEC.
     */
    fun decodeFec(nextPacket: ByteArray, pcmOut: ShortArray, frameSize: Int): Int {
        if (handle == 0L) return -1
        return OpusNative.decode(handle, nextPacket, nextPacket.size, pcmOut, frameSize, true)
    }

    /** Conceal one lost frame (no packet available). */
    fun concealLoss(pcmOut: ShortArray, frameSize: Int): Int {
        if (handle == 0L) return -1
        return OpusNative.decode(handle, null, 0, pcmOut, frameSize, false)
    }

    fun release() {
        if (handle != 0L) {
            OpusNative.decoderDestroy(handle)
            handle = 0L
        }
    }
}
