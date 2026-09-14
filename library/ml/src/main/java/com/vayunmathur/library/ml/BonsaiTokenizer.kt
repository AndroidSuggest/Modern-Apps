package com.vayunmathur.library.ml

import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * The Bonsai/Qwen3 chat tokenizer: byte-level BPE from the HuggingFace `tokenizer.json`.
 *
 * Vocabulary is 151,643 BPE entries plus 26 added tokens (`<|im_start|>`, `<|im_end|>`,
 * `<|endoftext|>`, `<tool_call>`/`</tool_call>`, vision/FIM markers) for 151,669 — the model's
 * own width. The pre-tokenizer is the GPT-2 split regex, the normalizer is NFC, and decode is
 * byte-level (byte tokens map back through the byte decoder).
 *
 * The BPE core (greedy lowest-rank merge, byte→unicode map) follows the same shape as
 * `:photos`' `ClipTokenizer`, but nothing is shared: CLIP lower-cases and pads to 77, Qwen
 * neither.
 *
 * `tokenizer.json` (~17 MB) ships with the model download rather than in the APK, and is read
 * from the same directory as the weights.
 */
class BonsaiTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val decoder: Map<Int, ByteArray>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
) {
    private val cache = HashMap<String, List<Int>>()

    /** Token ids for [text], without any special tokens. */
    fun encode(text: String): IntArray {
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        val ids = ArrayList<Int>()
        val matcher = SPLIT_PATTERN.matcher(normalized)
        while (matcher.find()) {
            val piece = matcher.group()
            if (piece.isEmpty()) continue
            ids.addAll(encodePiece(piece))
        }
        return ids.toIntArray()
    }

    /** Text for [ids], skipping special tokens. */
    fun decode(ids: IntArray): String {
        val bytes = ArrayList<Byte>()
        for (id in ids) {
            val chunk = decoder[id] ?: continue
            for (b in chunk) bytes.add(b)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun encodePiece(piece: String): List<Int> {
        cache[piece]?.let { return it }
        val bytes = piece.toByteArray(Charsets.UTF_8)
        // Map bytes to the visible unicode chars the merges were trained on.
        val token = StringBuilder(bytes.size)
        for (b in bytes) token.append(byteEncoder[b.toInt() and 0xFF])
        val out = bpe(token.toString()).split(' ').mapNotNull { encoder[it] }
        cache[piece] = out
        return out
    }

    private fun bpe(token: String): String {
        if (token.length <= 1) return token
        var word = ArrayList<String>(token.length)
        for (i in token.indices) word.add(token[i].toString())
        while (true) {
            var bestRank = Int.MAX_VALUE
            var bestPair: Pair<String, String>? = null
            for (i in 0 until word.size - 1) {
                val pair = word[i] to word[i + 1]
                val rank = bpeRanks[pair] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }
            val (first, second) = bestPair ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }
            word = merged
            if (word.size == 1) break
        }
        return word.joinToString(" ")
    }

    companion object {
        private const val TAG = "BonsaiTokenizer"

        const val TOKENIZER_FILE = "tokenizer.json"

        /** End of text / generation stop. */
        const val EOS = 151645

        /** Turn markers. */
        const val IM_START = 151644
        const val IM_END = 151645

        /** Tool-call markers. */
        const val TOOL_CALL_OPEN = 151657
        const val TOOL_CALL_CLOSE = 151658

        private val SPLIT_PATTERN = java.util.regex.Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
        )

        /** Reversible bytes→unicode table (GPT-2 `bytes_to_unicode`). */
        private val byteEncoder: Array<String> = run {
            val bs = ArrayList<Int>()
            for (i in '!'.code..'~'.code) bs.add(i)
            for (i in '¡'.code..'¬'.code) bs.add(i)
            for (i in '®'.code..'ÿ'.code) bs.add(i)
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            Array(256) { "" }.also { out ->
                for (i in bs.indices) out[bs[i]] = cs[i].toChar().toString()
            }
        }

        /**
         * Load `tokenizer.json` from [directory].
         *
         * Returns null when the file is absent or malformed — the engine then reports
         * unavailable rather than crashing.
         */
        fun inDirectory(directory: File): BonsaiTokenizer? {
            val file = File(directory, TOKENIZER_FILE)
            if (!file.isFile) {
                Log.w(TAG, "$TOKENIZER_FILE is missing from $directory")
                return null
            }
            return try {
                parse(JSONObject(file.readText(Charsets.UTF_8)))
            } catch (e: Throwable) {
                Log.e(TAG, "cannot parse $TOKENIZER_FILE", e)
                null
            }
        }

        private fun parse(json: JSONObject): BonsaiTokenizer? {
            val model = json.optJSONObject("model") ?: return null
            val vocabJson = model.optJSONObject("vocab") ?: return null
            val mergesJson = model.optJSONArray("merges") ?: return null
            val encoder = HashMap<String, Int>(vocabJson.length() * 2)
            val keys = vocabJson.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                encoder[token] = vocabJson.optInt(token)
            }
            val bpeRanks = HashMap<Pair<String, String>, Int>(mergesJson.length() * 2)
            for (i in 0 until mergesJson.length()) {
                val line = mergesJson.optString(i)
                val space = line.indexOf(' ')
                if (space < 0) continue
                // Merges may carry a trailing score; only the pair matters.
                val rest = line.substring(space + 1)
                val space2 = rest.indexOf(' ')
                val pair = if (space2 < 0) {
                    line.substring(0, space) to rest
                } else {
                    line.substring(0, space) to rest.substring(0, space2)
                }
                bpeRanks[pair] = i
            }
            // Added tokens live outside `model.vocab`.
            val added = json.optJSONArray("added_tokens")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val entry = added.optJSONObject(i) ?: continue
                    encoder[entry.optString("content")] = entry.optInt("id")
                }
            }
            // Byte-level decode table: single-byte vocab entries map to their byte.
            val byteDecoder = HashMap<Int, String>()
            for ((token, id) in encoder) {
                if (token.length == 1) byteDecoder[id] = token
            }
            val byteToByte = HashMap<String, Byte>()
            for (i in 0..255) byteToByte[byteEncoder[i]] = i.toByte()
            val decoder = HashMap<Int, ByteArray>(encoder.size)
            for ((token, id) in encoder) {
                val bytes = ArrayList<Byte>(token.length)
                var ok = true
                for (ch in token) {
                    val b = byteToByte[ch.toString()]
                    if (b == null) {
                        ok = false
                        break
                    }
                    bytes.add(b)
                }
                if (ok) decoder[id] = bytes.toByteArray()
            }
            return BonsaiTokenizer(encoder, decoder, bpeRanks)
        }
    }
}
