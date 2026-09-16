package com.vayunmathur.photos.platform

import android.content.Context
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.ml.ClipHandle
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File

/**
 * Runtime-download config for the split-tower TinyCLIP ExecuTorch pair.
 *
 * The Vulkan fp16 `.pte` pair (image ~77 MB + text ~89 MB, `wkcn/TinyCLIP-ViT-39M-16-Text-19M-YFCC15M`)
 * is far too large to ship inside the APK — hence a download directory and no asset path.
 * [ClipEmbedder] runs ET-only from [etDir]; without both files semantic search is
 * unavailable (fail closed, no bundled fallback).
 *
 * Files are fetched mirror-only via [downloadModels]. They are NOT gated behind
 * `InitialModelDownloadChecker`: semantic search is simply unavailable until the
 * pair downloads.
 */
object PhotosEtModels {
    private const val BASE = "https://data.vayunmathur.com/et/clip/"
    const val DIR = "clip_et"

    /** The ET pair, SHA-256 pinned (Vulkan fp16 ship candidates, see `analysis/et-clip/ET_PATH.md`). */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem(
            "${BASE}tinyclip_vit_39m_16_text_19m_yfcc15m_image_vulkan_fp16.pte",
            "$DIR/${ClipHandle.IMAGE_ET_FILE}",
            "TinyCLIP image tower (ET)",
            "a041db657ad11333ea76f0bfdac6e581dda3847358acaa0539ee223528f72a75",
        ),
        ModelDownloadItem(
            "${BASE}tinyclip_vit_39m_16_text_19m_yfcc15m_text_vulkan_fp16.pte",
            "$DIR/${ClipHandle.TEXT_ET_FILE}",
            "TinyCLIP text tower (ET)",
            "76cd49650a81ceae9f290fd3e66d8222e5fd6ed372e34a0c648adeb98b53f01c",
        ),
    )

    /** Directory [ClipHandle.inDirectory] loads the ET pair from. */
    fun etDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, DIR) }

    /** True once both ET towers are present on disk. */
    fun isDownloaded(context: Context): Boolean {
        val root = context.getExternalFilesDir(null) ?: return false
        return FILES.all { File(root, it.fileName).exists() }
    }

    /** Download any missing files (skips present ones); suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) = downloadModels(context, ds, FILES)
}
