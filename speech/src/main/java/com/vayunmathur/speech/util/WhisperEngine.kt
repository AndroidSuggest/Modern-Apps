package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.ncnn.Whisper

/**
 * Thin wrapper around the ncnn AAR [Whisper]: loads the whisper-tiny model from the
 * runtime-downloaded files (see [WhisperModel]) via [Whisper]'s filesystem-directory
 * constructor, once and lazily, and transcribes 16 kHz mono PCM. Reused across recognition
 * sessions by [com.vayunmathur.speech.service.WhisperRecognitionService]. Not thread-safe —
 * call [transcribe] from a single worker thread.
 */
class WhisperEngine(private val context: Context) {

    private var whisper: Whisper? = null
    private var loadFailed = false

    /** Whether the downloaded model files are present. */
    fun isModelPresent(): Boolean = WhisperModel.isReady(context)

    /** Load the model now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = ensure()

    @Synchronized
    private fun ensure(): Boolean {
        whisper?.let { return true }
        if (loadFailed) return false
        // Don't try to load a partially-downloaded model; wait until all files are present.
        if (!isModelPresent()) return false
        return try {
            val w = Whisper(WhisperModel.modelDir(context).absolutePath)
            if (w.isAvailable) { whisper = w; true } else { w.close(); loadFailed = true; false }
        } catch (t: Throwable) {
            Log.e(TAG, "Whisper load failed", t)
            loadFailed = true
            false
        }
    }

    /**
     * Transcribe [pcm16k] (16 kHz mono). [language] is ISO-639-1 or null/"auto" for
     * automatic detection. Returns the text, or null if the model isn't ready/failed.
     */
    fun transcribe(pcm16k: ShortArray, language: String?): String? {
        if (!ensure()) return null
        return try {
            whisper?.transcribe(pcm16k, language)
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            null
        }
    }

    fun close() {
        whisper?.close()
        whisper = null
    }

    companion object {
        private const val TAG = "WhisperEngine"
    }
}
