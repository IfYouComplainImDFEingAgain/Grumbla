// Thin JNI bridge to libopus for the not-mumla audio pipeline.
//
// Mumble uses Opus at 48 kHz. We expose a mono encoder (VOIP application) and a mono decoder
// with packet-loss concealment, operating on 16-bit PCM frames. Pointers to the native
// OpusEncoder/OpusDecoder are passed back and forth as opaque jlongs.

#include <jni.h>
#include <opus.h>
#include <cstdint>
#include <vector>

extern "C" {

// ---- Encoder ----

JNIEXPORT jlong JNICALL
Java_app_notmumla_audio_codec_OpusNative_encoderCreate(
        JNIEnv *env, jclass, jint sampleRate, jint channels, jint bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == nullptr) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(0));        // unconstrained VBR -> best quality
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(10));           // max quality
    // No inband FEC: audio rides the reliable TCP tunnel (no loss), so FEC would only waste bits.
    return reinterpret_cast<jlong>(enc);
}

JNIEXPORT void JNICALL
Java_app_notmumla_audio_codec_OpusNative_encoderSetBitrate(
        JNIEnv *, jclass, jlong handle, jint bitrate) {
    auto *enc = reinterpret_cast<OpusEncoder *>(handle);
    if (enc) opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
}

// Returns number of bytes written to `out`, or negative on error.
JNIEXPORT jint JNICALL
Java_app_notmumla_audio_codec_OpusNative_encode(
        JNIEnv *env, jclass, jlong handle, jshortArray pcm, jint frameSize,
        jbyteArray out, jint maxOut) {
    auto *enc = reinterpret_cast<OpusEncoder *>(handle);
    if (!enc) return -1;
    jshort *pcmBuf = env->GetShortArrayElements(pcm, nullptr);
    jbyte *outBuf = env->GetByteArrayElements(out, nullptr);
    int written = opus_encode(enc, pcmBuf, frameSize,
                              reinterpret_cast<unsigned char *>(outBuf), maxOut);
    env->ReleaseShortArrayElements(pcm, pcmBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outBuf, 0);
    return written;
}

JNIEXPORT void JNICALL
Java_app_notmumla_audio_codec_OpusNative_encoderDestroy(JNIEnv *, jclass, jlong handle) {
    auto *enc = reinterpret_cast<OpusEncoder *>(handle);
    if (enc) opus_encoder_destroy(enc);
}

// ---- Decoder ----

JNIEXPORT jlong JNICALL
Java_app_notmumla_audio_codec_OpusNative_decoderCreate(
        JNIEnv *, jclass, jint sampleRate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == nullptr) return 0;
    return reinterpret_cast<jlong>(dec);
}

// Decode a packet (or NULL/0-length for packet-loss concealment). Returns samples per channel,
// or negative on error.
JNIEXPORT jint JNICALL
Java_app_notmumla_audio_codec_OpusNative_decode(
        JNIEnv *env, jclass, jlong handle, jbyteArray data, jint dataLen,
        jshortArray pcmOut, jint frameSize, jboolean fec) {
    auto *dec = reinterpret_cast<OpusDecoder *>(handle);
    if (!dec) return -1;
    jshort *pcmBuf = env->GetShortArrayElements(pcmOut, nullptr);
    int decoded;
    if (data == nullptr || dataLen <= 0) {
        decoded = opus_decode(dec, nullptr, 0, pcmBuf, frameSize, fec ? 1 : 0);
    } else {
        jbyte *dataBuf = env->GetByteArrayElements(data, nullptr);
        decoded = opus_decode(dec, reinterpret_cast<unsigned char *>(dataBuf), dataLen,
                              pcmBuf, frameSize, fec ? 1 : 0);
        env->ReleaseByteArrayElements(data, dataBuf, JNI_ABORT);
    }
    env->ReleaseShortArrayElements(pcmOut, pcmBuf, 0);
    return decoded;
}

JNIEXPORT void JNICALL
Java_app_notmumla_audio_codec_OpusNative_decoderDestroy(JNIEnv *, jclass, jlong handle) {
    auto *dec = reinterpret_cast<OpusDecoder *>(handle);
    if (dec) opus_decoder_destroy(dec);
}

} // extern "C"
