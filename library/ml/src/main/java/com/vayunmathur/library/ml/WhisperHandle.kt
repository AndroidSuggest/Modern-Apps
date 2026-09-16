package com.vayunmathur.library.ml

import android.util.Log
import java.io.File
import com.google.ai.edge.litert.CompiledModel
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * On-device speech recognition in ~99 languages: whisper-base, ExecuTorch-first with a LiteRT
 * fallback.
 *
 * The preferred path is the Vulkan int8 `.pte` (`whisper_base_vulkan_int8.pte`, ~196 MB, with
 * `whisper_base_vulkan_fp16.pte` as fallback) on ExecuTorch, resolved opportunistically from
 * the download directory — no mirror pins exist yet, so it is never a gated download. Unlike
 * the LiteRT rung it eats **raw waveform**: `encode` takes `[480000]` float32 samples straight
 * to the decoder's full cross-attention KV cache (`[1,1500,6144]` = 512 x 6 layers x K+V),
 * and `decode` is single-token incremental (`token [1,1]` int64 + `position [1]` int64 +
 * cache → `[1,1,51865]` logits). No log-mel, no 128-prefix, no causal mask. See
 * `analysis/et-whisper/PARITY.md` for the interface notes.
 *
 * The fallback is the ladder ship rung (`base_30s_i8.tflite`, vendor int8) on LiteRT, which
 * exposes two signatures: `encode` (`args_0 [1,80,3000]` mel → `output_0 [1,1500,512]`
 * hidden) and `decode` (`args_0` hidden + `args_1 [1,128]` int32 token prefix +
 * `args_2 [1,1,128,128]` causal mask → `output_0 [1,128,51865]` logits). No KV cache:
 * each step feeds the whole produced prefix padded to 128.
 *
 * [transcribe] tries ExecuTorch first and falls back to LiteRT when the `.pte` is absent,
 * the Vulkan delegate is not linked, or the run fails — so the recogniser keeps working on
 * devices the custom Vulkan AAR does not cover yet. The `.tflite` stays regardless, until
 * the on-device transcript-level parity gate passes (exact token-id match on fixed
 * fixtures; encoder-hidden cosine is NOT a valid gate — the representations differ).
 *
 * 72.6 million parameters.
 *
 * # One downloaded file (plus an opportunistic second)
 *
 * `whisper/base_30s_i8.tflite` downloads on first use (see `WhisperModel` in `:speech`),
 * so this has an [inDirectory] and no asset path. The ET candidate lives next to it when
 * present; it is never required.
 *
 * An asset must be stored **uncompressed** if ever bundled: `noCompress += "tflite"`.
 *
 * # The special ids come from the asset, not from here
 *
 * [inDirectory] takes them as arguments rather than hardcoding them, because they live in
 * the model's own `generation_config.json` and a second copy would be a second thing to
 * get wrong. The `<|notimestamps|>` id in particular is part of the decoder prompt, and
 * dropping it turns the output into timestamped text rather than failing. Both backends
 * share the same five scalars, suppress lists and 224-token cap.
 *
 * # Latency
 *
 * The encoder is ~43.7 GMAC per 30-second window regardless of how much speech is in it.
 * The ET path replaces each full-prefix LiteRT decode step with one single-position pass,
 * so it is strictly cheaper per token once loaded. Either way this is not a real-time
 * recogniser; call it from a worker.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when neither backend came up — the
 * files are absent, have an operator the runtime cannot execute, or the ids do not
 * describe this model — and then [transcribe] and [transcribePcm] return null.
 *
 * # Threading
 *
 * Not thread-safe: a transcription runs one decode step per token, so two concurrent
 * calls would interleave states. A caller must hold a lock across [transcribe],
 * [transcribePcm] and [close].
 */
