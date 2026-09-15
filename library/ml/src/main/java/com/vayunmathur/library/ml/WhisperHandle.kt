package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device speech recognition in ~99 languages: whisper-base on the reduced ONNX Runtime
 * build.
 *
 * A two-convolution stem that turns `[80, 3000]` log-mel frames into 1500 positions, 6 encoder
 * layers, and 6 decoder layers with self and cross attention; `d_model` 512, 8 heads of 64, and a
 * 51,865-entry vocabulary tied between the input embedding and the logits projection. 72.6 million
 * parameters.
 *
 * # Two bundled assets
 *
 * `whisper-base/encoder_model_int8.onnx` and `whisper-base/decoder_model_merged_int8.onnx`
 * ship inside the APK, so this has an [inAssets] and no download. The decoder is the merged
 * export: it takes all 24 `past_key_values.*` inputs on every call (zero-length with
 * `use_cache_branch = false` on the first step, which makes it compute cross-attention K/V
 * from `encoder_hidden_states`) and returns `present.*` for the next step.
 *
 * An asset must be stored **uncompressed**: `noCompress += "onnx"` in
 * `speech/build.gradle.kts`.
 *
 * # The special ids come from the asset, not from here
 *
 * [inAssets] takes them as arguments rather than hardcoding them, because they live in the model's
 * own `generation_config.json` and a second copy would be a second thing to get wrong. The
 * `<|notimestamps|>` id in particular is part of the decoder prompt, and dropping it turns the
 * output into timestamped text rather than failing.
 *
 * # Latency
 *
 * The encoder is ~43.7 GMAC per 30-second window regardless of how much speech is in it, and every
 * decode step runs the full decoder. This is not a real-time recogniser; call it from a worker.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when an asset is absent or has an
 * operator outside the reduced build, or when the ids do not describe this model — and then
 * [transcribe] returns null.
 *
 * When the Vulkan backend is usable and a graph is allowlisted, that graph runs on the
 * Vulkan fast path and ORT is kept only as the fallback, per graph: a Vulkan failure (or
 * short output) falls back to the ORT session when it exists, preserving the null-on-failure
 * contract. The encoder needs no cache; the decoder threads a native KV cache (one
 * `kvCreate` per transcription, mirroring the [Past]/emptyPast structure below) and the
 * 51,865-wide argmax goes through the bridge so the host never scans the full row.
 *
 * # Threading
 *
 * Not thread-safe, and more sharply than most handles here: a transcription threads the KV
 * cache through one decode step per token, so two concurrent calls would interleave cache
 * states. A caller must hold a lock across [transcribe] and [close].
 */
class WhisperHandle private constructor(private val source: String) : AutoCloseable {
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vulkanEnc: Long = 0L
    private var vulkanDec: Long = 0L
    private var special: IntArray = IntArray(0)
    private var suppress: Set<Int> = emptySet()
    private var suppressAtBegin: Set<Int> = emptySet()
    private var assetDir: String = DIR

