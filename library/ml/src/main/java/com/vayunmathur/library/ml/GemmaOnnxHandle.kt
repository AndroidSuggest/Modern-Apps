package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Gemma 4 E2B on the reduced ONNX Runtime build (`onnx-community/gemma-4-E2B-it-ONNX`, q4f16).
 *
 * Four exports, mirroring the old `.maml` split exactly:
 *
 * - **embed** (`embed_tokens_q4f16.onnx`): `input_ids [B, S]` → `inputs_embeds [B, S, 1536]`
 *   + `per_layer_inputs [B, S, 35, 256]`. Run once per turn over the whole prompt; the
 *   decoder never sees raw ids.
 * - **decoder** (`decoder_model_merged_q4f16.onnx`): embeds + per-layer inputs +
 *   `position_ids` + `attention_mask` + 30 `past_key_values.*` (15 layers with their own
 *   cache — `num_kv_shared_layers: 20` share; layer dims 256 sliding / 512 global) →
 *   `logits [B, K, 262144]` + 30 `present.*`. Prefill the prompt, then one-token decode
 *   steps threading the cache.
 * - **vision** (`vision_encoder_q4f16.onnx`): `pixel_values` + `pixel_position_ids` →
 *   `image_features [N, 1536]` flat soft tokens, spliced into the embedding sequence on
 *   the host between the `<|image>` / `<image|>` markers.
 * - **audio** (`audio_encoder_q4f16.onnx`): `input_features [mel]` + mask →
 *   `audio_features [N, 1536]`, spliced the same way between `<|audio>` / `<audio|>`.
 *
 * # Files, downloaded rather than bundled
 *
 * The four `.onnx` plus their `.onnx_data` companions and `tokenizer.json`, fetched to
 * `getExternalFilesDir` by `:library:downloadservice` like the old `.maml` files were —
 * hence [inDirectory] and no asset path. See `ModelUrls` for the mirror pins.
 *
 * # Sampling
 *
 * Greedy, like the native path was (litertlm used top_k 64 / top_p 0.95; the deterministic
 * replies this produces are the established behaviour now). Stops at [STOP] (eos 1, turn
 * 106, double-newline 50).
 *
 * # Threading
 *
 * Not thread-safe: the KV cache is per-handle state. A caller must hold a lock across a
 * whole turn, not merely across a call.
 */
class GemmaOnnxHandle private constructor(private val directory: File) : AutoCloseable {
    private val lock = Any()

    @Volatile private var embed: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    @Volatile private var vision: OrtSession? = null
    @Volatile private var audio: OrtSession? = null
    @Volatile private var tokenizer: GemmaOnnxTokenizer? = null
    @Volatile private var loadTried = false

    /** True if the decoder + embed graphs came up and the tokenizer parsed. */
    val isAvailable: Boolean get() = ensure(requireTowers = false)

    /** True if everything including both towers came up. */
    val isFullyAvailable: Boolean get() = ensure(requireTowers = true)