class WhisperHandle private constructor(private val source: String) : AutoCloseable {
    private var session: CompiledModel? = null
    private var etModule: Module? = null
    private var special: IntArray = IntArray(0)
    private var suppress: Set<Int> = emptySet()
    private var suppressAtBegin: Set<Int> = emptySet()
    private var languages: IntArray = IntArray(0)
    private var modelFile: String = ""
    private var etModelFile: String = ""
    private val lock = Any()

    /** True if either backend came up. */
    val isAvailable: Boolean get() = session != null || etModule != null

    /**
     * Transcribe one 30-second log-mel window into token ids, or null on failure.
     *
     * [mel] is `MELS * FRAMES` floats row-major. [languageToken] is a `<|xx|>` id, or negative to let
     * the model detect the language. The ids are raw — the caller's tokenizer skips the special and
     * timestamp ones.
     */
    fun transcribe(mel: FloatArray, languageToken: Int): IntArray? {
        if (mel.size != MELS * FRAMES) return null
        if (special.size != SPECIAL_IDS) return null
        val live = session ?: return null
        return try {
            val hidden = synchronized(lock) {
                LiteRtSessions.runSignature(live, mapOf("args_0" to mel), listOf("output_0"), ENCODE)
            }?.get("output_0") as? FloatArray ?: return null
            val lang = if (languageToken < 0) {
                detectLanguage(live, hidden) ?: return null
            } else {
                languageToken
            }
            val prompt = intArrayOf(special[0], lang, special[2], special[3])
            synchronized(lock) { greedyDecode(live, hidden, prompt) }?.toIntArray()
        } catch (e: Throwable) {
            Log.e(TAG, "whisper transcription failed", e)
            null
        }
    }

    /**
     * Transcribe one 30-second window of **raw PCM** into token ids, or null on failure.
     *
     * [pcm16k] is 16 kHz mono, padded with silence or truncated to [SAMPLES_30S] exactly
     * like the mel front end does. This is the ExecuTorch-first entry point: the Vulkan
     * export eats raw waveform, so no log-mel is computed. Null when the ET module is
     * down (or a run fails) so the caller can fall back to [transcribe] on the `.tflite`.
     * [languageToken] is a `<|xx|>` id, or negative to let the model detect the language.
     * The ids are raw — the caller's tokenizer skips the special and timestamp ones.
     */
    fun transcribePcm(pcm16k: ShortArray, languageToken: Int): IntArray? {
        if (special.size != SPECIAL_IDS) return null
        val live = etModule ?: return null
        return try {
            val pcm = FloatArray(SAMPLES_30S)
            val count = pcm16k.size.coerceAtMost(SAMPLES_30S)
            for (i in 0 until count) pcm[i] = pcm16k[i] / 32768f
            val hidden = synchronized(lock) { etEncode(live, pcm) } ?: return null
            val lang = if (languageToken < 0) {
                etDetectLanguage(live, hidden) ?: return null
            } else {
                languageToken
            }
            val prompt = intArrayOf(special[0], lang, special[2], special[3])
            synchronized(lock) { etGreedyDecode(live, hidden, prompt) }?.toIntArray()
        } catch (e: Throwable) {
            Log.e(TAG, "whisper ET transcription failed, falling back to LiteRT", e)
            null
        }
    }

    /** Free both backends. Idempotent. */
    override fun close() {
        synchronized(lock) {
            session = null
            LiteRtSessions.close(sessionKey())
            etModule = null
            if (etModelFile.isNotEmpty()) ExecutorchSessions.close("file:$etModelFile")
        }
    }

    private fun sessionKey(): String = "file:$modelFile"

    override fun toString(): String = "whisper-base from $source"

    /** Language detection: one decode step from `<|startoftranscript|>`, argmax over [languages]. */
    private fun detectLanguage(live: CompiledModel, hidden: FloatArray): Int? {
        return try {
            val logits = decodeStep(live, hidden, intArrayOf(special[0])) ?: return null
            val vocab = logits.size
            val fallback = 50259
            var best = fallback
            var bestScore = -Float.MAX_VALUE
            for (id in languages) {
                if (id < vocab && logits[id] > bestScore) {
                    bestScore = logits[id]
                    best = id
                }
            }
            best
        } catch (e: Throwable) {
            Log.w(TAG, "language detection failed, assuming en", e)
            50259
        }
    }

