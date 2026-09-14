package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * On-device chat LLM: Bonsai-8B 1-bit (`onnx-community/Bonsai-8B-ONNX`, Qwen3) on the reduced
 * ONNX Runtime build.
 *
 * A standard HuggingFace decoder export with full KV cache: `input_ids [B, S]` +
 * `attention_mask [B, T]` + `num_logits_to_keep []` + 72 `past_key_values.* [B, 8, P, 128]`
 * in, `logits [B, K, 151669]` + 72 `present.*` out (36 layers, GQA 8 KV heads of 128).
 * Generation is a prefill of the whole prompt followed by one-token decode steps threading
 * the cache — the same shape as the Whisper decoder port.
 *
 * # Files, downloaded rather than bundled
 *
 * `model_q1.onnx` + `model_q1.onnx_data` (~1.33 GB) and `tokenizer.json`, fetched to
 * `getExternalFilesDir` by `:library:downloadservice` like the Gemma files were — hence
 * [inDirectory] and no asset path. See `ModelUrls` for the mirror pins.
 *
 * # Sampling
 *
 * Temperature + top-k + top-p from the export's `generation_config.json` (0.5 / 20 / 0.85),
 * greedy when temperature is 0. The sampler runs on the host over the final position's
 * logits; `num_logits_to_keep = 1` on decode steps so the 151k-wide head only scores one
 * position.
 *
 * # Threading
 *
 * Not thread-safe: the KV cache is per-handle state. A caller must hold a lock across
 * [generate] and [close].
 */
class BonsaiHandle private constructor(private val directory: File) : AutoCloseable {
    private var session: OrtSession? = null
    private var tokenizer: BonsaiTokenizer? = null

    /** True if the session opened and the tokenizer parsed. */
    val isAvailable: Boolean get() = session != null && tokenizer != null

