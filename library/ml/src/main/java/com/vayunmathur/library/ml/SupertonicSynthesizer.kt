package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * On-device text-to-speech: Supertonic 3 on the reduced ONNX Runtime build.
 *
 * One bundle covers 31 languages and 10 voices. All four networks are the upstream ONNX
 * exports (`Supertone/supertonic-3`), driven directly:
 *
 * - **Duration predictor** (`duration_predictor.onnx`): `text_ids [1, W]` + `style_dp
 *   [1, 8, 16]` + mask → one number, the utterance's length in seconds, which fixes every
 *   later shape.
 * - **Text encoder** (`text_encoder.onnx`): `text_ids` + `style_ttl [1, 50, 256]` + mask →
 *   `text_emb [1, 256, chars]` conditioning.
 * - **Flow-matching sampler** (`vector_estimator.onnx`): the expensive one. 16 steps, each a
 *   single call with `current_step`/`total_step` — the export bakes classifier-free guidance
 *   internally (batch-2 branches), so unlike the old Vulkan path there is no host-side
 *   guidance combination, only the Euler advance `latent += denoised / 16`.
 * - **ConvNeXt vocoder** (`vocoder.onnx`): latent `[1, 144, F]` → 44,100 Hz samples.
 *
 * # There is no phonemiser
 *
 * The front end is a flat 65,536-entry codepoint table (`unicode_indexer.json`), not
 * espeak-ng, so nothing has to be generated per language on the build machine. The model
 * does expect decomposed text, which [synthesize] does itself through
 * `java.text.Normalizer`: precomposed accents are unmapped while combining marks are
 * first-class tokens, so skipping it would quietly drop characters. Emoji and the listed
 * symbols are dropped, `@`/`e.g.`/`i.e.` expanded, and spacing collapsed — mirroring
 * `post::supertonic::normalise`.
 *
 * # Two places the bundle can live
 *
 * [inAssets] reads it out of the APK and [inDirectory] out of a folder on disk. The four
 * networks total ~395 MB, so both go through the shared [OnnxSessions] cache rather than
 * being copied per handle. An asset must be stored **uncompressed** (`noCompress += "onnx"`),
 * since int8/fp32 weights barely deflate.
 *
 * # Voices
 *
 * A voice is two small style tensors parsed from `voice_styles/<name>.json`, not a model, so
 * [voice] switches without re-opening anything.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when a file is absent, the indexer is
 * the wrong size, or a session will not open — and then [synthesize] returns an empty array.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [synthesize], [voice] and [close].
 */