    /**
     * Greedy decode: each step feeds the whole produced prefix (padded to [PREFIX_LEN]
     * with a matching causal mask) and takes the argmax of the last real row.
     */
    private fun greedyDecode(
        live: CompiledModel,
        hidden: FloatArray,
        prompt: IntArray,
    ): List<Int> {
        val out = ArrayList<Int>()
        val limit = (special[4] - prompt.size).coerceAtMost(MAX_NEW_TOKENS)
        val fed = ArrayList<Int>()
        fed.addAll(prompt.toList())
        var step = 0
        while (step < limit) {
            val logits = decodeStep(live, hidden, fed.toIntArray()) ?: break
            val suppressMerged = if (step == 0) suppress + suppressAtBegin else suppress
            val next = argmax(logits, suppressMerged)
            fed.add(next)
            step++
            if (next == special[1]) break
            // Skip the prompt tail: only tokens after the 4 prompt ids are output.
            if (fed.size > prompt.size) out.add(next)
        }
        return out
    }

    /**
     * One decode step over the prefix [fed]: pad to [PREFIX_LEN], build the causal mask
     * (row r attends to columns < fed.size), run, and return the last real row's logits.
     */
    private fun decodeStep(live: CompiledModel, hidden: FloatArray, fed: IntArray): FloatArray? {
        if (fed.size > PREFIX_LEN) return null
        val ids = IntArray(PREFIX_LEN)
        fed.copyInto(ids)
        val mask = FloatArray(PREFIX_LEN * PREFIX_LEN)
        for (r in 0 until PREFIX_LEN) {
            for (c in 0 until fed.size.coerceAtMost(r + 1)) {
                mask[r * PREFIX_LEN + c] = 1f
            }
        }
        val out = LiteRtSessions.runSignature(
            live,
            mapOf(
                "args_0" to hidden,
                "args_1" to ids,
                "args_2" to mask,
            ),
            listOf("output_0"),
            DECODE,
        ) ?: return null
        val flat = out["output_0"] as? FloatArray ?: return null
        val vocab = flat.size / PREFIX_LEN
        if (vocab <= 0) return null
        return flat.copyOfRange((fed.size - 1) * vocab, fed.size * vocab)
    }

    // -- ExecuTorch path (Vulkan export: raw waveform in, incremental decode) -------

    /**
     * One ET `encode` method call: `[480000]` waveform → `[1,1500,6144]` KV-cache hidden.
     *
     * Float-only, so the scaffold's `runFloat` covers it directly. The export config pins
     * the input shape at `[480000]` (flat, not `[1,480000]`).
     */
    private fun etEncode(live: Module, pcm: FloatArray): FloatArray? {
        val hidden = ExecutorchSessions.runFloat(live, pcm, longArrayOf(SAMPLES_30S.toLong()), ET_ENCODE)
            ?: return null
        if (hidden.size != ET_ENC_SEQ * ET_ENC_KV) {
            Log.e(TAG, "unexpected ET encode width ${hidden.size}, want ${ET_ENC_SEQ * ET_ENC_KV}")
            return null
        }
        return hidden
    }

    /** ET language detection: one decode step feeding `<|startoftranscript|>` at position 0. */
    private fun etDetectLanguage(live: Module, hidden: FloatArray): Int? {
        return try {
            val logits = etDecodeStep(live, hidden, special[0], 0) ?: return null
            val fallback = 50259
            var best = fallback
            var bestScore = -Float.MAX_VALUE
            for (id in languages) {
                if (id < logits.size && logits[id] > bestScore) {
                    bestScore = logits[id]
                    best = id
                }
            }
            best
        } catch (e: Throwable) {
            Log.w(TAG, "ET language detection failed, assuming en", e)
            50259
        }
    }

