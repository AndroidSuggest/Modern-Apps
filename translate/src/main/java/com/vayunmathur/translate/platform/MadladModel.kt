package com.vayunmathur.translate.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.MadladHandle
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the on-device **MADLAD400-3B-MT** translation model.
 *
 * Two files: `madlad400.maml` and `tokenizer.bin`, produced by model-eng's conversion
 * pipeline (`scripts/ml/fetch_madlad400.py`, pinning `google/madlad400-3b-mt`).
 * 32 encoder layers, 32 decoder layers, `d_model` 1024, 16 heads of `d_kv` 128, an
 * 8192-wide gated-GELU feed-forward, and a 256,000-entry vocabulary with untied
 * embeddings.
 *
 * The model runs on `:library:ml`'s own Vulkan runtime. Native checks the maml's graph
 * id (25, `graph::MADLAD`), so a wrong file fails at load.
 *
 * Files are fetched mirror-only from `data.vayunmathur.com/models/madlad400/` via
 * [downloadModels]. Auto-install via `InitialModelDownloadChecker` in MainActivity.
 */
object MadladModel {
    private const val BASE = "https://data.vayunmathur.com/models/madlad400/"
    const val DIR = "madlad400"

    /** The 2 runtime files, SHA-256 pinned. Names and order come from [MadladHandle.FILES]. */
    val FILES: List<ModelDownloadItem> = listOf(
        item(
            MadladHandle.GRAPH,
            // 1,015,713,728 bytes, the Q2_K port (`madlad400-q2k` graph, same graph id 25).
            // Verified against build/madlad400-q2k/madlad400.maml, uploaded to the mirror.
            "76991c58c3b8fd5bce8e5963dcba7f0d371afc65385ecd9bdd7701a789bd407b",
        ),
        item(
            MadladHandle.TOKENIZER,
            // 3,638,800 bytes, verified against build/madlad400-q2k/tokenizer.bin.
            "477abfdd0e236a20c4c960b6768ed965ce44fa250e22f2b504f2beafac6a2c5d",
        ),
    )

    private fun item(name: String, sha256: String?) =
        ModelDownloadItem("$BASE$name", "$DIR/$name", "MADLAD400 $name", sha256)

    /** Directory [MadladHandle.inDirectory] loads from. */
    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), DIR)

    /** True once both model files are present on disk. */
    fun isDownloaded(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return FILES.all { File(root, it.fileName).exists() }
    }

    /** Download any missing files (skips present ones); suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) = downloadModels(context, ds, FILES)

    /** Averaged 0..1 download progress across the files, read from DataStore. */
    fun progress(ds: DataStoreUtils): Float =
        (FILES.map { ds.getDouble("progress_${it.fileName}") ?: 0.0 }.average()).toFloat()

    /**
     * The MADLAD target tag for a UI language code, e.g. `en` -> `"<2en>"`.
     *
     * MADLAD's tags are coarse ISO-639-1 codes (`<2en>` covers every English-script entry),
     * while the UI codes are BCP-47 (`en`, `es`, `yue-Hant`). The UI code's language part
     * before `-` IS the ISO-639-1 code MADLAD wants, so this maps directly — unlike NLLB's
     * flores codes (`eng_Latn`), whose three-letter part is a different standard.
     *
     * Takes the UI [code], not the flores code: `spa_Latn`.substringBefore('_') is `spa`,
     * which is not a MADLAD tag (`<2spa>` does not exist — Spanish is `<2es>`). Returns
     * null only for a code with no language part at all. Native validates the tag against
     * the Unigram table and refuses an unknown one rather than mistranslating.
     */
    fun targetTag(code: String): String? {
        val lang = code.substringBefore('-').lowercase()
        if (lang.isEmpty() || lang.any { !it.isLetter() }) return null
        return "<2$lang>"
    }
}
