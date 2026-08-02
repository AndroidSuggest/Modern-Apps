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
 * Switched to **high quality 22.05 kHz** variants — all voices now use `high` checkpoint
 * (`<lang>-high.zip`, id `<locale>-<speaker>-high`, 22050 Hz). Previously used low 16 kHz
 * variants (en lessac/low, ar kareem/low, cs jirka/low true 16k + medium ckpt labeled low).
 *
 * The original single-voice layout `piper/voice` (voice3.zip, Amy medium, 22050 Hz) is kept
 * as legacy and migrated. On upgrade, old `-low.zip` / `-low` / `-medium` installs are cleaned
 * via [cleanOldLowVariants] so storage doesn't double-count.
 *
 * Each [PiperVoiceDef] is SHA-256 pinned once its high zip is published at
 * `https://data.vayunmathur.com/models/piper/`. SHAs are null until high variants are
 * built & uploaded via `scripts/speech/fetch_piper_model.sh` (now defaults to high).
 */
data class PiperVoiceDef(
    /** HF-style id "<locale>-<speaker>-<quality>", e.g. "en_US-lessac-high". */
    val id: String,
    /** ISO-639-1 or broader code, e.g. "en", "zh". */
    val code: String,
    /** BCP-47 tag used for `Voice(name)` and `Locale.forLanguageTag`. */
    val bcp47: String,
    /** ISO-639-3, e.g. "eng", "deu". */
    val iso3: String,
    /** ISO-3166-1 alpha-3 country, e.g. "USA", "DEU". */
    val iso3Country: String,
    /** Native name, e.g. "Deutsch". */
    val nativeName: String,
    /** English name, e.g. "German". */
    val englishName: String,
    /** Remote archive name at BASE, e.g. "de-high.zip". */
    val remoteArchive: String,
    /** Dictionary file inside voice dir, e.g. "de-word_id.bin". */
    val dictFile: String,
    /** SHA-256 of remote archive, null until published. */
    val sha256: String?,
    /** Quality — now "high" (22 kHz) for all. */
    val quality: String,
    /** Sample rate from config.json. */
    val sampleRate: Int,
    /** Estimated size badge in MB. */
    val sizeEstimateMb: Int,
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
     * Full catalog: high quality 22.05 kHz variants for all languages.
     * SHAs are null until high zips are built via fetch_piper_model.sh and uploaded.
     */
    val ALL: List<PiperVoiceDef> = listOf(
        // --- Original 20 curated Translate languages — now high ---
        PiperVoiceDef(
            id = "en_US-lessac-high",
            code = "en",
            bcp47 = "en-US",
            iso3 = "eng",
            iso3Country = "USA",
            nativeName = "English",
            englishName = "English",
            remoteArchive = "en-high.zip",
            dictFile = "en-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 90,
        ),
        PiperVoiceDef(
            id = "es_ES-sharvard-high",
            code = "es",
            bcp47 = "es-ES",
            iso3 = "spa",
            iso3Country = "ESP",
            nativeName = "Español",
            englishName = "Spanish",
            remoteArchive = "es-high.zip",
            dictFile = "es-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 85,
        ),
        PiperVoiceDef(
            id = "fr_FR-siwis-high",
            code = "fr",
            bcp47 = "fr-FR",
            iso3 = "fra",
            iso3Country = "FRA",
            nativeName = "Français",
            englishName = "French",
            remoteArchive = "fr-high.zip",
            dictFile = "fr-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 85,
        ),
        PiperVoiceDef(
            id = "de_DE-thorsten-high",
            code = "de",
            bcp47 = "de-DE",
            iso3 = "deu",
            iso3Country = "DEU",
            nativeName = "Deutsch",
            englishName = "German",
            remoteArchive = "de-high.zip",
            dictFile = "de-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 85,
        ),
        PiperVoiceDef(
            id = "it_IT-riccardo-high",
            code = "it",
            bcp47 = "it-IT",
            iso3 = "ita",
            iso3Country = "ITA",
            nativeName = "Italiano",
            englishName = "Italian",
            remoteArchive = "it-high.zip",
            dictFile = "it-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "pt_BR-faber-high",
            code = "pt",
            bcp47 = "pt-BR",
            iso3 = "por",
            iso3Country = "BRA",
            nativeName = "Português",
            englishName = "Portuguese",
            remoteArchive = "pt-high.zip",
            dictFile = "pt-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "nl_BE-nathalie-high",
            code = "nl",
            bcp47 = "nl-NL",
            iso3 = "nld",
            iso3Country = "NLD",
            nativeName = "Nederlands",
            englishName = "Dutch",
            remoteArchive = "nl-high.zip",
            dictFile = "nl-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ru_RU-denis-high",
            code = "ru",
            bcp47 = "ru-RU",
            iso3 = "rus",
            iso3Country = "RUS",
            nativeName = "Русский",
            englishName = "Russian",
            remoteArchive = "ru-high.zip",
            dictFile = "ru-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "pl_PL-darkman-high",
            code = "pl",
            bcp47 = "pl-PL",
            iso3 = "pol",
            iso3Country = "POL",
            nativeName = "Polski",
            englishName = "Polish",
            remoteArchive = "pl-high.zip",
            dictFile = "pl-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "tr_TR-dfki-high",
            code = "tr",
            bcp47 = "tr-TR",
            iso3 = "tur",
            iso3Country = "TUR",
            nativeName = "Türkçe",
            englishName = "Turkish",
            remoteArchive = "tr-high.zip",
            dictFile = "tr-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ar_JO-kareem-high",
            code = "ar",
            bcp47 = "ar-SA",
            iso3 = "ara",
            iso3Country = "SAU",
            nativeName = "العربية",
            englishName = "Arabic",
            remoteArchive = "ar-high.zip",
            dictFile = "ar-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "hi_IN-rohan-high",
            code = "hi",
            bcp47 = "hi-IN",
            iso3 = "hin",
            iso3Country = "IND",
            nativeName = "हिन्दी",
            englishName = "Hindi",
            remoteArchive = "hi-high.zip",
            dictFile = "hi-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "zh_CN-huayan-high",
            code = "zh",
            bcp47 = "zh-CN",
            iso3 = "zho",
            iso3Country = "CHN",
            nativeName = "中文",
            englishName = "Chinese",
            remoteArchive = "zh-high.zip",
            dictFile = "zh-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ja_JP-kana-high",
            code = "ja",
            bcp47 = "ja-JP",
            iso3 = "jpn",
            iso3Country = "JPN",
            nativeName = "日本語",
            englishName = "Japanese",
            remoteArchive = "ja-high.zip",
            dictFile = "ja-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ko_KR-kss-high",
            code = "ko",
            bcp47 = "ko-KR",
            iso3 = "kor",
            iso3Country = "KOR",
            nativeName = "한국어",
            englishName = "Korean",
            remoteArchive = "ko-high.zip",
            dictFile = "ko-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "vi_VN-vais1000-high",
            code = "vi",
            bcp47 = "vi-VN",
            iso3 = "vie",
            iso3Country = "VNM",
            nativeName = "Tiếng Việt",
            englishName = "Vietnamese",
            remoteArchive = "vi-high.zip",
            dictFile = "vi-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "th_TH-th-high",
            code = "th",
            bcp47 = "th-TH",
            iso3 = "tha",
            iso3Country = "THA",
            nativeName = "ไทย",
            englishName = "Thai",
            remoteArchive = "th-high.zip",
            dictFile = "th-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "id_ID-news_tts-high",
            code = "id",
            bcp47 = "id-ID",
            iso3 = "ind",
            iso3Country = "IDN",
            nativeName = "Bahasa Indonesia",
            englishName = "Indonesian",
            remoteArchive = "id-high.zip",
            dictFile = "id-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "uk_UA-lada-high",
            code = "uk",
            bcp47 = "uk-UA",
            iso3 = "ukr",
            iso3Country = "UKR",
            nativeName = "Українська",
            englishName = "Ukrainian",
            remoteArchive = "uk-high.zip",
            dictFile = "uk-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 90,
        ),
        PiperVoiceDef(
            id = "sv_SE-nst-high",
            code = "sv",
            bcp47 = "sv-SE",
            iso3 = "swe",
            iso3Country = "SWE",
            nativeName = "Svenska",
            englishName = "Swedish",
            remoteArchive = "sv-high.zip",
            dictFile = "sv-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),

        // --- Expanded checkpoint set — now high quality too ---
        PiperVoiceDef(
            id = "bg_BG-dimitar-high",
            code = "bg",
            bcp47 = "bg-BG",
            iso3 = "bul",
            iso3Country = "BGR",
            nativeName = "Български",
            englishName = "Bulgarian",
            remoteArchive = "bg-high.zip",
            dictFile = "bg-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "bn_BD-google-high",
            code = "bn",
            bcp47 = "bn-BD",
            iso3 = "ben",
            iso3Country = "BGD",
            nativeName = "বাংলা",
            englishName = "Bengali",
            remoteArchive = "bn-high.zip",
            dictFile = "bn-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ca_ES-upc_ona-high",
            code = "ca",
            bcp47 = "ca-ES",
            iso3 = "cat",
            iso3Country = "ESP",
            nativeName = "Català",
            englishName = "Catalan",
            remoteArchive = "ca-high.zip",
            dictFile = "ca-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "cs_CZ-jirka-high",
            code = "cs",
            bcp47 = "cs-CZ",
            iso3 = "ces",
            iso3Country = "CZE",
            nativeName = "Čeština",
            englishName = "Czech",
            remoteArchive = "cs-high.zip",
            dictFile = "cs-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "da_DK-talesyntese-high",
            code = "da",
            bcp47 = "da-DK",
            iso3 = "dan",
            iso3Country = "DNK",
            nativeName = "Dansk",
            englishName = "Danish",
            remoteArchive = "da-high.zip",
            dictFile = "da-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "el_GR-rapunzelina-high",
            code = "el",
            bcp47 = "el-GR",
            iso3 = "ell",
            iso3Country = "GRC",
            nativeName = "Ελληνικά",
            englishName = "Greek",
            remoteArchive = "el-high.zip",
            dictFile = "el-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "fi_FI-harri-high",
            code = "fi",
            bcp47 = "fi-FI",
            iso3 = "fin",
            iso3Country = "FIN",
            nativeName = "Suomi",
            englishName = "Finnish",
            remoteArchive = "fi-high.zip",
            dictFile = "fi-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "he_IL-saspeech-high",
            code = "he",
            bcp47 = "he-IL",
            iso3 = "heb",
            iso3Country = "ISR",
            nativeName = "עברית",
            englishName = "Hebrew",
            remoteArchive = "he-high.zip",
            dictFile = "he-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "hu_HU-anna-high",
            code = "hu",
            bcp47 = "hu-HU",
            iso3 = "hun",
            iso3Country = "HUN",
            nativeName = "Magyar",
            englishName = "Hungarian",
            remoteArchive = "hu-high.zip",
            dictFile = "hu-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ka_GE-natia-high",
            code = "ka",
            bcp47 = "ka-GE",
            iso3 = "kat",
            iso3Country = "GEO",
            nativeName = "ქართული",
            englishName = "Georgian",
            remoteArchive = "ka-high.zip",
            dictFile = "ka-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ku_TR-berfin_renas-high",
            code = "ku",
            bcp47 = "ku-TR",
            iso3 = "kur",
            iso3Country = "TUR",
            nativeName = "Kurdî",
            englishName = "Kurdish",
            remoteArchive = "ku-high.zip",
            dictFile = "ku-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "lb_LU-marylux-high",
            code = "lb",
            bcp47 = "lb-LU",
            iso3 = "ltz",
            iso3Country = "LUX",
            nativeName = "Lëtzebuergesch",
            englishName = "Luxembourgish",
            remoteArchive = "lb-high.zip",
            dictFile = "lb-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ml_IN-arjun-high",
            code = "ml",
            bcp47 = "ml-IN",
            iso3 = "mal",
            iso3Country = "IND",
            nativeName = "മലയാളം",
            englishName = "Malayalam",
            remoteArchive = "ml-high.zip",
            dictFile = "ml-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "mr_IN-google-high",
            code = "mr",
            bcp47 = "mr-IN",
            iso3 = "mar",
            iso3Country = "IND",
            nativeName = "मराठी",
            englishName = "Marathi",
            remoteArchive = "mr-high.zip",
            dictFile = "mr-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ne_NP-google-high",
            code = "ne",
            bcp47 = "ne-NP",
            iso3 = "nep",
            iso3Country = "NPL",
            nativeName = "नेपाली",
            englishName = "Nepali",
            remoteArchive = "ne-high.zip",
            dictFile = "ne-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "no_NO-talesyntese-high",
            code = "nb",
            bcp47 = "nb-NO",
            iso3 = "nob",
            iso3Country = "NOR",
            nativeName = "Norsk Bokmål",
            englishName = "Norwegian",
            remoteArchive = "nb-high.zip",
            dictFile = "nb-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ro_RO-mihai-high",
            code = "ro",
            bcp47 = "ro-RO",
            iso3 = "ron",
            iso3Country = "ROU",
            nativeName = "Română",
            englishName = "Romanian",
            remoteArchive = "ro-high.zip",
            dictFile = "ro-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "sk_SK-lili-high",
            code = "sk",
            bcp47 = "sk-SK",
            iso3 = "slk",
            iso3Country = "SVK",
            nativeName = "Slovenčina",
            englishName = "Slovak",
            remoteArchive = "sk-high.zip",
            dictFile = "sk-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "sr_RS-serbski_institut-high",
            code = "sr",
            bcp47 = "sr-RS",
            iso3 = "srp",
            iso3Country = "SRB",
            nativeName = "Српски",
            englishName = "Serbian",
            remoteArchive = "sr-high.zip",
            dictFile = "sr-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "sw_CD-lanfrica-high",
            code = "sw",
            bcp47 = "sw-CD",
            iso3 = "swa",
            iso3Country = "COD",
            nativeName = "Kiswahili",
            englishName = "Swahili",
            remoteArchive = "sw-high.zip",
            dictFile = "sw-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "te_IN-maya-high",
            code = "te",
            bcp47 = "te-IN",
            iso3 = "tel",
            iso3Country = "IND",
            nativeName = "తెలుగు",
            englishName = "Telugu",
            remoteArchive = "te-high.zip",
            dictFile = "te-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
        PiperVoiceDef(
            id = "ur_PK-fasih-high",
            code = "ur",
            bcp47 = "ur-PK",
            iso3 = "urd",
            iso3Country = "PAK",
            nativeName = "اردو",
            englishName = "Urdu",
            remoteArchive = "ur-high.zip",
            dictFile = "ur-word_id.bin",
            sha256 = null,
            quality = "high",
            sampleRate = 22050,
            sizeEstimateMb = 80,
        ),
    )

    /** Default English voice — now en-high.zip (Lessac high, 22 kHz). */
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

    fun resolve(
        lang: String? = null,
        country: String? = null,
        variant: String? = null,
        voiceName: String? = null,
    ): PiperVoiceDef? {
        voiceName?.let { name ->
            val lower = name.lowercase()
            val xIdx = lower.indexOf("-x-")
            if (xIdx >= 0) {
                val bcpPart = name.substring(0, xIdx)
                byBcp47(bcpPart)?.let { return it }
                val idPart = name.substring(xIdx + 3)
                byId(idPart)?.let { return it }
            }
            byBcp47(name)?.let { return it }
            byId(name)?.let { return it }
            val parts = name.split("-", "_")
            if (parts.isNotEmpty()) {
                byCode(parts[0])?.let { return it }
                byIso3(parts[0])?.let { return it }
            }
            if (parts.size >= 2) {
                byIso3(parts[0])?.let { return it }
            }
        }

        lang?.let { l ->
            val ll = l.lowercase()
            byCode(ll)?.let { return it }
            byIso3(ll)?.let { return it }
            byBcp47(ll)?.let { return it }
            val normalized = ll.replace('_', '-')
            byBcp47(normalized)?.let { return it }
            if (ll.length >= 2) {
                val prefix = ll.substring(0, 2)
                byCode(prefix)?.let { return it }
            }
        }

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

    fun legacyArchive(context: Context): File {
        val root = rootDir(context) ?: return File(LEGACY_ARCHIVE)
        return File(root, LEGACY_ARCHIVE)
    }

    fun legacyVoiceDir(context: Context): File {
        val root = rootDir(context) ?: return File(LEGACY_VOICE_DIR)
        return File(root, LEGACY_VOICE_DIR)
    }

    fun voicePrefix(context: Context, def: PiperVoiceDef): String? {
        val files = voiceDir(context, def).listFiles() ?: return null
        val encoder = files.firstOrNull { it.name.endsWith(ENCODER_SUFFIX) } ?: return null
        return encoder.name.removeSuffix(ENCODER_SUFFIX)
    }

    fun isExtracted(context: Context, def: PiperVoiceDef): Boolean {
        val dir = voiceDir(context, def)
        if (!dir.isDirectory) {
            if (def.code == "en") {
                val legacyDir = legacyVoiceDir(context)
                if (legacyDir.isDirectory) {
                    return isLegacyExtracted(context)
                }
            }
            return false
        }
        if (dir.listFiles()?.any { it.name.endsWith(".onnx") } == true) return false

        val dictPath = File(dir, def.dictFile)
        val altEnDict = File(dir, "en-word_id.bin")
        val dictFile = when {
            dictPath.exists() -> dictPath
            altEnDict.exists() -> altEnDict
            else -> {
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

    fun isAnyExtracted(context: Context): Boolean =
        ALL.any { isExtracted(context, it) } || isLegacyExtracted(context)

    fun installedDefs(context: Context): List<PiperVoiceDef> =
        ALL.filter { isExtracted(context, it) }.let { list ->
            if (list.isEmpty() && isLegacyExtracted(context)) listOf(DEFAULT) else list
        }

    fun installedCodes(context: Context): List<String> =
        installedDefs(context).map { it.code }.distinct()

    fun downloadItems(codes: List<String>): List<ModelDownloadItem> {
        val defs = if (codes.isEmpty()) ALL else codes.mapNotNull { byCode(it) }
        return defs.map { def ->
            val fileName = "$VOICES_ROOT/${def.remoteArchive}"
            ModelDownloadItem(
                url = "$BASE${def.remoteArchive}",
                fileName = fileName,
                description = "${def.nativeName} (${def.englishName}) voice high 22kHz (${def.sizeEstimateMb} MB)",
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

    fun overallProgress(ds: DataStoreUtils, codes: List<String>? = null): Float {
        val defs = if (codes == null) ALL else codes.mapNotNull { byCode(it) }
        if (defs.isEmpty()) return 0f
        return defs.map { progress(ds, it) }.average().toFloat()
    }

    @Synchronized
    fun installIfNeeded(context: Context, def: PiperVoiceDef): Boolean {
        if (isExtracted(context, def)) return true
        val zip = archiveFile(context, def)

        val altZips = mutableListOf<File>()
        if (def.code == "en") {
            altZips += legacyArchive(context)
            File(rootDir(context), "$DIR/${def.remoteArchive}").let { altZips += it }
            File(rootDir(context), "$DIR/$LEGACY_REMOTE_ARCHIVE").let { altZips += it }
        }

        val usableZip = when {
            zip.exists() -> zip
            else -> altZips.firstOrNull { it.exists() }?.also { found ->
                found.renameTo(zip)
            } ?: return false
        }

        val dir = voiceDir(context, def)
        val tmp = File(voicesDir(context), ".${def.id}.tmp")
        tmp.deleteRecursively()

        return try {
            unzip(usableZip, tmp)

            if (!File(tmp, "en-word_id.bin").exists()) {
                val enDir = voiceDir(context, DEFAULT)
                val enDictSrc = File(enDir, "en-word_id.bin")
                val anyDict = tmp.listFiles()?.firstOrNull { it.name.endsWith("-word_id.bin") }
                try {
                    if (enDictSrc.exists()) {
                        enDictSrc.copyTo(File(tmp, "en-word_id.bin"), overwrite = false)
                    } else if (anyDict != null) {
                        anyDict.copyTo(File(tmp, "en-word_id.bin"), overwrite = false)
                        Log.d(TAG, "copied ${anyDict.name} -> en-word_id.bin for 1.7.0 compat in ${def.id}")
                    }
                } catch (_: Throwable) {
                }
            }

            dir.parentFile?.mkdirs()
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IllegalStateException("rename $tmp -> $dir failed")
            usableZip.delete()
            altZips.forEach { it.takeIf { f -> f.exists() && f.absolutePath != usableZip.absolutePath }?.delete() }
            if (def.code == "en") {
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

    fun installedBytes(context: Context): Long {
        var total = 0L
        for (def in ALL) {
            if (isExtracted(context, def)) {
                val dir = voiceDir(context, def)
                total += dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            }
        }
        if (isLegacyExtracted(context)) {
            val legacy = legacyVoiceDir(context)
            total += legacy.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        return total
    }

    @Synchronized
    fun cleanOldLowVariants(context: Context) {
        try {
            val vRoot = voicesDir(context)
            if (vRoot.isDirectory) {
                // Delete old -low.zip archives
                vRoot.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith("-low.zip")) {
                        Log.d(TAG, "deleting old low archive ${f.name}")
                        f.delete()
                    }
                }
                // Delete extracted dirs whose id ends with -low or -medium (previous scheme)
                vRoot.listFiles()?.forEach { bcpDir ->
                    if (!bcpDir.isDirectory) return@forEach
                    bcpDir.listFiles()?.forEach { voiceDir ->
                        val n = voiceDir.name
                        if (voiceDir.isDirectory && (n.endsWith("-low") || n.endsWith("-medium"))) {
                            Log.d(TAG, "deleting old low/medium voice dir ${bcpDir.name}/${voiceDir.name}")
                            voiceDir.deleteRecursively()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cleanOldLowVariants failed", t)
        }
    }

    @Synchronized
    fun migrateLegacyIfNeeded(context: Context) {
        cleanOldLowVariants(context)
        val legacy = legacyVoiceDir(context)
        if (!legacy.isDirectory) return
        if (!isLegacyExtracted(context)) {
            Log.d(TAG, "legacy dir present but not valid, deleting for migration")
            legacy.deleteRecursively()
            return
        }
        val def = DEFAULT
        val newDir = voiceDir(context, def)
        if (newDir.exists() && isExtracted(context, def)) {
            Log.d(TAG, "legacy already migrated, deleting legacy $legacy")
            legacy.deleteRecursively()
            return
        }
        try {
            newDir.parentFile?.mkdirs()
            if (newDir.exists()) newDir.deleteRecursively()
            val renamed = legacy.renameTo(newDir)
            if (!renamed) {
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
