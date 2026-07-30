package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.ncnn.Whisper

/**
 * Thin wrapper around the ncnn AAR [Whisper]: loads the bundled whisper-tiny model
 * **directly from the APK assets** (via [Whisper]'s AssetManager constructor — no
 * extraction) once, lazily, and transcribes 16 kHz mono PCM. Reused across recognition
 * sessions by [com.vayunmathur.speech.service.WhisperRecognitionService]. Not
 * thread-safe — call [transcribe] from a single worker thread.
 */
class WhisperEngine(private val context: Context) {

    private var whisper: Whisper? = null
    private var loadFailed = false

    /** Whether the bundled model assets are present. */
    fun isModelPresent(): Boolean = WhisperModel.isReady(context)

    /** Load the model now (e.g. to warm up off the main thread). */
    fun preload() { ensure() }

    @Synchronized
    private fun ensure(): Boolean {
        whisper?.let { return true }
        if (loadFailed) return false
        return try {
            val w = Whisper(context.assets, WhisperModel.DIR)
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