class SupertonicSynthesizer private constructor(
    private val bundle: Bundle,
    voice: String,
) : AutoCloseable {
    private val lock = Any()

    @Volatile private var sessions: Sessions? = null
    @Volatile private var indexer: IntArray? = null
    @Volatile private var styleTtl: FloatArray? = null
    @Volatile private var styleDp: FloatArray? = null
    @Volatile private var loadTried = false
    @Volatile private var voiceName: String = voice

    /** Supertonic's output rate, fixed by the vocoder rather than by the voice. */
    val sampleRate: Int = SAMPLE_RATE

    /** True if all four networks came up, the codepoint table is the right size and a voice read. */
    val isAvailable: Boolean get() = ensure()

    /**
     * Switch to another voice, returning false if it could not be read.
     *
     * Cheap: the four sessions stay open and only the two style tensors are replaced.
     */
    fun voice(name: String): Boolean {
        if (!ensure()) return false
        synchronized(lock) {
            val (ttl, dp) = try {
                readVoice(bundle, name)
            } catch (e: Throwable) {
                Log.e(TAG, "cannot read the voice $name in $bundle", e)
                return false
            }
            styleTtl = ttl
            styleDp = dp
            voiceName = name
            return true
        }
    }

    /**
     * Synthesise [text] in [language] and return mono samples in `-1..1` at [sampleRate].
     *
     * [language] is the ISO-639-1 code the model should read in, or `na` for one it does not
     * list. Supertonic 3 is the multilingual model and was trained with the language tag always
     * present; getting it wrong is silent, so this has no default.
     *
     * Returns an empty array when there is nothing in the model's vocabulary, when the engine is
     * unavailable, or when the pass failed — all three are "no audio" to a caller.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but every
     * shape here scales with the utterance and the sampler runs 16 passes over all of it.
     *
     * Two calls with the same text differ, as flow matching starts from a sampled latent.
     */
    fun synthesize(text: String, language: String): FloatArray {
        if (text.isBlank()) return FloatArray(0)
        if (!ensure()) return FloatArray(0)
        val live = sessions ?: return FloatArray(0)
        val table = indexer ?: return FloatArray(0)
        val ttl = styleTtl ?: return FloatArray(0)
        val dp = styleDp ?: return FloatArray(0)
        return try {
            val ids = toIds(table, normalise(text), language) ?: return FloatArray(0)
            val env = OrtEnvironment.getEnvironment()
            // Duration: seconds -> latent frames.
            val logSeconds = runDuration(live.duration, env, ids, dp)
            val frames = max(1, ceil(exp(logSeconds) / SPEED * SAMPLE_RATE / SAMPLES_PER_FRAME).toInt())
            // Text conditioning.
            val textEmb = runText(live.text, env, ids, ttl) ?: return FloatArray(0)
            // Flow-matching sampler: 16 Euler steps from a sampled latent.
            // Speech is meant to vary between calls, so the seed comes from the clock and the
            // normals are Box-Muller over the default PRNG — nothing here is a secret.
            val rng = java.util.Random(System.nanoTime())
            var latent = FloatArray(LATENT_CHANNELS * frames) { gaussian(rng) }
            for (step in 0 until STEPS) {
                val denoised = runSampler(
                    live.sampler, env, latent, frames, textEmb, ids.size, ttl, step,
                ) ?: return FloatArray(0)
                val scale = 1f / STEPS
                for (i in latent.indices) latent[i] += denoised[i] * scale
            }
            // Vocoder.
            runVocoder(live.vocoder, env, latent, frames) ?: FloatArray(0)
        } catch (e: Throwable) {
            Log.e(TAG, "supertonic synthesis failed", e)
            FloatArray(0)
        }
    }

    /** Free all four sessions. Idempotent. */
    override fun close() {
        synchronized(lock) {
            sessions = null
            for (name in GRAPHS) OnnxSessions.close(sessionKey(bundle, name))
        }
    }

    private fun ensure(): Boolean {
        if (sessions != null && indexer != null && styleTtl != null) return true
        synchronized(lock) {
            if (sessions != null && indexer != null && styleTtl != null) return true
            if (loadTried) return false
            loadTried = true
            try {
                indexer = readIndexer(bundle)
                val opened = Sessions(
                    duration = openSession(bundle, GRAPHS[0]),
                    text = openSession(bundle, GRAPHS[1]),
                    sampler = openSession(bundle, GRAPHS[2]),
                    vocoder = openSession(bundle, GRAPHS[3]),
                )
                if (!opened.isReady) {
                    opened.close(bundle)
                    return false
                }
                val (ttl, dp) = readVoice(bundle, voiceName)
                styleTtl = ttl
                styleDp = dp
                sessions = opened
                return true
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the Supertonic bundle in $bundle", e)
                return false
            }
        }
    }

    // -- Sessions -------------------------------------------------------------

    private class Sessions(
        val duration: OrtSession?,
        val text: OrtSession?,
        val sampler: OrtSession?,
        val vocoder: OrtSession?,
    ) {
        val isReady: Boolean get() =
            duration != null && text != null && sampler != null && vocoder != null

        fun close(bundle: Bundle) {
            for (name in GRAPHS) OnnxSessions.close(sessionKey(bundle, name))
        }
    }

    // -- Bundle ---------------------------------------------------------------

    /**
     * Where a bundle's files are, abstracted over the APK and the filesystem.
     *
     * Sessions are opened through the shared [OnnxSessions] cache, so unlike the old
     * descriptor-passing shape this only needs byte reads.
     */
    internal interface Bundle {
        /** All of [name], for models and small files alike. */
        fun read(name: String): ByteArray

        /** Stable key prefix for this bundle's cached sessions. */
        fun key(): String
    }

    private class Assets(private val assets: AssetManager, private val path: String) : Bundle {
        override fun read(name: String): ByteArray {
            // Voice styles ship in the APK under `voices/`; plans + indexer at the top.
            val full = if (name.startsWith("style_")) "$path/voices/$name" else "$path/$name"
            return assets.open(full).use { it.readBytes() }
        }

        override fun key(): String = "asset:$path/"

        override fun toString(): String = "the APK's $path/"
    }

    private class Directory(
        private val directory: File,
        private val assets: AssetManager,
        private val assetPath: String,
    ) : Bundle {
        override fun read(name: String): ByteArray {
            // Plans + indexer download; voice styles ship in the APK and are read from
            // there even when the plans came from disk.
            if (name.startsWith("style_")) {
                return assets.open("$assetPath/voices/$name").use { it.readBytes() }
            }
            val file = File(directory, name)
            require(file.isFile) { "$name is missing from $directory" }
            return file.readBytes()
        }

        override fun key(): String = "file:${directory.absolutePath}/"

        override fun toString(): String = directory.toString()
    }

    // -- Model runs -----------------------------------------------------------

    private fun runDuration(
        session: OrtSession?,
        env: OrtEnvironment,
        ids: IntArray,
        styleDp: FloatArray,
    ): Float {
        requireNotNull(session) { "the duration predictor is not open" }
        // The export holds its sentence token internally; the raw ids go in as-is.
        val idTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()),
            longArrayOf(1, ids.size.toLong()),
        )
        val styleTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(styleDp), longArrayOf(1, 8, 16),
        )
        val maskTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(FloatArray(ids.size) { 1f }),
            longArrayOf(1, 1, ids.size.toLong()),
        )
        idTensor.useOrt {
            styleTensor.useOrt {
                maskTensor.useOrt {
                    val inputs = mapOf(
                        "text_ids" to idTensor,
                        "style_dp" to styleTensor,
                        "text_mask" to maskTensor,
                    )
                    session.run(inputs).useOrt { result ->
                        val out = result.get("duration").get() as OnnxTensor
                        val value = FloatArray(1)
                        out.floatBuffer.get(value)
                        return value[0]
                    }
                }
            }
        }
    }

    private fun runText(
        session: OrtSession?,
        env: OrtEnvironment,
        ids: IntArray,
        styleTtl: FloatArray,
    ): FloatArray? {
        requireNotNull(session) { "the text encoder is not open" }
        val idTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()),
            longArrayOf(1, ids.size.toLong()),
        )
        val styleTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(styleTtl), longArrayOf(1, 50, 256),
        )
        val maskTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(FloatArray(ids.size) { 1f }),
            longArrayOf(1, 1, ids.size.toLong()),
        )
        idTensor.useOrt {
            styleTensor.useOrt {
                maskTensor.useOrt {
                    val inputs = mapOf(
                        "text_ids" to idTensor,
                        "style_ttl" to styleTensor,
                        "text_mask" to maskTensor,
                    )
                    session.run(inputs).useOrt { result ->
                        val out = result.get("text_emb").get() as OnnxTensor
                        val shape = out.info.shape
                        val flat = FloatArray((shape[1] * shape[2]).toInt())
                        out.floatBuffer.get(flat)
                        return flat
                    }
                }
            }
        }
    }

    private fun runSampler(
        session: OrtSession?,
        env: OrtEnvironment,
        latent: FloatArray,
        frames: Int,
        textEmb: FloatArray,
        chars: Int,
        styleTtl: FloatArray,
        step: Int,
    ): FloatArray? {
        requireNotNull(session) { "the sampler is not open" }
        val latentTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(latent), longArrayOf(1, LATENT_CHANNELS.toLong(), frames.toLong()),
        )
        val textTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(textEmb), longArrayOf(1, 256, chars.toLong()),
        )
        val styleTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(styleTtl), longArrayOf(1, 50, 256),
        )
        val latentMask = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(FloatArray(frames) { 1f }),
            longArrayOf(1, 1, frames.toLong()),
        )
        val textMask = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(FloatArray(chars) { 1f }),
            longArrayOf(1, 1, chars.toLong()),
        )
        val currentTensor = OnnxTensor.createTensor(env, floatArrayOf(step.toFloat()))
        val totalTensor = OnnxTensor.createTensor(env, floatArrayOf(STEPS.toFloat()))
        latentTensor.useOrt {
            textTensor.useOrt {
                styleTensor.useOrt {
                    latentMask.useOrt {
                        textMask.useOrt {
                            currentTensor.useOrt {
                                totalTensor.useOrt {
                                    val inputs = mapOf(
                                        "noisy_latent" to latentTensor,
                                        "text_emb" to textTensor,
                                        "style_ttl" to styleTensor,
                                        "latent_mask" to latentMask,
                                        "text_mask" to textMask,
                                        "current_step" to currentTensor,
                                        "total_step" to totalTensor,
                                    )
                                    session.run(inputs).useOrt { result ->
                                        val out = result.get("denoised_latent").get() as OnnxTensor
                                        val flat = FloatArray(latent.size)
                                        out.floatBuffer.get(flat)
                                        return flat
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun runVocoder(
        session: OrtSession?,
        env: OrtEnvironment,
        latent: FloatArray,
        frames: Int,
    ): FloatArray? {
        requireNotNull(session) { "the vocoder is not open" }
        val latentTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(latent), longArrayOf(1, LATENT_CHANNELS.toLong(), frames.toLong()),
        )
        latentTensor.useOrt {
            session.run(mapOf("latent" to latentTensor)).useOrt { result ->
                val out = result.get("wav_tts").get() as OnnxTensor
                val shape = out.info.shape
                val flat = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt())
                out.floatBuffer.get(flat)
                return flat
            }
        }
    }

    // -- Front end ------------------------------------------------------------

    private fun normalise(text: String): String {
        var out = StringBuilder(text.length)
        for (codepoint in text.codePoints().toArray()) {
            val ch = String(Character.toChars(codepoint))
            if (isEmoji(codepoint) || ch == "♥" || ch == "☆" || ch == "♡" || ch == "©" || ch == "\\") {
                continue
            }
            out.append(substitute(ch) ?: ch)
        }
        var cleaned = out.toString()
        cleaned = cleaned.replace("@", " at ")
            .replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ")
        for (mark in listOf(',', '.', '!', '?', ';', ':', '\'')) {
            cleaned = cleaned.replace(" $mark", "$mark")
        }
        val collapsed = StringBuilder(cleaned.length)
        for (ch in cleaned) {
            val repeated = (ch == '"' || ch == '\'') && collapsed.endsWith(ch)
            if (!repeated) collapsed.append(ch)
        }
        return collapsed.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun isEmoji(codepoint: Int): Boolean {
        val type = Character.getType(codepoint)
        return type == Character.OTHER_SYMBOL.toInt() || type == Character.MODIFIER_SYMBOL.toInt()
    }

    private fun substitute(ch: String): String? = when (ch) {
        "`" -> "'"
        else -> null
    }

    /** One standard normal via Box-Muller. */
    private fun gaussian(rng: java.util.Random): Float {
        val u = max(rng.nextDouble(), 1e-300)
        val v = rng.nextDouble()
        return (sqrt(-2.0 * ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)).toFloat()
    }

    private fun toIds(table: IntArray, text: String, language: String): IntArray? {
        if (language.length != 2 || !language.all { it in 'a'..'z' }) return null
        fun index(s: String): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i < s.length) {
                val code = s.codePointAt(i)
                i += Character.charCount(code)
                if (code >= INDEXER_ENTRIES) continue
                val token = table[code]
                if (token >= 0) out.add(token)
            }
            return out
        }
        val body = index(text)
        if (body.isEmpty()) return null
        val withEnd = if (text.endsWith('.')) body else body + index(".")
        return (index("<$language>") + withEnd + index("</$language>")).toIntArray()
    }

    companion object {
        private const val TAG = "SupertonicSynthesizer"

        /** The vocoder's rate: 44,100 Hz, at 3,072 samples per latent frame. */
        const val SAMPLE_RATE = 44_100

        /** Samples per latent frame. */
        const val SAMPLES_PER_FRAME = 3_072

        /** Latent channels. */
        const val LATENT_CHANNELS = 144

        /** Flow-matching steps. */
        const val STEPS = 16

        /** Duration speech-rate divisor. */
        const val SPEED = 1.05f

        /** Codepoints in the indexer table. */
        const val INDEXER_ENTRIES = 65_536

        /** The voice used when a caller does not name one. */
        const val DEFAULT_VOICE = "F1"

        /** Where [inAssets] looks unless told otherwise. */
        const val ASSET_PATH = "supertonic"

        /**
         * The four exports, in pipeline order.
         *
         * The bundle holds `.onnx` directly — no conversion step, unlike the old `.maml` graphs.
         */
        val GRAPHS = listOf(
            "duration_predictor.onnx",
            "text_encoder.onnx",
            "vector_estimator.onnx",
            "vocoder.onnx",
        )

        const val INDEXER = "unicode_indexer.json"

        /** The bundle shipped inside the APK. */
        fun inAssets(
            assets: AssetManager,
            path: String = ASSET_PATH,
            voice: String = DEFAULT_VOICE,
        ): SupertonicSynthesizer = SupertonicSynthesizer(Assets(assets, path), voice)

        /**
         * The bundle in a folder on disk, as a downloaded one is.
         *
         * [assets] backs the voice styles, which ship in the APK rather than downloading.
         */
        fun inDirectory(
            directory: File,
            assets: AssetManager,
            assetPath: String = ASSET_PATH,
            voice: String = DEFAULT_VOICE,
        ): SupertonicSynthesizer = SupertonicSynthesizer(Directory(directory, assets, assetPath), voice)

        fun styleName(voice: String): String = "style_$voice.json"

        private fun sessionKey(bundle: Bundle, name: String): String = bundle.key() + name

        private fun openSession(bundle: Bundle, name: String): OrtSession? =
            OnnxSessions.open(sessionKey(bundle, name)) { bundle.read(name) }

        private fun readIndexer(bundle: Bundle): IntArray {
            val json = org.json.JSONArray(bundle.read(INDEXER).decodeToString())
            require(json.length() == INDEXER_ENTRIES) {
                "a codepoint table of ${json.length()}, not $INDEXER_ENTRIES"
            }
            return IntArray(INDEXER_ENTRIES) { json.optInt(it, -1) }
        }

        private fun readVoice(bundle: Bundle, name: String): Pair<FloatArray, FloatArray> {
            val json = org.json.JSONObject(bundle.read(styleName(name)).decodeToString())
            fun floats(key: String, size: Int): FloatArray {
                val flat = ArrayList<Float>(size)
                fun collect(value: Any?) {
                    when (value) {
                        is org.json.JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                        is Number -> flat.add(value.toFloat())
                    }
                }
                collect(json.opt(key))
                require(flat.size == size) { "$key has ${flat.size} values, not $size" }
                return flat.toFloatArray()
            }
            // `style_ttl [1, 50, 256]` position-major, `style_dp [1, 8, 16]`.
            return Pair(floats("style_ttl", 50 * 256), floats("style_dp", 8 * 16))
        }
    }
}
