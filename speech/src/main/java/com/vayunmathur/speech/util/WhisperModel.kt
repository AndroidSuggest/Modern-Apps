package com.vayunmathur.speech.util

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.WhisperHandle
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the offline **whisper-base** (multilingual, ~99 languages)
 * recogniser.
 *
 * One int8 LiteRT export (ladder_v2 ship rung, byte-identical to the analysis copy)
 * plus the tokenizer and generation config, mirrored under
 * `data.vayunmathur.com/tflite/whisper/`. At ~77 MB this stays out of the APK to keep it
 * small. The small `vocab.json`/`generation_config.json` still ship in APK assets (they are
 * kilobytes and version with the app).
 */
object WhisperModel {
    private const val BASE = "https://data.vayunmathur.com/tflite/whisper/"
    const val DIR = "whisper"

    /** Asset directory holding the small config files. */
    const val ASSET_DIR = "whisper-base"

    const val MODEL = "base_30s_i8.tflite"

    /**
     * The ExecuTorch candidates ([WhisperHandle.ET_MODEL], [WhisperHandle.ET_MODEL_FALLBACK],
     * [WhisperHandle.ET_MODEL_LEGACY]).
     *
     * Opportunistic, deliberately NOT in [FILES]: no mirror pins exist yet, and gating
     * first launch on an unmirrored 196 MB file would brick it. When a `.pte` with one of these
     * names lands next to the `.tflite`, the handle picks it up on its own; the gated
     * download list stays the ship rung.
     */
    val ET_MODELS: List<String> =
        listOf(WhisperHandle.ET_MODEL, WhisperHandle.ET_MODEL_FALLBACK, WhisperHandle.ET_MODEL_LEGACY)

    /** The export, SHA-256 pinned. */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem(
            "${BASE}base_30s_i8.tflite",
            "$DIR/base_30s_i8.tflite",
            "Whisper base",
            "f6943d9d293138850b729e074057956c664891c57837692b1bac4608c4506cd1",
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
     * True if the recogniser can run: download present, or the legacy bundled asset.
     */
    fun isReady(context: Context): Boolean =
        isDownloaded(context) || try {
            val entries = context.assets.list(ASSET_DIR)?.toList().orEmpty()
            entries.contains(MODEL)
        } catch (_: Throwable) {
            false
        }
}
