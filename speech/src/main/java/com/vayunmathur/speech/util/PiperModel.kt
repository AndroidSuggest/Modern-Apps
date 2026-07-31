package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Runtime-download config for the offline **Piper (VITS)** text-to-speech voice. The voice
 * is a directory tree (the `.onnx` model, `tokens.txt`, and the large `espeak-ng-data/`
 * phonemizer data), so it's fetched as a single `.zip` from the mirror into
 * `getExternalFilesDir()` and extracted once into [voiceDir]; the archive is then deleted.
 * sherpa-onnx loads it from those real file paths (espeak-ng needs real paths — it can't
 * read from the APK, which is why the voice isn't bundled).
 *
 * Mirror layout (zip the voice dir's *contents* — `<voice>.onnx`, `tokens.txt`,
 * `espeak-ng-data/` at the zip root; `scripts/speech/fetch_piper_model.sh` stages it):
 *   https://data.vayunmathur.com/models/piper/voice.zip
 */
object PiperModel {
    const val DIR = "piper"
    const val TOKENS = "tokens.txt"
    const val ESPEAK_DATA = "espeak-ng-data"

    private const val BASE = "https://data.vayunmathur.com/models/piper/"
    private const val ARCHIVE = "$DIR/voice.zip"

    /** The single downloadable archive (extracted app-side into [voiceDir]). */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem("${BASE}voice.zip", ARCHIVE, "Piper voice (TTS)"),
    )

    private fun archive(context: Context): File =
        File(context.getExternalFilesDir(null), ARCHIVE)

    /** The extracted voice directory sherpa-onnx loads from. */
    fun voiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), "$DIR/voice")

    fun onnxFile(context: Context): File? =
        voiceDir(context).listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()

    /** True once the voice has been extracted and looks complete. */
    fun isExtracted(context: Context): Boolean {
        val dir = voiceDir(context)
        return dir.isDirectory &&
            onnxFile(context) != null &&
            File(dir, TOKENS).exists() &&
            File(dir, ESPEAK_DATA).isDirectory
    }

    /** True if TTS can run now (extracted). Extraction happens immediately after download. */
    fun isReady(context: Context): Boolean = isExtracted(context)

    /** Download the voice archive if missing; suspends until complete. */
    suspend fun download(context: Context, ds: DataStoreUtils) = downloadModels(context, ds, FILES)

    /** Averaged 0..1 download progress, read from DataStore. */
    fun progress(ds: DataStoreUtils): Float =
        FILES.map { ds.getDouble("progress_${it.fileName}") ?: 0.0 }.average().toFloat()

    /**
     * Ensure the voice is extracted, unzipping the downloaded archive on first use and then
     * deleting it to save space. Returns true if the voice is ready. Safe to call repeatedly.
     */
    @Synchronized
    fun installIfNeeded(context: Context): Boolean {
        if (isExtracted(context)) return true
        val zip = archive(context)
        if (!zip.exists()) return false
        val dir = voiceDir(context)
        // Extract to a temp dir then swap in, so a crash mid-unzip can't leave a half-written
        // voice that looks installed.
        val tmp = File(context.getExternalFilesDir(null), "$DIR/voice.tmp")
        tmp.deleteRecursively()
        return try {
            unzip(zip, tmp)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IllegalStateException("rename $tmp -> $dir failed")
            zip.delete()
            isExtracted(context)
        } catch (t: Throwable) {
            Log.e(TAG, "extracting Piper voice failed", t)
            tmp.deleteRecursively()
            // Drop the (possibly corrupt) archive so it re-downloads next time.
            zip.delete()
            false
        }
    }

    /** Unzip [zip] into [outDir], guarding against Zip-Slip path traversal. */
    private fun unzip(zip: File, outDir: File) {
        outDir.mkdirs()
        val root = outDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(outDir, entry.name)
                if (!out.canonicalPath.startsWith(root)) {
                    throw SecurityException("Zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    private const val TAG = "PiperModel"
}
