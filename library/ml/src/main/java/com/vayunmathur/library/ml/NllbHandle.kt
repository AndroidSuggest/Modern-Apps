package com.vayunmathur.library.ml

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * On-device translation between any two of 202 languages: NLLB-200-distilled-600M,
 * ExecuTorch-only (Vulkan-lowered `nllb-enc.pte` + `nllb-dec.pte`).
 *
 * NLLB distilled to 600M parameters: 12 encoder layers, 12 decoder layers, `d_model` 1024,
 * 16 heads, a 4096-wide ReLU feed-forward - and a 256,206-entry vocabulary shared between
 * the input embedding and the output projection. **Direct** translation, with no English
 * pivot, so every pair is a single hop.
 *
 * # Two `.pte` files plus the tokenizer, downloaded rather than bundled
 *
 * `nllb-enc.pte` + `nllb-dec.pte` (Vulkan-lowered encoder/decoder modules mirroring the
 * export split: encoder `input_ids` + `attention_mask` to `[1,S,1024]` hidden, decoder
 * `decoder_input_ids` + `encoder_hidden_states` + `encoder_attention_mask` to logits)
 * and `tokenizer.bin` (SPM1, built by `scripts/ml/nllb_tokenizer.py`), which are far too
 * much to ship inside an APK - hence [inDirectory] and no asset path. See `NllbModel` in
 * `:translate` for the mirror pins.
 *
 * There are no ORT, VulkanSessions, LiteRT or `.onnx`/`.tflite` paths: when the `.pte`
 * pair is absent (or the Vulkan delegate is not linked) the handle is unavailable and
 * [translate] returns null. Fail-closed, never a silent fallback.
 *
 * The decoder is the no-cache export: every step feeds the whole produced prefix (capped
 * at [MAX_TOKENS] = 128, so the quadratic cost is bounded). The forcing protocol
 * (source-token lead, forced-BOS target, EOS stop, [MAX_TOKENS] cap) and the SPM1 codec
 * are shared.
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
 * Construction never throws. [isAvailable] is false when a file is absent or malformed,
 * when the Vulkan delegate is not linked, or when a run fails - and then [translate]
 * returns null.
 *
 * # Threading
 *
 * Not thread-safe: a translation runs up to 128 decoder steps sharing both modules, so
 * two concurrent calls would interleave them. A caller must hold a lock across [translate]
 * and [close].
 */
class NllbHandle private constructor(private val directory: File) : AutoCloseable {
    private val lock = Any()

    @Volatile private var etEnc: Module? = null
    @Volatile private var etDec: Module? = null
    @Volatile private var table: SpmTable? = null
    @Volatile private var etTried = false

