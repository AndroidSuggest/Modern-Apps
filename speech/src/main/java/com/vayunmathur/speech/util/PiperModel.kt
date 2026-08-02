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
 *   https://data.vayunmathur.com/models/piper/voice3.zip
 *
 * voice3.zip is the ncnn VITS bundle (amy medium, 22050 Hz, 125k-word dict, 2.2 MB
 * en-word_id.bin). voice.zip was the old sherpa-onnx layout, voice2.zip was the first
 * ncnn bundle but with the broken 33k-word CMUdict parse (593 KB dict, missing HELLO)
 * due to shell sed illegal-byte-sequence on macOS; voice3 busts Cloudflare cache again.
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

    /**
     * The downloadable archive name. Uses voice3.zip to bust Cloudflare cache — voice.zip
     * served stale sherpa-onnx data, voice2.zip served the first ncnn bundle with the broken
     * 33k-word dict (HELLO missing → letter-by-letter spelling). voice3 is the 125k-word full
     * dict. The on-disk archive path is still piper/voice.zip for backward compat.
     */
    const val REMOTE_ARCHIVE = "voice3.zip"

    /** On-disk archive path (under getExternalFilesDir). Kept as voice.zip for compat. */
    private const val ARCHIVE = "$DIR/voice.zip"

    /** The single downloadable archive (extracted app-side into [voiceDir]), SHA-256 pinned. */
    val FILES: List<ModelDownloadItem> = listOf(
        ModelDownloadItem(
            "${BASE}${REMOTE_ARCHIVE}",
            ARCHIVE,
            "Piper voice (TTS)",
            "49a18080c2e97b066854d2a5360443275ef3041c7524fcc023b7efdcb063952c",
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
        // Invalidate: old sherpa .onnx bundle, or old ncnn bundle with tiny 33k dict (593 KB)
        // due to CMUdict shell-pipeline breakage. New dict is 2.2 MB / 125k entries.
        val legacyOnnx = dir.listFiles()?.any { it.name.endsWith(".onnx") } == true
        if (legacyOnnx) {
            Log.d(TAG, "legacy .onnx detected, forcing invalidation")
            return false
        }
        val dictFile = File(dir, DICT)
        if (dictFile.exists() && dictFile.length() < 1_000_000L) {
            Log.d(TAG, "old tiny dict ${dictFile.length()} bytes, forcing invalidation")
            return false
        }
        val result = dir.isDirectory &&
            prefix != null &&
            REQUIRED_NETS.all { net ->
                File(dir, "$prefix$net.ncnn.param").exists() &&
                    File(dir, "$prefix$net.ncnn.bin").exists()
            } &&
            dictFile.exists() &&
            File(dir, CONFIG).exists()
        Log.d(TAG, "isExtracted dir=$dir exists=${dir.exists()} isDir=${dir.isDirectory} prefix=$prefix dictSize=${if (dictFile.exists()) dictFile.length() else -1} root=${rootDir(context)} result=$result")
        if (!result) {
            try {
                val ext = context.getExternalFilesDir(null)
                Log.d(TAG, "extDir=$ext dictExists=${dictFile.exists()} configExists=${File(dir, CONFIG).exists()} list=${dir.list()?.toList()}")
            } catch (t: Throwable) {
                Log.d(TAG, "isExtracted probe failed", t)
            }
        }
        return result
    }

    /** True if TTS can run now (extracted). Extraction happens immediately after download. */
    fun isReady(context: Context): Boolean {
        val dir = voiceDir(context)
        if (dir.isDirectory) {
            val legacy = dir.listFiles()?.any { it.name.endsWith(".onnx") } == true ||
                File(dir, "tokens.txt").exists() ||
                File(dir, "espeak-ng-data").isDirectory ||
                File(dir, DICT).let { it.exists() && it.length() < 1_000_000L }
            if (legacy) {
                Log.d(TAG, "deleting legacy/broken voice at $dir for migration to ncnn full dict")
                dir.deleteRecursively()
            }
        }
        return isExtracted(context)
    }

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
        if (!zip.exists()) {
            // Also check new archive name on disk (in case download used new path).
            val zip2 = File(rootDir(context), "$DIR/$REMOTE_ARCHIVE")
            if (!zip2.exists()) return false
            // Rename to expected on-disk name for rest of flow.
            zip2.renameTo(zip)
        }
        val dir = voiceDir(context)
        val tmp = File(context.getExternalFilesDir(null), "$DIR/voice.tmp")
        tmp.deleteRecursively()
        return try {
            unzip(zip, tmp)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IllegalStateException("rename $tmp -> $dir failed")
            zip.delete()
            // Clean up possible leftover voice2 zip at new name.
            File(rootDir(context), "$DIR/$REMOTE_ARCHIVE").takeIf { it.exists() }?.delete()
            isExtracted(context)
        } catch (t: Throwable) {
            Log.e(TAG, "extracting Piper voice failed", t)
            tmp.deleteRecursively()
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
