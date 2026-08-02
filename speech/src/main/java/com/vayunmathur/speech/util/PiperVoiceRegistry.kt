package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Multi-voice registry for offline **Piper (VITS)** TTS. Each voice is a directory tree
 * fetched as a single `.zip` from the mirror into `getExternalFilesDir()/piper/voices/`
 * and extracted into `piper/voices/<bcp47>/<id>/`.
 *
 * Mirrors the curated Translate 20-language set (`translate/util/Languages.kt:ALL`):
 * en, es, fr, de, it, pt, nl, ru, pl, tr, ar, hi, zh, ja, ko, vi, th, id, uk, sv.
 *
 * The original single-voice layout `piper/voice` (voice3.zip, Amy medium, 22050 Hz,
 * 125k-word dict 2.2 MB) is kept as a legacy path and migrated to the new layout on
 * first launch. New voices follow `<code>-low.zip` naming, but where a true low
 * checkpoint doesn't exist (only en, ar, cs had low on piper-checkpoints) we ship the
 * medium checkpoint labeled low (size ~28 MB instead of ~12-15 MB). The enum records
 * the real sample rate so the TTS service can `callback.start(sampleRate, ...)`.
 *
 * Each [PiperVoiceDef] is SHA-256 pinned once its zip is published at
 * `https://data.vayunmathur.com/models/piper/`. Placeholder entries with null sha
 * skip verification until the mirror is populated.
 */
data class PiperVoiceDef(
    /** HF-style id "<locale>-<speaker>-<quality>", e.g. "en_US-amy-medium". */
    val id: String,
    /** ISO-639-1 code, e.g. "en", "de". Matches Languages.ALL codes. */
    val code: String,
    /** BCP-47 tag used for `Voice(name)` and `Locale.forLanguageTag`. */
    val bcp47: String,
    /** ISO-639-3, e.g. "eng", "deu". Used for CHECK_TTS_DATA. */
    val iso3: String,
    /** ISO-3166-1 alpha-3 country, e.g. "USA", "DEU". Used with iso3 for TTS id. */
    val iso3Country: String,
    /** Native name from Languages.byCode, e.g. "Deutsch". */
    val nativeName: String,
    /** English name, e.g. "German". */
    val englishName: String,
    /** Remote archive name at BASE, e.g. "de-low.zip". */
    val remoteArchive: String,
    /** Dictionary file inside voice dir, e.g. "de-word_id.bin". */
    val dictFile: String,
    /** SHA-256 of the remote archive, null until published. */
    val sha256: String?,
    /** "low" or "medium" (low may be medium ckpt placeholder). */
    val quality: String,
    /** Sample rate from config.json, 16000 for true low, 22050 for medium-as-low. */
    val sampleRate: Int,
    /** Estimated installed size badge in MB (zip ~12-28). */
    val sizeEstimateMb: Int,
    /** Speaker id for multi-speaker voices, 0 for single. */
    val speakerId: Int = 0,
    val isMultiSpeaker: Boolean = false,
)

object PiperVoiceRegistry {

    const val DIR = "piper"
    const val VOICES_ROOT = "piper/voices"
    const val LEGACY_VOICE_DIR = "piper/voice"
    const val LEGACY_ARCHIVE = "piper/voice.zip"
    const val LEGACY_REMOTE_ARCHIVE = "voice3.zip"
    const val BASE = "https://data.vayunmathur.com/models/piper/"

    private const val ENCODER_SUFFIX = "_enc_p.ncnn.param"
    private const val CONFIG = "config.json"
    private val REQUIRED_NETS = listOf("_enc_p", "_dp", "_flow", "_dec")

