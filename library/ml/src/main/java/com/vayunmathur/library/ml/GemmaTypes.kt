package com.vayunmathur.library.ml

/**
 * Gemma 4's chat types, prompt template and tool protocol — pure Kotlin, no native code.
 *
 * Split out of the old Vulkan `Gemma4Handle` so the ONNX path (`GemmaOnnxHandle`,
 * `Gemma4Engine`) keeps the conversation model, the template, the tool syntax and the
 * window budget without the JNI handle. Everything here is exercised by the unit tests and
 * by `:openassistant`; nothing here touches a model file.
 */

/**
 * One message in a conversation.
 *
 * [images] are soft tokens (`n * 1536` floats) at the head of the turn, [audio] likewise
 * for clips. Pass them unscaled. [imagePaths]/[audioPaths] carry the source files for
 * runtimes (like LiteRT-LM) that consume files directly instead of soft tokens.
 */
data class GemmaTurn(
    val role: GemmaRole,
    val text: String,
    val images: List<FloatArray> = emptyList(),
    val audio: List<FloatArray> = emptyList(),
    val imagePaths: List<String> = emptyList(),
    val audioPaths: List<String> = emptyList(),
)

/** Who spoke. Gemma's own names, not OpenAI's: the model turn is `model`, not `assistant`. */
enum class GemmaRole(internal val marker: String) {
    USER("user"),
    MODEL("model"),
}

/** A tool the model may call, in the shape [declareGemmaTools] serialises. */
data class GemmaToolDeclaration(
    val name: String,
    val description: String,
    val parameters: List<Parameter>,
) {
    /** One argument. [type] is Gemma's spelling: `STRING`, `NUMBER`, `BOOLEAN`. */
    data class Parameter(
        val name: String,
        val description: String,
        val type: String,
        val required: Boolean = true,
    )
}

/** A call the model asked for, as [parseGemmaToolCall] found it. */
data class GemmaToolCall(val name: String, val arguments: Map<String, String>)

/**
 * A prompt segment: tokenised text, or soft tokens for one image or clip.
 *
 * Media sits at the head of the turn that carries it, bracketed by [BOI_MARKER]/[EOI_MARKER]
 * or [BOA_MARKER]/[EOA_MARKER] the way the reference processor's `replace_image_token` and
 * `replace_audio_token` bracket their runs of placeholders.
 */
sealed interface GemmaPart {
    /** How many positions in the context window this part occupies. */
    val positions: Int

    /** Text, tokenised. */
    data class Tokens(val ids: IntArray) : GemmaPart {
        override val positions: Int get() = ids.size

        override fun equals(other: Any?): Boolean =
            this === other || (other is Tokens && ids.contentEquals(other.ids))

        override fun hashCode(): Int = ids.contentHashCode()
    }

    /** One image's soft tokens, `n * 1536`. */
    data class Image(val soft: FloatArray) : GemmaPart {
        override val positions: Int get() = soft.size / GEMMA_SOFT_TOKEN_WIDTH

        override fun equals(other: Any?): Boolean =
            this === other || (other is Image && soft.contentEquals(other.soft))

        override fun hashCode(): Int = soft.contentHashCode()
    }

    /**
     * One clip's soft tokens, `n * 1536`.
     *
     * Distinct from [Image] despite carrying the same shape, because the two are bracketed
     * by different markers and a clip in an image's brackets is a prompt the model was never
     * trained on.
     */
    data class Audio(val soft: FloatArray) : GemmaPart {
        override val positions: Int get() = soft.size / GEMMA_SOFT_TOKEN_WIDTH

        override fun equals(other: Any?): Boolean =
            this === other || (other is Audio && soft.contentEquals(other.soft))

        override fun hashCode(): Int = soft.contentHashCode()
    }
}

/** Reply positions `generate` reserves unless a caller says otherwise. */
const val GEMMA_DEFAULT_REPLY = 512

/** Positions the runtime offers. */
const val GEMMA_MAX_CONTEXT = 16384

/** Channels in one soft token, which is the decoder's `hidden_size`. */
const val GEMMA_SOFT_TOKEN_WIDTH = 1536

/**
 * The most positions a prompt may occupy while still leaving [limit] for the reply.
 *
 * The guard's own bound and the reply reserve are not additive - they compete for the
 * same tail of the window, so the binding constraint is the larger of the two.
 */
fun gemmaPromptCeiling(limit: Int = GEMMA_DEFAULT_REPLY): Int =
    GEMMA_MAX_CONTEXT - maxOf(limit, 3)

/**
 * [parts] with its audio trimmed to at most [budget] positions in total.
 *
 * A clip is trimmed from its end, so the model hears the start of a recording rather
 * than a window out of the middle of it, and earlier clips are served before later ones.
 * A clip trimmed to nothing is dropped. Returns [parts] itself when there is no audio.
 */
fun fitGemmaAudio(parts: List<GemmaPart>, budget: Int): List<GemmaPart> {
    if (parts.none { it is GemmaPart.Audio }) return parts
    var left = budget.coerceAtLeast(0)
    val out = ArrayList<GemmaPart>(parts.size)
    for (part in parts) {
        if (part !is GemmaPart.Audio) {
            out.add(part)
            continue
        }
        val want = part.positions
        val keep = want.coerceAtMost(left)
        left -= keep
        when {
            keep == want -> out.add(part)
            keep > 0 -> out.add(GemmaPart.Audio(part.soft.copyOf(keep * GEMMA_SOFT_TOKEN_WIDTH)))
        }
    }
    return out
}