    /**
     * ET incremental greedy decode: feed the 4 prompt ids at positions 0..3, then sample
     * each new token from the previous position's logits and feed it at the next position.
     * The KV cache rides inside [hidden], re-passed per step per the export's interface.
     */
    private fun etGreedyDecode(live: Module, hidden: FloatArray, prompt: IntArray): List<Int> {
        val out = ArrayList<Int>()
        val limit = (special[4] - prompt.size).coerceAtMost(ET_MAX_NEW_TOKENS)
        // Prime with the prompt: step i feeds prompt[i] at position i.
        var logits: FloatArray? = null
        for (i in prompt.indices) {
            logits = etDecodeStep(live, hidden, prompt[i], i) ?: return out
        }
        var position = prompt.size
        var step = 0
        while (step < limit) {
            val row = logits ?: break
            val suppressMerged = if (step == 0) suppress + suppressAtBegin else suppress
            val next = argmax(row, suppressMerged)
            step++
            if (next == special[1]) break
            out.add(next)
            logits = etDecodeStep(live, hidden, next, position) ?: break
            position++
        }
        return out
    }

    /**
     * One ET `decode` method call: token [tokenId] at [position] with the KV-cache
     * [hidden]; returns the single row of logits (`[1,1,51865]`).
     *
     * Mixed int64 + float inputs, so this goes through `run` with one `EValue` per input
     * — `runFloat` is float-only. Token ids cross as int64 (`Tensor.fromBlob(LongArray,
     * LongArray)` is the int64 tensor; do not narrow to int). Logits read HALF-tolerant
     * via [floatsAllowingHalf] (Vulkan fp16 ladders lower outputs to half).
     */
    private fun etDecodeStep(
        live: Module,
        hidden: FloatArray,
        tokenId: Int,
        position: Int,
    ): FloatArray? {
        val outs = ExecutorchSessions.run(
            live,
            listOf(
                EValue.from(
                    Tensor.fromBlob(longArrayOf(tokenId.toLong()), longArrayOf(1, 1)),
                ),
                EValue.from(
                    Tensor.fromBlob(longArrayOf(position.toLong()), longArrayOf(1)),
                ),
                EValue.from(
                    Tensor.fromBlob(hidden, longArrayOf(1, ET_ENC_SEQ.toLong(), ET_ENC_KV.toLong())),
                ),
            ),
            ET_DECODE,
        ) ?: return null
        val first = outs.firstOrNull() ?: return null
        val row = first.floatsAllowingHalf(TAG, ET_VOCAB) ?: return null
        if (row.size != ET_VOCAB) {
            Log.e(TAG, "unexpected ET decode width ${row.size}, want $ET_VOCAB")
            return null
        }
        return row
    }

    private fun argmax(row: FloatArray, skip: Set<Int>): Int {
        var best = 0
        var bestScore = -Float.MAX_VALUE
        for (i in row.indices) {
            if (i in skip) continue
            if (row[i] > bestScore) {
                bestScore = row[i]
                best = i
            }
        }
        return best
    }

