// JNI bridge between WhisperContext.kt and the whisper.cpp engine.
// Three calls: init a model from a file, transcribe a chunk of audio, free it.
// Audio in is mono 16 kHz float samples in the range [-1, 1].

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jlong JNICALL
Java_com_offlineinc_voicetotext_WhisperContext_nativeInit(
        JNIEnv* env, jclass, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;                 // CPU only on this phone

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file failed");
        return 0;
    }
    LOGI("model loaded");
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_offlineinc_voicetotext_WhisperContext_nativeTranscribe(
        JNIEnv* env, jclass, jlong ptr, jfloatArray samples, jint nThreads) {
    auto* ctx = reinterpret_cast<whisper_context*>(ptr);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(samples);
    std::vector<float> audio(n);
    env->GetFloatArrayRegion(samples, 0, n, audio.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.translate        = false;
    params.language         = "en";
    params.n_threads        = nThreads > 0 ? nThreads : 4;
    params.no_context       = true;
    params.single_segment   = true;   // voice input is short -> one segment, faster
    // Temperature fallback stays OFF (single greedy pass). It's whisper's "proper"
    // fix for repetition loops, but on this slow 32-bit phone it re-decodes the clip
    // at up to 6 rising temperatures and turned a ~1-2s transcription into ~19s —
    // unusable. Loops are instead cleaned up cheaply after the fact in
    // LocalRecognitionService.collapseRepeats().
    params.temperature_inc  = 0.0f;
    // Scale the encoder window to the ACTUAL clip length (50 ctx units/sec of
    // 16 kHz audio) so we never encode silent padding. Biggest speed lever.
    int dyn_ctx = (int)((double)n / 16000.0 * 50.0) + 32;
    if (dyn_ctx > 1500) dyn_ctx = 1500;
    if (dyn_ctx < 128)  dyn_ctx = 128;
    params.audio_ctx        = dyn_ctx;
    // Bound runaway repetition loops ("hello hello hello…"): cap tokens well
    // above any real speech rate but far below a hundreds-long loop.
    params.max_tokens       = (n / 16000) * 16 + 32;

    if (whisper_full(ctx, params, audio.data(), n) != 0) {
        LOGE("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string result;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        result += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_offlineinc_voicetotext_WhisperContext_nativeFree(
        JNIEnv* env, jclass, jlong ptr) {
    if (ptr != 0) {
        whisper_free(reinterpret_cast<whisper_context*>(ptr));
    }
}
