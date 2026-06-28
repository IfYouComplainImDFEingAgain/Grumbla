cmake_minimum_required(VERSION 3.22.1)
project(notmumla_audio C CXX)

# Build libopus (static) from the vendored source. Disable everything we don't need.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus)

add_library(opusjni SHARED opus_jni.cpp)
target_link_libraries(opusjni opus)
target_include_directories(opusjni PRIVATE opus/include)

# RNNoise (vendored, self-contained model) — built as a static lib from its C sources.
add_library(rnnoise STATIC
    rnnoise/src/celt_lpc.c
    rnnoise/src/denoise.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/pitch.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
    rnnoise/src/rnn_reader.c
)
target_include_directories(rnnoise PRIVATE rnnoise/src rnnoise/include)

add_library(rnnoisejni SHARED rnnoise_jni.cpp)
target_link_libraries(rnnoisejni rnnoise)
target_include_directories(rnnoisejni PRIVATE rnnoise/include)
