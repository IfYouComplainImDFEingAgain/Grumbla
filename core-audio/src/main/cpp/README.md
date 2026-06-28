# Native audio (libopus + RNNoise + JNI)

`opus_jni.cpp` / `rnnoise_jni.cpp` are thin JNI bridges to **libopus** (Opus codec) and **RNNoise**
(ML noise suppression). Both libraries' sources are vendored but **not** checked in (third-party).

Before building the `:core-audio` module, fetch both into `cpp/`:

```sh
# Opus
git clone --depth 1 --branch v1.5.2 https://github.com/xiph/opus.git \
  core-audio/src/main/cpp/opus
rm -rf core-audio/src/main/cpp/opus/.git

# RNNoise — pinned to the classic self-contained model (commit bad0a75, before the
# nnet.c rewrite / model-download split). Copy only src + include.
git clone https://github.com/xiph/rnnoise.git /tmp/rnnoise && \
  git -C /tmp/rnnoise checkout bad0a75 && \
  mkdir -p core-audio/src/main/cpp/rnnoise/src core-audio/src/main/cpp/rnnoise/include && \
  cp /tmp/rnnoise/include/rnnoise.h core-audio/src/main/cpp/rnnoise/include/ && \
  cp /tmp/rnnoise/src/*.c /tmp/rnnoise/src/*.h core-audio/src/main/cpp/rnnoise/src/ && \
  rm -f core-audio/src/main/cpp/rnnoise/src/dump_features.c && rm -rf /tmp/rnnoise
```

The Gradle `externalNativeBuild` compiles each as a static lib and links them into `libopusjni.so`
and `librnnoisejni.so` for `arm64-v8a` and `x86_64`. Requires NDK r27 (`ndkVersion` in
`core-audio/build.gradle.kts`) and CMake 3.22.1.
