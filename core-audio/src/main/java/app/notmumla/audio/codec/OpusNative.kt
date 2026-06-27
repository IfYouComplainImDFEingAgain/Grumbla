package app.notmumla.audio.codec

/** JNI bindings to libopus (see src/main/cpp/opus_jni.cpp). */
internal object OpusNative {
    init {
        System.loadLibrary("opusjni")
    }

    external fun encoderCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    external fun encoderSetBitrate(handle: Long, bitrate: Int)
    external fun encode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxOut: Int): Int
    external fun encoderDestroy(handle: Long)

    external fun decoderCreate(sampleRate: Int, channels: Int): Long
    external fun decode(handle: Long, data: ByteArray?, dataLen: Int, pcmOut: ShortArray, frameSize: Int, fec: Boolean): Int
    external fun decoderDestroy(handle: Long)
}
