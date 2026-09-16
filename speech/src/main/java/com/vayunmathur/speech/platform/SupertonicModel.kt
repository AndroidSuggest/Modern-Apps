package com.vayunmathur.speech.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.SupertonicSynthesizer
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the Supertonic 3 LiteRT bundle.
 *
 * Three quantized exports (ladder_v2 ship rungs: duration w4, textenc w8,
 * vocoder w8) plus the codepoint table and ten voice styles, mirrored under
 * `data.vayunmathur.com/tflite/supertonic/`. At ~45 MB this cannot
 * ship in the APK, so `:speech` downloads it on first use and `SupertonicEngine` prefers
 * the download directory, falling back to APK assets (which carry nothing in a fresh
 * install but keep side-loaded bundles working).
 */
object SupertonicModel {
    private const val BASE = "https://data.vayunmathur.com/tflite/supertonic/"
    const val DIR = "supertonic"

    /** The three plans + estimator, SHA-256 pinned at upload time. */
    val FILES: List<ModelDownloadItem> = listOf(
        item(
            "duration_w4.tflite",
            "305ca2b63b722fa8e3188face10f970511ebdf773aceee939d24c5980d889111",
        ),
        item(
            "textenc_w8.tflite",
            "ac4b3d83df4f6b5d39bf5b1c0c2314e46e242ca0f2f592e406c0ef0e27a63aa6",
        ),
        item(
            "estimator_w8.tflite",
            "03e4040d132170e8105b4695d52e4f4e7d1afcf1f490116887d1f582bf9359c9",
        ),
        item(
            "vocoder_w8.tflite",
            "385d87cea1e4832ccd7af50e213bf70351c43f6db16dd86aadf63c9c45c37b45",
        ),
        item(
            "unicode_indexer.json",
            "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
        ),
    )

    /** Voice styles ship in the APK (each ~290 KB); the four plans download. */
    val VOICES: List<ModelDownloadItem> = emptyList()

    /**
     * The ExecuTorch twins ([SupertonicSynthesizer.ET_GRAPHS]).
     *
     * Opportunistic, deliberately NOT in [FILES]: no mirror pins exist yet, and gating
     * first launch on unmirrored files would brick it. When a `.pte` with one of these
     * names lands next to the `.tflite` plans, the synthesizer picks it up on its own;
     * the gated download list stays the ship ladder.
     */
    val ET_FILES: List<String> = SupertonicSynthesizer.ET_GRAPHS

    private fun item(name: String, sha256: String?) =
        ModelDownloadItem("$BASE$name", "$DIR/$name", "Supertonic $name", sha256)

    /** Directory [SupertonicSynthesizer.inDirectory] loads from. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

    /** True once the four plans and the indexer are on disk. */
    fun isDownloaded(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return FILES.all { File(root, it.fileName).exists() }
    }

    /** Download any missing files (skips present ones); suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) =
        downloadModels(context, ds, FILES + VOICES)
}