    /**
     * Generate a reply to [promptIds] with [GeneratorConfig], streaming pieces to [onPiece].
     *
     * [onPiece] returning false stops generation. Returns the full reply, or null on failure.
     * The prompt is prefilled in one call; each step after decodes a single token with the
     * cache. Stops at EOS, [config] stop ids, or [config] max tokens.
     */
    fun generate(
        promptIds: IntArray,
        config: GeneratorConfig = GeneratorConfig(),
        onPiece: (String) -> Boolean = { true },
    ): String? {
        val live = session ?: return null
        val tok = tokenizer ?: return null
        if (promptIds.isEmpty()) return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val past = emptyPast(env)
            try {
                // Prefill the whole prompt, keeping all logits (needed for the first token
                // only, but the slice is cheap relative to the matmul).
                var result = runStep(
                    live, env, promptIds, totalLen = promptIds.size,
                    keepLogits = promptIds.size, past = past,
                ) ?: return null
                var produced = ArrayList<Int>(config.maxTokens)
                var next = sample(lastLogits(result, promptIds.size), config)
                // `past` takes ownership of the result: its `present.*` tensors are the next
                // step's inputs, so the caller must not close it. `replace` closes the
                // previous generation's result.
                past.replace(result)
                val output = StringBuilder()
                var step = 0
                while (step < config.maxTokens) {
                    if (next == BonsaiTokenizer.EOS || next in config.stopIds) break
                    produced.add(next)
                    val piece = tok.decode(intArrayOf(next))
                    output.append(piece)
                    if (!onPiece(output.toString())) break
                    step++
                    if (step >= config.maxTokens) break
                    // One-token decode with the cache.
                    result = runStep(
                        live, env, intArrayOf(next),
                        totalLen = promptIds.size + produced.size,
                        keepLogits = 1, past = past,
                    ) ?: break
                    next = sample(lastLogits(result, 1), config)
                    past.replace(result)
                }
                output.toString()
            } finally {
                past.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "bonsai generation failed", e)
            null
        }
    }

    /** Token ids for [text], without specials. */
    fun encode(text: String): IntArray? = tokenizer?.encode(text)

    /** Text for [ids]. */
    fun decode(ids: IntArray): String? = tokenizer?.decode(ids)

    /** Free the session. Idempotent. */
    override fun close() {
        val live = session
        session = null
        if (live != null) runCatching { live.close() }
    }

    override fun toString(): String = "Bonsai-8B in $directory"

    // -- One model step -------------------------------------------------------

    private fun runStep(
        session: OrtSession,
        env: OrtEnvironment,
        ids: IntArray,
        totalLen: Int,
        keepLogits: Int,
        past: Past,
    ): OrtSession.Result? {
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val idTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longIds), longArrayOf(1, ids.size.toLong()),
        )
        val mask = LongArray(totalLen) { 1L }
        val maskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(mask), longArrayOf(1, totalLen.toLong()),
        )
        val keepTensor = OnnxTensor.createTensor(env, longArrayOf(keepLogits.toLong()))
        val inputs = LinkedHashMap<String, OnnxTensor>(4 + past.tensors().size)
        inputs["input_ids"] = idTensor
        inputs["attention_mask"] = maskTensor
        inputs["num_logits_to_keep"] = keepTensor
        for ((name, tensor) in past.tensors()) inputs[name] = tensor
        return try {
            // All outputs: `logits` for sampling plus every `present.*` for the next step's
            // cache. Restricting to `logits` alone would leave the cache unfed.
            session.run(inputs)
        } finally {
            runCatching { idTensor.close() }
            runCatching { maskTensor.close() }
            runCatching { keepTensor.close() }
        }
    }

    private fun lastLogits(result: OrtSession.Result, positions: Int): FloatArray {
        val out = result.get("logits").get() as OnnxTensor
        val shape = out.info.shape
        val vocab = shape.last().toInt()
        val row = FloatArray(vocab)
        out.floatBuffer.position((positions - 1) * vocab)
        out.floatBuffer.get(row)
        return row
    }

    private fun sample(logits: FloatArray, config: GeneratorConfig): Int {
        if (config.temperature <= 0f) {
            var best = 0
            var peak = logits[0]
            for (i in 1 until logits.size) {
                if (logits[i] > peak) {
                    peak = logits[i]
                    best = i
                }
            }
            return best
        }
        val scaled = FloatArray(logits.size) { logits[it] / config.temperature }
        // Top-k truncation.
        val order = scaled.indices.sortedByDescending { scaled[it] }
        val kept = order.take(max(config.topK, 1)).toSet()
        // Softmax over the kept, with the peak subtracted for range.
        var peak = Float.NEGATIVE_INFINITY
        for (i in kept) if (scaled[i] > peak) peak = scaled[i]
        var total = 0.0
        val probs = HashMap<Int, Double>(kept.size)
        for (i in kept) {
            val p = exp((scaled[i] - peak).toDouble())
            probs[i] = p
            total += p
        }
        // Top-p (nucleus) cutoff in probability order.
        var cutoff = total
        var nucleus = ArrayList<Int>(kept.size)
        for (i in order) {
            if (i !in kept) continue
            nucleus.add(i)
            cutoff -= probs[i] ?: 0.0
            if (cutoff <= (1.0 - config.topP) * total) break
        }
        if (nucleus.isEmpty()) nucleus = ArrayList(kept)
        var sum = 0.0
        for (i in nucleus) sum += probs[i] ?: 0.0
        var draw = Math.random() * sum
        for (i in nucleus) {
            draw -= probs[i] ?: 0.0
            if (draw <= 0.0) return i
        }
        return nucleus.last()
    }

    // -- KV cache -------------------------------------------------------------

    private fun emptyPast(env: OrtEnvironment): Past = Past(env, null)

    private inner class Past(
        private val env: OrtEnvironment,
        private var result: OrtSession.Result?,
    ) {
        fun tensors(): List<Pair<String, OnnxTensor>> = PAST_NAMES.map { name ->
            val live = result
            val tensor = if (live == null) {
                emptyKv(env).also { empties.add(it) }
            } else {
                live.get(name.replace("past_key_values", "present")).get() as OnnxTensor
            }
            name to tensor
        }

        fun replace(newResult: OrtSession.Result) {
            runCatching { result?.close() }
            empties.forEach { runCatching { it.close() } }
            empties.clear()
            result = newResult
        }

        fun close() {
            runCatching { result?.close() }
            result = null
            empties.forEach { runCatching { it.close() } }
            empties.clear()
        }

        private val empties = ArrayList<OnnxTensor>()
    }

    /** Sampling knobs for one reply. Defaults from the export's `generation_config.json`. */
    data class GeneratorConfig(
        val maxTokens: Int = 512,
        val temperature: Float = 0.5f,
        val topK: Int = 20,
        val topP: Float = 0.85f,
        val stopIds: IntArray = intArrayOf(),
    )

    /** Tool declaration in the Qwen chat template's `<tools>` block. */
    data class ToolDeclaration(
        val name: String,
        val description: String,
        val parameters: List<Parameter>,
    ) {
        data class Parameter(
            val name: String,
            val description: String,
            val type: String,
            val required: Boolean,
        )
    }

    /** A parsed `<tool_call>` request. */
    data class ToolCall(val name: String, val arguments: Map<String, String>)

    companion object {
        private const val TAG = "BonsaiHandle"

        /** The decoder export. */
        const val MODEL_FILE = "model_q1.onnx"

        /** The external-data companion. */
        const val MODEL_DATA = "model_q1.onnx_data"

        /** The HuggingFace tokenizer. */
        const val TOKENIZER_FILE = BonsaiTokenizer.TOKENIZER_FILE

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(MODEL_FILE, MODEL_DATA, TOKENIZER_FILE)

        /** 36 layers × key/value, 8 GQA heads of 128. */
        const val N_LAYERS = 36
        const val N_KV_HEADS = 8L
        const val HEAD_DIM = 128L

        private val EMPTY_KV = longArrayOf(1, N_KV_HEADS, 0, HEAD_DIM)

        private fun emptyKv(env: OrtEnvironment): OnnxTensor =
            OnnxTensor.createTensor(env, java.nio.FloatBuffer.allocate(0), EMPTY_KV)

        private val PAST_NAMES: List<String> = buildList {
            for (i in 0 until N_LAYERS) {
                for (kv in listOf("key", "value")) add("past_key_values.$i.$kv")
            }
        }

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart: at ~1.33 GB this is a runtime download to
         * `getExternalFilesDir`, so there is no APK entry to open.
         */
        fun inDirectory(directory: File): BonsaiHandle {
            val instance = BonsaiHandle(directory)
            val model = File(directory, MODEL_FILE)
            if (!model.isFile) {
                Log.w(TAG, "$MODEL_FILE is missing from $directory")
                return instance
            }
            instance.tokenizer = BonsaiTokenizer.inDirectory(directory)
            if (instance.tokenizer == null) return instance
            // External data loads relative to the model path: pass the file path, not bytes.
            instance.session = try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setInterOpNumThreads(1)
                }
                env.createSession(model.absolutePath, opts)
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open $MODEL_FILE", e)
                null
            }
            if (!instance.isAvailable) {
                Log.w(TAG, "bonsai did not load from $directory")
                instance.close()
            }
            return instance
        }

        /**
         * Parse a `<tool_call>{"name": ..., "arguments": {...}}</tool_call>` request.
         *
         * Returns null when the reply holds no complete call. Tolerates a trailing
         * `</tool_call>` close tag and JSON values of any scalar type (numbers and booleans
         * arrive as their JSON spelling, matching the Double convention `ToolRegistry` keeps).
         */
        fun parseToolCall(reply: String): ToolCall? {
            val open = reply.indexOf("<tool_call>")
            if (open < 0) return null
            var body = reply.substring(open + "<tool_call>".length)
            val close = body.indexOf("</tool_call>")
            if (close >= 0) body = body.substring(0, close)
            return try {
                val json = org.json.JSONObject(body)
                val name = json.optString("name").ifEmpty { return null }
                val args = json.optJSONObject("arguments")
                val map = HashMap<String, String>()
                if (args != null) {
                    val keys = args.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = args.opt(key)?.toString() ?: ""
                    }
                }
                ToolCall(name, map)
            } catch (e: Throwable) {
                null
            }
        }

        /** Render one tool declaration for the `<tools>` block (OpenAI-style JSON schema). */
        fun renderTool(declaration: ToolDeclaration): String {
            val properties = declaration.parameters.joinToString(",") { parameter ->
                val type = when (parameter.type) {
                    "NUMBER" -> "number"
                    "BOOLEAN" -> "boolean"
                    else -> "string"
                }
                "\"${parameter.name}\":{\"type\":\"$type\",\"description\":${jsonString(parameter.description)}}"
            }
            val required = declaration.parameters.filter { it.required }
                .joinToString(",", "[", "]") { "\"${it.name}\"" }
            return "{\"type\":\"function\",\"function\":{\"name\":\"${declaration.name}\"," +
                "\"description\":${jsonString(declaration.description)}," +
                "\"parameters\":{\"type\":\"object\",\"properties\":{$properties},\"required\":$required}}}"
        }

        /** Render a tool result for the `<tool_response>` turn. */
        fun renderToolResponse(name: String, result: String): String =
            "<tool_response>$result</tool_response>"

        private fun jsonString(value: String): String =
            org.json.JSONObject.quote(value)
    }
}
