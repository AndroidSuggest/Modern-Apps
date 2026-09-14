package com.vayunmathur.openassistant.util

import android.util.Log
import com.vayunmathur.library.ml.BonsaiHandle
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A chat turn on Bonsai-8B, replacing `Gemma4Engine`.
 *
 * The same shape: the tool loop lives here ([ask] is generate / parse / invoke / resume),
 * one conversation at a time behind a whole-turn lock, history re-rendered every turn so a
 * stale cache can never answer a conversation that never happened.
 *
 * # What changed with the model
 *
 * The prompt is the Qwen3 chat template (`<|im_start|>system … <|im_end|>`, tools in a
 * `<tools>` JSON block, calls as `<tool_call>{"name": …, "arguments": …}</tool_call>`)
 * instead of Gemma's markers. Tokenizer, sampler and tool-call parse/render live in
 * [BonsaiHandle]; the reflection-built [ToolRegistry] declarations are rendered through
 * [BonsaiHandle.renderTool].
 *
 * Multimodal input is text-only for now: Bonsai ships no vision/audio tower, so there is no
 * `encodeImages`/`encodeAudio`. Callers that passed attachments get the text with a note.
 */
class BonsaiEngine(private val directory: File) : AutoCloseable {

    private val lock = ReentrantLock()
    private var handle: BonsaiHandle? = null

    /** Whether the model is loaded and usable. */
    val isReady: Boolean
        get() = lock.withLock { handle?.isAvailable == true }

    /**
     * Load the model if it is not loaded. Returns whether it is usable afterwards.
     *
     * Idempotent, so `onCreate` can pre-warm and the first turn can call it again without cost.
     * Loading is slow - over a gigabyte of weights - so callers should pre-warm off the main
     * thread.
     */
    fun ensureLoaded(): Boolean = lock.withLock {
        val live = handle
        if (live != null && live.isAvailable) return@withLock true
        live?.close()
        val opened = BonsaiHandle.inDirectory(directory)
        if (!opened.isAvailable) {
            Log.w(TAG, "bonsai did not load from $directory")
            opened.close()
            handle = null
            return@withLock false
        }
        handle = opened
        true
    }

    /**
     * Positions the prompt for [conversation] would occupy, against [promptCeiling].
     *
     * For a caller dropping history before a turn.
     */
    fun positionsFor(
        conversation: List<Turn>,
        system: String?,
        tools: ToolRegistry?,
        limit: Int = DEFAULT_REPLY,
    ): Int = lock.withLock {
        val live = handle ?: return@withLock 0
        val declarations = tools?.declarations?.map { toBonsai(it) } ?: emptyList()
        live.encode(renderPrompt(conversation, system, declarations, ""))?.size ?: 0
    }

    /** What [positionsFor] must not exceed if the reply is to keep its [limit]. */
    fun promptCeiling(limit: Int = DEFAULT_REPLY): Int = MAX_CONTEXT - maxOf(limit, 3)

    /**
     * Run a turn, resolving any tool calls, and stream the visible reply to [onPartial].
     *
     * [onPartial] returning false stops generation. Returns the reply with all tool syntax
     * stripped, or null if the model is unavailable.
     */
    fun ask(
        conversation: List<Turn>,
        system: String?,
        tools: ToolRegistry?,
        limit: Int = DEFAULT_REPLY,
        onPartial: (String) -> Boolean = { true },
    ): String? = lock.withLock {
        val live = handle ?: return@withLock null
        if (!live.isAvailable) return@withLock null
        val declarations = tools?.declarations?.map { toBonsai(it) } ?: emptyList()

        var pending = ""
        var visible = ""
        for (hop in 0..MAX_TOOL_HOPS) {
            val prompt = renderPrompt(conversation, system, declarations, pending)
            val promptIds = live.encode(prompt) ?: return@withLock null
            if (promptIds.size + limit + 2 > MAX_CONTEXT) {
                Log.w(TAG, "a prompt of ${promptIds.size} positions does not fit $MAX_CONTEXT")
                return@withLock null
            }
            Log.i(
                TAG,
                "prompt ${promptIds.size} positions, ${conversation.size} turns, " +
                    "${declarations.size} tools, reply budget $limit",
            )
            var stopped = false
            val reply = live.generate(
                promptIds,
                BonsaiHandle.GeneratorConfig(maxTokens = limit),
            ) { partial ->
                val shown = visible + strip(partial)
                if (onPartial(shown)) {
                    BonsaiHandle.parseToolCall(partial) == null
                } else {
                    stopped = true
                    false
                }
            } ?: return@withLock null

            visible += strip(reply)
            if (stopped) return@withLock visible
            val call = BonsaiHandle.parseToolCall(reply)
            if (call == null || tools == null) return@withLock visible
            if (hop == MAX_TOOL_HOPS) {
                Log.w(TAG, "stopping after $MAX_TOOL_HOPS tool hops")
                return@withLock visible
            }
            val adapted = com.vayunmathur.library.ml.Gemma4Handle.ToolCall(call.name, call.arguments)
            val result = tools.invoke(adapted)
            Log.d(TAG, "tool ${call.name}(${call.arguments}) -> ${result.take(120)}")
            pending += reply.substringBefore("<tool_call>") + "<tool_call>" +
                BonsaiHandle.renderToolResponse(call.name, result)
        }
        visible
    }

