# Native audio (libopus + JNI)

`opus_jni.cpp` is a thin JNI bridge to **libopus**, built via `CMakeLists.txt` against a vendored
copy of the Opus source that is **not** checked in (it's large and third-party).

Before building the `:core-audio` module, fetch the Opus source into `opus/`:

```sh
git clone --depth 1 --branch v1.5.2 https://github.com/xiph/opus.git \
  core-audio/src/main/cpp/opus
rm -rf core-audio/src/main/cpp/opus/.git
```

The Gradle `externalNativeBuild` then compiles libopus as a static lib and links it into
`libopusjni.so` for `arm64-v8a` and `x86_64`. Requires NDK r27 (`ndkVersion` in
`core-audio/build.gradle.kts`) and CMake 3.22.1.
