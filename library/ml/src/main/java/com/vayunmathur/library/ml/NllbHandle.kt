package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * On-device translation between any two of 202 languages: NLLB-200-distilled-600M on the
 * reduced ONNX Runtime build.
 *
 * NLLB distilled to 600M parameters: 12 encoder layers, 12 decoder layers, `d_model` 1024,
 * 16 heads, a 4096-wide ReLU feed-forward - and a 256,206-entry vocabulary shared between
 * the input embedding and the output projection. **Direct** translation, with no English
 * pivot, so every pair is a single hop.
 *
 * # Three files, downloaded rather than bundled
 *
 * `encoder_model_int8.onnx` + `decoder_model_int8.onnx` (`venddair/nllb-200-distilled-600M-onnx`)
 * and `tokenizer.bin` (SPM1, built by `scripts/ml/nllb_tokenizer.py`), which are far too much
 * to ship inside an APK - hence [inDirectory] and no asset path. See `NllbModel` in
 * `:translate` for the mirror pins.
 *
 * The decoder is the no-cache export: every step feeds the whole produced prefix (capped at
 * [MAX_TOKENS] = 128, so the quadratic cost is bounded). This replaces the old Vulkan path's
 * incremental KV decode; the forcing protocol is identical.
 *
 * # Both language tokens are required
 *
 * NLLB puts the **source** language token on the encoder source and forces the **target**
 * language token as the decoder's first token (forced-BOS). Backwards, it produces fluent
 * output in the wrong language rather than an error - which is why [translate] takes flores
 * codes rather than bare ids and resolves both through
 * [com.vayunmathur.translate.platform.NllbModel.tokenId].
 *
 * # Normalisation
 *
 * The model's normaliser is `nmt_nfkc` with a precompiled charsmap, so [translate] applies
 * `java.text.Normalizer` **NFKC** first and the SPM1 codec does the rest (whitespace
 * collapsing + metaspace prefix, greedy highest-score merge, byte fallback).
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when a file is absent or malformed, or
 * when a session will not open - and then [translate] returns null.
 *
 * # Threading
 *
 * Not thread-safe: a translation runs up to 128 decoder steps sharing both sessions, so two
 * concurrent calls would interleave them. A caller must hold a lock across [translate] and
 * [close].
 */
class NllbHandle private constructor(private val directory: File) : AutoCloseable {
    private val lock = Any()

    @Volatile private var encoder: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    @Volatile private var table: SpmTable? = null
    @Volatile private var loadTried = false

    /** True if both graphs came up and the tokenizer table parsed. */
    val isAvailable: Boolean get() = ensure()