    /**
     * Run one turn's prefill + decode over [parts], calling [onPiece] with the reply so far
     * after each token.
     *
     * [parts] is the prompt as embedding-space segments (text runs and soft-token blocks);
     * see [assemble]. Returns the reply text, or null on failure.
     */
    fun generate(
        parts: List<PromptPart>,
        limit: Int = Gemma4Handle.DEFAULT_REPLY,
        onPiece: (String) -> Boolean = { true },
    ): String? {
        if (!ensure(requireTowers = parts.any { it is PromptPart.Media })) return null
        val dec = decoder ?: return null
        val tok = tokenizer ?: return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val assembled = assemble(parts) ?: return null
            val past = emptyPast(env)
            try {
                var result = runPrefill(dec, env, assembled, past) ?: return null
                var next = argmax(lastLogits(result, assembled.totalLen)) ?: return null
                past.replace(result)
                val produced = ArrayList<Int>(limit)
                val output = StringBuilder()
                var step = 0
                while (step < limit) {
                    if (next in STOP) break
                    produced.add(next)
                    output.append(tok.decode(intArrayOf(next)))
                    if (!onPiece(output.toString())) break
                    step++
                    if (step >= limit) break
                    result = runDecode(dec, env, next, assembled.totalLen + produced.size, past)
                        ?: break
                    next = argmax(lastLogits(result, 1)) ?: break
                    past.replace(result)
                }
                output.toString()
            } finally {
                past.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "gemma generation failed", e)
            null
        }
    }

    /** Token ids for [text] as a user would write it (no markers honoured). */
    fun encodeText(text: String): IntArray =
        tokenizer?.encode(text) ?: IntArray(0)

    /** Text for token ids. */
    fun decode(ids: IntArray): String =
        tokenizer?.decode(ids).orEmpty()

    /**
     * Soft tokens for [bitmap]: resized per the reference preprocessor, through the vision
     * tower, returned as `n * 1536` floats.
     */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        if (!ensure(requireTowers = true)) return null
        val tower = vision ?: return null
        return try {
            val (pixels, positions) = preprocessImage(bitmap) ?: return null
            val env = OrtEnvironment.getEnvironment()
            val pixelTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(pixels),
                longArrayOf(1, (pixels.size / 768).toLong(), 768),
            )
            val posTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(positions),
                longArrayOf(1, (positions.size / 2).toLong(), 2),
            )
            pixelTensor.useOrt {
                posTensor.useOrt {
                    tower.run(
                        mapOf("pixel_values" to pixelTensor, "pixel_position_ids" to posTensor),
                    ).useOrt { result ->
                        val out = result.get("image_features").get() as OnnxTensor
                        val shape = out.info.shape
                        val flat = FloatArray((shape[0] * shape[1]).toInt())
                        out.floatBuffer.get(flat)
                        flat
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "gemma vision encode failed", e)
            null
        }
    }

    /**
     * Soft tokens for 16 kHz mono [samples]: log-mel front end, through the audio tower,
     * returned as `n * 1536` floats.
     */
    fun encodeAudio(samples: FloatArray): FloatArray? {
        if (!ensure(requireTowers = true)) return null
        val tower = audio ?: return null
        return try {
            val mel = logMel(samples) ?: return null
            val frames = mel.size / N_MELS
            val env = OrtEnvironment.getEnvironment()
            val melTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(mel), longArrayOf(1, frames.toLong(), N_MELS.toLong()),
            )
            val maskTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(frames) { 1L }), longArrayOf(1, frames.toLong()),
            )
            melTensor.useOrt {
                maskTensor.useOrt {
                    tower.run(
                        mapOf("input_features" to melTensor, "input_features_mask" to maskTensor),
                    ).useOrt { result ->
                        val out = result.get("audio_features").get() as OnnxTensor
                        val shape = out.info.shape
                        val flat = FloatArray((shape[0] * shape[1]).toInt())
                        out.floatBuffer.get(flat)
                        flat
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "gemma audio encode failed", e)
            null
        }
    }

    /** Free all sessions. Idempotent. */
    override fun close() {
        synchronized(lock) {
            embed = null
            decoder = null
            vision = null
            audio = null
            tokenizer = null
            for (name in ALL_FILES) OnnxSessions.close(sessionKey(name))
        }
    }

    override fun toString(): String = "Gemma 4 E2B (ONNX) in $directory"

    private fun ensure(requireTowers: Boolean): Boolean {
        if (decoder != null && embed != null && tokenizer != null &&
            (!requireTowers || (vision != null && audio != null))
        ) {
            return true
        }
        synchronized(lock) {
            if (decoder != null && embed != null && tokenizer != null &&
                (!requireTowers || (vision != null && audio != null))
            ) {
                return true
            }
            if (loadTried && decoder == null) return false
            loadTried = true
            try {
                if (tokenizer == null) tokenizer = GemmaOnnxTokenizer.inDirectory(directory)
                if (embed == null) embed = openSession(EMBED_FILE)
                if (decoder == null) decoder = openSession(DECODER_FILE)
                if (vision == null) vision = openSession(VISION_FILE)
                if (audio == null) audio = openSession(AUDIO_FILE)
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the Gemma ONNX bundle in $directory", e)
            }
            return decoder != null && embed != null && tokenizer != null &&
                (!requireTowers || (vision != null && audio != null))
        }
    }

    private fun sessionKey(name: String): String =
        "file:${File(directory, name).absolutePath}"

    private fun openSession(name: String): OrtSession? {
        val path = File(directory, name)
        if (!path.isFile) {
            Log.w(TAG, "$name is missing from $directory")
            return null
        }
        // External data loads relative to the model path: open by path, not bytes.
        return try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
            }
            env.createSession(path.absolutePath, opts)
        } catch (e: Throwable) {
            Log.e(TAG, "cannot open $name", e)
            null
        }
    }

    // -- Prompt assembly ------------------------------------------------------

    /** One segment of a prompt in embedding space. */
    sealed interface PromptPart {
        /** Text to embed through the embed graph. */
        data class Text(val text: String) : PromptPart

        /**
         * Pre-encoded soft tokens (`n * 1536`), spliced verbatim. Null when the tower
         * refused the input — skipped in assembly rather than failing the turn.
         */
        data class Media(val soft: FloatArray?) : PromptPart
    }

    private class Assembled(
        val embeds: FloatArray,
        val perLayer: FloatArray,
        val totalLen: Int,
    )

    private fun assemble(parts: List<PromptPart>): Assembled? {
        val emb = embed ?: return null
        val env = OrtEnvironment.getEnvironment()
        // Embed each text run; media splices in verbatim (its per-layer rows are zeros —
        // the towers produce embeddings, not per-layer inputs).
        val embedSeq = ArrayList<FloatArray>()
        val layerSeq = ArrayList<FloatArray>()
        var totalLen = 0
        for (part in parts) {
            when (part) {
                is PromptPart.Text -> {
                    val ids = tokenizer?.encode(part.text) ?: return null
                    if (ids.isEmpty()) continue
                    val idTensor = OnnxTensor.createTensor(
                        env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()),
                        longArrayOf(1, ids.size.toLong()),
                    )
                    idTensor.useOrt {
                        emb.run(mapOf("input_ids" to idTensor)).useOrt { result ->
                            val e = result.get("inputs_embeds").get() as OnnxTensor
                            val p = result.get("per_layer_inputs").get() as OnnxTensor
                            val ef = FloatArray(ids.size * HIDDEN)
                            val pf = FloatArray(ids.size * N_LAYERS * LAYER_WIDTH)
                            e.floatBuffer.get(ef)
                            p.floatBuffer.get(pf)
                            embedSeq.add(ef)
                            layerSeq.add(pf)
                            totalLen += ids.size
                        }
                    }
                }
                is PromptPart.Media -> {
                    val soft = part.soft ?: continue
                    val n = soft.size / HIDDEN
                    if (n == 0) continue
                    embedSeq.add(soft)
                    layerSeq.add(FloatArray(n * N_LAYERS * LAYER_WIDTH))
                    totalLen += n
                }
            }
        }
        if (totalLen == 0) return null
        val embeds = FloatArray(totalLen * HIDDEN)
        val perLayer = FloatArray(totalLen * N_LAYERS * LAYER_WIDTH)
        var at = 0
        var lat = 0
        for (i in embedSeq.indices) {
            val e = embedSeq[i]
            e.copyInto(embeds, at)
            at += e.size
            val p = layerSeq[i]
            p.copyInto(perLayer, lat)
            lat += p.size
        }
        return Assembled(embeds, perLayer, totalLen)
    }

    // -- Decoder steps --------------------------------------------------------

    private fun runPrefill(
        dec: OrtSession,
        env: OrtEnvironment,
        assembled: Assembled,
        past: Past,
    ): OrtSession.Result? {
        val n = assembled.totalLen
        val embedTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(assembled.embeds), longArrayOf(1, n.toLong(), HIDDEN.toLong()),
        )
        val layerTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(assembled.perLayer),
            longArrayOf(1, n.toLong(), N_LAYERS.toLong(), LAYER_WIDTH.toLong()),
        )
        val maskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(n) { 1L }), longArrayOf(1, n.toLong()),
        )
        val posTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(n) { it.toLong() }), longArrayOf(1, n.toLong()),
        )
        val keepTensor = OnnxTensor.createTensor(env, longArrayOf(n.toLong()))
        val inputs = LinkedHashMap<String, OnnxTensor>(5 + past.tensors().size)
        inputs["inputs_embeds"] = embedTensor
        inputs["per_layer_inputs"] = layerTensor
        inputs["attention_mask"] = maskTensor
        inputs["position_ids"] = posTensor
        inputs["num_logits_to_keep"] = keepTensor
        for ((name, tensor) in past.tensors()) inputs[name] = tensor
        return try {
            dec.run(inputs)
        } finally {
            runCatching { embedTensor.close() }
            runCatching { layerTensor.close() }
            runCatching { maskTensor.close() }
            runCatching { posTensor.close() }
            runCatching { keepTensor.close() }
        }
    }

    private fun runDecode(
        dec: OrtSession,
        env: OrtEnvironment,
        token: Int,
        totalLen: Int,
        past: Past,
    ): OrtSession.Result? {
        // One-token decode: the embed graph scores a single id, positions advance by one.
        val idTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(token.toLong())), longArrayOf(1, 1),
        )
        var single: Assembled? = null
        idTensor.useOrt {
            val e = embed ?: return null
            e.run(mapOf("input_ids" to idTensor)).useOrt { result ->
                val re = result.get("inputs_embeds").get() as OnnxTensor
                val rp = result.get("per_layer_inputs").get() as OnnxTensor
                val ef = FloatArray(HIDDEN)
                val pf = FloatArray(N_LAYERS * LAYER_WIDTH)
                re.floatBuffer.get(ef)
                rp.floatBuffer.get(pf)
                single = Assembled(ef, pf, 1)
            }
        }
        val one = single ?: return null
        val pos = totalLen - 1
        val embedTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(one.embeds), longArrayOf(1, 1, HIDDEN.toLong()),
        )
        val layerTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(one.perLayer),
            longArrayOf(1, 1, N_LAYERS.toLong(), LAYER_WIDTH.toLong()),
        )
        val maskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(totalLen) { 1L }), longArrayOf(1, totalLen.toLong()),
        )
        val posTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(pos.toLong())), longArrayOf(1, 1),
        )
        val keepTensor = OnnxTensor.createTensor(env, longArrayOf(1L))
        val inputs = LinkedHashMap<String, OnnxTensor>(5 + past.tensors().size)
        inputs["inputs_embeds"] = embedTensor
        inputs["per_layer_inputs"] = layerTensor
        inputs["attention_mask"] = maskTensor
        inputs["position_ids"] = posTensor
        inputs["num_logits_to_keep"] = keepTensor
        for ((name, tensor) in past.tensors()) inputs[name] = tensor
        return try {
            dec.run(inputs)
        } finally {
            runCatching { embedTensor.close() }
            runCatching { layerTensor.close() }
            runCatching { maskTensor.close() }
            runCatching { posTensor.close() }
            runCatching { keepTensor.close() }
        }
    }

    private fun lastLogits(result: OrtSession.Result, positions: Int): FloatArray {
        val out = result.get("logits").get() as OnnxTensor
        val vocab = out.info.shape.last().toInt()
        // Logits arrive fp16 (q4f16 export); widen for the argmax.
        val bytes = ByteArray(vocab * 2)
        out.byteBuffer.position((positions - 1) * vocab * 2)
        out.byteBuffer.get(bytes)
        val shorts = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val row = ShortArray(vocab)
        shorts.get(row)
        return FloatArray(vocab) { fp16ToFloat(row[it].toInt() and 0xFFFF) }
    }

    /** IEEE 754 binary16 interpreted as float32. */
    private fun fp16ToFloat(bits: Int): Float {
        val sign = (bits shr 15) and 1
        val exp = (bits shr 10) and 0x1F
        val mant = bits and 0x3FF
        val fbits = when {
            exp == 0 -> {
                // Subnormal: renormalize.
                var m = mant
                var e = -14
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                m = m and 0x3FF
                ((sign shl 31) or ((e + 127) shl 23) or (m shl 13))
            }
            exp == 31 -> (sign shl 31) or (0xFF shl 23) or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(fbits)
    }

    private fun argmax(logits: FloatArray): Int? {
        if (logits.isEmpty()) return null
        var best = -1
        var top = Float.NaN
        for (i in logits.indices) {
            val value = logits[i]
            if (value.isNaN()) continue
            if (best < 0 || value > top) {
                best = i
                top = value
            }
        }
        return if (best < 0) null else best
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
                emptyKv(env, name).also { empties.add(it) }
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

    // -- Tower front ends (to be filled against the downloaded weights) --------

    private fun preprocessImage(bitmap: Bitmap): Pair<FloatArray, LongArray>? {
        // TODO: aspect-preserving fit to the 768-wide grid + pixel_position_ids, per the
        // reference preprocessor (rescale 1/255, no normalize, patch 16, pool 3).
        return null
    }

    private fun logMel(samples: FloatArray): FloatArray? {
        // TODO: 128-bin HTK mel matching `logmel.rs` (16 kHz, hop 160), then frames.
        return null
    }

    companion object {
        private const val TAG = "GemmaOnnxHandle"

        /** Decoder export. */
        const val DECODER_FILE = "decoder_q4f16.onnx"

        /** Decoder weights. */
        const val DECODER_DATA = "decoder_q4f16.onnx_data"

        /** Embed export + weights. */
        const val EMBED_FILE = "embed_q4f16.onnx"
        const val EMBED_DATA = "embed_q4f16.onnx_data"

        /** Vision export + weights. */
        const val VISION_FILE = "vision_q4f16.onnx"
        const val VISION_DATA = "vision_q4f16.onnx_data"

        /** Audio export + weights. */
        const val AUDIO_FILE = "audio_q4f16.onnx"
        const val AUDIO_DATA = "audio_q4f16.onnx_data"

        /** HuggingFace tokenizer. */
        const val TOKENIZER_FILE = "tokenizer.json"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(DECODER_FILE, EMBED_FILE, TOKENIZER_FILE)
        val ALL_FILES: List<String> = FILES + listOf(VISION_FILE, AUDIO_FILE)

        /** Decoder width. */
        const val HIDDEN = 1536

        /** Layers carrying per-layer inputs. */
        const val N_LAYERS = 35

        /** Per-layer input width. */
        const val LAYER_WIDTH = 256

        /** Stop ids: eos 1, turn 106, double-newline 50. */
        val STOP = intArrayOf(1, 106, 50)

        /** Mel bins the audio front end produces. */
        const val N_MELS = 128

        /** Layers carrying their own KV: 0..14 (20 sliding layers share). */
        const val N_KV_LAYERS = 15

        /** Global-attention layers (512-wide KV); the rest are 256-wide sliding. */
        private val GLOBAL_LAYERS = setOf(4, 9, 14)

        private fun kvDim(layer: Int): Long = if (layer in GLOBAL_LAYERS) 512L else 256L

        private val PAST_NAMES: List<String> = buildList {
            for (i in 0 until N_KV_LAYERS) {
                for (kv in listOf("key", "value")) add("past_key_values.$i.$kv")
            }
        }

        private fun emptyKv(env: OrtEnvironment, name: String): OnnxTensor {
            val layer = name.split(".")[1].toInt()
            val dim = kvDim(layer)
            // KV is fp16 in the q4f16 export.
            return OnnxTensor.createTensor(
                env, java.nio.ByteBuffer.allocateDirect(0), longArrayOf(1, 1, 0, dim),
                ai.onnxruntime.OnnxJavaType.FLOAT16,
            )
        }

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart: at several GB this is a runtime download to
         * `getExternalFilesDir`, so there is no APK entry to open.
         */
        fun inDirectory(directory: File): GemmaOnnxHandle = GemmaOnnxHandle(directory)
    }
}
