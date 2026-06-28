// JNI bridge to RNNoise (RNN-based noise suppression). Operates on 48 kHz, 480-sample (10 ms)
// frames — exactly the not-mumla capture frame — so no resampling/reframing is needed.

#include <jni.h>
#include <rnnoise.h>
#include <cmath>
#include <vector>

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_notmumla_audio_codec_RnnoiseNative_create(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(rnnoise_create(nullptr)); // built-in model
}

JNIEXPORT jint JNICALL
Java_app_notmumla_audio_codec_RnnoiseNative_frameSize(JNIEnv *, jclass) {
    return rnnoise_get_frame_size();
}

/**
 * Denoise one frame in place. [frame] holds rnnoise_get_frame_size() 16-bit samples. RNNoise works
 * on float samples in the int16 magnitude range. Returns the voice-activity probability (0..1).
 */
JNIEXPORT jfloat JNICALL
Java_app_notmumla_audio_codec_RnnoiseNative_process(JNIEnv *env, jclass, jlong handle, jshortArray frame) {
    auto *st = reinterpret_cast<DenoiseState *>(handle);
    if (!st) return 0.f;
    const jsize n = env->GetArrayLength(frame);
    jshort *s = env->GetShortArrayElements(frame, nullptr);

    std::vector<float> buf(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) buf[i] = static_cast<float>(s[i]);

    const float vad = rnnoise_process_frame(st, buf.data(), buf.data());

    for (jsize i = 0; i < n; i++) {
        long v = lrintf(buf[i]);
        if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
        s[i] = static_cast<jshort>(v);
    }
    env->ReleaseShortArrayElements(frame, s, 0);
    return vad;
}

JNIEXPORT void JNICALL
Java_app_notmumla_audio_codec_RnnoiseNative_destroy(JNIEnv *, jclass, jlong handle) {
    if (handle) rnnoise_destroy(reinterpret_cast<DenoiseState *>(handle));
}

} // extern "C"
