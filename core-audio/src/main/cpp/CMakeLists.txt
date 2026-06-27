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
