package com.vayunmathur.speech.util

import android.content.Context

/**
 * The offline **Whisper tiny** (multilingual, ~99 languages) speech model, converted to
 * ncnn by nihui, bundled in the app's assets under `assets/`[DIR] and loaded **directly
 * from the APK** by the ncnn AAR's `Whisper(assetManager, DIR)` — no extraction. The
 * assets are `noCompress` (see build.gradle) so ncnn mmaps them.
 *
 * The 13 files are committed with the app so the APK bundles them directly (no download).
 * `scripts/speech/fetch_whisper_model.sh` can re-fetch them from the nihui release if ever
 * needed.
 */
object WhisperModel {
    const val DIR = "whisper-tiny"

    /** True if the bundled model assets are present. */
    fun isReady(context: Context): Boolean =
        (context.assets.list(DIR)?.size ?: 0) >= 13
}
