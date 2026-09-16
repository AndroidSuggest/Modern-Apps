package com.vayunmathur.openassistant.util

import android.util.Log
import com.vayunmathur.library.ml.ExecutorchSessions
import com.vayunmathur.library.ml.GemmaTokenizer
import com.vayunmathur.library.ml.GemmaTurn
import com.vayunmathur.library.ml.parseGemmaToolCall
import com.vayunmathur.library.ml.renderGemmaPrompt
import com.vayunmathur.library.ml.renderGemmaToolResponse
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * A chat turn on Gemma 4 via ExecuTorch: the SpinQuant `.pte` (Vulkan 8da4w) with a
 * KV-cached decode loop.
 *
 * Structural counterpart to [Gemma4Engine] (`.litertlm` on LiteRT-LM): same whole-turn lock,
 * same `ask()` streaming shape, same tool loop over [AssistantToolSet] — only the execution
 * moves. The `.pte` is text-only (no vision/audio towers — see
 * `analysis/et-gemma/EXPORT_PLAN.md` §8), so image/audio paths stay empty here and
 * `canSeeImages`/`canHearAudio` are false by construction.
 *
 * # Status: stub until the export + tokenizer land
 *
 * Neither the SpinQuant `.pte` (`gemma-4-e2b-vulkan-8da4w.pte`, ~2.4 GB staged in
 * `analysis/et-gemma/`) nor an in-app BPE tokenizer ships yet, so today this resolves to
 * unavailable and every turn takes the `.litertlm` path. When the mirror serves the file
 * (a *new* `ModelUrls` entry — `GEMMA_LITERTLM` is untouched) and a tokenizer lands, this
 * fills in: `ensureLoaded` opens the module, `ask` prefills the rendered prompt (chunked
 * to [MAX_SEQ_LEN] = 128 positions per `forward` on Vulkan) and decodes greedily to
 * [GEMMA_STOP] with the tool protocol resolved between chunks.
 *
 * # The Vulkan 128-token chunk constraint
 *
 * `sm-gemma4-e2b-vulkan-config.json`: `forward` takes `tokens [1, 1..2048]` int64 and
 * returns `logits [1, 1..2048, 262144]` fp32, `use_kv_cache`, `max_context_len` 2048 but
 * `max_seq_len` **128** — each `forward` call processes at most 128 positions. Prefill
 * therefore walks the prompt in 128-token chunks (KV accumulates across calls) and decode
 * is one single-token `forward` per step, stopping at [GEMMA_STOP] or [DEFAULT_REPLY_TOKENS].
 *
 * # Threading
 *
 * The lock is held for a whole turn rather than per call, because a turn is many calls and
 * interleaving them would mix two conversations. `InferenceService` already serialises
 * through a queue; the lock is here so that a second caller blocks rather than corrupts.
 */
class Gemma4EtEngine(private val directory: File) : AutoCloseable {

    private val lock = ReentrantLock()
    private var module: Module? = null
    private var tokenizer: GemmaTokenizer? = null

    /**
     * A token list the engine encoded, with its per-position logits available for parity.
     *
     * Returned by [prefillLogits] for on-device probes (cosine vs the `.litertlm`
     * reference); `ask` consumes the same path internally.
     */
    data class Prefill(val ids: LongArray, val logits: FloatArray, val vocab: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Prefill &&
                ids.contentEquals(other.ids) && logits.contentEquals(other.logits) &&
                vocab == other.vocab)

