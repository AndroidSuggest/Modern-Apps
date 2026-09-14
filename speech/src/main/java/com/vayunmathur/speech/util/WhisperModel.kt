package com.vayunmathur.speech.util

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the offline **whisper-base** (multilingual, ~99 languages)
 * recogniser.
 *
 * Two int8 ONNX exports (`onnx-community/whisper-base`, SHA-verified byte-identical to the
 * copies that shipped in the APK) plus the tokenizer and generation config, mirrored under
 * `data.vayunmathur.com/models/whisper/`. At ~77 MB this stays out of the APK to keep it
 * small. The small `vocab.json`/`generation_config.json` still ship in APK assets (they are
 * kilobytes and version with the app).
 */
object WhisperModel {
    private const val BASE = "https://data.vayunmathur.com/models/whisper/"
    const val DIR = "whisper"

    /** Asset directory holding the small config files. */
    const val ASSET_DIR = "whisper-base"

    const val ENCODER = "encoder_model_int8.onnx"
    const val DECODER = "decoder_model_merged_int8.onnx"

    /** The two exports, SHA-256 pinned (byte-identical to upstream). */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem(
            "${BASE}encoder_model_int8.onnx",
            "$DIR/encoder_model_int8.onnx",
            "Whisper encoder",
            "ca6177401f86a2c6b4dc5f7fc02fbca680678906bd0c22f6d89f0b80f124253f",
        ),
        ModelDownloadItem(
            "${BASE}decoder_model_merged_int8.onnx",
            "$DIR/decoder_model_merged_int8.onnx",
            "Whisper decoder",
            "fa3ef9902734ce5ae6f9ef2bdb2ba9a6c4b5785b09f4f420ce036573dc9d090b",
        ),
    )

    /** Directory [com.vayunmathur.library.ml.WhisperHandle.inDirectory] loads from. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

    /** True once both exports are on disk. */
    fun isDownloaded(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return FILES.all { File(root, it.fileName).exists() }
    }

    /** Download any missing files (skips present ones); suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) =
        downloadModels(context, ds, FILES)

    const val VOCAB = "vocab.json"
    const val GEN_CONFIG = "generation_config.json"

    /**
     * True if the recogniser can run: downloads present, or the legacy bundled assets.
     */
    fun isReady(context: Context): Boolean =
        isDownloaded(context) || try {
            val entries = context.assets.list(ASSET_DIR)?.toList().orEmpty()
            entries.contains(ENCODER) && entries.contains(DECODER)
        } catch (_: Throwable) {
            false
        }
}
