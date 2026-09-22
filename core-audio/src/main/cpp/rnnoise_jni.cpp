// JNI bridge to RNNoise (RNN-based noise suppression). Operates on 48 kHz, 480-sample (10 ms)
// frames — exactly the not-mumla capture frame — so no resampling/reframing is needed.

#include <jni.h>
#include <rnnoise.h>
#include <cmath>
#include <vector>

namespace {
// RNNoise's output lags its input by exactly one frame (it analyses [previous, current] and emits
// the overlap-added previous frame). The dry/wet blend must use the *delayed* dry signal, or the two
// paths comb-filter (notches every 100 Hz) and partially cancel — voice comes out thin and quiet.
struct Denoiser {
    DenoiseState *st;
    std::vector<float> prevDry;
};
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_notmumla_audio_codec_RnnoiseNative_create(JNIEnv *, jclass) {
    DenoiseState *st = rnnoise_create(nullptr); // built-in model
    if (!st) return 0;
    return reinterpret_cast<jlong>(new Denoiser{st, std::vector<float>(rnnoise_get_frame_size(), 0.f)});
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
Java_app_notmumla_audio_codec_RnnoiseNative_process(JNIEnv *env, jclass, jlong handle, jshortArray frame, jfloat mix) {
    auto *d = reinterpret_cast<Denoiser *>(handle);
    if (!d) return 0.f;
    const jsize n = env->GetArrayLength(frame);
    if (static_cast<size_t>(n) != d->prevDry.size()) return 0.f;
    jshort *s = env->GetShortArrayElements(frame, nullptr);

    std::vector<float> in(static_cast<size_t>(n)), buf(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) in[i] = static_cast<float>(s[i]);

    const float vad = rnnoise_process_frame(d->st, buf.data(), in.data());

    // Blend denoised (buf) with the one-frame-delayed original by [mix]: 1.0 = full RNNoise.
    const float dry = 1.0f - mix;
    for (jsize i = 0; i < n; i++) {
        long v = lrintf(d->prevDry[i] * dry + buf[i] * mix);
        if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
        s[i] = static_cast<jshort>(v);
    }
    d->prevDry.swap(in);
    env->ReleaseShortArrayElements(frame, s, 0);
    return vad;
}

JNIEXPORT void JNICALL
Java_app_notmumla_audio_codec_RnnoiseNative_destroy(JNIEnv *, jclass, jlong handle) {
    auto *d = reinterpret_cast<Denoiser *>(handle);
    if (!d) return;
    rnnoise_destroy(d->st);
    delete d;
}

} // extern "C"
