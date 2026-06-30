# Builds whisper.cpp (the offline speech engine) plus our small JNI bridge
# into one shared library: libwhisper_jni.so, which WhisperContext.kt loads.
#
# whisper.cpp is pulled straight from its official GitHub repo at configure
# time (FetchContent), so we don't vendor thousands of source files by hand.
# If the build can't reach GitHub or the tag is gone, bump WHISPER_TAG below.

cmake_minimum_required(VERSION 3.22)
project(whisper_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- SPEED ---
# This phone is a weak 32-bit ARM chip, so squeeze everything out of the engine.
# Android Studio's Run builds the DEBUG variant (optimization OFF by default),
# which made whisper take minutes. Force full optimization for every build type.
set(CMAKE_C_FLAGS_DEBUG     "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG   "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
# Extra vectorization + fast math, applied to every target including whisper.cpp.
add_compile_options(-O3 -ffast-math -funroll-loops -mfpu=neon-vfpv4)

include(FetchContent)

# Pin a known whisper.cpp release. Bump this if FetchContent fails on it.
set(WHISPER_TAG "v1.6.2")

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG ${WHISPER_TAG}
)

# Don't build whisper.cpp's own tests/examples, and disable -march=native
# (it breaks cross-compiling for the phone's ARM chip).
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni
    whisper      # the engine
    android
    log)
