package com.vayunmathur.translate.util

import android.util.Log

/**
 * JNI bridge to the native Rust translation / speech engine
 * (`libtranslate_engine.so`, built from `translate/src/main/rust/`).
 *
 * Mirrors the inert-safe loader pattern of `com.vayunmathur.pdf.util.PdfNative`
 * and `library:ocr`'s `OcrEngine`: the library is loaded once inside a
 * try/catch so a missing `.so` (e.g. an ABI with no build, or a debug run
 * without the cargo task) leaves [isAvailable] false and the app degrades
 * gracefully instead of crashing at class-load time.
 *
 * IMPORTANT (honesty): the native side is a **stub**. No model weights are
 * bundled and on-device neural translation (ncnn) / speech (whisper) must be
 * built and shipped separately, so [nativeLoadModel] returns 0 ("no model")
 * and [nativeTranslate] / [nativeDetectLanguage] return null. Consequently the
 * Kotlin engines below report "not installed" rather than inventing text. When a
 * real backend is dropped into the Rust crate these signatures do not change.
 *
 * All entry points are blocking and must be called off the main thread. Handles
 * returned by [nativeLoadModel] are opaque; 0 means "no model loaded".
 */
object TranslateNative {

    /** True if `libtranslate_engine.so` was found and loaded for this ABI. */
    val isAvailable: Boolean =
        try {
            System.loadLibrary("translate_engine")
            Log.i(TAG, "libtranslate_engine loaded")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "System.loadLibrary(translate_engine) failed", t)
            false
        }

    /**
     * Load a translation model bundle from [modelDir] and return an opaque
     * handle, or 0 if no usable model is present (the current stubbed default).
     */
    external fun nativeLoadModel(modelDir: String): Long

    /** Release the model behind [handle]. Safe to call with 0. */
    external fun nativeFreeModel(handle: Long)

    /** Detect the language of [text]; returns an ISO-639-1 code or null. */
    external fun nativeDetectLanguage(handle: Long, text: String): String?

    /**
     * Translate [text] from [from] (null = auto-detect) to [to]. Returns the
     * translation, or null when no model is loaded / translation is unavailable.
     */
    external fun nativeTranslate(handle: Long, text: String, from: String?, to: String): String?

    /**
     * Whether the native offline speech-to-text model is present. Stubbed to
     * false (no bundled whisper weights) so the Kotlin side falls back to the
     * platform `SpeechRecognizer`.
     */
    external fun nativeSpeechAvailable(): Boolean

    private const val TAG = "TranslateNative"
}