    /** Discard the conversation state. Cheap — the next turn re-prefills from scratch. */
    fun reset() = lock.withLock { /* stateless across turns: nothing cached */ }

    override fun close() = lock.withLock {
        handle?.close()
        handle = null
    }

    companion object {
        private const val TAG = "BonsaiEngine"

        /** Positions the model was exported with. */
        const val MAX_CONTEXT = 65536

        /** Default reply budget, in tokens. */
        const val DEFAULT_REPLY = 512

        /** Tool calls one turn may make before the loop gives up. */
        const val MAX_TOOL_HOPS = 8

        /** A turn of the conversation. Images/audio are noted, not encoded (text-only model). */
        data class Turn(
            val role: Role,
            val text: String,
            val note: String = "",
        )

        enum class Role { USER, MODEL }

        private fun toBonsai(declaration: com.vayunmathur.library.ml.Gemma4Handle.ToolDeclaration) =
            BonsaiHandle.ToolDeclaration(
                name = declaration.name,
                description = declaration.description,
                parameters = declaration.parameters.map {
                    BonsaiHandle.ToolDeclaration.Parameter(
                        name = it.name,
                        description = it.description,
                        type = it.type,
                        required = it.required,
                    )
                },
            )

        /**
         * The Qwen3 chat prompt: system block, `<tools>` JSON, history, generation header.
         *
         * `pending` is the model's own half-written turn (its call plus the tool result) and
         * goes in as a continuation inside the open assistant turn.
         */
        fun renderPrompt(
            conversation: List<Turn>,
            system: String?,
            tools: List<BonsaiHandle.ToolDeclaration>,
            pending: String,
        ): String {
            val out = StringBuilder()
            out.append("<|im_start|>system\n")
            if (!system.isNullOrEmpty()) out.append(system).append("\n\n")
            if (tools.isNotEmpty()) {
                out.append("# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
                out.append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
                for (tool in tools) {
                    out.append("\n")
                    out.append(BonsaiHandle.renderTool(tool))
                }
                out.append(
                    "\n</tools>\n\nFor each function call, return a json object with function name and " +
                        "arguments within <tool_call></tool_call> XML tags:\n" +
                        "<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n" +
                        "</tool_call><|im_end|>\n",
                )
            } else {
                out.append("<|im_end|>\n")
            }
            for (turn in conversation) {
                when (turn.role) {
                    Role.USER -> out.append("<|im_start|>user\n").append(turn.text)
                    Role.MODEL -> out.append("<|im_start|>assistant\n").append(turn.text)
                }
                if (turn.note.isNotEmpty()) out.append("\n").append(turn.note)
                out.append("<|im_end|>\n")
            }
            out.append("<|im_start|>assistant\n")
            out.append(pending)
            return out.toString()
        }

        /**
         * The prose in a reply, with the tool markers and everything inside them removed.
         *
         * Streamed partials arrive mid-marker, so a half-written `<tool_call>` must not flash
         * up in the UI: anything from an opening marker onwards is dropped whether or not it
         * has been closed yet.
         */
        fun strip(reply: String): String {
            var out = reply
            for (marker in listOf("<tool_call>", "<tool_response>", "</tool_call>", "</tool_response>")) {
                val at = out.indexOf(marker)
                if (at >= 0) out = out.substring(0, at)
            }
            val open = out.lastIndexOf('<')
            if (open >= 0 && out.length - open <= "</tool_response>".length) {
                val tail = out.substring(open)
                if ("<tool_call>".startsWith(tail) || "</tool_call>".startsWith(tail) ||
                    "<tool_response>".startsWith(tail) || "</tool_response>".startsWith(tail) ||
                    "<|im_end|>".startsWith(tail)
                ) {
                    out = out.substring(0, open)
                }
            }
            return out
        }
    }
}
