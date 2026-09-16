package com.vayunmathur.library.ml

import java.io.File

/**
 * Gemma's byte-level BPE tokenizer, parsed from the HuggingFace `tokenizer.json`.
 *
 * The reference artifact is `analysis/et-gemma/tokenizer.json`: BPE model, 262144 pieces,
 * 514906 merges, `byte_fallback` on, `fuse_unk` on, no continuing-subword prefix or
 * end-of-word suffix. The pipeline stages mirror the JSON exactly:
 *
 * - normalizer: ASCII space → `▁` (U+2581);
 * - pre-tokenizer: split on ASCII space, merged with previous — a no-op once the
 *   normalizer has replaced every space, so each run encodes whole;
 * - BPE: greedy lowest-rank merge (earlier in `merges` merges first), leftmost on a tie —
 *   the same shape as `NllbHandle`'s SPM1 loop, except ranks come straight from the merge
 *   list rather than inverted scores (see `scripts/ml/gemma4_tokenizer.py`);
 * - decoder: `▁` → space, then `<0xNN>` byte markers back to bytes, then UTF-8.
 *
 * Specials (`<bos>`, `<eos>`, `<start_of_turn>`, `<end_of_turn>`, …) are the `added_tokens`
 * entries flagged `special`: they match before BPE and round-trip as their own ids, the way
 * the prompt template needs `<bos>` to become id 2 rather than shredded pieces. Everything
 * else the template emits (`<|turn>`, `<tool_call|>`, …) is not in the vocabulary and
 * encodes through plain BPE plus byte fallback — deterministic and invertible, so a
 * produced id sequence always decodes back to the text the tool parser expects.
 *
 * Byte fallback covers every byte (all 256 `<0xNN>` pieces are asserted present at load),
 * so encoding never emits `<unk>`; loading fails closed (null) when the file is absent or
 * malformed and the engine falls back to `.litertlm`. Pure Kotlin, no Android APIs, so
 * unit tests exercise the real encode/decode paths on the JVM.
 */
class GemmaTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val tokenText: Array<String>,
    private val ranks: Map<String, Int>,
    private val specials: Map<String, Int>,
    private val bytePieces: Array<String>,
) {
    /** Token ids for [text], without any framing — the caller owns `<bos>` placement. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val special = matchSpecial(text, i)
            if (special != null) {
                out.add(special)
                i += specialLength(text, i)
                continue
            }
            var j = i
            while (j < text.length && matchSpecial(text, j) == null) j++
            encodeRun(text.substring(i, j), out)
            i = j
        }
        return out.toIntArray()
    }

    /** Text for [ids]. Structural controls (`<bos>`, `<eos>`, …) are skipped, not spelled. */
    fun decode(ids: IntArray): String {
        val skipped = specials.values.toSet()
        val pieces = StringBuilder()
        for (id in ids) {
            if (id in skipped) continue
            if (id < 0 || id >= tokenText.size) continue
            pieces.append(tokenText[id])
        }
        // Mixed content: literal pieces are UTF-8 text, `<0xNN>` markers are raw bytes.
        val bytes = ArrayList<Byte>(pieces.length * 2)
        var i = 0
        while (i < pieces.length) {
            val marker = parseByteMarker(pieces, i)
            if (marker != null) {
                bytes.add(marker.first)
                i = marker.second
            } else {
                val ch = pieces[i]
                val encoded = (if (ch == METASPACE) " " else ch.toString()).toByteArray(Charsets.UTF_8)
                for (b in encoded) bytes.add(b)
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun matchSpecial(text: String, at: Int): Int? {
        var best: Int? = null
        var bestLen = 0
        for ((piece, id) in specials) {
            if (piece.length > bestLen && text.startsWith(piece, at)) {
                best = id
                bestLen = piece.length
            }
        }
        return best
    }

    private fun specialLength(text: String, at: Int): Int {
        var bestLen = 0
        for (piece in specials.keys) {
            if (piece.length > bestLen && text.startsWith(piece, at)) bestLen = piece.length
        }
        return bestLen
    }

    private fun encodeRun(run: String, out: ArrayList<Int>) {
        val word = run.replace(' ', METASPACE)
        val symbols = ArrayList<String>(word.length + 1)
        var k = 0
        while (k < word.length) {
            val cp = word.codePointAt(k)
            val ch = String(Character.toChars(cp))
            if (encoder.containsKey(ch)) {
                symbols.add(ch)
            } else {
                for (b in ch.toByteArray(Charsets.UTF_8)) {
                    symbols.add(bytePieces[b.toInt() and 0xFF])
                }
            }
            k += Character.charCount(cp)
        }
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            var bestMerged = ""
            for (a in 0 until symbols.size - 1) {
                val merged = symbols[a] + symbols[a + 1]
                val rank = ranks[merged] ?: continue
                if (!encoder.containsKey(merged)) continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestAt = a
                    bestMerged = merged
                }
            }
            if (bestAt < 0) break
            symbols[bestAt] = bestMerged
            symbols.removeAt(bestAt + 1)
        }
        for (symbol in symbols) out.add(encoder[symbol] ?: return)
    }

    private fun parseByteMarker(pieces: CharSequence, at: Int): Pair<Byte, Int>? {
        if (at + 6 > pieces.length) return null
        if (pieces[at] != '<' || pieces[at + 1] != '0' ||
            pieces[at + 2] != 'x' && pieces[at + 2] != 'X' ||
            pieces[at + 5] != '>'
        ) {
            return null
        }
        val value = "${pieces[at + 3]}${pieces[at + 4]}".toIntOrNull(16) ?: return null
        return Pair(value.toByte(), at + 6)
    }

    companion object {
        /** The tokenizer table in the download directory, beside the `.pte`. */
        const val TOKENIZER_FILE = "gemma4_et_tokenizer.json"

        /**
         * Vocabulary width: the export's `get_vocab_size`, the logits' last dim, and the
         * piece count `scripts/ml/gemma4_tokenizer.py` asserts. A file with any other width
         * is not this model's table and fails closed.
         */
        const val VOCAB_SIZE = 262144

        private const val METASPACE = '▁'

        /**
         * Load the table from [directory]. Null when the file is absent or malformed — the
         * engine then reports unavailable rather than crashing.
         */
        fun inDirectory(directory: File): GemmaTokenizer? {
            val file = File(directory, TOKENIZER_FILE)
            if (!file.isFile) return null
            return try {
                parse(file.readText(Charsets.UTF_8))
            } catch (e: Throwable) {
                null
            }
        }

        internal fun parse(json: String): GemmaTokenizer? {
            val encoder = HashMap<String, Int>(VOCAB_SIZE * 2)
            val tokenText = Array(VOCAB_SIZE) { "" }
            val seen = BooleanArray(VOCAB_SIZE)
            if (!readVocab(json, encoder, tokenText, seen)) return null
            if (encoder.size != VOCAB_SIZE) return null
            if (seen.any { !it }) return null
            val ranks = readMerges(json) ?: return null
            if (ranks.isEmpty()) return null
            val specials = readSpecials(json) ?: return null
            if (!specials.containsKey("<bos>")) return null
            val bytePieces = Array(256) { "" }
            val bytePattern = Regex("<0[xX]([0-9A-Fa-f]{2})>")
            for ((piece, _) in encoder) {
                val match = bytePattern.matchEntire(piece) ?: continue
                val value = match.groupValues[1].toIntOrNull(16) ?: continue
                if (bytePieces[value].isEmpty()) bytePieces[value] = piece
            }
            if (bytePieces.any { it.isEmpty() }) return null
            return GemmaTokenizer(encoder, tokenText, ranks, specials, bytePieces)
        }

        private fun sectionCursor(json: String, key: String, open: Char): JsonCursor? {
            var from = 0
            while (true) {
                val hit = json.indexOf("\"$key\"", from)
                if (hit < 0) return null
                val cursor = JsonCursor(json, hit + key.length + 2)
                cursor.skipWs()
                if (cursor.peek() != ':') {
                    from = hit + 1
                    continue
                }
                cursor.next()
                cursor.skipWs()
                if (cursor.peek() != open) {
                    from = hit + 1
                    continue
                }
                cursor.next()
                return cursor
            }
        }

        private fun readVocab(
            json: String,
            encoder: HashMap<String, Int>,
            tokenText: Array<String>,
            seen: BooleanArray,
        ): Boolean {
            val cursor = sectionCursor(json, "vocab", '{') ?: return false
            while (true) {
                cursor.skipWs()
                if (cursor.peek() == '}') {
                    cursor.next()
                    return true
                }
                val piece = cursor.readString() ?: return false
                if (!cursor.expect(':')) return false
                val id = cursor.readInt() ?: return false
                if (id < 0 || id >= VOCAB_SIZE || seen[id]) return false
                seen[id] = true
                tokenText[id] = piece
                // The specials ride inside `vocab` with the same ids `added_tokens` states.
                encoder.putIfAbsent(piece, id)
                cursor.skipWs()
                when (cursor.peek()) {
                    ',' -> cursor.next()
                    '}' -> continue
                    else -> return false
                }
            }
        }

        private fun readMerges(json: String): HashMap<String, Int>? {
            val cursor = sectionCursor(json, "merges", '[') ?: return null
            val ranks = HashMap<String, Int>()
            var rank = 0
            while (true) {
                cursor.skipWs()
                when (cursor.peek()) {
                    ']' -> {
                        cursor.next()
                        return ranks
                    }
                    '[' -> {
                        cursor.next()
                        val first = cursor.readString() ?: return null
                        if (!cursor.expect(',')) return null
                        val second = cursor.readString() ?: return null
                        if (!cursor.expect(']')) return null
                        ranks.putIfAbsent(first + second, rank++)
                    }
                    '"' -> {
                        val line = cursor.readString() ?: return null
                        val space = line.indexOf(' ')
                        if (space < 0) return null
                        ranks.putIfAbsent(
                            line.substring(0, space) + line.substring(space + 1).substringBefore(' '),
                            rank++,
                        )
                    }
                    else -> return null
                }
                cursor.skipWs()
                when (cursor.peek()) {
                    ',' -> cursor.next()
                    ']' -> continue
                    else -> return null
                }
            }
        }

        private fun readSpecials(json: String): Map<String, Int>? {
            val cursor = sectionCursor(json, "added_tokens", '[') ?: return null
            val specials = HashMap<String, Int>()
            while (true) {
                cursor.skipWs()
                when (cursor.peek()) {
                    ']' -> {
                        cursor.next()
                        return specials
                    }
                    '{' -> {
                        cursor.next()
                        var id: Int? = null
                        var content: String? = null
                        var special = false
                        while (true) {
                            cursor.skipWs()
                            if (cursor.peek() == '}') {
                                cursor.next()
                                break
                            }
                            val field = cursor.readString() ?: return null
                            if (!cursor.expect(':')) return null
                            when (field) {
                                "id" -> id = cursor.readInt() ?: return null
                                "content" -> content = cursor.readString() ?: return null
                                "special" -> special = cursor.readBool() ?: return null
                                else -> cursor.skipValue() ?: return null
                            }
                            cursor.skipWs()
                            when (cursor.peek()) {
                                ',' -> cursor.next()
                                '}' -> continue
                                else -> return null
                            }
                        }
                        if (content != null && id != null && special) specials[content] = id
                    }
                    else -> return null
                }
                cursor.skipWs()
                when (cursor.peek()) {
                    ',' -> cursor.next()
                    ']' -> continue
                    else -> return null
                }
            }
        }
    }

    /** Minimal JSON reader for the three sections this tokenizer needs. */
    private class JsonCursor(val s: String, var i: Int = 0) {
        fun peek(): Char = if (i < s.length) s[i] else 0.toChar()

        fun next() {
            if (i < s.length) i++
        }

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun expect(c: Char): Boolean {
            skipWs()
            if (peek() != c) return false
            next()
            return true
        }

        fun readString(): String? {
            skipWs()
            if (peek() != '"') return null
            next()
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) return null
                when (s[i++]) {
                    '"', '\\', '/' -> sb.append(s[i - 1])
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) return null
                        val hi = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                        i += 4
                        if (hi in 0xD800..0xDBFF && i + 6 <= s.length &&
                            s[i] == '\\' && s[i + 1] == 'u'
                        ) {
                            val lo = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (lo != null && lo in 0xDC00..0xDFFF) {
                                sb.append(Character.toChars(0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)))
                                i += 6
                                continue
                            }
                        }
                        sb.append(hi.toChar())
                    }
                    else -> return null
                }
            }
            return null
        }

        fun readInt(): Int? {
            skipWs()
            val start = i
            if (peek() == '-') next()
            val digits = i
            while (peek().isDigit()) next()
            if (i == digits) return null
            return s.substring(start, i).toIntOrNull()
        }

        fun readBool(): Boolean? {
            skipWs()
            return when {
                s.startsWith("true", i) -> {
                    i += 4
                    true
                }
                s.startsWith("false", i) -> {
                    i += 5
                    false
                }
                else -> null
            }
        }

        fun skipValue(): Boolean {
            skipWs()
            return when (peek()) {
                '"' -> readString() != null
                '{' -> skipComposite(isObject = true)
                '[' -> skipComposite(isObject = false)
                else -> {
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']' &&
                        s[i] != ' ' && s[i] != '\t' && s[i] != '\n' && s[i] != '\r'
                    ) {
                        i++
                    }
                    i > start
                }
            }
        }

        private fun skipComposite(isObject: Boolean): Boolean {
            val close = if (isObject) '}' else ']'
            next()
            while (true) {
                skipWs()
                if (peek() == close) {
                    next()
                    return true
                }
                if (isObject) {
                    if (readString() == null || !expect(':') || !skipValue()) return false
                } else if (!skipValue()) {
                    return false
                }
                skipWs()
                when (peek()) {
                    ',' -> next()
                    close -> continue
                    else -> return false
                }
            }
        }
    }
}
