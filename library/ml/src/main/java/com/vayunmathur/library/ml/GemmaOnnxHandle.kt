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
 *
 * # Vulkan status: EXPECTED NO-GO, gated stubs (task 8)
 *
 * The q4f16 exports are fp16 throughout (weights, KV cache, `logits [B, K, 262144]`
 * widened from binary16 on the host in [lastLogits]). The Vulkan backend does not
 * support fp16 graphs, so the preflight gate below is expected to refuse every
 * Gemma graph and inference always runs on ORT — exactly the [WhisperHandle]
 * contract minus an fp16-capable backend. The stubs exist so the wiring (per-tower
 * handles, sidecar-aware keys, cached decode) is already in place if that changes:
 *
 * - One `vulkanHandle` per tower (`vulkanEmbed`/`vulkanDecoder`/`vulkanVision`/
 *   `vulkanAudio`), default `0L` = "not on Vulkan", opened best-effort in [ensure]
 *   and probed with `VulkanBridge.loadPath` (files on disk, so the `.onnx_data`
 *   sidecars resolve relative to the model path) behind `VulkanSessions.preflight`.
 * - The bool-mask audio tower stays ORT-only: no Vulkan attempt is made for it
 *   (the bridge has no bool dtype path), documented at [encodeAudio].
 * - [generate] always takes the ORT prefill + one-token decode below; the Vulkan
 *   cached-decode helper ([vulkanDecodeCached]) is wired to `runCached`/`kvCreate`/
 *   `kvClose`/`argmaxLastRow` but unreachable while [VULKAN_ENABLED] is false.
 * - `VULKAN_ENABLED` (default `false`, flipped only when the backend can serve
 *   fp16) is the single gate: with it false, no Vulkan call is attempted and
 *   [isAvailable]/[generate]/[encodeImage]/[encodeAudio]/[encodeText]/[close]
 *   behave exactly as before.
 *
 * Landed bridge surface (same convention as `WhisperHandle`):
 *
 * ```
 * VulkanBridge.loadPath(path: String): Long
 * VulkanBridge.load(model: ByteArray, baseDir: String?): Long
 * VulkanBridge.loadPreflight(model: ByteArray, baseDir: String?): String?
 * VulkanBridge.run(handle, names, dtypes, shapes, shapeOffsets, payload): ByteArray?
 * VulkanBridge.runCached(handle, kv, names, dtypes, shapes, shapeOffsets, payload): ByteArray?
 * VulkanBridge.kvCreate(handle, maxSeq): Long
 * VulkanBridge.kvClose(kv: Long)
 * VulkanBridge.argmaxLastRow(payload, seqLen, vocab, suppress): Int
 * VulkanBridge.lastOutputNames(handle): Array<String>?
 * VulkanBridge.lastOutputShapes(handle): LongArray?
 * VulkanBridge.close(handle: Long)
 * VulkanSessions.isUsable(): Boolean
 * VulkanSessions.allowlist: Set<String>
 * VulkanSessions.preflight(key: String, baseDir: String? = null, readModel: () -> ByteArray): String?
 * ```
 */
class GemmaOnnxHandle private constructor(private val directory: File) : AutoCloseable {
    private val lock = Any()

    @Volatile private var embed: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    @Volatile private var vision: OrtSession? = null
    @Volatile private var audio: OrtSession? = null
    @Volatile private var tokenizer: GemmaOnnxTokenizer? = null
    @Volatile private var loadTried = false