/** The marker that opens an image, `boi_token` in the checkpoint's tokenizer config. */
const val GEMMA_BOI_MARKER = "<|image>"

/** The marker that closes an image, `eoi_token`. */
const val GEMMA_EOI_MARKER = "<image|>"

/** The marker that opens a clip, `boa_token`, id 256000. */
const val GEMMA_BOA_MARKER = "<|audio>"

/** The marker that closes a clip, `eoa_token`. */
const val GEMMA_EOA_MARKER = "<audio|>"

/** Ids that end a reply: `<eos>` 1, `<turn|>` 106, double-newline 50. */
val GEMMA_STOP = intArrayOf(1, 106, 50)

/**
 * The markers prompt encoding honours, and which user text therefore cannot spell.
 *
 * **Every marker the renderer emits must be in here.** A marker that is emitted but not
 * declared encodes as literal characters instead of its own id, which is not an error
 * anywhere - and the decoder simply never sees the delimiter it was trained on.
 */
val GEMMA_MARKERS = arrayOf(
    "<bos>", "<eos>", "<|turn>", "<turn|>",
    "<|tool>", "<tool|>", "<|tool_call>", "<tool_call|>",
    "<|tool_response>", "<tool_response|>", "<|\"|>",
    GEMMA_BOI_MARKER, GEMMA_EOI_MARKER, GEMMA_BOA_MARKER, GEMMA_EOA_MARKER,
)

/**
 * A conversation as the prompt string Gemma was trained on.
 *
 * Ported from `chat_template.jinja` by rendering it and matching the output exactly:
 * `<bos><|turn>system\n{system}{tools}<turn|>\n<|turn>user\n{text}<turn|>\n<|turn>model\n`.
 * The system turn is emitted only when there is a system prompt or a tool to declare.
 */
fun renderGemmaPrompt(
    conversation: List<GemmaTurn>,
    system: String?,
    tools: List<GemmaToolDeclaration> = emptyList(),
    continuation: String = "",
): String = buildString {
    append("<bos>")
    val declared = declareGemmaTools(tools)
    if (!system.isNullOrBlank() || declared.isNotEmpty()) {
        append("<|turn>system\n")
        if (!system.isNullOrBlank()) append(system)
        append(declared)
        append("<turn|>\n")
    }
    for (turn in conversation) {
        append("<|turn>").append(turn.role.marker).append('\n')
        append(turn.text)
        append("<turn|>\n")
    }
    // A tool call and its result belong **inside** the open model turn, not before it.
    append("<|turn>model\n")
    append(continuation)
}

/**
 * Tool declarations in the template's own syntax: `<|tool>declaration:name{...}<tool|>`
 * with values wrapped in the `<|"|>` marker, parameters in alphabetical order.
 */
fun declareGemmaTools(tools: List<GemmaToolDeclaration>): String = buildString {
    for (tool in tools) {
        append("<|tool>declaration:").append(tool.name).append('{')
        append("description:").append(quotedGemma(tool.description))
        append(",parameters:{properties:{")
        val sorted = tool.parameters.sortedBy { it.name }
        for ((index, parameter) in sorted.withIndex()) {
            if (index > 0) append(',')
            append(parameter.name).append(":{description:")
            append(quotedGemma(parameter.description))
            append(",type:").append(quotedGemma(parameter.type.uppercase()))
            append('}')
        }
        append("}")
        val required = sorted.filter { it.required }
        if (required.isNotEmpty()) {
            append(",required:[")
            append(required.joinToString(",") { quotedGemma(it.name) })
            append(']')
        }
        append(",type:").append(quotedGemma("OBJECT"))
        append("}}<tool|>")
    }
}

/**
 * The tool call in [reply], or null if there is not a complete one.
 *
 * The model emits `<|tool_call>call:name{arg:<|"|>value<|"|>}<tool_call|>`. Returns null
 * until the closing marker is present, so a half-written call during streaming is never
 * acted on.
 */
fun parseGemmaToolCall(reply: String): GemmaToolCall? {
    val open = reply.indexOf("<|tool_call>call:")
    if (open < 0) return null
    val close = reply.indexOf("<tool_call|>", open)
    if (close < 0) return null
    val body = reply.substring(open + "<|tool_call>call:".length, close)
    val brace = body.indexOf('{')
    if (brace < 0) return null
    val name = body.substring(0, brace).trim()
    if (name.isEmpty()) return null
    val arguments = LinkedHashMap<String, String>()
    var at = brace + 1
    while (at < body.length) {
        val colon = body.indexOf(':', at)
        if (colon < 0) break
        val key = body.substring(at, colon).trim().trim(',', '{', '}')
        val valueStart = body.indexOf(GEMMA_QUOTE, colon)
        if (valueStart < 0) break
        val valueEnd = body.indexOf(GEMMA_QUOTE, valueStart + GEMMA_QUOTE.length)
        if (valueEnd < 0) break
        if (key.isNotEmpty()) {
            arguments[key] = body.substring(valueStart + GEMMA_QUOTE.length, valueEnd)
        }
        at = valueEnd + GEMMA_QUOTE.length
        if (at < body.length && body[at] == ',') at++
    }
    return GemmaToolCall(name, arguments)
}

/** A tool's result, in the shape the model expects to read back. */
fun renderGemmaToolResponse(name: String, value: String): String =
    "<|tool_response>response:$name{value:${quotedGemma(value)}}<tool_response|>"

/** Gemma's value delimiter, which is a token rather than a quotation mark. */
internal const val GEMMA_QUOTE = "<|\"|>"

internal fun quotedGemma(value: String): String = GEMMA_QUOTE + value + GEMMA_QUOTE