    /**
     * Translate [text] from the language [sourceToken] names into the language [targetToken]
     * names, or null on failure.
     *
     * An empty string means there was nothing to translate, which is not a failure - blank
     * input and input the tokenizer maps to nothing both come back empty. Null means the
     * engine is unavailable or the pass failed.
     *
     * Long text should be split into sentences first. Nothing refuses a paragraph, but
     * decoding is capped at 128 tokens and every step attends over the whole source.
     */
    fun translate(text: String, sourceToken: Int, targetToken: Int): String? {
        if (text.isBlank()) return ""
        if (!isNllbLangToken(sourceToken) || !isNllbLangToken(targetToken)) return null
        if (!ensure()) return null
        val enc = encoder ?: return null
        val dec = decoder ?: return null
        val vocab = table ?: return null
        return try {
            val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val body = vocab.encode(normalised)
            if (body.isEmpty()) return ""
            val source = intArrayOf(sourceToken) + body + intArrayOf(EOS)
            val env = OrtEnvironment.getEnvironment()
            val hidden = encodeSource(enc, env, source) ?: return null
            try {
                // The decoder starts from `</s>`, and step 0 is forced to the target language
                // (HuggingFace's `forced_bos_token_id`): the step-0 argmax is discarded, the
                // forced token feeds step 1 but is never emitted.
                val produced = ArrayList<Int>(MAX_TOKENS)
                // The fed prefix starts from `</s>` and grows by each generated token. Step 0
                // is forced to the target language (never emitted); decoding proceeds greedily
                // from there, stopping at the first `</s>`.
                val fed = ArrayList<Int>()
                fed.add(DECODER_START)
                for (step in 0 until MAX_TOKENS) {
                    val logits = decodeStep(dec, env, hidden, source.size, fed.toIntArray())
                        ?: return null
                    val next = if (step == 0) targetToken else argmax(logits) ?: return null
                    if (next == EOS) break
                    fed.add(next)
                    if (step > 0) produced.add(next)
                }
                vocab.decode(produced.toIntArray())
            } finally {
                runCatching { hidden.close() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "nllb translation failed", e)
            null
        }
    }

    /** Free both networks. Idempotent. */
    override fun close() {
        synchronized(lock) {
            encoder = null
            decoder = null
            table = null
            OnnxSessions.close(sessionKey(ENCODER_FILE))
            OnnxSessions.close(sessionKey(DECODER_FILE))
        }
    }

    override fun toString(): String = "NLLB-200 in $directory"

    private fun ensure(): Boolean {
        if (encoder != null && decoder != null && table != null) return true
        synchronized(lock) {
            if (encoder != null && decoder != null && table != null) return true
            if (loadTried) return false
            loadTried = true
            try {
                table = SpmTable.parse(File(directory, TOKENIZER).readBytes())
                encoder = OnnxSessions.open(sessionKey(ENCODER_FILE)) {
                    File(directory, ENCODER_FILE).readBytes()
                }
                decoder = OnnxSessions.open(sessionKey(DECODER_FILE)) {
                    File(directory, DECODER_FILE).readBytes()
                }
                return encoder != null && decoder != null && table != null
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the NLLB model in $directory", e)
                return false
            }
        }
    }

    private fun sessionKey(name: String): String = "file:${File(directory, name).absolutePath}"

    private fun encodeSource(enc: OrtSession, env: OrtEnvironment, source: IntArray): OnnxTensor? {
        val ids = OnnxTensor.createTensor(
            env, LongBuffer.wrap(source.map { it.toLong() }.toLongArray()),
            longArrayOf(1, source.size.toLong()),
        )
        val mask = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(source.size) { 1L }),
            longArrayOf(1, source.size.toLong()),
        )
        ids.useOrt {
            mask.useOrt {
                enc.run(mapOf("input_ids" to ids, "attention_mask" to mask)).useOrt { result ->
                    val out = result.get("last_hidden_state").get() as OnnxTensor
                    val shape = out.info.shape
                    val flat = FloatArray((shape[1] * shape[2]).toInt())
                    out.floatBuffer.get(flat)
                    // Copy into a tensor we own; the result closes its own values.
                    return OnnxTensor.createTensor(
                        env, java.nio.FloatBuffer.wrap(flat), longArrayOf(1, shape[1], shape[2]),
                    )
                }
            }
        }
    }

    private fun decodeStep(
        dec: OrtSession,
        env: OrtEnvironment,
        hidden: OnnxTensor,
        sourceLen: Int,
        prefix: IntArray,
    ): FloatArray? {
        val ids = OnnxTensor.createTensor(
            env, LongBuffer.wrap(prefix.map { it.toLong() }.toLongArray()),
            longArrayOf(1, prefix.size.toLong()),
        )
        val encMask = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(sourceLen) { 1L }),
            longArrayOf(1, sourceLen.toLong()),
        )
        ids.useOrt {
            encMask.useOrt {
                dec.run(
                    mapOf(
                        "input_ids" to ids,
                        "encoder_attention_mask" to encMask,
                        "encoder_hidden_states" to hidden,
                    ),
                ).useOrt { result ->
                    val out = result.get("logits").get() as OnnxTensor
                    val shape = out.info.shape
                    val seq = shape[1].toInt()
                    val vocab = shape[2].toInt()
                    val row = FloatArray(vocab)
                    out.floatBuffer.position((seq - 1) * vocab)
                    out.floatBuffer.get(row)
                    return row
                }
            }
        }
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

    // -- SPM1 codec -----------------------------------------------------------

    /**
     * The flat SPM1 table: BPE pieces with merge-rank scores, byte fallback, fairseq
     * specials. Parses `tokenizer.bin` (magic `SPM1`, u32 count, then per id: i32 score,
     * u16 length, bytes).
     */
    private class SpmTable(
        private val pieces: List<ByteArray>,
        private val scores: IntArray,
        private val byPiece: Map<String, Int>,
        private val byteFallback: IntArray?,
    ) {
        fun encode(normalised: String): IntArray {
            val prepared = buildString(normalised.length + 3) {
                // `remove_extra_whitespaces` + `add_dummy_prefix`: collapse runs, trim, prefix
                // one metaspace.
                val words = normalised.split(Regex("\\s+")).filter { it.isNotEmpty() }
                for (word in words) {
                    append(METASPACE)
                    append(word)
                }
            }
            if (prepared.isEmpty()) return IntArray(0)
            // Symbols as char ranges; a merge joins adjacent ranges. Highest score wins,
            // leftmost on a tie.
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var i = 0
            while (i < prepared.length) {
                val cp = prepared.codePointAt(i)
                starts.add(i)
                ends.add(i + Character.charCount(cp))
                i += Character.charCount(cp)
            }
            while (true) {
                var bestScore = Int.MIN_VALUE
                var bestIndex = -1
                var found = false
                for (index in 0 until starts.size - 1) {
                    val joined = prepared.substring(starts[index], ends[index + 1])
                    val id = byPiece[joined] ?: continue
                    val score = scores[id]
                    if (!found || score > bestScore) {
                        found = true
                        bestScore = score
                        bestIndex = index
                    }
                }
                if (!found) break
                ends[bestIndex] = ends[bestIndex + 1]
                starts.removeAt(bestIndex + 1)
                ends.removeAt(bestIndex + 1)
            }
            val out = ArrayList<Int>(starts.size)
            for (index in starts.indices) {
                val bytes = prepared.substring(starts[index], ends[index]).toByteArray(Charsets.UTF_8)
                val id = byPiece[String(bytes, Charsets.UTF_8)]
                if (id != null) {
                    out.add(id)
                } else if (byteFallback != null) {
                    for (b in bytes) out.add(byteFallback[b.toInt() and 0xFF])
                } else {
                    out.add(UNK)
                }
            }
            return out.toIntArray()
        }

        fun decode(ids: IntArray): String {
            val out = ArrayList<Byte>(ids.size * 4)
            for (id in ids) {
                val piece = pieces.getOrNull(id) ?: continue
                if (piece.size == 1 && piece[0] in 0..255) {
                    // Byte pieces are handled below by range; single-byte pieces that are
                    // actual content bytes pass through.
                }
                for (b in piece) out.add(b)
            }
            // Byte-fallback pieces are `<0xHH>` markers; resolve them back to bytes.
            val bytes = ArrayList<Byte>(out.size)
            var j = 0
            while (j < out.size) {
                val marker = parseByteMarker(out, j)
                if (marker != null) {
                    bytes.add(marker.first)
                    j = marker.second
                } else {
                    bytes.add(out[j])
                    j++
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
                .replace(METASPACE.toString(), " ").trim()
        }

        private fun parseByteMarker(out: List<Byte>, at: Int): Pair<Byte, Int>? {
            // `<0xHH>`: 6 ASCII bytes.
            if (at + 6 > out.size) return null
            if (out[at].toInt().toChar() != '<' || out[at + 1].toInt().toChar() != '0' ||
                out[at + 2].toInt().toChar() != 'x' || out[at + 5].toInt().toChar() != '>'
            ) {
                return null
            }
            val hex = String(byteArrayOf(out[at + 3], out[at + 4]), Charsets.US_ASCII)
            val value = hex.toIntOrNull(16) ?: return null
            return Pair(value.toByte(), at + 6)
        }

        companion object {
            private const val METASPACE = '▁'

            fun parse(bytes: ByteArray): SpmTable? {
                return try {
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    if (buf.int != 0x314D5053) return null // "SPM1"
                    val count = buf.int
                    if (count <= 0 || count > 1_000_000) return null
                    val pieces = ArrayList<ByteArray>(count)
                    val scores = IntArray(count)
                    for (i in 0 until count) {
                        scores[i] = buf.int
                        val length = buf.short.toInt() and 0xFFFF
                        val piece = ByteArray(length)
                        buf.get(piece)
                        pieces.add(piece)
                    }
                    val byPiece = HashMap<String, Int>(count * 2)
                    for (i in pieces.indices) {
                        byPiece[String(pieces[i], Charsets.UTF_8)] = i
                    }
                    // Byte fallback: `<0x00>`..`<0xFF>` pieces, if present.
                    val fallback = IntArray(256) { -1 }
                    var any = false
                    for (b in 0..255) {
                        val id = byPiece["<0x%02X>".format(b)]
                        if (id != null) {
                            fallback[b] = id
                            any = true
                        }
                    }
                    SpmTable(pieces, scores, byPiece, if (any) fallback else null)
                } catch (e: Throwable) {
                    Log.e(TAG, "cannot parse the SPM1 table", e)
                    null
                }
            }
        }
    }

    companion object {
        private const val TAG = "NllbHandle"

        /** Encoder export. */
        const val ENCODER_FILE = "encoder_model_int8.onnx"

        /** Decoder export (no KV cache; feeds the whole prefix each step). */
        const val DECODER_FILE = "decoder_model_int8.onnx"

        /** SPM1 vocabulary. */
        const val TOKENIZER = "tokenizer.bin"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(ENCODER_FILE, DECODER_FILE, TOKENIZER)

        /** Decoder start / EOS / pad / unk, fairseq convention. */
        const val DECODER_START = 2
        const val EOS = 2
        const val UNK = 3

        /** Greedy steps per translation. */
        const val MAX_TOKENS = 128

        /** First NLLB language id; the 202 flores codes follow contiguously. */
        const val FIRST_NLLB_LANG_TOKEN = 256001
        const val NLLB_LANGUAGES = 202

        private fun isNllbLangToken(token: Int): Boolean =
            token in FIRST_NLLB_LANG_TOKEN until FIRST_NLLB_LANG_TOKEN + NLLB_LANGUAGES

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart: at ~1.1 GB int8 this is a runtime download to
         * `getExternalFilesDir`, so there is no APK entry to open.
         */
        fun inDirectory(directory: File): NllbHandle = NllbHandle(directory)
    }
}