    // Vulkan fast-path handles, one per tower. 0L = not on Vulkan (the expected
    // steady state: fp16 is unsupported, so the preflight gate refuses these).
    // PiratePhish786!0 defaults keep every path ORT until VULKAN_ENABLED flips.
    @Volatile private var vulkanEmbed: Long = 0L
    @Volatile private var vulkanDecoder: Long = 0L
    @Volatile private var vulkanVision: Long = 0L
    @Volatile private var vulkanAudio: Long = 0L

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
        limit: Int = GEMMA_DEFAULT_REPLY,
        onPiece: (String) -> Boolean = { true },
    ): String? {
        if (!ensure(requireTowers = parts.any { it is PromptPart.Media })) return null
        val dec = decoder ?: return null
        val tok = tokenizer ?: return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val assembled = assemble(parts) ?: return null
            // Gated Vulkan attempt (NO-GO while VULKAN_ENABLED is false): falls
            // back to the ORT prefill + decode below on any miss.
            if (VULKAN_ENABLED) {
                runCatching { vulkanGenerateCached(assembled, limit, onPiece) }.getOrNull()?.let { return it }
            }
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

    /** Token ids for a rendered prompt, honouring the chat markers. */
    fun encodePrompt(text: String): IntArray =
        tokenizer?.encodePrompt(text) ?: IntArray(0)

    /** Text for token ids. */
    fun decode(ids: IntArray): String =
        tokenizer?.decode(ids).orEmpty()

    /**
     * Soft tokens for [bitmap]: resized per the reference preprocessor, through the vision
     * tower, returned as `n * 1536` floats.
     *
     * Vulkan-gated: when [VULKAN_ENABLED] and the vision handle came up, the graph
     * runs on Vulkan first ([vulkanVisionFeatures]) and falls back to ORT per call;
     * otherwise this is the ORT path unchanged.
     */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        if (!ensure(requireTowers = true)) return null
        return try {
            val (pixels, positions) = preprocessImage(bitmap) ?: return null
            if (VULKAN_ENABLED && vulkanVision != 0L) {
                runCatching { vulkanVisionFeatures(pixels, positions) }.getOrNull()?.let { return it }
            }
            val tower = vision ?: return null
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
     *
     * ORT-only by design: the audio graph takes a bool `input_features_mask` and the
     * Vulkan bridge has no bool dtype path, so no Vulkan attempt is made here even
     * when [VULKAN_ENABLED] is true. The [vulkanAudio] handle is retained (0L) so the
     * tower set stays symmetric with embed/decoder/vision.
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
            // Bool mask: one byte per element through the BOOL overload.
            val maskBytes = java.nio.ByteBuffer.allocateDirect(frames)
            for (i in 0 until frames) maskBytes.put(i, 1)
            val maskTensor = OnnxTensor.createTensor(
                env, maskBytes, longArrayOf(1, frames.toLong()),
                ai.onnxruntime.OnnxJavaType.BOOL,
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

    /** Free all sessions and any Vulkan handles. Idempotent. */
    override fun close() {
        synchronized(lock) {
            embed = null
            decoder = null
            vision = null
            audio = null
            tokenizer = null
            for (name in ALL_FILES) OnnxSessions.close(sessionKey(name))
            val handles = longArrayOf(vulkanEmbed, vulkanDecoder, vulkanVision, vulkanAudio)
            vulkanEmbed = 0L
            vulkanDecoder = 0L
            vulkanVision = 0L
            vulkanAudio = 0L
            for (handle in handles) {
                if (handle != 0L) runCatching { VulkanBridge.close(handle) }
            }
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
                // Gated Vulkan stubs (EXPECTED NO-GO): fp16 graphs are refused by the
                // preflight gate, so these stay 0L and every path below runs on ORT.
                // `loadPath` (not `load`) so the `.onnx_data` sidecars resolve
                // relative to the model file on disk.
                if (VULKAN_ENABLED) {
                    if (vulkanEmbed == 0L) tryVulkanTower(EMBED_FILE) { vulkanEmbed = it }
                    if (vulkanDecoder == 0L) tryVulkanTower(DECODER_FILE) { vulkanDecoder = it }
                    if (vulkanVision == 0L) tryVulkanTower(VISION_FILE) { vulkanVision = it }
                    // No audio attempt: bool mask has no bridge dtype path (see encodeAudio).
                }
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the Gemma ONNX bundle in $directory", e)
            }
            return decoder != null && embed != null && tokenizer != null &&
                (!requireTowers || (vision != null && audio != null))
        }
    }

    /**
     * Best-effort Vulkan open for one tower file on disk; leaves the handle at 0L
     * on any failure (missing file, allowlist refusal, preflight NO-GO, load
     * failure) so ORT stays the path that serves.
     */
    private fun tryVulkanTower(name: String, assign: (Long) -> Unit) {
        try {
            if (!VulkanSessions.isUsable()) return
            val key = sessionKey(name)
            if (key !in VulkanSessions.allowlist) return
            val path = File(directory, name)
            if (!path.isFile) return
            val modelBytes = try {
                path.readBytes()
            } catch (e: Throwable) {
                Log.w(TAG, "cannot read $name for vulkan preflight", e)
                return
            }
            val problem = VulkanSessions.preflight(key) { modelBytes }
            if (problem != null) {
                // EXPECTED for q4f16: fp16 unsupported.
                Log.w(TAG, "vulkan preflight refused $name: $problem")
                return
            }
            val handle = try {
                VulkanBridge.loadPath(path.absolutePath)
            } catch (e: Throwable) {
                Log.w(TAG, "vulkan load failed for $name, using ORT", e)
                0L
            }
            if (handle == 0L) return
            // `loadPath` succeeding means the graph loaded; there is no separate
            // handle-level preflight on the landed bridge, so the handle serves.
            assign(handle)
            Log.i(TAG, "vulkan session open for $key")
        } catch (e: Throwable) {
            Log.w(TAG, "vulkan open failed for $name, using ORT", e)
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
        // Gated Vulkan attempt for text runs (NO-GO while VULKAN_ENABLED is
        // false): per-run fallback to the ORT embed graph below.
        if (VULKAN_ENABLED && vulkanEmbed != 0L) {
            runCatching { vulkanAssemble(parts) }.getOrNull()?.let { return it }
        }
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
    //
    // Vulkan-gated stubs (EXPECTED NO-GO): [generate] always uses the ORT
    // [runPrefill]/[runDecode] below. These helpers are wired against
    // runCached/kvCreate/kvClose/argmaxLastRow so the cached-decode shape is in
    // place if an fp16-capable backend ever flips [VULKAN_ENABLED] on; while it
    // is false they are unreachable and every turn falls back to ORT.

    /**
     * One turn's prefill + cached decode over Vulkan, or null to fall back to ORT.
     *
     * Mirrors [generate]'s loop: prefill the whole prompt uncached through
     * [VulkanBridge.run], then one-token cached steps through
     * [VulkanBridge.runCached] threading the native KV cache, with the argmax of
     * the last logits row served by [VulkanBridge.argmaxLastRow].
     */
    private fun vulkanGenerateCached(
        assembled: Assembled,
        limit: Int,
        onPiece: (String) -> Boolean,
    ): String? {
        if (!VULKAN_ENABLED) return null
        val decHandle = vulkanDecoder
        val embHandle = vulkanEmbed
        if (decHandle == 0L || embHandle == 0L) return null
        val tok = tokenizer ?: return null
        val kv = try {
            VulkanBridge.kvCreate(decHandle, assembled.totalLen + limit)
        } catch (e: Throwable) {
            Log.w(TAG, "gemma vulkan kvCreate failed", e)
            return null
        }
        if (kv == 0L) return null
        try {
            var next = vulkanPrefill(decHandle, kv, assembled) ?: return null
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
                next = vulkanDecodeCached(
                    decHandle, embHandle, kv, next,
                    assembled.totalLen + produced.size,
                ) ?: break
            }
            return output.toString()
        } finally {
            runCatching { VulkanBridge.kvClose(kv) }
        }
    }

    /** Prefill over Vulkan: whole prompt, uncached, returning the first next-token id. */
    private fun vulkanPrefill(handle: Long, kv: Long, assembled: Assembled): Int? {
        val n = assembled.totalLen.toLong()
        val mask = LongArray(assembled.totalLen) { 1L }
        val pos = LongArray(assembled.totalLen) { it.toLong() }
        val payload = packPrefillInputs(assembled, mask, pos)
        val outBytes = try {
            VulkanBridge.runCached(
                handle, kv,
                arrayOf(
                    "inputs_embeds", "per_layer_inputs", "attention_mask",
                    "position_ids", "num_logits_to_keep",
                ),
                intArrayOf(DTYPE_F32, DTYPE_F32, DTYPE_I64, DTYPE_I64, DTYPE_I64),
                longArrayOf(
                    1, n, HIDDEN.toLong(),
                    1, n, N_LAYERS.toLong(), LAYER_WIDTH.toLong(),
                    1, n,
                    1, n,
                    1,
                ),
                intArrayOf(0, 3, 7, 9, 11),
                payload,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "gemma vulkan prefill failed", e)
            return null
        } ?: return null
        return vulkanArgmaxLastRow(outBytes, assembled.totalLen)
    }

    /**
     * One-token cached decode over Vulkan ([vulkanDecodeCached] in the task brief's
     * `runCached` sense): the embed graph scores the single id on Vulkan, then the
     * decoder step threads the native KV cache.
     */
    private fun vulkanDecodeCached(
        decHandle: Long,
        embHandle: Long,
        kv: Long,
        token: Int,
        totalLen: Int,
    ): Int? {
        val one = vulkanEmbedSingle(embHandle, token) ?: return null
        val pos = (totalLen - 1).toLong()
        val mask = LongArray(totalLen) { 1L }
        val payload = packDecodeInputs(one, mask, pos)
        val outBytes = try {
            VulkanBridge.runCached(
                decHandle, kv,
                arrayOf(
                    "inputs_embeds", "per_layer_inputs", "attention_mask",
                    "position_ids", "num_logits_to_keep",
                ),
                intArrayOf(DTYPE_F32, DTYPE_F32, DTYPE_I64, DTYPE_I64, DTYPE_I64),
                longArrayOf(
                    1, 1, HIDDEN.toLong(),
                    1, 1, N_LAYERS.toLong(), LAYER_WIDTH.toLong(),
                    1, totalLen.toLong(),
                    1, 1,
                    1,
                ),
                intArrayOf(0, 3, 7, 9, 11),
                payload,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "gemma vulkan decode step failed", e)
            return null
        } ?: return null
        return vulkanArgmaxLastRow(outBytes, 1)
    }

    /** Single-id embed over Vulkan; null to fall back to the ORT embed graph. */
    private fun vulkanEmbedSingle(handle: Long, token: Int): Assembled? {
        val payload = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(token.toLong()).array()
        val outBytes = try {
            VulkanBridge.run(
                handle,
                arrayOf("input_ids"),
                intArrayOf(DTYPE_I64),
                longArrayOf(1, 1),
                intArrayOf(0),
                payload,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "gemma vulkan embed failed", e)
            return null
        } ?: return null
        // Two outputs concatenated: inputs_embeds [1,1,HIDDEN] then
        // per_layer_inputs [1,1,N_LAYERS,LAYER_WIDTH].
        val floats = leToFloats(outBytes)
        val wantEmb = HIDDEN
        val wantLayer = N_LAYERS * LAYER_WIDTH
        if (floats.size < wantEmb + wantLayer) {
            Log.w(TAG, "gemma vulkan embed returned ${floats.size} floats, want ${wantEmb + wantLayer}")
            return null
        }
        return Assembled(
            embeds = floats.copyOfRange(0, wantEmb),
            perLayer = floats.copyOfRange(wantEmb, wantEmb + wantLayer),
            totalLen = 1,
        )
    }

    /**
     * Argmax of the last logits row via the bridge, with a host scan fallback.
     *
     * The fp16 `logits [B, K, 262144]` arrive as little-endian fp32 in the bridge
     * payload (widened natively); without output metadata the vocabulary is
     * inferred as `payload / seqLen`.
     */
    private fun vulkanArgmaxLastRow(payload: ByteArray, seqLen: Int): Int? {
        val floats = leToFloats(payload)
        if (floats.isEmpty() || seqLen <= 0 || floats.size % seqLen != 0) return null
        val vocab = floats.size / seqLen
        if (vocab <= 0) return null
        try {
            return VulkanBridge.argmaxLastRow(payload, seqLen, vocab, STOP)
        } catch (e: Throwable) {
            Log.w(TAG, "gemma bridge argmax failed, scanning on host", e)
        }
        var best = 0
        var bestScore = -Float.MAX_VALUE
        val base = (seqLen - 1) * vocab
        for (i in 0 until vocab) {
            if (i in STOP) continue
            val score = floats[base + i]
            if (!score.isNaN() && (bestScore == -Float.MAX_VALUE || score > bestScore)) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Vision `image_features [N, 1536]` from Vulkan, or null to fall back to ORT. */
    private fun vulkanVisionFeatures(pixels: FloatArray, positions: LongArray): FloatArray? {
        val handle = vulkanVision
        if (handle == 0L) return null
        val patches = (pixels.size / 768).toLong()
        val count = (positions.size / 2).toLong()
        val buf = java.nio.ByteBuffer.allocate(pixels.size * 4 + positions.size * 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in pixels) buf.putFloat(v)
        for (p in positions) buf.putLong(p)
        val outBytes = try {
            VulkanBridge.run(
                handle,
                arrayOf("pixel_values", "pixel_position_ids"),
                intArrayOf(DTYPE_F32, DTYPE_I64),
                longArrayOf(1, patches, 768, 1, count, 2),
                intArrayOf(0, 3),
                buf.array(),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "gemma vulkan vision encode failed", e)
            return null
        } ?: return null
        val floats = leToFloats(outBytes)
        if (floats.isEmpty() || floats.size % HIDDEN != 0) {
            Log.w(TAG, "gemma vulkan vision returned ${floats.size} floats, want multiples of $HIDDEN")
            return null
        }
        return floats
    }

    /**
     * Vulkan assembly: each text run through [vulkanEmbedSingle], media spliced
     * verbatim with zero per-layer rows, exactly like [assemble]. Null on any
     * miss so the caller falls back to the ORT assembly.
     */
    private fun vulkanAssemble(parts: List<PromptPart>): Assembled? {
        val handle = vulkanEmbed
        if (handle == 0L) return null
        val embedSeq = ArrayList<FloatArray>()
        val layerSeq = ArrayList<FloatArray>()
        var totalLen = 0
        for (part in parts) {
            when (part) {
                is PromptPart.Text -> {
                    val ids = tokenizer?.encode(part.text) ?: return null
                    if (ids.isEmpty()) continue
                    for (id in ids) {
                        val one = vulkanEmbedSingle(handle, id) ?: return null
                        embedSeq.add(one.embeds)
                        layerSeq.add(one.perLayer)
                        totalLen += 1
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

    // -- Tower front ends --------------------------------------------------------

    /**
     * Patchify [bitmap] for the vision tower: aspect-preserving fit to a 280-soft-token
     * budget (grid sides rounded down to multiples of 48 px), bilinear resample, 1/255
     * rescale, 16×16×3 patches, and column-first position ids.
     */
    private fun preprocessImage(bitmap: Bitmap): Pair<FloatArray, LongArray>? {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return null
        try {
            val srcW = readable.width
            val srcH = readable.height
            if (srcW == 0 || srcH == 0) return null
            // Budget: 280 soft tokens × 9 patches = 2520 patches of 16×16.
            val budgetPx = 280.0 * 9 * 16 * 16
            val factor = kotlin.math.sqrt(budgetPx / (srcW * srcH))
            val side = 48.0
            var targetW = (kotlin.math.floor(factor * srcW / side).toInt() * 48)
            var targetH = (kotlin.math.floor(factor * srcH / side).toInt() * 48)
            if (targetW == 0 && targetH == 0) return null
            if (targetW == 0) {
                targetW = 48
                targetH = minOf(maxOf(((srcW.toDouble() / srcH).toInt()) * 48, 48), 280 * 48)
            } else if (targetH == 0) {
                targetH = 48
                targetW = minOf(maxOf(((srcH.toDouble() / srcW).toInt()) * 48, 48), 280 * 48)
            }
            val rows = targetH / 16
            val cols = targetW / 16
            if (rows % 3 != 0 || cols % 3 != 0 || rows == 0 || cols == 0) return null
            val pixels = IntArray(srcW * srcH)
            readable.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH)
            val patches = FloatArray(rows * cols * 768)
            val xScale = srcW.toFloat() / targetW
            val yScale = srcH.toFloat() / targetH
            for (py in 0 until targetH) {
                val (y0, y1, wy) = sampleBilinear(py, yScale, srcH)
                for (px in 0 until targetW) {
                    val (x0, x1, wx) = sampleBilinear(px, xScale, srcW)
                    val pr = py / 16
                    val pc = px / 16
                    val base = (pr * cols + pc) * 768 + ((py % 16) * 16 + (px % 16)) * 3
                    for (c in 0..2) {
                        val shift = (2 - c) * 8
                        val top = lerpChan(pixels, srcW, x0, y0, x1, shift, wx)
                        val bottom = lerpChan(pixels, srcW, x0, y1, x1, shift, wx)
                        patches[base + c] = (top + (bottom - top) * wy) / 255f
                    }
                }
            }
            // Column-first: component 0 is the column.
            val positions = LongArray(rows * cols * 2)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    positions[(r * cols + c) * 2] = c.toLong()
                    positions[(r * cols + c) * 2 + 1] = r.toLong()
                }
            }
            return Pair(patches, positions)
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    private fun sampleBilinear(out: Int, scale: Float, extent: Int): Triple<Int, Int, Float> {
        val source = (out + 0.5f) * scale - 0.5f
        val clamped = maxOf(source, 0f)
        val low = clamped.toInt()
        val weight = clamped - low
        val lo = minOf(low, extent - 1)
        val hi = minOf(lo + 1, extent - 1)
        return Triple(lo, hi, weight)
    }

    private fun lerpChan(
        pixels: IntArray, width: Int, x0: Int, y0: Int, x1: Int, shift: Int, wx: Float,
    ): Float {
        fun channel(x: Int, y: Int): Float = ((pixels[y * width + x] shr shift) and 0xFF).toFloat()
        return channel(x0, y0) + (channel(x1, y0) - channel(x0, y0)) * wx
    }

    /**
     * 128-bin HTK log-mel for 16 kHz mono [samples], matching `logmel.rs`: 512-point
     * transform at a 160-sample hop, triangles over all 257 bins, magnitude, plain log.
     * Returns `frames * 128` row-major, or null when the clip is shorter than one window.
     */
    private fun logMel(samples: FloatArray): FloatArray? {
        val hop = 160
        val win = 512
        if (samples.size < win) return null
        val frames = (samples.size - win) / hop + 1
        if (frames <= 0) return null
        // Hann window.
        val window = DoubleArray(win) { i ->
            0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * (i + 0.5) / win)
        }
        // HTK mel filterbank: 128 triangles over 257 bins, 0..8000 Hz.
        val bins = win / 2 + 1
        val hzPerBin = 16000.0 / win
        fun hzToMel(hz: Double): Double = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        val low = hzToMel(0.0)
        val high = hzToMel(8000.0)
        val centres = DoubleArray(N_MELS + 2) { i -> low + (high - low) * i / (N_MELS + 1) }
        val filters = Array(N_MELS) { DoubleArray(bins) }
        for (m in 0 until N_MELS) {
            val left = centres[m]
            val center = centres[m + 1]
            val right = centres[m + 2]
            for (k in 0 until bins) {
                val mel = hzToMel(k * hzPerBin)
                filters[m][k] = when {
                    mel < left || mel > right -> 0.0
                    mel <= center -> (mel - left) / (center - left)
                    else -> (right - mel) / (right - center)
                }
            }
        }
        val out = FloatArray(frames * N_MELS)
        val re = DoubleArray(win)
        val im = DoubleArray(win)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until win) {
                re[i] = samples[off + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (m in 0 until N_MELS) {
                var energy = 0.0
                for (k in 0 until bins) {
                    val mag = re[k] * re[k] + im[k] * im[k]
                    energy += mag * filters[m][k]
                }
                // Plain log, floored to avoid -inf on silence.
                out[f * N_MELS + m] = kotlin.math.ln(maxOf(energy, 1e-10)).toFloat()
            }
        }
        return out
    }

    /** In-place radix-2 FFT (length must be a power of two; 512 here). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wr = kotlin.math.cos(angle)
            val wi = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val TAG = "GemmaOnnxHandle"

        /**
         * Vulkan gate: false until the backend can serve fp16 graphs (EXPECTED
         * NO-GO — q4f16 weights, KV cache and logits are all fp16). With it
         * false no Vulkan call is attempted and every path runs on ORT; with it
         * true the per-tower handles above are attempted with ORT fallback.
         */
        private const val VULKAN_ENABLED = false

        /** ONNX TensorProto elem types as carried in the Vulkan `dtypes` array. */
        private const val DTYPE_F32 = 1
        private const val DTYPE_I64 = 7

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

        /** Prefill inputs in ORT order: embeds + per-layer + i64 mask/positions/keep. */
        private fun packPrefillInputs(assembled: Assembled, mask: LongArray, pos: LongArray): ByteArray {
            val keep = longArrayOf(assembled.totalLen.toLong())
            val buf = java.nio.ByteBuffer.allocate(
                (assembled.embeds.size + assembled.perLayer.size) * 4 +
                    (mask.size + pos.size + keep.size) * 8,
            ).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in assembled.embeds) buf.putFloat(v)
            for (v in assembled.perLayer) buf.putFloat(v)
            for (m in mask) buf.putLong(m)
            for (p in pos) buf.putLong(p)
            for (k in keep) buf.putLong(k)
            return buf.array()
        }

        /** One-token decode inputs in ORT order. */
        private fun packDecodeInputs(one: Assembled, mask: LongArray, pos: Long): ByteArray {
            val keep = longArrayOf(1L)
            val buf = java.nio.ByteBuffer.allocate(
                (one.embeds.size + one.perLayer.size) * 4 +
                    (mask.size + 1 + keep.size) * 8,
            ).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in one.embeds) buf.putFloat(v)
            for (v in one.perLayer) buf.putFloat(v)
            for (m in mask) buf.putLong(m)
            buf.putLong(pos)
            for (k in keep) buf.putLong(k)
            return buf.array()
        }

        private fun leToFloats(bytes: ByteArray): FloatArray {
            val out = FloatArray(bytes.size / 4)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(out)
            return out
        }
    }
}
