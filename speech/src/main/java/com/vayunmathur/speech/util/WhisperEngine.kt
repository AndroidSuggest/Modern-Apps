package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.library.ml.WhisperHandle
import org.json.JSONObject

/**
 * Speech-to-text entry point for [com.vayunmathur.speech.service.WhisperRecognitionService].
 *
 * A thin seam over [WhisperHandle], which runs **whisper-base** ExecuTorch-first with a
 * LiteRT fallback: raw 16 kHz PCM goes straight at the Vulkan `.pte` when it is present
 * (no log-mel — the export eats waveform), and otherwise falls back to the [WhisperFeatures]
 * mel front end plus the int8 `.tflite` rung. [WhisperTokenizer] (byte-level decode) serves
 * both paths, unchanged.
 *
 * # The ids come out of the asset
 *
 * [GenerationConfig] reads `generation_config.json`, as the ONNX engine did, and hands the ids to
 * either backend. Nothing here hardcodes them — `<|notimestamps|>` especially, because dropping it turns the
 * transcript into timestamped text rather than failing.
 *
 * Not thread-safe: a transcription re-records the network once per token. Call [transcribe] from a
 * single worker thread.
 */
class WhisperEngine(context: Context) {
    private val app = context.applicationContext
    private val lock = Any()

    private var whisper: WhisperHandle? = null
    private var tokenizer: WhisperTokenizer? = null
    private var config: GenerationConfig? = null
    private var loadFailed = false

    /** Ids and token maps read from `generation_config.json` rather than hardcoded. */
    private class GenerationConfig(json: JSONObject) {
        val startOfTranscript = json.optInt("decoder_start_token_id", 50258)
        val endOfText = json.optInt("eos_token_id", 50257)
        val noTimestamps = json.optInt("no_timestamps_token_id", 50363)
        val maxLength = json.optInt("max_length", 448)

        /** `<|transcribe|>`; `<|translate|>` is the id one lower and unused here. */
        val transcribe: Int =
            json.optJSONObject("task_to_id")?.optInt("transcribe", 50359) ?: 50359

        /** ISO-639-1 code to its `<|xx|>` token id, e.g. `en` to 50259. */
        val langToId: Map<String, Int> = buildMap {
            val obj = json.optJSONObject("lang_to_id") ?: return@buildMap
            for (key in obj.keys()) {
                // Keys arrive as "<|en|>"; store the bare code.
                put(key.removePrefix("<|").removeSuffix("|>"), obj.optInt(key))
            }
        }

        /** Never-emit ids (music notes, formatting artefacts) that upstream masks out. */
        val suppress: IntArray = ids(json, "suppress_tokens")

        /** Additionally suppressed at the very first generated position (leading space, EOT). */
        val suppressAtBegin: IntArray = ids(json, "begin_suppress_tokens")

        /** The five scalars native wants, in [WhisperHandle]'s documented order. */
        val special: IntArray =
            intArrayOf(startOfTranscript, endOfText, transcribe, noTimestamps, maxLength)

        private companion object {
            fun ids(json: JSONObject, key: String): IntArray {
                val array = json.optJSONArray(key) ?: return IntArray(0)
                return IntArray(array.length()) { array.optInt(it) }
            }
        }
    }

    /**
     * Whether the recogniser can run. The model ships in the APK, so this is only false if it fails
     * to load — never because something still needs downloading.
     */
    fun isModelPresent(): Boolean = ensure()

    /** Load the model now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = ensure()

    @Synchronized
    private fun ensure(): Boolean {
        whisper?.let { return true }
        if (loadFailed) return false
        loadFailed = true
        val cfg = try {
            GenerationConfig(
                JSONObject(
                    app.assets.open("${WhisperModel.ASSET_DIR}/$GEN_CONFIG").use {
                        it.bufferedReader().readText()
                    },
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read $GEN_CONFIG", t)
            return false
        }
        val tok = try {
            app.assets.open("${WhisperModel.ASSET_DIR}/$VOCAB").use { WhisperTokenizer.load(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read $VOCAB", t)
            return false
        }
        // Download-only (77 MB stays out of the APK); MainActivity gates on the
        // download checker, so by the time this runs the file is present.
        val handle = WhisperHandle.inDirectory(
            WhisperModel.modelDir(app),
            cfg.special,
            cfg.langToId.values.toIntArray(),
            cfg.suppress,
            cfg.suppressAtBegin,
        )
        if (!handle.isAvailable) {
            Log.e(TAG, "cannot bring up $handle")
            handle.close()
            return false
        }
        whisper = handle
        tokenizer = tok
        config = cfg
        loadFailed = false
        Log.i(TAG, "whisper-base ready (${cfg.langToId.size} languages)")
        return true
    }

    /**
     * Transcribe [pcm16k] (16 kHz mono). [language] is ISO-639-1 or null/"auto" for automatic
     * detection. Returns the text, or null if the model isn't ready or inference failed.
     *
     * ExecuTorch-first: raw PCM goes at the Vulkan `.pte` (no mel front end), and only a
     * null from that path pays for [WhisperFeatures.logMel] plus the `.tflite` rung.
     */
    fun transcribe(pcm16k: ShortArray, language: String?): String? {
        if (!ensure()) return null
        val cfg = config ?: return null
        val tok = tokenizer ?: return null
        return try {
            val token = languageToken(cfg, language)
            val etIds = synchronized(lock) { whisper?.transcribePcm(pcm16k, token) }
            if (etIds != null) return tok.decode(etIds.toList())
            val mel = WhisperFeatures.logMel(pcm16k)
            val ids = synchronized(lock) { whisper?.transcribe(mel, token) }
                ?: return null
            tok.decode(ids.toList())
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            null
        }
    }

    /**
     * The `<|xx|>` id for [requested], or **-1** to let the model detect the language.
     *
     * An unrecognised code detects rather than falling back to English: whisper's own detection is
     * what it does by default, and it beats confidently transcribing Japanese as English.
     */
    private fun languageToken(cfg: GenerationConfig, requested: String?): Int {
        val code = requested?.substringBefore('-')?.lowercase()
        if (code == null || code == "auto") return DETECT
        cfg.langToId[code]?.let { return it }
        Log.w(TAG, "no Whisper language token for '$requested', detecting instead")
        return DETECT
    }

    @Synchronized
    fun close() {
        synchronized(lock) {
            runCatching { whisper?.close() }
            whisper = null
        }
        tokenizer = null
        config = null
        loadFailed = false
    }

    private companion object {
        const val TAG = "WhisperEngine"
        const val VOCAB = "vocab.json"
        const val GEN_CONFIG = "generation_config.json"

        /** What native reads as "detect the language yourself". */
        const val DETECT = -1
    }
}
