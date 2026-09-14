package com.vayunmathur.speech.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.SupertonicSynthesizer
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the Supertonic 3 ONNX bundle.
 *
 * Four upstream exports (`Supertone/supertonic-3`) plus the codepoint table and ten voice
 * styles, mirrored under `data.vayunmathur.com/models/supertonic/`. At ~395 MB this cannot
 * ship in the APK, so `:speech` downloads it on first use and `SupertonicEngine` prefers
 * the download directory, falling back to APK assets (which carry nothing in a fresh
 * install but keep side-loaded bundles working).
 */
object SupertonicModel {
    private const val BASE = "https://data.vayunmathur.com/models/supertonic/"
    const val DIR = "supertonic"

    /** The four plans, SHA-256 pinned at upload time. */
    val FILES: List<ModelDownloadItem> = listOf(
        item(
            "duration_predictor.onnx",
            "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
        ),
        item(
            "text_encoder.onnx",
            "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
        ),
        item(
            "vector_estimator.onnx",
            "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c",
        ),
        item(
            "vocoder.onnx",
            "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba",
        ),
        item(
            "unicode_indexer.json",
            "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f",
        ),
    )

    /** Voice styles ship in the APK (each ~290 KB); the four plans download. */
    val VOICES: List<ModelDownloadItem> = emptyList()

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