    /**
     * Full 20-language catalog matching `Languages.ALL`. English keeps the existing
     * voice3.zip SHA so existing installs don't re-download.
     *
     * SHA values for non-EN voices are null until mirror publishes per-lang zips.
     * When published, pin them here for integrity verification.
     *
     * Quality: en uses medium (amy, 22050 Hz, 28 MB) as baseline; de, fr, es etc.
     * use medium-as-low until true low checkpoints are trained. True low where ckpt
     * exists: en (lessac/low 16000), ar (kareem/low 16000). Others marked sampleRate
     * 22050 to indicate they're actually medium.
     */
    val ALL: List<PiperVoiceDef> = listOf(
        PiperVoiceDef(
            id = "en_US-amy-medium",
            code = "en",
            bcp47 = "en-US",
            iso3 = "eng",
            iso3Country = "USA",
            nativeName = "English",
            englishName = "English",
            remoteArchive = "voice3.zip",
            dictFile = "en-word_id.bin",
            sha256 = "49a18080c2e97b066854d2a5360443275ef3041c7524fcc023b7efdcb063952c",
            quality = "medium",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "es_ES-sharvard-medium",
            code = "es",
            bcp47 = "es-ES",
            iso3 = "spa",
            iso3Country = "ESP",
            nativeName = "Español",
            englishName = "Spanish",
            remoteArchive = "es-low.zip",
            dictFile = "es-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "fr_FR-siwis-medium",
            code = "fr",
            bcp47 = "fr-FR",
            iso3 = "fra",
            iso3Country = "FRA",
            nativeName = "Français",
            englishName = "French",
            remoteArchive = "fr-low.zip",
            dictFile = "fr-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "de_DE-thorsten-medium",
            code = "de",
            bcp47 = "de-DE",
            iso3 = "deu",
            iso3Country = "DEU",
            nativeName = "Deutsch",
            englishName = "German",
            remoteArchive = "de-low.zip",
            dictFile = "de-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "it_IT-riccardo-medium",
            code = "it",
            bcp47 = "it-IT",
            iso3 = "ita",
            iso3Country = "ITA",
            nativeName = "Italiano",
            englishName = "Italian",
            remoteArchive = "it-low.zip",
            dictFile = "it-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "pt_BR-faber-medium",
            code = "pt",
            bcp47 = "pt-BR",
            iso3 = "por",
            iso3Country = "BRA",
            nativeName = "Português",
            englishName = "Portuguese",
            remoteArchive = "pt-low.zip",
            dictFile = "pt-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "nl_BE-nathalie-medium",
            code = "nl",
            bcp47 = "nl-NL",
            iso3 = "nld",
            iso3Country = "NLD",
            nativeName = "Nederlands",
            englishName = "Dutch",
            remoteArchive = "nl-low.zip",
            dictFile = "nl-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "ru_RU-denis-medium",
            code = "ru",
            bcp47 = "ru-RU",
            iso3 = "rus",
            iso3Country = "RUS",
            nativeName = "Русский",
            englishName = "Russian",
            remoteArchive = "ru-low.zip",
            dictFile = "ru-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "pl_PL-darkman-medium",
            code = "pl",
            bcp47 = "pl-PL",
            iso3 = "pol",
            iso3Country = "POL",
            nativeName = "Polski",
            englishName = "Polish",
            remoteArchive = "pl-low.zip",
            dictFile = "pl-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "tr_TR-dfki-medium",
            code = "tr",
            bcp47 = "tr-TR",
            iso3 = "tur",
            iso3Country = "TUR",
            nativeName = "Türkçe",
            englishName = "Turkish",
            remoteArchive = "tr-low.zip",
            dictFile = "tr-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "ar_JO-kareem-low",
            code = "ar",
            bcp47 = "ar-SA",
            iso3 = "ara",
            iso3Country = "SAU",
            nativeName = "العربية",
            englishName = "Arabic",
            remoteArchive = "ar-low.zip",
            dictFile = "ar-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 16000,
            sizeEstimateMb = 13,
        ),
        PiperVoiceDef(
            id = "hi_IN-rohan-medium",
            code = "hi",
            bcp47 = "hi-IN",
            iso3 = "hin",
            iso3Country = "IND",
            nativeName = "हिन्दी",
            englishName = "Hindi",
            remoteArchive = "hi-low.zip",
            dictFile = "hi-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "zh_CN-huayan-medium",
            code = "zh",
            bcp47 = "zh-CN",
            iso3 = "zho",
            iso3Country = "CHN",
            nativeName = "中文",
            englishName = "Chinese",
            remoteArchive = "zh-low.zip",
            dictFile = "zh-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "ja_JP-kana-medium",
            code = "ja",
            bcp47 = "ja-JP",
            iso3 = "jpn",
            iso3Country = "JPN",
            nativeName = "日本語",
            englishName = "Japanese",
            remoteArchive = "ja-low.zip",
            dictFile = "ja-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "ko_KR-kss-medium",
            code = "ko",
            bcp47 = "ko-KR",
            iso3 = "kor",
            iso3Country = "KOR",
            nativeName = "한국어",
            englishName = "Korean",
            remoteArchive = "ko-low.zip",
            dictFile = "ko-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "vi_VN-vais1000-medium",
            code = "vi",
            bcp47 = "vi-VN",
            iso3 = "vie",
            iso3Country = "VNM",
            nativeName = "Tiếng Việt",
            englishName = "Vietnamese",
            remoteArchive = "vi-low.zip",
            dictFile = "vi-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "th_TH-th-medium",
            code = "th",
            bcp47 = "th-TH",
            iso3 = "tha",
            iso3Country = "THA",
            nativeName = "ไทย",
            englishName = "Thai",
            remoteArchive = "th-low.zip",
            dictFile = "th-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "id_ID-news_tts-medium",
            code = "id",
            bcp47 = "id-ID",
            iso3 = "ind",
            iso3Country = "IDN",
            nativeName = "Bahasa Indonesia",
            englishName = "Indonesian",
            remoteArchive = "id-low.zip",
            dictFile = "id-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "uk_UA-lada-medium",
            code = "uk",
            bcp47 = "uk-UA",
            iso3 = "ukr",
            iso3Country = "UKR",
            nativeName = "Українська",
            englishName = "Ukrainian",
            remoteArchive = "uk-low.zip",
            dictFile = "uk-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
        PiperVoiceDef(
            id = "sv_SE-nst-medium",
            code = "sv",
            bcp47 = "sv-SE",
            iso3 = "swe",
            iso3Country = "SWE",
            nativeName = "Svenska",
            englishName = "Swedish",
            remoteArchive = "sv-low.zip",
            dictFile = "sv-word_id.bin",
            sha256 = null,
            quality = "low",
            sampleRate = 22050,
            sizeEstimateMb = 28,
        ),
    )

    /** Default English voice used for backward compat (voice3.zip / Amy medium). */
    val DEFAULT: PiperVoiceDef = ALL.first { it.code == "en" }

    private val byCodeMap: Map<String, PiperVoiceDef> = ALL.associateBy { it.code.lowercase() }
    private val byBcp47Map: Map<String, PiperVoiceDef> = ALL.associateBy { it.bcp47.lowercase() }
    private val byIso3Map: Map<String, PiperVoiceDef> = ALL.associateBy { it.iso3.lowercase() }
    private val byIdMap: Map<String, PiperVoiceDef> = ALL.associateBy { it.id }
    private val byRemoteMap: Map<String, PiperVoiceDef> = ALL.associateBy { it.remoteArchive }

    fun byCode(code: String): PiperVoiceDef? = byCodeMap[code.lowercase()]
    fun byBcp47(tag: String): PiperVoiceDef? = byBcp47Map[tag.lowercase()]
    fun byIso3(iso3: String): PiperVoiceDef? = byIso3Map[iso3.lowercase()]
    fun byId(id: String): PiperVoiceDef? = byIdMap[id]
    fun byRemoteArchive(name: String): PiperVoiceDef? = byRemoteMap[name]

    /**
     * Resolve a def from free-form TTS request fields:
     * lang may be ISO-639-1 ("de"), ISO-639-3 ("deu"), BCP-47 ("de-DE"),
     * country "DEU", variant "x-en_US-amy-medium", voiceName BCP-47+extension.
     */
    fun resolve(
        lang: String? = null,
        country: String? = null,
        variant: String? = null,
        voiceName: String? = null,
    ): PiperVoiceDef? {
        // Voice name has highest priority — it may contain bcp47 + x-id extension.
        voiceName?.let { name ->
            // Exact bcp47 or bcp47-x-<id> prefix: "en-US-x-en_US-amy-medium" or "de-DE"
            val lower = name.lowercase()
            // Try id contained in extension: extract after "-x-" if present.
            val xIdx = lower.indexOf("-x-")
            if (xIdx >= 0) {
                val bcpPart = name.substring(0, xIdx)
                byBcp47(bcpPart)?.let { return it }
                // Id part might be the voice id itself.
                val idPart = name.substring(xIdx + 3)
                // idPart could be like "en_US-amy-medium" or "de-low" alias
                byId(idPart)?.let { return it }
                // Also try without ext: search by prefix.
                ALL.firstOrNull { idPart.contains(it.code) }?.let { def ->
                    // Prefer exact bcp match first, then code.
                    byBcp47(bcpPart)?.let { return it }
                }
            }
            // Direct BCP47
            byBcp47(name)?.let { return it }
            // Direct id
            byId(name)?.let { return it }
            // Code embedded (e.g. "de-DE")
            // Try first 2-3 chars as iso code
            val parts = name.split("-", "_")
            if (parts.isNotEmpty()) {
                byCode(parts[0])?.let { return it }
                byIso3(parts[0])?.let { return it }
            }
            // Legacy "eng-USA" style — iso3-country
            if (parts.size >= 2) {
                byIso3(parts[0])?.let { return it }
            }
        }

        lang?.let { l ->
            val ll = l.lowercase()
            byCode(ll)?.let { return it }
            byIso3(ll)?.let { return it }
            byBcp47(ll)?.let { return it }
            // Handle "en_US" with underscore
            val normalized = ll.replace('_', '-')
            byBcp47(normalized)?.let { return it }
            // ISO3 with variant may come as "eng" -> resolve
            // Last resort: prefix match "en-..." startsWith code
            if (ll.length >= 2) {
                val prefix = ll.substring(0, 2)
                byCode(prefix)?.let { return it }
            }
        }

        // country as iso3 country e.g. "DEU" could still hint at language via lang param already?
        // If only country given? Ignore.

        return null
    }

    fun rootDir(context: Context): File? =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun voicesDir(context: Context): File {
        val root = rootDir(context) ?: return File(VOICES_ROOT)
        return File(root, VOICES_ROOT)
    }

    fun voiceDir(context: Context, def: PiperVoiceDef): File {
        val root = rootDir(context) ?: return File("$VOICES_ROOT/${def.bcp47}/${def.id}")
        return File(root, "$VOICES_ROOT/${def.bcp47}/${def.id}")
    }

    fun archiveFile(context: Context, def: PiperVoiceDef): File {
        val root = rootDir(context) ?: return File("$VOICES_ROOT/${def.remoteArchive}")
        return File(root, "$VOICES_ROOT/${def.remoteArchive}")
    }

    /** Historical on-disk archive location used before multi-voice (piper/voice.zip). */
    fun legacyArchive(context: Context): File {
        val root = rootDir(context) ?: return File(LEGACY_ARCHIVE)
        return File(root, LEGACY_ARCHIVE)
    }

    fun legacyVoiceDir(context: Context): File {
        val root = rootDir(context) ?: return File(LEGACY_VOICE_DIR)
        return File(root, LEGACY_VOICE_DIR)
    }

    /** Discovered `<voice>` prefix inside voiceDir, e.g. "amy" from amy_enc_p.ncnn.param. */
    fun voicePrefix(context: Context, def: PiperVoiceDef): String? {
        val files = voiceDir(context, def).listFiles() ?: return null
        val encoder = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return null
        return encoder.name.removeSuffix(ENCODER_SUFFIX)
    }

    /**
     * True once [def] has been extracted and looks complete. Guards:
     * - No .onnx legacy
     * - Dict exists and size >= threshold (100k for non-en, 1M for en legacy guard)
     * - All required nets param+bin present under discovered prefix
     * - config.json exists
     */
    fun isExtracted(context: Context, def: PiperVoiceDef): Boolean {
        val dir = voiceDir(context, def)
        if (!dir.isDirectory) {
            // Also check legacy location for default EN voice before migration.
            if (def.code == "en") {
                val legacyDir = legacyVoiceDir(context)
                if (legacyDir.isDirectory) {
                    // Legacy check delegated to legacy helpers — treat as not extracted here
                    // so migration logic can handle it.
                    return isLegacyExtracted(context)
                }
            }
            return false
        }
        if (dir.listFiles()?.any { it.name.endsWith(".onnx") } == true) return false

        // Dict size guard: old 33k dict 593 KB vs new 2.2 MB (English). For non-EN
        // we only know minimal 100k threshold; small dict indicates broken download.
        val dictPath = File(dir, def.dictFile)
        // Also accept en-word_id.bin as alternative for non-EN dirs that may ship both
        val altEnDict = File(dir, "en-word_id.bin")
        val dictFile = when {
            dictPath.exists() -> dictPath
            altEnDict.exists() -> altEnDict
            else -> {
                // For EN def, dictFile must exist; for non-EN we allowed fallback to any *-word_id.bin
                // via native patch, but Kotlin side still expects a dict to consider extracted.
                // Accept any *-word_id.bin found.
                dir.listFiles()?.firstOrNull { it.name.endsWith("-word_id.bin") }
            }
        }

        if (dictFile == null || !dictFile.exists()) {
            Log.d(TAG, "dict missing for ${def.id} in $dir")
            return false
        }
        val minSize = if (def.code == "en") 1_000_000L else 100_000L
        if (dictFile.length() < minSize) {
            Log.d(TAG, "tiny dict for ${def.id}: ${dictFile.length()} < $minSize")
            return false
        }

        val prefix = voicePrefix(context, def)
        if (prefix == null) {
            Log.d(TAG, "no prefix for ${def.id} in $dir")
            return false
        }

        val netsOk = REQUIRED_NETS.all { net ->
            File(dir, "${prefix}${net}.ncnn.param").exists() &&
                File(dir, "${prefix}${net}.ncnn.bin").exists()
        }
        if (!netsOk) {
            Log.d(TAG, "nets missing for ${def.id} prefix=$prefix in $dir")
            return false
        }

        if (!File(dir, CONFIG).exists()) {
            Log.d(TAG, "config missing for ${def.id} in $dir")
            return false
        }

        return true
    }

    /** Legacy single-voice extraction check (piper/voice). */
    private fun isLegacyExtracted(context: Context): Boolean {
        val dir = legacyVoiceDir(context)
        val files = dir.listFiles() ?: return false
        if (files.any { it.name.endsWith(".onnx") }) return false
        val dict = File(dir, "en-word_id.bin")
        if (!dict.exists() || dict.length() < 1_000_000L) return false
        val enc = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return false
        val prefix = enc.name.removeSuffix(ENCODER_SUFFIX)
        val netsOk = REQUIRED_NETS.all { net ->
            File(dir, "${prefix}${net}.ncnn.param").exists() &&
                File(dir, "${prefix}${net}.ncnn.bin").exists()
        }
        return netsOk && File(dir, CONFIG).exists()
    }

    /** True if any voice is ready (used as overall TTS ready flag). */
    fun isAnyExtracted(context: Context): Boolean =
        ALL.any { isExtracted(context, it) } || isLegacyExtracted(context)

    fun installedDefs(context: Context): List<PiperVoiceDef> =
        ALL.filter { isExtracted(context, it) }.let { list ->
            // Include default EN if only legacy exists (pre-migration)
            if (list.isEmpty() && isLegacyExtracted(context)) listOf(DEFAULT) else list
        }

    fun installedCodes(context: Context): List<String> =
        installedDefs(context).map { it.code }.distinct()

    /** ModelDownloadItem list for the given codes (or all pending if codes empty). */
    fun downloadItems(codes: List<String>): List<ModelDownloadItem> {
        val defs = if (codes.isEmpty()) ALL else codes.mapNotNull { byCode(it) }
        return defs.map { def ->
            val fileName = "$VOICES_ROOT/${def.remoteArchive}"
            ModelDownloadItem(
                url = "$BASE${def.remoteArchive}",
                fileName = fileName,
                description = "${def.nativeName} (${def.englishName}) voice (${def.sizeEstimateMb} MB)",
                sha256 = def.sha256,
            )
        }
    }

    suspend fun download(
        context: Context,
        ds: DataStoreUtils,
        codes: List<String>,
    ) {
        val items = downloadItems(codes)
        if (items.isNotEmpty()) {
            downloadModels(context, ds, items)
        }
    }

    fun progress(ds: DataStoreUtils, def: PiperVoiceDef): Float {
        val key = "progress_$VOICES_ROOT/${def.remoteArchive}"
        return (ds.getDouble(key) ?: 0.0).toFloat()
    }

    /** Averaged progress across given codes (for overall badge). */
    fun overallProgress(ds: DataStoreUtils, codes: List<String>? = null): Float {
        val defs = if (codes == null) ALL else codes.mapNotNull { byCode(it) }
        if (defs.isEmpty()) return 0f
        return defs.map { progress(ds, it) }.average().toFloat()
    }

    /**
     * Ensure [def] is extracted, unzipping the downloaded archive on first use and
     * then deleting the archive to save space. Returns true if ready.
     */
    @Synchronized
    fun installIfNeeded(context: Context, def: PiperVoiceDef): Boolean {
        if (isExtracted(context, def)) return true
        val zip = archiveFile(context, def)

        // Compatibility: also check alternative locations where DownloadManager may
        // have placed the file before the multi-voice refactor.
        val altZips = mutableListOf<File>()
        if (def.code == "en") {
            // Old single-voice archive locations
            altZips += legacyArchive(context)
            File(rootDir(context), "$DIR/${def.remoteArchive}").let { altZips += it }
        }

        val usableZip = when {
            zip.exists() -> zip
            else -> altZips.firstOrNull { it.exists() }?.also { found ->
                // Normalize to expected location
                found.renameTo(zip)
            } ?: return false
        }

        val dir = voiceDir(context, def)
        val tmp = File(voicesDir(context), ".${def.id}.tmp")
        tmp.deleteRecursively()

        return try {
            unzip(usableZip, tmp)

            // Backward-compat for ncnn-android <1.7.1: old native requires en-word_id.bin mandatory.
            // If the extracted voice only has <lang>-word_id.bin, ensure en-word_id.bin also exists
            // so 1.7.0 AAR still loads (copy from default en voice dir if available, or duplicate lang dict).
            // With 1.7.1 the native fallback handles any *-word_id.bin, so this copy is harmless.
            if (!File(tmp, "en-word_id.bin").exists()) {
                val enDir = voiceDir(context, DEFAULT)
                val enDictSrc = File(enDir, "en-word_id.bin")
                val anyDict = tmp.listFiles()?.firstOrNull { it.name.endsWith("-word_id.bin") }
                try {
                    if (enDictSrc.exists()) {
                        enDictSrc.copyTo(File(tmp, "en-word_id.bin"), overwrite = false)
                    } else if (anyDict != null) {
                        // Duplicate lang dict as en fallback for 1.7.0 compat.
                        anyDict.copyTo(File(tmp, "en-word_id.bin"), overwrite = false)
                        Log.d(TAG, "copied ${anyDict.name} -> en-word_id.bin for 1.7.0 compat in ${def.id}")
                    }
                } catch (_: Throwable) {
                    // Ignore, isExtracted will report false if dict still missing.
                }
            }

            dir.parentFile?.mkdirs()
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IllegalStateException("rename $tmp -> $dir failed")
            usableZip.delete()
            // Clean up alternative leftover zips for this voice
            altZips.forEach { it.takeIf { f -> f.exists() && f.absolutePath != usableZip.absolutePath }?.delete() }
            if (def.code == "en") {
                // Also clean up legacy remote name file if present
                File(rootDir(context), "$DIR/${LEGACY_REMOTE_ARCHIVE}").takeIf { it.exists() }?.delete()
            }
            isExtracted(context, def)
        } catch (t: Throwable) {
            Log.e(TAG, "extracting ${def.id} failed", t)
            tmp.deleteRecursively()
            usableZip.delete()
            false
        }
    }

    /** Install all archives whose download has completed (or progress >= 1.0). */
    fun installAllIfNeeded(context: Context, ds: DataStoreUtils) {
        for (def in ALL) {
            val p = progress(ds, def)
            if (p >= 0.999f || archiveFile(context, def).exists()) {
                installIfNeeded(context, def)
            }
        }
    }

    fun deleteFiles(context: Context, def: PiperVoiceDef) {
        try {
            voiceDir(context, def).deleteRecursively()
            archiveFile(context, def).delete()
        } catch (t: Throwable) {
            Log.e(TAG, "delete files ${def.id} failed", t)
        }
    }

    suspend fun delete(context: Context, def: PiperVoiceDef, ds: DataStoreUtils? = null) {
        try {
            voiceDir(context, def).deleteRecursively()
            archiveFile(context, def).delete()
            ds?.let {
                it.setDouble("progress_$VOICES_ROOT/${def.remoteArchive}", 0.0)
                it.setLong("dlid_$VOICES_ROOT/${def.remoteArchive}", 0L)
                it.setDouble("speed_$VOICES_ROOT/${def.remoteArchive}", 0.0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "delete ${def.id} failed", t)
        }
    }

    /** Total installed bytes across all extracted voices. */
    fun installedBytes(context: Context): Long {
        var total = 0L
        for (def in ALL) {
            if (isExtracted(context, def)) {
                val dir = voiceDir(context, def)
                total += dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            }
        }
        // Include legacy if present
        if (isLegacyExtracted(context)) {
            val legacy = legacyVoiceDir(context)
            total += legacy.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        return total
    }

    /**
     * Migrate legacy `piper/voice` (amy medium) to new location
     * `piper/voices/en-US/en_US-amy-medium`. Called on first launch after upgrade.
     * Keeps legacy dir if rename fails.
     */
    @Synchronized
    fun migrateLegacyIfNeeded(context: Context) {
        val legacy = legacyVoiceDir(context)
        if (!legacy.isDirectory) return
        if (!isLegacyExtracted(context)) {
            // Tiny dict or broken — delete so it is re-downloaded at new location.
            Log.d(TAG, "legacy dir present but not valid, deleting for migration")
            legacy.deleteRecursively()
            return
        }
        val def = DEFAULT
        val newDir = voiceDir(context, def)
        if (newDir.exists() && isExtracted(context, def)) {
            // Already migrated
            Log.d(TAG, "legacy already migrated, deleting legacy $legacy")
            legacy.deleteRecursively()
            return
        }
        try {
            newDir.parentFile?.mkdirs()
            if (newDir.exists()) newDir.deleteRecursively()
            val renamed = legacy.renameTo(newDir)
            if (!renamed) {
                // Fallback copy
                Log.d(TAG, "rename failed, copying legacy to $newDir")
                legacy.copyRecursively(newDir, overwrite = true)
                legacy.deleteRecursively()
            }
            Log.d(TAG, "migrated legacy $legacy -> $newDir")
        } catch (t: Throwable) {
            Log.e(TAG, "migration failed", t)
        }
    }

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

    private const val TAG = "PiperVoiceRegistry"
}
