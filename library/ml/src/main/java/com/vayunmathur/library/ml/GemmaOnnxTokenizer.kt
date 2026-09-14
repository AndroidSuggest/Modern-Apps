package com.vayunmathur.library.ml

import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * The Gemma 4 chat tokenizer: SentencePiece-style byte-level BPE from the HuggingFace
 * `tokenizer.json`.
 *
 * Vocabulary is 262,144 BPE entries plus 24 added tokens (`<bos>` 2, `<eos>` 1, turn/tool
 * markers, image/audio brackets) — the model's own 262,144 width. Normalization replaces
 * spaces with the metaspace; encoding splits on spaces and applies greedy lowest-rank
 * merges per piece; decoding fuses pieces, resolves byte-fallback markers, and turns the
 * metaspace back into spaces.
 *
 * Merge ranks come from the merge-list order (rank = index): HF BPE carries no scores.
 */
class GemmaOnnxTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val decoder: Map<Int, ByteArray>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
) {
    private val cache = HashMap<String, List<Int>>()

    /** Token ids for [text] as a user would write it (no markers honoured). */
    fun encode(text: String): IntArray {
        val normalized = text.replace(' ', METASPACE)
        val ids = ArrayList<Int>()
        // Split on spaces (already metaspaces): each piece keeps its leading metaspace.
        var start = 0
        for (i in normalized.indices) {
            if (normalized[i] == ' ' || i == normalized.length - 1) {
                val end = if (i == normalized.length - 1) normalized.length else i
                if (end > start) ids.addAll(encodePiece(normalized.substring(start, end)))
                start = i + 1
            }
        }
        if (start < normalized.length) ids.addAll(encodePiece(normalized.substring(start)))
        return ids.toIntArray()
    }

    /** Token ids for a rendered prompt, honouring the chat markers. */
    fun encodePrompt(text: String): IntArray {
        // Split out known markers first so they encode as their own ids.
        val ids = ArrayList<Int>()
        var rest = text
        while (rest.isNotEmpty()) {
            var matched = false
            for ((marker, id) in markersByLength) {
                if (rest.startsWith(marker)) {
                    ids.add(id)
                    rest = rest.substring(marker.length)
                    matched = true
                    break
                }
            }
            if (!matched) {
                // Run to the next marker (or end) and encode as user text.
                var next = rest.length
                for ((marker, _) in markersByLength) {
                    val at = rest.indexOf(marker, 1)
                    if (at > 0) next = minOf(next, at)
                }
                for (id in encode(rest.substring(0, next))) ids.add(id)
                rest = rest.substring(next)
            }
        }
        return ids.toIntArray()
    }

    /** Text for token ids, with byte pieces fused back into characters. */
    fun decode(ids: IntArray): String {
        val bytes = ArrayList<Byte>()
        for (id in ids) {
            val chunk = decoder[id] ?: continue
            for (b in chunk) bytes.add(b)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).replace(METASPACE.toString(), " ")
    }

    private var markersByLength: List<Pair<String, Int>> = emptyList()

    private fun encodePiece(piece: String): List<Int> {
        cache[piece]?.let { return it }
        val bytes = piece.toByteArray(Charsets.UTF_8)
        val token = StringBuilder(bytes.size)
        for (b in bytes) token.append(byteEncoder[b.toInt() and 0xFF])
        val out = bpe(token.toString()).split(' ').mapNotNull { encoder[it] }
        // Unknown spans become unk rather than vanishing.
        val result = if (out.isEmpty() && piece.isNotEmpty()) listOf(UNK) else out
        cache[piece] = result
        return result
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
        private const val TAG = "GemmaOnnxTokenizer"

        const val TOKENIZER_FILE = "tokenizer.json"
        private const val METASPACE = '▁'
        private const val UNK = 3

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

        fun inDirectory(directory: File): GemmaOnnxTokenizer? {
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

        private fun parse(json: JSONObject): GemmaOnnxTokenizer? {
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
                val entry = mergesJson.opt(i)
                val pair = when (entry) {
                    is String -> {
                        val space = entry.indexOf(' ')
                        if (space < 0) continue
                        entry.substring(0, space) to entry.substring(space + 1)
                    }
                    is JSONObject -> {
                        entry.optString("0") to entry.optString("1")
                    }
                    is org.json.JSONArray -> {
                        entry.optString(0) to entry.optString(1)
                    }
                    else -> continue
                }
                // Merges may carry trailing scores; the pair is what matters.
                bpeRanks[pair.first to pair.second.split(' ').first()] = i
            }
            val added = json.optJSONArray("added_tokens")
            val markers = ArrayList<Pair<String, Int>>()
            if (added != null) {
                for (i in 0 until added.length()) {
                    val entry = added.optJSONObject(i) ?: continue
                    val content = entry.optString("content")
                    val id = entry.optInt("id")
                    encoder[content] = id
                    markers.add(content to id)
                }
            }
            markers.sortByDescending { it.first.length }
            // Byte-level decode table.
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
            // Byte-fallback `<0xHH>` markers.
            for (b in 0..255) {
                val id = encoder["<0x%02X>".format(b)] ?: encoder["<0x%x>".format(b)]
                if (id != null) decoder[id] = byteArrayOf(b.toByte())
            }
            return GemmaOnnxTokenizer(encoder, decoder, bpeRanks).also {
                it.markersByLength = markers
            }
        }
    }
}
