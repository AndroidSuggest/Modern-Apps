package com.vayunmathur.translate.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device translation, behind a clean interface so a real neural backend can
 * be dropped in without touching the UI. All calls are safe to invoke from a
 * coroutine and run off the main thread.
 */
interface TranslationEngine {
    /** True if a translation model is loaded and translation will actually work. */
    suspend fun isAvailable(): Boolean

    /** Best-effort ISO-639-1 language code for [text], or null if unknown. */
    suspend fun detectLanguage(text: String): String?

    /**
     * Translate [text] to [to]. [from] is the source language code, or null to
     * auto-detect. Returns null if the engine is unavailable (caller shows a
     * "model not installed" state) — never a fabricated translation.
     */
    suspend fun translate(text: String, from: String?, to: String): String?
}

/**
 * [TranslationEngine] backed by [TranslateNative]. Loads a model bundle from the
 * app's files dir once; because no weights are bundled the native load returns a
 * 0 handle, so [isAvailable] is false and the UI reports "not installed". This is
 * intentional and honest — see [TranslateNative].
 */
class NativeTranslator(private val context: Context) : TranslationEngine {

    private val lock = Mutex()
    private var handle: Long = 0L
    private var initTried = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        lock.withLock { ensureModel() }
    }

    override suspend fun detectLanguage(text: String): String? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        lock.withLock {
            if (!ensureModel()) return@withContext null
            try {
                TranslateNative.nativeDetectLanguage(handle, text)
            } catch (t: Throwable) {
                Log.e(TAG, "detectLanguage failed", t)
                null
            }
        }
    }

    override suspend fun translate(text: String, from: String?, to: String): String? =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext ""
            lock.withLock {
                if (!ensureModel()) return@withContext null
                try {
                    TranslateNative.nativeTranslate(handle, text, from, to)
                } catch (t: Throwable) {
                    Log.e(TAG, "translate failed", t)
                    null
                }
            }
        }

    /** Free the native model. Safe to call more than once. */
    fun close() {
        if (handle != 0L) {
            try {
                TranslateNative.nativeFreeModel(handle)
            } catch (_: Throwable) {
            }
            handle = 0L
        }
        initTried = false
    }

    /** Try to load the model exactly once; returns whether a model is loaded. */
    private fun ensureModel(): Boolean {
        if (handle != 0L) return true
        if (initTried) return false
        initTried = true
        if (!TranslateNative.isAvailable) return false
        return try {
            // Models would live under filesDir/translate_models once a real
            // backend + downloader exist. Absent weights -> native returns 0.
            val dir = File(context.filesDir, MODEL_DIR).absolutePath
            handle = TranslateNative.nativeLoadModel(dir)
            handle != 0L
        } catch (t: Throwable) {
            Log.e(TAG, "nativeLoadModel failed", t)
            handle = 0L
            false
        }
    }

    companion object {
        private const val TAG = "NativeTranslator"
        private const val MODEL_DIR = "translate_models"
    }
}
