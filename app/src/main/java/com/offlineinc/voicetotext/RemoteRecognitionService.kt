package com.offlineinc.voicetotext

import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud speech recognition: records COMPRESSED audio (AAC in an .m4a) on the
 * phone, uploads it to our faster-whisper server on Heroku, and returns the text.
 * Registers as a RecognitionService, so TT9 uses it once it's set as the device's
 * default recognizer — no TT9 changes.
 *
 * Why compressed: AAC (~24 kbps) is roughly 10x smaller than raw WAV, so the
 * upload is far faster on weak cellular — which the logs showed was the
 * bottleneck (server was <1s, network was ~8s).
 *
 * Press-to-start / press-to-stop:
 *   - onStartListening -> start recording
 *   - onStopListening  -> stop, upload, return text
 *   - 60-second max-duration safety cap
 */
class RemoteRecognitionService : RecognitionService() {

    // ── Your deployed server. Change this if the app name ever changes. ──
    private val serverUrl = "https://your-voice-stt-ce837e1e5d92.herokuapp.com/transcribe"

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    @Volatile private var recording = false
    private var activeCallback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        if (recording) return
        activeCallback = callback

        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = newRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(24000)
            rec.setMaxDuration(60_000)           // safety cap
            rec.setOutputFile(file.absolutePath)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    activeCallback?.let { stopAndUpload(it) }
                }
            }
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed: ${e.message}")
            try { rec.release() } catch (_: Exception) {}
            file.delete()
            try { callback.error(SpeechRecognizer.ERROR_AUDIO) } catch (_: Exception) {}
            return
        }

        recorder = rec
        outFile = file
        recording = true
        try { callback.readyForSpeech(Bundle()) } catch (_: Exception) {}
        try { callback.beginningOfSpeech() } catch (_: Exception) {}
    }

    override fun onStopListening(callback: Callback) {
        try { callback.endOfSpeech() } catch (_: Exception) {}
        stopAndUpload(callback)
    }

    override fun onCancel(callback: Callback) {
        recording = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        outFile?.delete()
        outFile = null
        activeCallback = null
    }

    @Synchronized
    private fun stopAndUpload(callback: Callback) {
        if (!recording) return
        recording = false

        val rec = recorder
        recorder = null
        val file = outFile
        outFile = null

        try {
            rec?.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws if the clip was too short (< ~1s).
            Log.e(TAG, "stop failed (clip too short?): ${e.message}")
            try { rec?.release() } catch (_: Exception) {}
            file?.delete()
            try { callback.error(SpeechRecognizer.ERROR_NO_MATCH) } catch (_: Exception) {}
            return
        }
        try { rec?.release() } catch (_: Exception) {}

        if (file == null || !file.exists() || file.length() == 0L) {
            try { callback.error(SpeechRecognizer.ERROR_NO_MATCH) } catch (_: Exception) {}
            return
        }

        // Network must not run on the main thread.
        Thread { uploadAndReturn(file, callback) }.start()
    }

    private fun uploadAndReturn(file: File, callback: Callback) {
        try {
            val audio = file.readBytes()
            val startMs = System.currentTimeMillis()
            val body = postAudio(audio)
            val totalMs = System.currentTimeMillis() - startMs
            val json = JSONObject(body)
            val text = json.optString("text", "").trim()
            val serverSecs = json.optDouble("seconds", -1.0)
            // upload size + round-trip (upload+server+download) + server-only time.
            Log.d(TAG, "upload ${audio.size}B | round-trip ${totalMs}ms | server ${serverSecs}s | text: '$text'")
            val results = Bundle()
            results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            callback.results(results)
        } catch (e: Exception) {
            Log.e(TAG, "upload failed: ${e.message}")
            try { callback.error(SpeechRecognizer.ERROR_NETWORK) } catch (_: Exception) {}
        } finally {
            file.delete()
        }
    }

    /** Sends the audio file as multipart/form-data; returns the raw JSON body. */
    private fun postAudio(audio: ByteArray): String {
        val boundary = "----vtt${System.currentTimeMillis()}"
        val conn = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 45000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\n")
            out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
            out.write(audio)
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: $body")
        return body
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    companion object { private const val TAG = "RemoteRecog" }
}
