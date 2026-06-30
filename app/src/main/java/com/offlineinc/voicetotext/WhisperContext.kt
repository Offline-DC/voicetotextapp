package com.offlineinc.voicetotext

/**
 * Thin Kotlin wrapper around the native whisper.cpp engine (libwhisper_jni.so).
 *
 * Holds a pointer to a loaded model and lets us transcribe 16 kHz mono float
 * audio entirely on-device. One model is loaded and reused (loading is the slow,
 * memory-heavy part), so we keep a single shared instance — see
 * LocalRecognitionService.getWhisper().
 */
class WhisperContext private constructor(private var ptr: Long) {

    /** samples: mono, 16 kHz, values in [-1, 1]. Returns the recognized text. */
    fun transcribe(samples: FloatArray, threads: Int): String {
        check(ptr != 0L) { "WhisperContext already released" }
        return nativeTranscribe(ptr, samples, threads)
    }

    fun release() {
        if (ptr != 0L) {
            nativeFree(ptr)
            ptr = 0L
        }
    }

    companion object {
        init { System.loadLibrary("whisper_jni") }

        /** Loads a ggml model file (e.g. ggml-tiny.en.bin) from an absolute path. */
        fun fromFile(modelPath: String): WhisperContext {
            val p = nativeInit(modelPath)
            require(p != 0L) { "whisper model failed to load: $modelPath" }
            return WhisperContext(p)
        }

        @JvmStatic external fun nativeInit(modelPath: String): Long
        @JvmStatic external fun nativeTranscribe(ptr: Long, samples: FloatArray, threads: Int): String
        @JvmStatic external fun nativeFree(ptr: Long)
    }
}
