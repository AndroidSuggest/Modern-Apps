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
 * is a directory tree, so it's fetched as a single `.zip` from the mirror into
 * `getExternalFilesDir()` and extracted once into [voiceDir]; the archive is then deleted.
 * `com.vayunmathur.ncnn.Vits` loads it from those real file paths, which is why the voice
 * isn't bundled in the APK.
 *
 * The VITS graph is exported as five separate ncnn nets, because the alignment between the
 * duration predictor and the flow depends on the predicted durations and can't be expressed
 * as ncnn ops. So the voice directory holds, for a voice named `<voice>`:
 *   `<voice>_enc_p.ncnn.{param,bin}`   text encoder
 *   `<voice>_dp.ncnn.{param,bin}`      duration predictor
 *   `<voice>_flow.ncnn.{param,bin}`
 *   `<voice>_dec.ncnn.{param,bin}`     HiFi-GAN vocoder
 *   `<voice>_emb_g.ncnn.{param,bin}`   speaker embedding, multi-speaker voices only
 *   `en-word_id.bin`                   grapheme-to-phoneme dictionary
 *   `config.json`                      sample rate + inference scales
 *
 * Mirror layout (zip the voice dir's *contents* at the zip root;
 * `scripts/speech/fetch_piper_model.sh` stages it):
 *   https://data.vayunmathur.com/models/piper/voice.zip
 */
object PiperModel {
    const val DIR = "piper"
    const val DICT = "en-word_id.bin"
    const val CONFIG = "config.json"

    /** Identifies the voice: whatever precedes this is the `<voice>` prefix. */
    private const val ENCODER_SUFFIX = "_enc_p.ncnn.param"

    /** The nets that every voice has. `_emb_g` is multi-speaker-only, so not required. */
    private val REQUIRED_NETS = listOf("_enc_p", "_dp", "_flow", "_dec")

    private const val BASE = "https://data.vayunmathur.com/models/piper/"
    private const val ARCHIVE = "$DIR/voice.zip"

    /** The single downloadable archive (extracted app-side into [voiceDir]), SHA-256 pinned. */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem(
            "${BASE}voice.zip",
            ARCHIVE,
            "Piper voice (TTS)",
            "ca20be58bda0514d57cb8ce6c0cf84b40aae8427a6f48739472f99e9bcdd8fa6",
        ),
    )

    private fun rootDir(context: Context): File? {
        // TTS service binder thread may have external storage unavailable momentarily;
        // fallback to filesDir so File() doesn't become relative.
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    private fun archive(context: Context): File {
        val root = rootDir(context) ?: return File(ARCHIVE)
        return File(root, ARCHIVE)
    }

    /** The extracted voice directory [com.vayunmathur.ncnn.Vits] loads from. */
    fun voiceDir(context: Context): File {
        val root = rootDir(context) ?: return File("$DIR/voice")
        return File(root, "$DIR/voice")
    }

    /**
     * The `<voice>` prefix the net files share, discovered from whichever file ends in
     * `_enc_p.ncnn.param`. Null if the voice isn't extracted. Vits does the same scan
     * natively; this mirrors it so [isExtracted] can verify the rest of the set.
     */
    fun voicePrefix(context: Context): String? {
        val files = voiceDir(context).listFiles() ?: return null
        val encoder = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return null
        return encoder.name.removeSuffix(ENCODER_SUFFIX)
    }

    /** True once the voice has been extracted and looks complete. */
    fun isExtracted(context: Context): Boolean {
        val dir = voiceDir(context)
        val prefix = voicePrefix(context)
        val result = dir.isDirectory &&
            prefix != null &&
            REQUIRED_NETS.all { net ->
                File(dir, "$prefix$net.ncnn.param").exists() &&
                    File(dir, "$prefix$net.ncnn.bin").exists()
            } &&
            File(dir, DICT).exists() &&
            File(dir, CONFIG).exists()
        Log.d(TAG, "isExtracted dir=$dir exists=${dir.exists()} isDir=${dir.isDirectory} prefix=$prefix root=${rootDir(context)} result=$result")
        if (!result) {
            // Also probe the known external absolute path as fallback diagnostic
            try {
                val ext = context.getExternalFilesDir(null)
                Log.d(TAG, "extDir=$ext dictExists=${File(dir, DICT).exists()} configExists=${File(dir, CONFIG).exists()} list=${dir.list()?.toList()}")
            } catch (t: Throwable) {
                Log.d(TAG, "isExtracted probe failed", t)
            }
        }
        return result
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
