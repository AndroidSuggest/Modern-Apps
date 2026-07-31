package com.vayunmathur.speech.util

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the offline **Whisper-tiny** (multilingual, ~99 languages)
 * ncnn model. The 13 files are fetched from the self-hosted mirror into
 * `getExternalFilesDir()/`[DIR] and loaded from the filesystem by the ncnn AAR's
 * `Whisper(dirPath)` — nothing ships in the APK.
 *
 * Mirror layout (upload the nihui/ncnn-android-whisper "models" release files under these
 * paths; `scripts/speech/fetch_whisper_model.sh` stages them for upload):
 *   https://data.vayunmathur.com/models/whisper-tiny/<file>
 */
object WhisperModel {
    const val DIR = "whisper-tiny"
    private const val BASE = "https://data.vayunmathur.com/models/whisper-tiny/"

    /** The 13 runtime files, SHA-256 pinned (supply-chain mitigation, verified on download). */
    val FILES: List<ModelDownloadItem> = listOf(
        item("whisper_tiny_decoder.ncnn.bin", "8b5be3db26af79c8d0408e7a07affae07e5464af3e9f5cf1eba503b63cab4dec"),
        item("whisper_tiny_decoder.ncnn.param", "371cc62f8ea86d24ee170732e848dfc9a15a16ba7a3e22a175cb3ceefac3757b"),
        item("whisper_tiny_embed_position.ncnn.bin", "02d1ce594b3917847f0cc333337af4999787bae77823d0655fd5df40177a64f9"),
        item("whisper_tiny_embed_position.ncnn.param", "b5fbfba5ea5c294258bf5cb9f4b6704f459099921e2cdbd1dfdef2c0c14085b4"),
        item("whisper_tiny_embed_token.ncnn.bin", "e33c7db8ebb73eafc2cc791ae77b0e710c9cf54bd14321ae28277cb86b0f4968"),
        item("whisper_tiny_embed_token.ncnn.param", "c1917f8b90e81cede6fe8b82cab7d05d7d6461f9ff04ad58932bd7a62ec38627"),
        item("whisper_tiny_encoder.ncnn.bin", "14b8d453780ca0df7971d12134b4826bb6206624e4217236204183f793dae74a"),
        item("whisper_tiny_encoder.ncnn.param", "675a3141db1ed515f1d08d722ddeb31e2a556b1a7baf35c3dd14a2adf575c058"),
        item("whisper_tiny_fbank.ncnn.bin", "2150c30cbbeb6029f52002ffa666c1c72d83dbf53f463cb8462052055806e891"),
        item("whisper_tiny_fbank.ncnn.param", "9ff0d0da904ea62c9c4d1e806f943df31209bb4df36cebb1a3fea955eaca3ac4"),
        item("whisper_tiny_proj_out.ncnn.bin", "e33c7db8ebb73eafc2cc791ae77b0e710c9cf54bd14321ae28277cb86b0f4968"),
        item("whisper_tiny_proj_out.ncnn.param", "428368e792a833f6efa5905c21e977e7c44ee917ed3a826db1ac67fc7a8bb3c7"),
        item("whisper_vocab.txt", "c3e28c60daa5956c08e02a08e82dc6ef4c8882805db4940d59343638234b6c6e"),
    )

    private fun item(name: String, sha256: String) =
        ModelDownloadItem("$BASE$name", "$DIR/$name", "Whisper $name", sha256)

    /** Directory the ncnn `Whisper(dirPath)` loads from. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

    /** True once every model file is present on disk. */
    fun isReady(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return FILES.all { File(root, it.fileName).exists() }
    }

    /** Download any missing files (skips present ones); suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) = downloadModels(context, ds, FILES)

    /** Averaged 0..1 download progress across the files, read from DataStore. */
    fun progress(ds: DataStoreUtils): Float =
        FILES.map { ds.getDouble("progress_${it.fileName}") ?: 0.0 }.average().toFloat()
}