    companion object {
        private const val TAG = "WhisperHandle"

        /** Mel bins the front end produces. */
        const val MELS = 80

        /** Mel frames in one 30-second window at a 160-sample hop. */
        const val FRAMES = 3000

        /** 30 s at 16 kHz mono — what the ET export's `encode` eats as raw waveform. */
        const val SAMPLES_30S = 480_000

        /** The one graph (ladder ship rung). */
        const val MODEL = "base_30s_i8.tflite"

        /**
         * The ExecuTorch candidate (lead: smallest Vulkan export holding the parity gate),
         * resolved opportunistically from the download directory — never a gated download
         * until mirror pins land. Prefers the Vulkan-100% rungs (0 XNNPACK ops) over the
         * staged partial-delegation files; see PARITY.md §6.
         */
        const val ET_MODEL = "whisper_base_vulkan_int8_100pte.pte"

        /** ET fallback when int8 diverges: the fp16 Vulkan export (100% rung first). */
        const val ET_MODEL_FALLBACK = "whisper_base_vulkan_fp16.pte"

        /** ET second fallback: the staged partial-delegation files, if those are what is on disk. */
        const val ET_MODEL_LEGACY = "whisper_base_vulkan_int8.pte"

        private const val ENCODE = "encode"
        private const val DECODE = "decode"
        private const val ET_ENCODE = "encode"
        private const val ET_DECODE = "decode"

        /** Fixed decode prefix length of the TFLite export. */
        private const val PREFIX_LEN = 128

        /**
         * Encoder output geometry of the Vulkan export: 1500 frames, width 6144 =
         * 512 (d_model) x 6 (encoder layers) x 2 (K and V) — the decoder's full
         * cross-attention KV cache, NOT the TFLite rung's [1500, 512].
         */
        private const val ET_ENC_SEQ = 1500
        private const val ET_ENC_KV = 6144

        /** Output vocabulary width of the ET decode logits. */
        private const val ET_VOCAB = 51865

        /** One 30 s window cannot say more than this on the ET path either. */
        private const val ET_MAX_NEW_TOKENS = 224

        /**
         * The five values [inDirectory] wants in `special`, in order:
         * `decoder_start_token_id`, `eos_token_id`, `transcribe`, `no_timestamps_token_id`,
         * `max_length`.
         */
        const val SPECIAL_IDS = 5

        /** Encoder output geometry: 3000 mel frames through the /2 stem, width 512. */
        private const val ENC_SEQ = 1500
        private const val ENC_DIM = 512

        /**
         * The model in a folder on disk, as a downloaded one is.
         *
         * Same arguments as the old `inAssets`; sessions open from files instead of APK assets.
         * The ET candidate opens opportunistically from the same directory (int8 first, then
         * fp16) when the Vulkan delegate is linked — a missing `.pte` just means the
         * `.tflite` serves every turn.
         */
        fun inDirectory(
            directory: File,
            special: IntArray,
            languages: IntArray,
            suppress: IntArray,
            suppressAtBegin: IntArray,
        ): WhisperHandle {
            val instance = WhisperHandle(directory.toString())
            if (special.size != SPECIAL_IDS) {
                Log.e(TAG, "${special.size} special ids, not $SPECIAL_IDS")
                return instance
            }
            instance.special = special.copyOf()
            instance.languages = languages.copyOf()
            instance.suppress = suppress.toSet()
            instance.suppressAtBegin = suppressAtBegin.toSet()
            val file = File(directory, MODEL)
            instance.modelFile = file.absolutePath
            instance.session = LiteRtSessions.openFile(file.absolutePath)
            val etFile = listOf(ET_MODEL, ET_MODEL_FALLBACK, ET_MODEL_LEGACY)
                .map { File(directory, it) }
                .firstOrNull { it.isFile }
            if (etFile != null) {
                instance.etModelFile = etFile.absolutePath
                instance.etModule = openEt(etFile.absolutePath)
            }
            if (!instance.isAvailable) Log.e(TAG, "cannot open $directory")
            return instance
        }

        /**
         * The ET module for an absolute `.pte` path, or null when it cannot serve here.
         *
         * Skipped unless the Vulkan delegate is linked into the runtime (the stock
         * `executorch-android` AAR ships XNNPACK only), so this degrades to LiteRT
         * behaviour on devices without the custom Vulkan AAR rather than loading a
         * module that can never run.
         */
        private fun openEt(path: String): Module? {
            val backends = ExecutorchSessions.registeredBackends()
            if (backends?.any { it.contains("Vulkan", ignoreCase = true) } != true) {
                Log.i(TAG, "Vulkan backend absent, skipping ET whisper at $path")
                return null
            }
            return ExecutorchSessions.openPath(path)
        }

        /** One 30 s window cannot say more than this; guards against a runaway loop. */
        private const val MAX_NEW_TOKENS = 224
    }
}