        override fun hashCode(): Int = 31 * (31 * ids.contentHashCode() + logits.contentHashCode()) + vocab
    }

    /** Text-only: the `.pte` has no vision tower. Always false. */
    val canSeeImages: Boolean get() = false

    /** Text-only: the `.pte` has no audio tower. Always false. */
    val canHearAudio: Boolean get() = false

    /** Whether the module is loaded and usable. */
    val isReady: Boolean
        get() = lock.withLock { module != null && tokenizer != null }

    /**
     * Load the model if it is not loaded. Returns whether it is usable afterwards.
     *
     * Idempotent, so `onCreate` can pre-warm and the first turn can call it again without
     * cost. False when the `.pte` is unmirrored (its `ModelUrls` entry is inert until the
     * mirror serves it), the Vulkan delegate is absent, or the tokenizer table is missing
     * — the caller then serves via `.litertlm`. Loading is slow — gigabytes of weights —
     * so callers should pre-warm off the main thread. The 33 MB tokenizer table parses on
     * the same path; a malformed table fails closed like a missing `.pte`.
     */
    fun ensureLoaded(): Boolean = lock.withLock {
        if (module != null && tokenizer != null) return@withLock true
        try {
            val backends = ExecutorchSessions.registeredBackends()
            if (backends?.any { it.contains("Vulkan", ignoreCase = true) } != true) {
                Log.i(TAG, "Vulkan backend absent, $MODEL_FILE unavailable")
                return@withLock false
            }
            val table = GemmaTokenizer.inDirectory(directory)
            if (table == null) {
                Log.i(TAG, "gemma4 ET ${GemmaTokenizer.TOKENIZER_FILE} missing from $directory")
                return@withLock false
            }
            val file = File(directory, MODEL_FILE)
            if (!file.isFile) {
                Log.i(TAG, "gemma4 ET $MODEL_FILE missing from $directory")
                return@withLock false
            }
            val opened = ExecutorchSessions.openPath(file.absolutePath) ?: return@withLock false
            module = opened
            tokenizer = table
            true
        } catch (e: Throwable) {
            Log.e(TAG, "gemma4 ET failed to load from $directory", e)
            false
        }
    }

    /** Image paths are not consumable here; always empty so the caller notes them unread. */
    fun encodeImages(paths: List<String>): List<String> = emptyList()

    /** Audio paths are not consumable here; always empty so the caller notes them unheard. */
    fun encodeAudio(paths: List<String>): List<String> = emptyList()

    /**
     * Positions the prompt for [conversation] would occupy, against [promptCeiling].
     *
     * Encoded with the in-app BPE table when loaded, estimated (4 chars/token) before.
     */
    fun positionsFor(
        conversation: List<GemmaTurn>,
        system: String?,
        tools: AssistantToolSet?,
        limit: Int = DEFAULT_REPLY_TOKENS,
    ): Int {
        val table = lock.withLock { tokenizer }
        if (table == null) {
            var total = (system?.length ?: 0) / 4
            for (turn in conversation) {
                total += turn.text.length / 4
            }
            return total
        }
        val declarations = tools?.gemmaDeclarations().orEmpty()
        val prompt = renderGemmaPrompt(conversation, system, declarations)
        return table.encode(prompt).size
    }

    /** What [positionsFor] must not exceed if the reply is to keep its [limit]. */
    fun promptCeiling(limit: Int = DEFAULT_REPLY_TOKENS): Int = MAX_CONTEXT - limit

    /**
     * Run a turn, resolving any tool calls, and stream the visible reply to [onPartial].
     *
     * [onPartial] returning false stops generation. Returns the reply with all tool syntax
     * stripped, or null if the model is unavailable — the caller (`InferenceService`) then
     * falls back to [Gemma4Engine]. Text-only: a turn carrying image/audio paths resolves
     * to null so the `.litertlm` path serves it.
     */
    suspend fun ask(
        conversation: List<GemmaTurn>,
        system: String?,
        tools: AssistantToolSet?,
        limit: Int = DEFAULT_REPLY_TOKENS,
        onPartial: (String) -> Boolean = { true },
    ): String? {
        val live = lock.withLock { module } ?: return null
        val table = lock.withLock { tokenizer } ?: return null
        if (conversation.any { it.imagePaths.isNotEmpty() || it.audioPaths.isNotEmpty() }) {
            return null
        }
        return try {
            runTurn(live, table, conversation, system, tools, limit, onPartial)
        } catch (e: Throwable) {
            Log.e(TAG, "gemma4 ET turn failed", e)
            null
        }
    }

    /** Discard the conversation state. The next turn builds a fresh KV cache. */
    fun reset() = lock.withLock { /* KV cache is per-turn; nothing cached */ }

    override fun close() = lock.withLock {
        module = null
        tokenizer = null
        ExecutorchSessions.close(etSessionKey())
    }

    private fun etSessionKey(): String = "file:${File(directory, MODEL_FILE).absolutePath}"

    /**
     * One turn over [live]: render, prefill in 128-token chunks, decode to [GEMMA_STOP].
     *
     * The prompt is [renderGemmaPrompt] over the conversation with [tools]' declarations —
     * the same table litertlm builds by reflection, so prompts agree across backends. Each
     * tool hop appends the call plus its result as the model-turn continuation and re-runs
     * the whole prompt (prefill again from the start); the KV cache is per-turn state
     * inside the module, so a fresh prefill is a fresh cache and no reset call exists to
     * make. Null on any native failure so the caller falls back to `.litertlm`.
     */
    private fun runTurn(
        live: Module,
        table: GemmaTokenizer,
        conversation: List<GemmaTurn>,
        system: String?,
        tools: AssistantToolSet?,
        limit: Int,
        onPartial: (String) -> Boolean,
    ): String? {
        val declarations = tools?.gemmaDeclarations().orEmpty()
        var continuation = ""
        var visible = ""
        repeat(MAX_TOOL_HOPS + 1) {
            val prompt = renderGemmaPrompt(conversation, system, declarations, continuation)
            val ids = table.encode(prompt).map { it.toLong() }.toLongArray()
            if (ids.size > MAX_CONTEXT - limit) {
                Log.w(TAG, "gemma4 ET prompt of ${ids.size} ids exceeds context, using litertlm")
                return null
            }
            if (!prefillChunks(live, ids)) return null
            // decodeStreamed already streamed strip() deltas via onPartial; fold the hop's
            // stripped text into visible once it returns.
            val reply = decodeStreamed(live, table, ids.last(), limit, onPartial, visible)
                ?: return null
            visible += strip(reply)
            val call = parseGemmaToolCall(reply)
            if (tools == null || call == null) return visible.ifEmpty { null }
            val result = tools.invokeGemmaTool(call.name, call.arguments)
                ?: "Error: unknown tool '${call.name}'"
            continuation += reply + renderGemmaToolResponse(call.name, result)
        }
        return visible.ifEmpty { null }
    }

    /**
     * Prefill [ids] in [MAX_SEQ_LEN]-token chunks; the KV cache accumulates across chunk
     * calls inside the module. False when a chunk fails or the logits shape is unexpected.
     */
    private fun prefillChunks(live: Module, ids: LongArray): Boolean {
        var at = 0
        while (at < ids.size) {
            val end = minOf(at + MAX_SEQ_LEN, ids.size)
            val (logits, shape) = forwardTokens(live, ids.copyOfRange(at, end)) ?: return false
            if (shape.size != 3 || shape[0] != 1L || vocabOf(shape) != VOCAB_SIZE) return false
            if (logits.size != shape[1].toInt() * VOCAB_SIZE) return false
            at = end
        }
        return true
    }

    /**
     * Greedy-decode one token per `forward` from the prefilled cache, streaming the visible
     * reply through [onPartial] as strip() deltas (false stops and yields what streamed so
     * far). Stops at [GEMMA_STOP] or [limit] tokens. Returns the raw reply — tool syntax
     * included — since the tool loop parses it next. Null on native failure.
     *
     * [promptLast] is the prompt's final id: the first step continues from it (the KV cache
     * already holds the whole prompt, including that id), and each step after from the last
     * produced id.
     */
    private fun decodeStreamed(
        live: Module,
        table: GemmaTokenizer,
        promptLast: Long,
        limit: Int,
        onPartial: (String) -> Boolean,
        alreadyVisible: String,
    ): String? {
        val produced = ArrayList<Int>(limit.coerceAtMost(DEFAULT_REPLY_TOKENS))
        val raw = StringBuilder()
        var shown = 0
        var feed = promptLast
        val cap = limit.coerceAtMost(DEFAULT_REPLY_TOKENS)
        while (produced.size < cap) {
            val (logits, shape) = forwardTokens(live, longArrayOf(feed)) ?: return null
            if (shape.size != 3 || vocabOf(shape) != VOCAB_SIZE) return null
            val row = lastRow(logits, shape) ?: return null
            val next = argmax(row)
            if (next in GEMMA_STOP) break
            produced.add(next)
            feed = next.toLong()
            raw.append(table.decode(intArrayOf(next)))
            val stripped = strip(raw.toString())
            // Partial tool syntax strips to a prefix of itself, so only the newly visible
            // tail streams — never a resend of what the UI already wrote.
            if (stripped.length > shown) {
                shown = stripped.length
                if (!onPartial(alreadyVisible + stripped)) break
            }
        }
        return raw.toString()
    }

    /**
     * Prefill [ids] in [MAX_SEQ_LEN]-token chunks and return the last chunk's logits.
     *
     * The KV cache accumulates across chunk calls inside the module; each `forward` sees at
     * most 128 new positions (the Vulkan `max_seq_len`). Public for on-device parity
     * probes (logit cosine vs the `.litertlm` reference).
     */
    fun prefillLogits(ids: LongArray): Prefill? {
        val live = lock.withLock { module } ?: return null
        return try {
            var last: FloatArray? = null
            var vocab = 0
            var at = 0
            while (at < ids.size) {
                val end = minOf(at + MAX_SEQ_LEN, ids.size)
                val chunk = ids.copyOfRange(at, end)
                val (logits, shape) = forwardTokens(live, chunk) ?: return null
                if (shape.size != 3 || shape[0] != 1L) return null
                vocab = shape[2].toInt()
                if (vocab != VOCAB_SIZE) return null
                last = logits
                at = end
            }
            val out = last ?: return null
            Prefill(ids, out, vocab)
        } catch (e: Throwable) {
            Log.w(TAG, "gemma4 ET prefill failed", e)
            null
        }
    }

    /**
     * Greedy decode from a prefilled cache: one single-token `forward` per step, argmax
     * over the 262,144-wide row, stopping at [GEMMA_STOP] or [limit] tokens.
     *
     * [seed] is the prefilled prompt's last id — the first step continues from it, since
     * the KV cache already holds the whole prompt. [decodeIds] maps each produced id to
     * its text.
     */
    fun greedyDecode(seed: Long, limit: Int, decodeIds: (IntArray) -> String?): String? {
        val live = lock.withLock { module } ?: return null
        return try {
            val produced = ArrayList<Int>(limit.coerceAtMost(DEFAULT_REPLY_TOKENS))
            val cap = limit.coerceAtMost(DEFAULT_REPLY_TOKENS)
            var feed = seed
            while (produced.size < cap) {
                val (logits, shape) = forwardTokens(live, longArrayOf(feed)) ?: return null
                if (shape.size != 3 || vocabOf(shape) != VOCAB_SIZE) return null
                val row = lastRow(logits, shape) ?: return null
                val next = argmax(row)
                if (next in GEMMA_STOP) break
                produced.add(next)
                feed = next.toLong()
            }
            decodeIds(produced.toIntArray())
        } catch (e: Throwable) {
            Log.w(TAG, "gemma4 ET decode failed", e)
            null
        }
    }

    /** One `forward` over [tokens]: raw logits with their shape, or null on failure. */
    private fun forwardTokens(live: Module, tokens: LongArray): Pair<FloatArray, LongArray>? {
        return try {
            val input = EValue.from(Tensor.fromBlob(tokens, longArrayOf(1, tokens.size.toLong())))
            val outs = ExecutorchSessions.run(live, listOf(input), FORWARD_METHOD) ?: return null
            val value = outs.firstOrNull() ?: return null
            if (!value.isTensor) return null
            val tensor = value.toTensor()
            // The export pins fp32 logits (see sm-gemma4-e2b-vulkan-config.json); anything
            // else means "not this backend" and yields null so the turn falls back to
            // litertlm. (Vision drop-ins read HALF-tolerant via EtTensors'
            // floatsAllowingHalf, which is internal to :library:ml and invisible here —
            // the Gemma logits dtype is config-pinned, so no tolerant path is needed.)
            if (tensor.dtype() != org.pytorch.executorch.DType.FLOAT) return null
            val outShape = tensor.shape()
            if (outShape.size != 3 || outShape[0] != 1L) return null
            val want = (outShape[1] * outShape[2]).toInt()
            if (tensor.numel().toInt() < want) return null
            val out = FloatArray(want)
            tensor.copyDataInto(java.nio.FloatBuffer.wrap(out))
            Pair(out, outShape)
        } catch (e: Throwable) {
            Log.w(TAG, "gemma4 ET forward failed", e)
            null
        }
    }

    private fun vocabOf(shape: LongArray): Int =
        if (shape.size == 3) shape[2].toInt() else -1

    /** The last position's logits row of a `[1, seq, vocab]` output, or null. */
    private fun lastRow(logits: FloatArray, shape: LongArray): FloatArray? {
        if (shape.size != 3) return null
        val seq = shape[1].toInt()
        val vocab = shape[2].toInt()
        if (seq <= 0 || vocab <= 0 || logits.size != seq * vocab) return null
        return logits.copyOfRange((seq - 1) * vocab, seq * vocab)
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        var top = row[0]
        for (i in 1 until row.size) {
            val value = row[i]
            if (!value.isNaN() && value > top) {
                top = value
                best = i
            }
        }
        return best
    }

    companion object {
        private const val TAG = "Gemma4EtEngine"

        /** The SpinQuant `.pte` in the download directory (staged, unmirrored). */
        const val MODEL_FILE = "gemma-4-e2b-vulkan-8da4w.pte"

        /** Reply reserve kept clear of the context window. */
        const val DEFAULT_REPLY_TOKENS = 512

        /** Context window of the Vulkan 8da4w export (`get_max_context_len`). */
        const val MAX_CONTEXT = 2048

        /**
         * Positions per `forward` call on Vulkan (`get_max_seq_len`): prefill walks the
         * prompt in chunks of this size; decode is single-token.
         */
        const val MAX_SEQ_LEN = 128

        /** The export's vocabulary (`get_vocab_size`). */
        const val VOCAB_SIZE = 262144

        /** Beginning-of-sequence (`get_bos_id`). */
        const val BOS_ID = 2

        /** Ids that end a reply: `<eos>` 1, `<turn|>` 106, double-newline 50. */
        val GEMMA_STOP: IntArray = intArrayOf(1, 106, 50)

        /** Tool calls one turn may make before the loop gives up. */
        const val MAX_TOOL_HOPS = 8

        /**
         * The prose in a reply, with the tool markers and everything inside them removed.
         * Same protocol as [Gemma4Engine.strip] so streamed partials render identically.
         */
        fun strip(reply: String): String = Gemma4Engine.strip(reply)

        /**
         * Files [ensureLoaded] needs, for a caller checking a download is complete.
         * Deliberately NOT in `ModelUrls.INITIAL`: unmirrored, so gating first launch on
         * it would brick the app — the engine resolves it opportunistically.
         */
        val FILES: List<String> = listOf(MODEL_FILE)

        private const val FORWARD_METHOD = "forward"
    }
}
