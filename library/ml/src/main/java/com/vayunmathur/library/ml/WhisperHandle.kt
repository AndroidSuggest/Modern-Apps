package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
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
 * # Threading
 *
 * Not thread-safe, and more sharply than most handles here: a transcription threads the KV
 * cache through one decode step per token, so two concurrent calls would interleave cache
 * states. A caller must hold a lock across [transcribe] and [close].
 */
class WhisperHandle private constructor(private val source: String) : AutoCloseable {
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var special: IntArray = IntArray(0)
    private var suppress: Set<Int> = emptySet()
    private var suppressAtBegin: Set<Int> = emptySet()
    private var assetDir: String = DIR

    /** True if both graphs came up and the ids describe the model they were built from. */
    val isAvailable: Boolean get() = encoder != null && decoder != null

    /**
     * Transcribe one 30-second log-mel window into token ids, or null on failure.
     *
     * [mel] is `MELS * FRAMES` floats row-major. [languageToken] is a `<|xx|>` id, or negative to let
     * the model detect the language. The ids are raw — the caller's tokenizer skips the special and
     * timestamp ones.
     */
    fun transcribe(mel: FloatArray, languageToken: Int): IntArray? {
        val enc = encoder ?: return null
        val dec = decoder ?: return null
        if (mel.size != MELS * FRAMES) return null
        if (special.size != SPECIAL_IDS) return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            encode(enc, env, mel).useOrt { hidden ->
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

    /** Free both networks. Idempotent. */
    override fun close() {
        encoder = null
        decoder = null
        OnnxSessions.close(sessionKey(ENCODER))
        OnnxSessions.close(sessionKey(DECODER))
    }

    private var sessionPrefix: String = ""

    private fun sessionKey(name: String): String = "$sessionPrefix$assetDir/$name"

    override fun toString(): String = "whisper-base from $source"

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
    }
}