    /** True if both graphs came up — on Vulkan, on ORT, or one of each — and the ids describe them. */
    val isAvailable: Boolean
        get() = (encoder != null || vulkanEnc != 0L) &&
            (decoder != null || vulkanDec != 0L)

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
        // Vulkan-first, per graph; any failure drops through to the ORT path.
        try {
            vulkanTranscribe(mel, languageToken)?.let { return it }
        } catch (e: Throwable) {
            Log.w(TAG, "whisper vulkan transcription failed, falling back to ORT", e)
        }
        val dec = decoder ?: return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            ortHiddenTensor(env, mel).useOrt { hidden ->
                if (hidden == null) return null
                val lang = if (languageToken < 0) {
                    detectLanguage(dec, env, hidden) ?: return null
                } else {
                    languageToken
                }
                val prompt = intArrayOf(
                    special[0], lang, special[2], special[3],
                )
                greedyDecode(dec, env, hidden, prompt).toIntArray()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "whisper transcription failed", e)
            null
        }
    }

    /**
     * ORT hidden states for [mel]: the ORT encoder when it exists, otherwise the
     * Vulkan encoder's floats wrapped back into a tensor so a mixed
     * Vulkan-encode + ORT-decode turn still serves. Null when neither can encode.
     */
    private fun ortHiddenTensor(env: OrtEnvironment, mel: FloatArray): OnnxTensor? {
        val enc = encoder
        if (enc != null) {
            return runCatching { encode(enc, env, mel) }.getOrNull()
        }
        val fast = runCatching { vulkanEncodeMel(mel) }.getOrNull() ?: return null
        return runCatching {
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(fast.data),
                longArrayOf(1, fast.seq.toLong(), fast.dim.toLong()),
            )
        }.getOrNull()
    }

    /** Free both networks (Vulkan and/or ORT). Idempotent. */
    override fun close() {
        val encHandle = vulkanEnc
        vulkanEnc = 0L
        if (encHandle != 0L) {
            try {
                VulkanSessions.close(encHandle)
            } catch (e: Throwable) {
                Log.w(TAG, "whisper vulkan encoder close failed", e)
            }
        }
        val decHandle = vulkanDec
        vulkanDec = 0L
        if (decHandle != 0L) {
            try {
                VulkanSessions.close(decHandle)
            } catch (e: Throwable) {
                Log.w(TAG, "whisper vulkan decoder close failed", e)
            }
        }
        encoder = null
        decoder = null
        OnnxSessions.close(sessionKey(ENCODER))
        OnnxSessions.close(sessionKey(DECODER))
    }

    private var sessionPrefix: String = ""

    private fun sessionKey(name: String): String = "$sessionPrefix$assetDir/$name"

    override fun toString(): String = "whisper-base from $source"

    // -- Vulkan fast path -----------------------------------------------------

    /** Encoder output materialised on the host so either decoder can consume it. */
    private class EncHidden(val data: FloatArray, val seq: Int, val dim: Int)

    /**
     * Best-effort Vulkan transcription; null when Vulkan cannot serve it so the caller
     * falls back to ORT. Either, both, or neither graph may be on Vulkan — the hidden
     * states cross the boundary as plain floats.
     */
    private fun vulkanTranscribe(mel: FloatArray, languageToken: Int): IntArray? {
        val vDec = vulkanDec
        // Without a Vulkan decoder the fast path cannot serve the turn: the ORT
        // caller handles whole turns itself (including mixed Vulkan-encode graphs
        // via [ortHiddenTensor]), so bail before spending an encode here.
        if (vDec == 0L) return null
        // Encode: Vulkan-first, ORT fallback.
        var hidden: EncHidden? = null
        if (vulkanEnc != 0L) {
            hidden = runCatching { vulkanEncodeMel(mel) }.getOrNull()
            if (hidden == null) Log.w(TAG, "whisper vulkan encode failed, trying ORT encode")
        }
        if (hidden == null) {
            val enc = encoder ?: return null
            hidden = runCatching {
                val env = OrtEnvironment.getEnvironment()
                encode(enc, env, mel).useOrt { tensor ->
                    val shape = tensor.info.shape
                    val flat = FloatArray(tensor.floatBuffer.remaining())
                    tensor.floatBuffer.get(flat)
                    EncHidden(flat, shape[1].toInt(), shape[2].toInt())
                }
            }.getOrNull() ?: return null
        }
        val encHidden = hidden
        val promptLang = if (languageToken < 0) {
            vulkanDetectLanguage(vDec, encHidden) ?: return null
        } else {
            languageToken
        }
        val prompt = intArrayOf(special[0], promptLang, special[2], special[3])
        return vulkanGreedyDecode(vDec, encHidden, prompt)
    }

    /** One encoder run over Vulkan; null when the bridge returns nothing usable. */
    private fun vulkanEncodeMel(mel: FloatArray): EncHidden? {
        val handle = vulkanEnc
        if (handle == 0L) return null
        val names = arrayOf("input_features")
        val dtypes = intArrayOf(DTYPE_F32)
        val shapes = longArrayOf(1, MELS.toLong(), FRAMES.toLong())
        val payload = packFloats(mel)
        val outBytes = VulkanBridge.run(handle, names, dtypes, shapes, intArrayOf(0), payload)
            ?: return null
        val floats = leToFloats(outBytes)
        if (floats.isEmpty()) {
            Log.w(TAG, "whisper vulkan encoder returned no floats")
            return null
        }
        // Prefer the bridge's output metadata; fall back to the known [1, 1500, 512].
        val outShapes = runCatching { VulkanBridge.lastOutputShapes(handle) }.getOrNull()
        var seq = ENC_SEQ
        var dim = ENC_DIM
        if (outShapes != null && outShapes.size == 3) {
            seq = outShapes[1].toInt()
            dim = outShapes[2].toInt()
        }
        if (seq <= 0 || dim <= 0 || floats.size != seq * dim) {
            // Last resort: infer the sequence length from the payload itself.
            if (floats.size % ENC_DIM == 0) {
                seq = floats.size / ENC_DIM
                dim = ENC_DIM
            } else {
                Log.w(TAG, "whisper vulkan encoder returned ${floats.size} floats, want ${ENC_SEQ * ENC_DIM}")
                return null
            }
        }
        return EncHidden(floats, seq, dim)
    }

    /** Language detection over Vulkan: one uncached step from `<|startoftranscript|>`. */
    private fun vulkanDetectLanguage(decHandle: Long, hidden: EncHidden): Int? {
        return try {
            val kv = VulkanBridge.kvCreate(decHandle, DETECT_MAX_SEQ)
            if (kv == 0L) return null
            try {
                val outBytes = VulkanBridge.runCached(
                    decHandle, kv,
                    arrayOf("input_ids", "encoder_hidden_states", "use_cache_branch"),
                    intArrayOf(DTYPE_I64, DTYPE_F32, DTYPE_BOOL),
                    longArrayOf(1, 1, 1, hidden.seq.toLong(), hidden.dim.toLong(), 1),
                    intArrayOf(0, 2, 5),
                    packDecodeStep(intArrayOf(special[0]), hidden.data, useCache = false),
                ) ?: return null
                val floats = leToFloats(outBytes)
                if (floats.isEmpty()) return null
                val vocab = floats.size // seqLen is 1 here.
                val fallback = 50259
                var best = fallback
                var bestScore = -Float.MAX_VALUE
                for (id in languages) {
                    if (id < vocab && floats[id] > bestScore) {
                        bestScore = floats[id]
                        best = id
                    }
                }
                best
            } finally {
                runCatching { VulkanBridge.kvClose(kv) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "whisper vulkan language detection failed, assuming en", e)
            50259
        }
    }

    /**
     * Greedy decode over Vulkan, mirroring [greedyDecode]: the prompt goes out uncached
     * (which makes the graph compute cross-attention K/V from the hidden states), then
     * one-token cached steps thread the native KV cache. The large-vocabulary argmax
     * runs on the bridge; the host parse is only the fallback.
     */
    private fun vulkanGreedyDecode(
        decHandle: Long,
        hidden: EncHidden,
        prompt: IntArray,
    ): IntArray? {
        val limit = (special[4] - prompt.size).coerceAtMost(MAX_NEW_TOKENS)
        if (limit <= 0) return IntArray(0)
        val kv = try {
            VulkanBridge.kvCreate(decHandle, prompt.size + limit)
        } catch (e: Throwable) {
            Log.w(TAG, "whisper vulkan kvCreate failed", e)
            return null
        }
        if (kv == 0L) return null
        val out = ArrayList<Int>()
        try {
            var next = -1
            var step = 0
            while (step < limit) {
                val ids = if (step == 0) prompt else intArrayOf(next)
                val payload = packDecodeStep(ids, hidden.data, useCache = step > 0)
                val shapes = longArrayOf(
                    1, ids.size.toLong(),
                    1, hidden.seq.toLong(), hidden.dim.toLong(),
                    1,
                )
                val outBytes = try {
                    VulkanBridge.runCached(
                        decHandle, kv,
                        arrayOf("input_ids", "encoder_hidden_states", "use_cache_branch"),
                        intArrayOf(DTYPE_I64, DTYPE_F32, DTYPE_BOOL),
                        shapes, intArrayOf(0, 2, 5), payload,
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "whisper vulkan decode step $step failed", e)
                    return null
                } ?: return null
                next = vulkanArgmax(outBytes, ids.size, step == 0) ?: return null
                step++
                if (next == special[1]) break
                out.add(next)
            }
            return out.toIntArray()
        } finally {
            runCatching { VulkanBridge.kvClose(kv) }
        }
    }

    /**
     * Argmax of the last logits row via the bridge, with the same suppression as the
     * ORT path; falls back to a host scan of the payload.
     */
    private fun vulkanArgmax(payload: ByteArray, seqLen: Int, firstStep: Boolean): Int? {
        val suppressMerged = if (firstStep) {
            (suppress + suppressAtBegin).toIntArray()
        } else {
            suppress.toIntArray()
        }
        val floats = leToFloats(payload)
        if (floats.isEmpty()) return null
        val vocab = floats.size / seqLen
        if (vocab <= 0 || floats.size % seqLen != 0) return null
        try {
            return VulkanBridge.argmaxLastRow(payload, seqLen, vocab, suppressMerged)
        } catch (e: Throwable) {
            Log.w(TAG, "whisper bridge argmax failed, scanning on host", e)
        }
        var best = 0
        var bestScore = -Float.MAX_VALUE
        val base = (seqLen - 1) * vocab
        val skip = suppressMerged.toSet()
        for (i in 0 until vocab) {
            if (i in skip) continue
            val score = floats[base + i]
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /**
     * Best-effort Vulkan open for one graph; leaves the handle at 0 on any failure so
     * ORT stays the fallback. The model bytes are read once and shared by the
     * preflight check and the open.
     */
    private fun tryVulkan(key: String, readModel: () -> ByteArray, assign: (Long) -> Unit) {
        try {
            if (!VulkanSessions.isUsable()) return
            if (key !in VulkanSessions.allowlist) return
            val modelBytes = try {
                readModel()
            } catch (e: Throwable) {
                Log.w(TAG, "cannot read $key for vulkan preflight", e)
                return
            }
            val problem = VulkanSessions.preflight(key) { modelBytes }
            if (problem != null) {
                Log.w(TAG, "vulkan preflight skipped for $key: $problem")
                return
            }
            val handle = VulkanSessions.open(key, { modelBytes }, null)
            if (handle != 0L) {
                assign(handle)
                Log.i(TAG, "vulkan session open for $key")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vulkan open failed for $key, using ORT", e)
        }
    }

    // -- ORT path (unchanged) -------------------------------------------------

    private fun encode(enc: OrtSession, env: OrtEnvironment, mel: FloatArray): OnnxTensor {
        val shape = longArrayOf(1, MELS.toLong(), FRAMES.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), shape).useOrt { input ->
            enc.run(mapOf("input_features" to input)).useOrt { result ->
                val out = result.get(0) as OnnxTensor
                val buf = FloatArray(out.info.shape.fold(1L) { a, b -> a * b }.toInt())
                out.floatBuffer.get(buf)
                return OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), out.info.shape)
            }
        }
    }

    private fun detectLanguage(dec: OrtSession, env: OrtEnvironment, hidden: OnnxTensor): Int? {
        return try {
            emptyPast(env).use { past ->
                val ids = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(special[0].toLong())), longArrayOf(1, 1),
                )
                ids.useOrt {
                    dec.run(feeds(hidden, past, it, useCache = false)).useOrt { result ->
                        val logits = result.get(0) as OnnxTensor
                        val vocab = logits.info.shape.last().toInt()
                        val row = FloatArray(vocab)
                        logits.floatBuffer.position(0)
                        logits.floatBuffer.get(row)
                        val fallback = 50259
                        var best = fallback
                        var bestScore = -Float.MAX_VALUE
                        for (id in languages) {
                            if (id < vocab && row[id] > bestScore) {
                                bestScore = row[id]
                                best = id
                            }
                        }
                        best
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "language detection failed, assuming en", e)
            50259
        }
    }

    private fun greedyDecode(
        dec: OrtSession,
        env: OrtEnvironment,
        hidden: OnnxTensor,
        prompt: IntArray,
    ): List<Int> {
        val out = ArrayList<Int>()
        val limit = (special[4] - prompt.size).coerceAtMost(MAX_NEW_TOKENS)
        var past = emptyPast(env)
        var step = 0
        var next = -1
        try {
            while (step < limit) {
                val ids = if (step == 0) prompt else intArrayOf(next)
                val longIds = LongArray(ids.size) { ids[it].toLong() }
                val idTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longIds), longArrayOf(1, ids.size.toLong()),
                )
                val result = idTensor.useOrt { dec.run(feeds(hidden, past, it, useCache = step > 0)) }
                past.close()
                next = argmax(
                    result.get(0) as OnnxTensor,
                    suppress = suppress,
                    alsoSuppress = if (step == 0) suppressAtBegin else emptySet(),
                )
                past = Past(env, result)
                step++
                if (next == special[1]) break
                out.add(next)
            }
        } finally {
            past.close()
        }
        return out
    }

    private fun argmax(logits: OnnxTensor, suppress: Set<Int>, alsoSuppress: Set<Int>): Int {
        val shape = logits.info.shape
        val seqLen = shape[1].toInt()
        val vocab = shape[2].toInt()
        val row = FloatArray(vocab)
        val buf = logits.floatBuffer
        buf.position((seqLen - 1) * vocab)
        buf.get(row)
        var best = 0
        var bestScore = -Float.MAX_VALUE
        for (i in 0 until vocab) {
            if (i in suppress || i in alsoSuppress) continue
            if (row[i] > bestScore) {
                bestScore = row[i]
                best = i
            }
        }
        return best
    }

    private fun feeds(
        hidden: OnnxTensor,
        past: Past,
        ids: OnnxTensor,
        useCache: Boolean,
    ): Map<String, OnnxTensor> {
        val map = LinkedHashMap<String, OnnxTensor>(20)
        map["input_ids"] = ids
        map["encoder_hidden_states"] = hidden
        for ((name, tensor) in past.tensors()) map[name] = tensor
        map["use_cache_branch"] = if (useCache) past.cacheTrue else past.cacheFalse
        return map
    }

    private var languages: IntArray = IntArray(0)

    private fun emptyPast(env: OrtEnvironment): Past = Past(env, null)

    private inner class Past(private val env: OrtEnvironment, private val result: OrtSession.Result?) {
        val cacheFalse: OnnxTensor = OnnxTensor.createTensor(env, booleanArrayOf(false))
        val cacheTrue: OnnxTensor = OnnxTensor.createTensor(env, booleanArrayOf(true))
        private val empties = ArrayList<OnnxTensor>()

        fun tensors(): List<Pair<String, OnnxTensor>> = PAST_NAMES.map { name ->
            val tensor = if (result == null) {
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), EMPTY_SHAPE)
                    .also { empties.add(it) }
            } else {
                result.get(name.replace("past_key_values", "present")).get() as OnnxTensor
            }
            name to tensor
        }

        fun close() {
            empties.forEach { runCatching { it.close() } }
            empties.clear()
            runCatching { cacheFalse.close() }
            runCatching { cacheTrue.close() }
            runCatching { result?.close() }
        }

        inline fun <T> use(block: (Past) -> T): T = try {
            block(this)
        } finally {
            close()
        }
    }

    companion object {
        private const val TAG = "WhisperHandle"

        /** Mel bins the front end produces. */
        const val MELS = 80

        /** Mel frames in one 30-second window at a 160-sample hop. */
        const val FRAMES = 3000

        /** Asset directory. */
        const val DIR = "whisper-base"

        /** The two graphs. A wrong file fails at load. */
        const val ENCODER = "encoder_model_int8.onnx"
        const val DECODER = "decoder_model_merged_int8.onnx"

        /**
         * The five values [inAssets] wants in `special`, in order:
         * `decoder_start_token_id`, `eos_token_id`, `transcribe`, `no_timestamps_token_id`,
         * `max_length`.
         */
        const val SPECIAL_IDS = 5

        /** Encoder output geometry: 3000 mel frames through the /2 stem, width 512. */
        private const val ENC_SEQ = 1500
        private const val ENC_DIM = 512

        /** Positions the detection probe may occupy: prompt length plus one. */
        private const val DETECT_MAX_SEQ = 8

        /** ONNX TensorProto elem types as carried in the Vulkan `dtypes` array. */
        private const val DTYPE_F32 = 1
        private const val DTYPE_I64 = 7
        private const val DTYPE_BOOL = 9

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * `special`, `languages`, `suppress` and `suppressAtBegin` all come from the bundled
         * `generation_config.json`; see the class docs for why they are arguments.
         */
        fun inAssets(
            assets: AssetManager,
            special: IntArray,
            languages: IntArray,
            suppress: IntArray,
            suppressAtBegin: IntArray,
            path: String = DIR,
        ): WhisperHandle {
            val instance = WhisperHandle("the APK's $path")
            if (special.size != SPECIAL_IDS) {
                Log.e(TAG, "${special.size} special ids, not $SPECIAL_IDS")
                return instance
            }
            instance.assetDir = path
            instance.sessionPrefix = "asset:"
            instance.special = special.copyOf()
            instance.languages = languages.copyOf()
            instance.suppress = suppress.toSet()
            instance.suppressAtBegin = suppressAtBegin.toSet()
            instance.encoder = OnnxSessions.openAssetManager(assets, "$path/$ENCODER")
            instance.decoder = OnnxSessions.openAssetManager(assets, "$path/$DECODER")
            instance.tryVulkan(instance.sessionKey(ENCODER), { assets.open("$path/$ENCODER").use { it.readBytes() } }) {
                instance.vulkanEnc = it
            }
            instance.tryVulkan(instance.sessionKey(DECODER), { assets.open("$path/$DECODER").use { it.readBytes() } }) {
                instance.vulkanDec = it
            }
            if (!instance.isAvailable) Log.e(TAG, "cannot open $path")
            return instance
        }

        /**
         * The model in a folder on disk, as a downloaded one is.
         *
         * Same arguments as [inAssets]; sessions open from files instead of APK assets.
         */
        fun inDirectory(
            directory: java.io.File,
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
            instance.assetDir = ""
            instance.sessionPrefix = "file:${directory.absolutePath}/"
            instance.special = special.copyOf()
            instance.languages = languages.copyOf()
            instance.suppress = suppress.toSet()
            instance.suppressAtBegin = suppressAtBegin.toSet()
            instance.encoder = OnnxSessions.open(instance.sessionKey(ENCODER)) {
                java.io.File(directory, ENCODER).readBytes()
            }
            instance.decoder = OnnxSessions.open(instance.sessionKey(DECODER)) {
                java.io.File(directory, DECODER).readBytes()
            }
            val encKey = "file:${java.io.File(directory, ENCODER).absolutePath}"
            val decKey = "file:${java.io.File(directory, DECODER).absolutePath}"
            instance.tryVulkan(encKey, { java.io.File(directory, ENCODER).readBytes() }) {
                instance.vulkanEnc = it
            }
            instance.tryVulkan(decKey, { java.io.File(directory, DECODER).readBytes() }) {
                instance.vulkanDec = it
            }
            if (!instance.isAvailable) Log.e(TAG, "cannot open $directory")
            return instance
        }

        /** One 30 s window cannot say more than this; guards against a runaway loop. */
        private const val MAX_NEW_TOKENS = 224

        private const val N_LAYERS = 6
        private const val N_HEADS = 8L
        private const val HEAD_DIM = 64L

        private val EMPTY_SHAPE = longArrayOf(1, N_HEADS, 0, HEAD_DIM)

        private val PAST_NAMES: List<String> = buildList {
            for (i in 0 until N_LAYERS) {
                for (kind in listOf("decoder", "encoder")) {
                    for (kv in listOf("key", "value")) add("past_key_values.$i.$kind.$kv")
                }
            }
        }

        /** `input_ids` (i64) + `encoder_hidden_states` (f32) + `use_cache_branch` (bool), packed in order. */
        private fun packDecodeStep(ids: IntArray, hidden: FloatArray, useCache: Boolean): ByteArray {
            val buf = ByteBuffer.allocate(ids.size * 8 + hidden.size * 4 + 1)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (id in ids) buf.putLong(id.toLong())
            for (v in hidden) buf.putFloat(v)
            buf.put(if (useCache) 1 else 0)
            return buf.array()
        }

        private fun packFloats(values: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) buf.putFloat(v)
            return buf.array()
        }

        private fun leToFloats(bytes: ByteArray): FloatArray {
            val out = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