    /** True if the ET encoder, ET decoder and tokenizer all came up. */
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
        val vocab = table ?: return null
        return try {
            val normalised = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val body = vocab.encode(normalised)
            if (body.isEmpty()) return ""
            val source = intArrayOf(sourceToken) + body + intArrayOf(EOS)
            val hidden = etEncodeSource(source) ?: return null
            try {
                val produced = etTranslateDecode(hidden, source.size, targetToken) ?: return null
                vocab.decode(produced)
            } finally {
                hidden.closeQuietly()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "nllb translation failed", e)
            null
        }
    }

    /** Free both modules. Idempotent. */
    override fun close() {
        synchronized(lock) {
            etEnc = null
            etDec = null
            ExecutorchSessions.close(etSessionKey(ET_ENCODER_FILE))
            ExecutorchSessions.close(etSessionKey(ET_DECODER_FILE))
            table = null
        }
    }

    override fun toString(): String = "NLLB-200 in $directory"

    private fun ensure(): Boolean {
        if (table != null && etEnc != null && etDec != null) return true
        synchronized(lock) {
            if (table != null && etEnc != null && etDec != null) return true
            try {
                if (table == null) {
                    table = SpmTable.parse(File(directory, TOKENIZER).readBytes())
                }
            } catch (e: Throwable) {
                Log.e(TAG, "cannot open the NLLB model in $directory", e)
                return false
            }
            if (table == null) return false
            if (!etTried) {
                etTried = true
                tryEt(etSessionKey(ET_ENCODER_FILE), File(directory, ET_ENCODER_FILE).absolutePath) {
                    etEnc = it
                }
                tryEt(etSessionKey(ET_DECODER_FILE), File(directory, ET_DECODER_FILE).absolutePath) {
                    etDec = it
                }
            }
            return table != null && etEnc != null && etDec != null
        }
    }

    private fun etSessionKey(name: String): String = "file:${File(directory, name).absolutePath}"

    /** Best-effort ET open for one graph; absent means unavailable, never a fallback. */
    private fun tryEt(key: String, path: String, assign: (Module) -> Unit) {
        try {
            val file = File(path)
            if (!file.isFile) return
            val module = ExecutorchSessions.openPath(path) ?: return
            assign(module)
            Log.i(TAG, "executorch session open for $key")
        } catch (e: Throwable) {
            Log.w(TAG, "executorch open failed for $key", e)
        }
    }

    // -- Encoder outputs ------------------------------------------------------

    /** Encoder hidden states materialised on the host so the decoder can consume them. */
    private class EncHidden(val data: FloatArray, val seq: Int, val dim: Int) {
        fun closeQuietly() {
            // Plain floats; nothing native held. Kept for symmetry with the module path.
        }
    }

    // -- ExecuTorch split encode/decode -----------------------------------------

    /**
     * ET encoder run over the split `nllb-enc.pte`, materialised as floats so the decoder
     * can consume them. Null when the module is absent or the run fails.
     */
    private fun etEncodeSource(source: IntArray): EncHidden? {
        val mod = etEnc ?: return null
        return try {
            val ids = EValue.from(Tensor.fromBlob(source.map { it.toLong() }.toLongArray(), longArrayOf(1, source.size.toLong())))
            val mask = EValue.from(
                Tensor.fromBlob(LongArray(source.size) { 1L }, longArrayOf(1, source.size.toLong())),
            )
            val outs = ExecutorchSessions.run(mod, listOf(ids, mask), ET_ENCODE_METHOD) ?: return null
            val value = outs.firstOrNull() ?: return null
            if (!value.isTensor) return null
            val outShape = value.toTensor().shape()
            if (outShape.size != 3 || outShape[0] != 1L) return null
            val want = (outShape[1] * outShape[2]).toInt()
            val flat = value.floatsAllowingHalf("$TAG encode", want) ?: return null
            EncHidden(flat.copyOf(want), outShape[1].toInt(), outShape[2].toInt())
        } catch (e: Throwable) {
            Log.w(TAG, "nllb ET encode failed", e)
            null
        }
    }

    /**
     * ET whole-prefix decode over the split `nllb-dec.pte`: step 0 is forced to the target
     * language (never emitted), decoding proceeds greedily from there, stopping at the
     * first `</s>`. Null when ET cannot serve the turn.
     */
    private fun etTranslateDecode(
        hidden: EncHidden,
        sourceLen: Int,
        targetToken: Int,
    ): IntArray? {
        val mod = etDec ?: return null
        return try {
            val produced = ArrayList<Int>(MAX_TOKENS)
            val fed = ArrayList<Int>()
            fed.add(DECODER_START)
            for (step in 0 until MAX_TOKENS) {
                val prefix = fed.toIntArray()
                val row = etDecodeStep(mod, hidden, sourceLen, prefix) ?: return null
                val next = if (step == 0) targetToken else argmax(row) ?: return null
                if (next == EOS) break
                fed.add(next)
                if (step > 0) produced.add(next)
            }
            produced.toIntArray()
        } catch (e: Throwable) {
            Log.w(TAG, "nllb ET decode failed", e)
            null
        }
    }

    /** One ET decoder step over the prefix [fed]: the last row's logits, or null. */
    private fun etDecodeStep(
        mod: Module,
        hidden: EncHidden,
        sourceLen: Int,
        fed: IntArray,
    ): FloatArray? {
        return try {
            val ids = EValue.from(
                Tensor.fromBlob(fed.map { it.toLong() }.toLongArray(), longArrayOf(1, fed.size.toLong())),
            )
            val states = EValue.from(
                Tensor.fromBlob(hidden.data, longArrayOf(1, hidden.seq.toLong(), hidden.dim.toLong())),
            )
            val mask = EValue.from(
                Tensor.fromBlob(LongArray(sourceLen) { 1L }, longArrayOf(1, sourceLen.toLong())),
            )
            val outs = ExecutorchSessions.run(mod, listOf(ids, states, mask), ET_DECODE_METHOD)
                ?: return null
            val value = outs.firstOrNull() ?: return null
            if (!value.isTensor) return null
            val outShape = value.toTensor().shape()
            if (outShape.size != 3 || outShape[0] != 1L) return null
            val seq = outShape[1].toInt()
            val vocab = outShape[2].toInt()
            if (seq <= 0 || vocab <= 0) return null
            val flat = value.floatsAllowingHalf("$TAG decode", seq * vocab) ?: return null
            flat.copyOfRange((seq - 1) * vocab, seq * vocab)
        } catch (e: Throwable) {
            Log.w(TAG, "nllb ET decode step failed", e)
            null
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

        /**
         * ET encoder export (Vulkan-lowered split). Resolved from the download directory;
         * absent means unavailable, never a fallback.
         */
        const val ET_ENCODER_FILE = "nllb-enc.pte"

        /** ET decoder export (Vulkan-lowered split). Same rule. */
        const val ET_DECODER_FILE = "nllb-dec.pte"

        /** SPM1 vocabulary. */
        const val TOKENIZER = "tokenizer.bin"

        /** The files [inDirectory] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(ET_ENCODER_FILE, ET_DECODER_FILE, TOKENIZER)

        /** Decoder start / EOS / pad / unk, fairseq convention. */
        const val DECODER_START = 2
        const val EOS = 2
        const val UNK = 3

        /** Greedy steps per translation. */
        const val MAX_TOKENS = 128

        /** ET encoder method over the split module. */
        private const val ET_ENCODE_METHOD = "forward"

        /** ET decoder method over the split module. */
        private const val ET_DECODE_METHOD = "forward"

        /** First NLLB language id; the 202 flores codes follow contiguously. */
        const val FIRST_NLLB_LANG_TOKEN = 256001
        const val NLLB_LANGUAGES = 202

        private fun isNllbLangToken(token: Int): Boolean =
            token in FIRST_NLLB_LANG_TOKEN until FIRST_NLLB_LANG_TOKEN + NLLB_LANGUAGES

        /**
         * The model in a folder on disk, which is the only place it lives.
         *
         * No `inAssets` counterpart: far too much to ship inside an APK, so this is a
         * runtime download to `getExternalFilesDir` and there is no APK entry to open.
         */
        fun inDirectory(directory: File): NllbHandle = NllbHandle(directory)
    }
}
