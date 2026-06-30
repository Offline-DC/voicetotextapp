package com.offlineinc.voicetotext

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * ON-DEVICE speech recognition. Records raw PCM from the mic and runs the
 * whisper.cpp `tiny.en` model locally — no server, works offline, free.
 *
 * Because it registers as a standard RecognitionService and returns text
 * directly (it never shows its own screen), TT9 draws its own
 * "loading / speak / turn off mic" UI — NOT a popup box.
 *
 * This is the offline twin of RemoteRecognitionService (the cloud one). Both
 * are installed; you pick which one TT9 uses with:
 *   settings put secure voice_recognition_service
 *       com.offlineinc.voicetotext/.LocalRecognitionService   (this one)
 *       com.offlineinc.voicetotext/.RemoteRecognitionService  (cloud)
 *
 * Press-to-start / press-to-stop:
 *   onStartListening -> start recording
 *   onStopListening  -> stop, transcribe on-device, return text
 *   60-second safety cap.
 */
class LocalRecognitionService : RecognitionService() {

    private val sampleRate = 16000
    @Volatile private var recording = false

    override fun onCreate() {
        super.onCreate()
        // Warm the model up in the background so the first transcription isn't
        // slowed down by the one-time copy + load.
        thread { try { getWhisper(this) } catch (e: Exception) {
            Log.e(TAG, "model warmup failed: ${e.message}") } }
    }

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        startRecording(callback)
    }

    override fun onStopListening(callback: Callback) {
        try { callback.endOfSpeech() } catch (_: Exception) {}
        recording = false   // the recording loop sees this and finishes
    }

    override fun onCancel(callback: Callback) {
        recording = false
    }

    private fun startRecording(callback: Callback) {
        if (recording) return
        recording = true
        try { callback.readyForSpeech(Bundle()) } catch (_: Exception) {}

        thread {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, sampleRate)   // at least ~1s buffer
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord init failed: ${e.message}")
                safeError(callback, SpeechRecognizer.ERROR_AUDIO)
                recording = false
                return@thread
            }

            val buf = ShortArray(minBuf.coerceAtLeast(1024))
            val chunks = ArrayList<ShortArray>()
            var total = 0
            val maxSamples = sampleRate * 60   // 60s cap

            try {
                recorder.startRecording()
                try { callback.beginningOfSpeech() } catch (_: Exception) {}
                while (recording && total < maxSamples) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) { chunks.add(buf.copyOf(n)); total += n }
                }
            } catch (e: Exception) {
                Log.e(TAG, "recording error: ${e.message}")
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
            }

            if (total == 0) { safeError(callback, SpeechRecognizer.ERROR_NO_MATCH); return@thread }

            // Convert 16-bit PCM shorts -> float [-1, 1] for whisper.
            val floats = FloatArray(total)
            var idx = 0
            for (c in chunks) for (s in c) floats[idx++] = s / 32768.0f

            try {
                val whisper = getWhisper(this)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                val t0 = System.currentTimeMillis()
                val text = whisper.transcribe(floats, threads).trim()
                val ms = System.currentTimeMillis() - t0
                Log.d(TAG, "on-device transcribe ${ms}ms | ${total / sampleRate}s audio | text: '$text'")
                val results = Bundle()
                results.putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)
                )
                try { callback.results(results) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed: ${e.message}")
                safeError(callback, SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun safeError(callback: Callback, code: Int) {
        try { callback.error(code) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "RemoteRecog"   // same tag so logcat -s RemoteRecog catches both
        private const val MODEL_ASSET = "ggml-tiny.en-q5_1.bin"

        @Volatile private var cached: WhisperContext? = null
        private val lock = Any()

        /** Copies the bundled model out of assets on first use, then loads it once. */
        private fun getWhisper(svc: LocalRecognitionService): WhisperContext {
            cached?.let { return it }
            synchronized(lock) {
                cached?.let { return it }
                val modelFile = File(svc.filesDir, MODEL_ASSET)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    svc.assets.open(MODEL_ASSET).use { input ->
                        modelFile.outputStream().use { input.copyTo(it) }
                    }
                }
                Log.d(TAG, "loading model: $MODEL_ASSET (${modelFile.length() / 1024 / 1024} MB)")
                return WhisperContext.fromFile(modelFile.absolutePath).also { cached = it }
            }
        }
    }
}
