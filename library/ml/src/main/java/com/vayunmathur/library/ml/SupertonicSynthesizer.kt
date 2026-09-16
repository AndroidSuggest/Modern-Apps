package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import com.google.ai.edge.litert.CompiledModel
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * On-device text-to-speech: Supertonic 3, ExecuTorch-first with a LiteRT fallback.
 *
 * One bundle covers 31 languages and 10 voices. All four networks are the ladder ship
 * rungs (`Reza2kn/supertonic-3-litert`, plus the converted flow-matching estimator),
 * driven directly:
 *
 * - **Duration predictor** (`duration_w4.tflite`, cos 1.0): `text_ids [1,320]` int64 +
 *   `style_dp [1,8,16]` + `text_mask [1,1,320]` → one number, the utterance's length
 *   in seconds, which fixes every later shape.
 * - **Text encoder** (`textenc_w8.tflite`, cos 0.9947): `text_ids` + `style_ttl
 *   [1,50,256]` + mask → `text_emb [1,256,320]` conditioning.
 * - **Flow-matching sampler** (`estimator_w8.tflite`, cos 0.9969): the expensive one.
 *   16 steps; the export bakes classifier-free guidance internally (batch-2 branches),
 *   so there is no host-side guidance combination, only the Euler advance
 *   `latent += denoised / 16`. Note the TFLite layout is **transposed** vs the ONNX
 *   export: `noisy_latent [1,320,144]` in, `denoised_latent [1,144,320]` out — the
 *   handle transposes at the boundary and keeps `[144,F]` everywhere else.
 * - **ConvNeXt vocoder** (`vocoder_w8.tflite`, cos 0.9963): latent `[1,144,320]` →
 *   983,040 samples at 44,100 Hz (~22 s max).
 *
 * # ExecuTorch structure, LiteRT default
 *
 * Each network has an ExecuTorch twin that the per-net `run*` wrappers try first and
 * silently skip when unavailable. The twins staged so far are the XNNPACK fp32 set
 * (`st-<net>-xnnpack-fp32.pte`, same fixed 320 shapes, ONNX `[1,channels,length]`
 * layouts — no host transpose on the ET path); per the lead Vulkan-only directive that
 * set is history-only and the ship ladder for this structure will be the Vulkan
 * re-pass (task 23), at which point [ET_GRAPHS] flips to the new names. The fallback
 * default is deliberate either way: until `.pte`s resolve from the download directory,
 * every utterance runs the ladder above, exactly as before. Pocket-TTS was evaluated
 * as the alternate ET fallback and rejected — a 667 MB English-only bundle against a
 * 31-language ship contract (see `analysis/et-supertonic/POCKET_TTS_EVAL.md`).
 *
 * The exports pin **320** as the text/latent length: utterances longer than 320 codepoint
 * tokens are refused (empty array), matching the fixed-shape contract.
 *
 * # There is no phonemiser
 *
 * The front end is a flat 65,536-entry codepoint table (`unicode_indexer.json`), not
 * espeak-ng, so nothing has to be generated per language on the build machine. The model
 * does expect decomposed text, which [synthesize] does itself through
 * `java.text.Normalizer`: precomposed accents are unmapped while combining marks are
 * first-class tokens, so skipping it would quietly drop characters. Emoji and the listed
 * symbols are dropped, `@`/`e.g.`/`i.e.` expanded, and spacing collapsed.
 *
 * # Two places the bundle can live
 *
 * [inAssets] reads it out of the APK and [inDirectory] out of a folder on disk. The four
 * networks total ~110 MB, so both go through the shared [LiteRtSessions] cache rather
 * than being copied per handle. An asset must be stored **uncompressed**
 * (`noCompress += "tflite"`), since quantized weights barely deflate.
 *
 * # Voices
 *
 * A voice is two small style tensors parsed from `voice_styles/<name>.json`, not a model, so
 * [voice] switches without re-opening anything.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when neither backend came up — no
 * LiteRT bundle and not all four ET nets — and then [synthesize] returns an empty array.
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
    @Volatile private var etModules: EtModules? = null
    @Volatile private var indexer: IntArray? = null
    @Volatile private var styleTtl: FloatArray? = null
    @Volatile private var styleDp: FloatArray? = null
    @Volatile private var loadTried = false
    @Volatile private var etTried = false
    @Volatile private var etKeys: List<String> = emptyList()
    @Volatile private var voiceName: String = voice

    /** Supertonic's output rate, fixed by the vocoder rather than by the voice. */
    val sampleRate: Int = SAMPLE_RATE

    /** True if the LiteRT bundle or all four ET nets came up, the codepoint table parsed and a voice read. */
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
     * unavailable, when the text exceeds [MAX_TOKENS] codepoint tokens, or when the pass
     * failed — all are "no audio" to a caller.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph below the
     * token cap, but every shape here scales with the utterance and the sampler runs 16
     * passes over all of it.
     *
     * Two calls with the same text differ, as flow matching starts from a sampled latent.
     */
    fun synthesize(text: String, language: String): FloatArray {
        if (text.isBlank()) return FloatArray(0)
        if (!ensure()) return FloatArray(0)
        val live = sessions
        val et = etModules
        if (live == null && et == null) return FloatArray(0)
        val table = indexer ?: return FloatArray(0)
        val ttl = styleTtl ?: return FloatArray(0)
        val dp = styleDp ?: return FloatArray(0)
        return try {
            val ids = toIds(table, normalise(text), language) ?: return FloatArray(0)
            if (ids.size > MAX_TOKENS) {
                Log.w(TAG, "utterance of ${ids.size} tokens exceeds $MAX_TOKENS")
                return FloatArray(0)
            }
            // Duration: seconds -> latent frames.
            val logSeconds = runDuration(live?.duration, et, ids, dp)
            val frames = max(1, ceil(exp(logSeconds) / SPEED * SAMPLE_RATE / SAMPLES_PER_FRAME).toInt())
            if (frames > MAX_TOKENS) {
                Log.w(TAG, "utterance of $frames frames exceeds $MAX_TOKENS")
                return FloatArray(0)
            }
            // Text conditioning.
            val textEmb = runText(live?.text, et, ids, ttl) ?: return FloatArray(0)
            // Flow-matching sampler: 16 Euler steps from a sampled latent.
            // Speech is meant to vary between calls, so the seed comes from the clock and the
            // normals are Box-Muller over the default PRNG — nothing here is a secret.
            val rng = java.util.Random(System.nanoTime())
            var latent = FloatArray(LATENT_CHANNELS * frames) { gaussian(rng) }
            for (step in 0 until STEPS) {
                val denoised = runSampler(
                    live?.sampler, et, latent, frames, textEmb, ids.size, ttl, step,
                ) ?: return FloatArray(0)
                val scale = 1f / STEPS
                for (i in latent.indices) latent[i] += denoised[i] * scale
            }
            // Vocoder.
            runVocoder(live?.vocoder, et, latent, frames) ?: FloatArray(0)
        } catch (e: Throwable) {
            Log.e(TAG, "supertonic synthesis failed", e)
            FloatArray(0)
        }
    }

    /** Free all four sessions on both backends. Idempotent. */
    override fun close() {
        synchronized(lock) {
            sessions = null
            for (name in GRAPHS) LiteRtSessions.close(sessionKey(bundle, name))
            etModules = null
            for (key in etKeys) ExecutorchSessions.close(key)
            etKeys = emptyList()
        }
    }

    private fun ensure(): Boolean {
        if (isReady()) return true
        synchronized(lock) {
            if (isReady()) return true
            if (!loadTried) {
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
                    } else {
                        sessions = opened
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "cannot open the Supertonic bundle in $bundle", e)
                }
            }
            if (!etTried) {
                etTried = true
                ensureEt()
            }
            // Voices are tensors, not models: one read serves both backends, and it must
            // happen even when only the ET nets came up.
            if (styleTtl == null && (sessions != null || etModules != null)) {
                try {
                    val (ttl, dp) = readVoice(bundle, voiceName)
                    styleTtl = ttl
                    styleDp = dp
                } catch (e: Throwable) {
                    Log.e(TAG, "cannot read the voice $voiceName in $bundle", e)
                }
            }
            return isReady()
        }
    }

    private fun isReady(): Boolean =
        (sessions != null || etModules != null) &&
            indexer != null && styleTtl != null

    /**
     * The ExecuTorch twins, loaded once. Null when a `.pte` is missing (the normal case
     * until the Vulkan ladder lands under task 23), Vulkan is not linked, or a load
     * failed — all of which mean "stay on LiteRT", never a throw. Must be called with
     * [lock] held.
     */
    private fun ensureEt() {
        if (etModules != null) return
        val files = ET_GRAPHS.map { bundle.etFile(it) }
        if (files.any { it == null }) return
        val backends = ExecutorchSessions.registeredBackends()
        if (backends?.any { it.contains("Vulkan", ignoreCase = true) } != true) {
            Log.i(TAG, "Vulkan backend absent, skipping Supertonic ET nets")
            return
        }
        try {
            val paths = files.map { it!!.absolutePath }
            val modules = paths.map { ExecutorchSessions.openPath(it) }
            if (modules.any { it == null }) {
                paths.forEach { ExecutorchSessions.close("file:$it") }
                return
            }
            etModules = EtModules(
                duration = modules[0]!!,
                text = modules[1]!!,
                sampler = modules[2]!!,
                vocoder = modules[3]!!,
            )
            etKeys = paths.map { "file:$it" }
            Log.i(TAG, "Supertonic ET nets open, preferring ExecuTorch")
        } catch (e: Throwable) {
            Log.w(TAG, "cannot open the Supertonic ET nets, staying on LiteRT", e)
        }
    }

    // -- Sessions -------------------------------------------------------------

    private class Sessions(
        val duration: CompiledModel?,
        val text: CompiledModel?,
        val sampler: CompiledModel?,
        val vocoder: CompiledModel?,
    ) {
        val isReady: Boolean get() =
            duration != null && text != null && sampler != null && vocoder != null

        fun close(bundle: Bundle) {
            for (name in GRAPHS) LiteRtSessions.close(sessionKey(bundle, name))
        }
    }

    /**
     * The four ExecuTorch twins, all-or-nothing: [etModules] only exists when every net
     * opened, so the per-net wrappers branch on it without a second check. Keys are kept
     * for [close] because the files may move after opening.
     */
    private class EtModules(
        val duration: Module,
        val text: Module,
        val sampler: Module,
        val vocoder: Module,
    )

    // -- Bundle ---------------------------------------------------------------

    /**
     * Where a bundle's files are, abstracted over the APK and the filesystem.
     *
     * Sessions are opened through the shared [LiteRtSessions] cache, so unlike the old
     * descriptor-passing shape this only needs byte reads for small files; models open
     * from assets or files directly.
     */
    internal interface Bundle {
        /** All of [name], for small files (indexer, voices). */
        fun read(name: String): ByteArray

        /** Open the model [name] through the session cache. */
        fun openModel(name: String, key: String): CompiledModel?

        /**
         * The `.pte` twin [name] as a file for [ExecutorchSessions.openPath], or null
         * when this bundle cannot serve one (APK assets carry no 400 MB of `.pte`s, and
         * staging them needs a `Context` this handle does not keep).
         */
        fun etFile(name: String): File?

        /** Stable key prefix for this bundle's cached sessions. */
        fun key(): String
    }

    private class Assets(private val assets: AssetManager, private val path: String) : Bundle {
        override fun read(name: String): ByteArray {
            // Voice styles ship in the APK under `voices/`; plans + indexer at the top.
            val full = if (name.startsWith("style_")) "$path/voices/$name" else "$path/$name"
            return assets.open(full).use { it.readBytes() }
        }

        override fun openModel(name: String, key: String): CompiledModel? =
            LiteRtSessions.openAssetManager(assets, assetPath(name))

        override fun etFile(name: String): File? = null

        override fun key(): String = "asset:$path/"

        override fun toString(): String = "the APK's $path/"

        private fun assetPath(name: String): String {
            val full = if (name.startsWith("style_")) "$path/voices/$name" else "$path/$name"
            return full
        }
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

        override fun openModel(name: String, key: String): CompiledModel? {
            if (name.startsWith("style_")) return null
            val file = File(directory, name)
            return if (file.isFile) LiteRtSessions.openFile(file.absolutePath) else null
        }

        override fun etFile(name: String): File? {
            if (name !in ET_GRAPHS) return null
            val file = File(directory, name)
            return if (file.isFile) file else null
        }

        override fun key(): String = "file:${directory.absolutePath}/"

        override fun toString(): String = directory.toString()
    }

    // -- Model runs -----------------------------------------------------------

    private fun runDuration(
        session: CompiledModel?,
        et: EtModules?,
        ids: IntArray,
        styleDp: FloatArray,
    ): Float {
        et?.duration?.let { mod ->
            etRunDuration(mod, ids, styleDp)?.let { return it }
        }
        requireNotNull(session) { "the duration predictor is not open" }
        // The export holds its sentence token internally; the raw ids go in as-is.
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val mask = FloatArray(ids.size) { 1f }
        // Fixed-shape export pads to MAX_TOKENS.
        val paddedIds = LongArray(MAX_TOKENS)
        longIds.copyInto(paddedIds)
        val paddedMask = FloatArray(MAX_TOKENS)
        mask.copyInto(paddedMask)
        val outs = synchronized(lock) {
            LiteRtSessions.run(
                session,
                listOf(paddedIds, styleDp, paddedMask),
                listOf("f32"),
            )
        } ?: error("duration run failed")
        return (outs[0] as FloatArray)[0]
    }

    /**
     * The ET duration twin: ids int64 `[1,320]`, style f32 `[1,8,16]`, mask f32
     * `[1,1,320]` → one log-seconds scalar. Null (never throws) so the caller keeps
     * the LiteRT fallback.
     */
    private fun etRunDuration(mod: Module, ids: IntArray, styleDp: FloatArray): Float? {
        val paddedIds = LongArray(MAX_TOKENS) { if (it < ids.size) ids[it].toLong() else 0L }
        val paddedMask = FloatArray(MAX_TOKENS) { if (it < ids.size) 1f else 0f }
        val out = synchronized(lock) {
            etFloatOut(
                mod,
                listOf(
                    EValue.from(Tensor.fromBlob(paddedIds, longArrayOf(1, MAX_TOKENS.toLong()))),
                    EValue.from(Tensor.fromBlob(styleDp, longArrayOf(1, 8, 16))),
                    EValue.from(Tensor.fromBlob(paddedMask, longArrayOf(1, 1, MAX_TOKENS.toLong()))),
                ),
            )
        } ?: return null
        if (out.isEmpty()) return null
        return out[0]
    }

    private fun runText(
        session: CompiledModel?,
        et: EtModules?,
        ids: IntArray,
        styleTtl: FloatArray,
    ): FloatArray? {
        // The ET twin keeps the ONNX [1,256,320] layout, so the same channel slice
        // below applies — no transpose either way on this net.
        et?.text?.let { mod ->
            etRunText(mod, ids, styleTtl)?.let { return it }
        }
        requireNotNull(session) { "the text encoder is not open" }
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val mask = FloatArray(ids.size) { 1f }
        val paddedIds = LongArray(MAX_TOKENS)
        longIds.copyInto(paddedIds)
        val paddedMask = FloatArray(MAX_TOKENS)
        mask.copyInto(paddedMask)
        val outs = synchronized(lock) {
            LiteRtSessions.run(
                session,
                listOf(paddedIds, styleTtl, paddedMask),
                listOf("f32"),
            )
        } ?: return null
        val flat = outs[0] as? FloatArray ?: return null
        return sliceChannels(flat, ids.size)
    }

    /** The ET text-encoder twin: ids `[1,320]` int64 + style `[1,50,256]` + mask `[1,1,320]` → `[1,256,320]`. */
    private fun etRunText(mod: Module, ids: IntArray, styleTtl: FloatArray): FloatArray? {
        val paddedIds = LongArray(MAX_TOKENS) { if (it < ids.size) ids[it].toLong() else 0L }
        val paddedMask = FloatArray(MAX_TOKENS) { if (it < ids.size) 1f else 0f }
        val flat = synchronized(lock) {
            etFloatOut(
                mod,
                listOf(
                    EValue.from(Tensor.fromBlob(paddedIds, longArrayOf(1, MAX_TOKENS.toLong()))),
                    EValue.from(Tensor.fromBlob(styleTtl, longArrayOf(1, 50, 256))),
                    EValue.from(Tensor.fromBlob(paddedMask, longArrayOf(1, 1, MAX_TOKENS.toLong()))),
                ),
            )
        } ?: return null
        if (flat.size < 256 * MAX_TOKENS) {
            Log.w(TAG, "supertonic ET text encoder produced ${flat.size} floats")
            return null
        }
        // Slice the [256, 320] conditioning down to the real char count.
        return sliceChannels(flat, ids.size)
    }

    /** Slice a channel-major `[256,320]` conditioning down to [chars] columns. */
    private fun sliceChannels(flat: FloatArray, chars: Int): FloatArray {
        val out = FloatArray(256 * chars)
        for (c in 0 until 256) {
            flat.copyInto(out, c * chars, c * MAX_TOKENS, c * MAX_TOKENS + chars)
        }
        return out
    }

    private fun runSampler(
        session: CompiledModel?,
        et: EtModules?,
        latent: FloatArray,
        frames: Int,
        textEmb: FloatArray,
        chars: Int,
        styleTtl: FloatArray,
        step: Int,
    ): FloatArray? {
        // The ET twin keeps the ONNX [1,channels,length] layout: no host transpose, and
        // the output slices to [144,F] the same way.
        et?.sampler?.let { mod ->
            etRunSampler(mod, latent, frames, textEmb, chars, styleTtl, step)?.let { return it }
        }
        requireNotNull(session) { "the sampler is not open" }
        // Pad latent [144,F] -> [144,320], then transpose to the TFLite [320,144].
        val padded = FloatArray(LATENT_CHANNELS * MAX_TOKENS)
        for (c in 0 until LATENT_CHANNELS) {
            latent.copyInto(padded, c * MAX_TOKENS, c * frames, c * frames + frames)
        }
        val noisy = FloatArray(LATENT_CHANNELS * MAX_TOKENS)
        for (r in 0 until MAX_TOKENS) {
            for (c in 0 until LATENT_CHANNELS) {
                noisy[r * LATENT_CHANNELS + c] = padded[c * MAX_TOKENS + r]
            }
        }
        // Pad text_emb [256,C] -> [256,320], then transpose to [320,256].
        val paddedText = FloatArray(256 * MAX_TOKENS)
        for (c in 0 until 256) {
            textEmb.copyInto(paddedText, c * MAX_TOKENS, c * chars, c * chars + chars)
        }
        val textT = FloatArray(256 * MAX_TOKENS)
        for (r in 0 until MAX_TOKENS) {
            for (c in 0 until 256) {
                textT[r * 256 + c] = paddedText[c * MAX_TOKENS + r]
            }
        }
        // Masks: latent real frames, text real chars; style/steps scalar-ish.
        val latentMask = FloatArray(MAX_TOKENS * 1)
        for (i in 0 until frames) latentMask[i] = 1f
        val textMask = FloatArray(MAX_TOKENS * 1)
        for (i in 0 until chars) textMask[i] = 1f
        val outs = synchronized(lock) {
            LiteRtSessions.runSignature(
                session,
                mapOf(
                    "noisy_latent" to noisy,
                    "text_emb" to textT,
                    "style_ttl" to styleTtl,
                    "latent_mask" to latentMask,
                    "text_mask" to textMask,
                    "current_step" to floatArrayOf(step.toFloat()),
                    "total_step" to floatArrayOf(STEPS.toFloat()),
                ),
                listOf("denoised_latent"),
            )
        } ?: return null
        // Denoised comes back [144,320] (transposed); slice to [144,F] and un-transpose.
        val flat = outs["denoised_latent"] as? FloatArray ?: return null
        return sliceFrames(flat, frames)
    }

    /**
     * One ET Euler step: noisy `[1,144,320]` + text `[1,256,320]` + style `[1,50,256]` +
     * latent/text masks `[1,1,320]` + step scalars → denoised `[1,144,320]`, sliced to
     * `[144,F]`. The 16-step loop stays host-side in [synthesize].
     */
    private fun etRunSampler(
        mod: Module,
        latent: FloatArray,
        frames: Int,
        textEmb: FloatArray,
        chars: Int,
        styleTtl: FloatArray,
        step: Int,
    ): FloatArray? {
        // Pad latent [144,F] -> [144,320]; no transpose on the ET path.
        val padded = FloatArray(LATENT_CHANNELS * MAX_TOKENS)
        for (c in 0 until LATENT_CHANNELS) {
            latent.copyInto(padded, c * MAX_TOKENS, c * frames, c * frames + frames)
        }
        // Pad text_emb [256,C] -> [256,320]; no transpose on the ET path.
        val paddedText = FloatArray(256 * MAX_TOKENS)
        for (c in 0 until 256) {
            textEmb.copyInto(paddedText, c * MAX_TOKENS, c * chars, c * chars + chars)
        }
        val latentMask = FloatArray(MAX_TOKENS) { if (it < frames) 1f else 0f }
        val textMask = FloatArray(MAX_TOKENS) { if (it < chars) 1f else 0f }
        val flat = synchronized(lock) {
            etFloatOut(
                mod,
                listOf(
                    EValue.from(
                        Tensor.fromBlob(padded, longArrayOf(1, LATENT_CHANNELS.toLong(), MAX_TOKENS.toLong())),
                    ),
                    EValue.from(
                        Tensor.fromBlob(paddedText, longArrayOf(1, 256, MAX_TOKENS.toLong())),
                    ),
                    EValue.from(Tensor.fromBlob(styleTtl, longArrayOf(1, 50, 256))),
                    EValue.from(Tensor.fromBlob(latentMask, longArrayOf(1, 1, MAX_TOKENS.toLong()))),
                    EValue.from(Tensor.fromBlob(textMask, longArrayOf(1, 1, MAX_TOKENS.toLong()))),
                    EValue.from(Tensor.fromBlob(floatArrayOf(step.toFloat()), longArrayOf(1))),
                    EValue.from(Tensor.fromBlob(floatArrayOf(STEPS.toFloat()), longArrayOf(1))),
                ),
            )
        } ?: return null
        if (flat.size < LATENT_CHANNELS * MAX_TOKENS) {
            Log.w(TAG, "supertonic ET sampler produced ${flat.size} floats")
            return null
        }
        return sliceFrames(flat, frames)
    }

    /** Slice a channel-major `[144,320]` latent down to [frames] columns. */
    private fun sliceFrames(flat: FloatArray, frames: Int): FloatArray {
        val out = FloatArray(LATENT_CHANNELS * frames)
        for (c in 0 until LATENT_CHANNELS) {
            for (r in 0 until frames) {
                out[c * frames + r] = flat[c * MAX_TOKENS + r]
            }
        }
        return out
    }

    private fun runVocoder(
        session: CompiledModel?,
        et: EtModules?,
        latent: FloatArray,
        frames: Int,
    ): FloatArray? {
        et?.vocoder?.let { mod ->
            etRunVocoder(mod, latent, frames)?.let { return it }
        }
        requireNotNull(session) { "the vocoder is not open" }
        // Pad [144,F] -> [144,320].
        val padded = FloatArray(LATENT_CHANNELS * MAX_TOKENS)
        for (c in 0 until LATENT_CHANNELS) {
            latent.copyInto(padded, c * MAX_TOKENS, c * frames, c * frames + frames)
        }
        val outs = synchronized(lock) {
            LiteRtSessions.run(
                session,
                listOf(padded),
                listOf("f32"),
            )
        } ?: return null
        val flat = outs[0] as? FloatArray ?: return null
        return flat.copyOf(frames * SAMPLES_PER_FRAME)
    }

    /** The ET vocoder twin: latent `[1,144,320]` → `[1,983040]` waveform, sliced to `frames * 3072`. */
    private fun etRunVocoder(mod: Module, latent: FloatArray, frames: Int): FloatArray? {
        // Pad [144,F] -> [144,320], same as the LiteRT path.
        val padded = FloatArray(LATENT_CHANNELS * MAX_TOKENS)
        for (c in 0 until LATENT_CHANNELS) {
            latent.copyInto(padded, c * MAX_TOKENS, c * frames, c * frames + frames)
        }
        val flat = synchronized(lock) {
            etFloatOut(
                mod,
                listOf(
                    EValue.from(
                        Tensor.fromBlob(padded, longArrayOf(1, LATENT_CHANNELS.toLong(), MAX_TOKENS.toLong())),
                    ),
                ),
            )
        } ?: return null
        if (flat.size < frames * SAMPLES_PER_FRAME) {
            Log.w(TAG, "supertonic ET vocoder produced ${flat.size} floats")
            return null
        }
        return flat.copyOf(frames * SAMPLES_PER_FRAME)
    }

    /**
     * One multi-input ET `forward` invocation, first float output out.
     *
     * `ExecutorchSessions.runFloat` only covers the single-input case; every Supertonic net
     * takes several tensors (and int64 ids), so this wraps one [Tensor] per input in an
     * [EValue] and goes through [ExecutorchSessions.run] directly. Reads HALF-tolerant via
     * [floatsAllowingHalf] (Vulkan fp16 ladders lower outputs to half). Null (never throws)
     * so the caller can fall back to the `.tflite` rung.
     */
    private fun etFloatOut(mod: Module, inputs: List<EValue>): FloatArray? {
        return try {
            val outputs = ExecutorchSessions.run(mod, inputs) ?: return null
            val first = outputs.firstOrNull() ?: return null
            first.floatsAllowingHalf(TAG, 1)
        } catch (e: Throwable) {
            Log.w(TAG, "supertonic ET run failed, falling back to LiteRT", e)
            null
        }
    }

    // -- Front end (unchanged: codepoints, voices, sampling) --------------------

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

        /** Fixed text/latent length the TFLite exports pin. */
        const val MAX_TOKENS = 320

        /** The voice used when a caller does not name one. */
        const val DEFAULT_VOICE = "F1"

        /** Where [inAssets] looks unless told otherwise. */
        const val ASSET_PATH = "supertonic"

        /**
         * The four exports, in pipeline order (ladder ship rungs).
         */
        val GRAPHS = listOf(
            "duration_w4.tflite",
            "textenc_w8.tflite",
            "estimator_w8.tflite",
            "vocoder_w8.tflite",
        )

        /**
         * The four ExecuTorch twins, in pipeline order — the task-23 Vulkan fp16
         * ship ladder (`export_vulkan.py`; ~195 MB total vs ~110 MB ship `.tflite`).
         * Waveform gate PASS both rungs (cos 0.999997); int8 names are
         * `st-<net>-vulkan-int8.pte` if fp16 diverges on-device. Resolution is
         * presence-based, so absent files simply keep the LiteRT ladder serving.
         */
        val ET_GRAPHS = listOf(
            "st-duration-vulkan-fp16.pte",
            "st-textenc-vulkan-fp16.pte",
            "st-estimator-vulkan-fp16.pte",
            "st-vocoder-vulkan-fp16.pte",
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

        private fun openSession(bundle: Bundle, name: String): CompiledModel? =
            bundle.openModel(name, sessionKey(bundle, name))

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
                        is org.json.JSONObject -> {
                            // Upstream voice_styles wrap tensors as
                            // {"data": [...], "dims": [...], "type": ...}.
                            if (value.has("data")) collect(value.opt("data"))
                            else for (k in value.keys()) collect(value.opt(k))
                        }
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
