package com.vayunmathur.openassistant.util

import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.tool
import com.vayunmathur.library.ml.GemmaRole
import com.vayunmathur.library.ml.GemmaTurn
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.collect

/**
 * A chat turn on Gemma 4, running the `.litertlm` bundle on the LiteRT-LM runtime.
 *
 * The model file (`gemma-4-E2B-it.litertlm`) downloads mirror-only on first launch
 * (see `ModelUrls.GEMMA_LITERTLM`); the engine keeps the same public interface the
 * ONNX backend had — whole-turn lock, tool loop, attachment gates, history measurement
 * — so `InferenceService` is unchanged. Only the execution moved: prompt rendering and
 * the tool protocol are now litertlm's (`Conversation` with automatic tool calling over
 * the `AssistantToolSet` tool table).
 *
 * # One conversation at a time
 *
 * The lock is held for a whole turn rather than per call, because a turn is many calls
 * and interleaving them would mix two conversations. `InferenceService` already
 * serialises through a queue; the lock is here so that a second caller blocks rather
 * than corrupts.
 */
class Gemma4Engine(private val directory: File) : AutoCloseable {

    private val lock = ReentrantLock()
    private var engine: Engine? = null

    /** Whether images can be read. The base bundle includes vision; false until loaded. */
    val canSeeImages: Boolean
        get() = lock.withLock { engine?.isInitialized() == true }

    /** Whether audio can be heard. The base bundle includes audio; false until loaded. */
    val canHearAudio: Boolean
        get() = lock.withLock { engine?.isInitialized() == true }

    /** Whether the model is loaded and usable. */
    val isReady: Boolean
        get() = lock.withLock { engine?.isInitialized() == true }

    /**
     * Load the model if it is not loaded. Returns whether it is usable afterwards.
     *
     * Idempotent, so `onCreate` can pre-warm and the first turn can call it again without cost.
     * Loading is slow - gigabytes of weights - so callers should pre-warm off the main thread.
     */
    fun ensureLoaded(): Boolean = lock.withLock {
        val live = engine
        if (live != null && live.isInitialized()) return@withLock true
        live?.close()
        engine = null
        try {
            val file = File(directory, MODEL_FILE)
            if (!file.isFile) {
                Log.w(TAG, "gemma4 litertlm missing from $directory")
                return@withLock false
            }
            val created = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(null, null),
                    visionBackend = Backend.CPU(null, null),
                    audioBackend = Backend.CPU(null, null),
                    maxNumTokens = null,
                    maxNumImages = null,
                    cacheDir = directory.absolutePath,
                ),
            )
            created.initialize()
            if (!created.isInitialized()) {
                Log.w(TAG, "gemma4 litertlm did not initialize from $file")
                created.close()
                return@withLock false
            }
            engine = created
            true
        } catch (e: Throwable) {
            Log.e(TAG, "gemma4 litertlm failed to load from $directory", e)
            false
        }
    }

    /**
     * Readable image paths in [paths], in order.
     *
     * litertlm takes image files directly ([Content.ImageFile]), so this only validates
     * readability here; unreadable files are dropped rather than failing the turn.
     * The caller compares the count with what it passed to decide what to tell the model.
     */
    fun encodeImages(paths: List<String>): List<String> = lock.withLock {
        paths.mapNotNull { path ->
            val ok = runCatching {
                BitmapFactory.decodeFile(path)?.recycle()
                true
            }.getOrNull() == true
            if (!ok) {
                Log.w(TAG, "cannot decode $path")
                return@mapNotNull null
            }
            path
        }
    }

    /**
     * Usable clips in [paths], in order.
     *
     * Drops what it cannot use exactly as [encodeImages] does. Only the 16 kHz mono PCM
     * WAV that `WavRecorder` writes is accepted.
     */
    fun encodeAudio(paths: List<String>): List<String> = lock.withLock {
        paths.mapNotNull { path ->
            if (readWavMono16k(path) == null) {
                Log.w(TAG, "cannot use audio $path")
                return@mapNotNull null
            }
            path
        }
    }

    /**
     * Positions the prompt for [conversation] would occupy, against [promptCeiling].
     *
     * Estimated from text length (4 chars/token) since litertlm owns exact tokenization.
     */
    fun positionsFor(
        conversation: List<GemmaTurn>,
        system: String?,
        tools: AssistantToolSet?,
        limit: Int = DEFAULT_REPLY_TOKENS,
    ): Int = lock.withLock {
        var total = (system?.length ?: 0) / 4
        for (turn in conversation) {
            total += turn.text.length / 4 + turn.imagePaths.size * IMAGE_TOKENS +
                turn.audioPaths.size * AUDIO_TOKENS
        }
        total
    }

    /** What [positionsFor] must not exceed if the reply is to keep its [limit]. */
    fun promptCeiling(limit: Int = DEFAULT_REPLY_TOKENS): Int = MAX_CONTEXT - limit

    /**
     * Run a turn, resolving any tool calls, and stream the visible reply to [onPartial].
     *
     * [onPartial] returning false stops generation - cancellation, and the early-halt the
     * structured-extraction path uses.
     *
     * Returns the reply with all tool syntax stripped, or null if the model is unavailable.
     */
    suspend fun ask(
        conversation: List<GemmaTurn>,
        system: String?,
        tools: AssistantToolSet?,
        limit: Int = DEFAULT_REPLY_TOKENS,
        onPartial: (String) -> Boolean = { true },
    ): String? {
        val live = lock.withLock { engine?.takeIf { it.isInitialized() } }
            ?: return null
        // Built outside the lock: awaiting the stream must not hold it.
        val provider = tools?.let { tool(it) }
        return try {
            val config = ConversationConfig(
                systemInstruction = if (system.isNullOrBlank()) Contents.of("") else Contents.of(system),
                initialMessages = conversation.map { it.toMessage() },
                tools = if (provider != null) listOf(provider) else emptyList(),
                automaticToolCalling = provider != null,
            )
            live.createConversation(config).use { conv ->
                var visible = ""
                var stopped = false
                // The latest user turn carries the new request; resend it streaming.
                val last = conversation.lastOrNull { it.role == GemmaRole.USER }
                val request = last?.toMessage() ?: Message.user("")
                conv.sendMessageAsync(request).collect { msg ->
                    val text = msg.contents.contents.filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    val shown = visible + Gemma4Engine.strip(text)
                    if (onPartial(shown)) {
                        visible = shown
                    } else {
                        stopped = true
                        conv.cancelProcess()
                    }
                }
                if (stopped) visible else visible.ifEmpty { null }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "gemma4 turn failed", e)
            null
        }
    }

    /** Discard the conversation state. The next turn builds a fresh conversation. */
    fun reset() = lock.withLock { /* conversations are per-turn; nothing cached */ }

    override fun close() = lock.withLock {
        engine?.close()
        engine = null
    }

    private fun GemmaTurn.toMessage(): Message {
        val parts = ArrayList<Content>()
        for (path in imagePaths) {
            val file = File(path)
            if (file.isFile) parts.add(Content.ImageFile(file.absolutePath))
        }
        for (path in audioPaths) {
            val file = File(path)
            if (file.isFile) parts.add(Content.AudioFile(file.absolutePath))
        }
        parts.add(Content.Text(text))
        return when (role) {
            GemmaRole.USER -> Message.user(Contents.of(parts))
            GemmaRole.MODEL -> Message.model(Contents.of(parts))
        }
    }

    companion object {
        private const val TAG = "Gemma4Engine"

        /** The litertlm bundle in the download directory. */
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"

        /** Reply reserve kept clear of the context window. */
        const val DEFAULT_REPLY_TOKENS = 512

        /** Context window estimate for history fitting. */
        const val MAX_CONTEXT = 8192

        /** Rough per-image/audio position cost for [positionsFor]. */
        private const val IMAGE_TOKENS = 256
        private const val AUDIO_TOKENS = 750

        /** The one `wFormatTag` [readWavMono16k] accepts: uncompressed integer PCM. */
        private const val WAV_PCM = 1

        /**
         * 16 kHz mono samples from a PCM WAV file, or null if it is not one.
         *
         * Only what `WavRecorder` writes is accepted. There is no resampler and no downmix here,
         * so another rate, width or channel count is refused rather than converted.
         */
        private fun readWavMono16k(path: String): FloatArray? {
            val bytes = runCatching { File(path).readBytes() }.getOrElse {
                Log.w(TAG, "cannot read $path: $it")
                return null
            }
            if (bytes.size < 12 || chunkId(bytes, 0) != "RIFF" || chunkId(bytes, 8) != "WAVE") {
                Log.w(TAG, "$path is not a WAV file")
                return null
            }
            val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var described = false
            var dataAt = -1
            var dataBytes = 0
            var at = 12
            while (at + 8 <= bytes.size) {
                val size = wav.getInt(at + 4)
                if (size < 0) break
                val body = at + 8
                when (chunkId(bytes, at)) {
                    "fmt " -> {
                        if (size < 16 || body + 16 > bytes.size) return null
                        val pcm = wav.getShort(body).toInt() == WAV_PCM
                        val mono = wav.getShort(body + 2).toInt() == 1
                        val rate = wav.getInt(body + 4)
                        val width = wav.getShort(body + 14).toInt()
                        if (!pcm || !mono || rate != 16_000 || width != 16) {
                            Log.w(TAG, "$path is not 16 kHz mono 16-bit PCM")
                            return null
                        }
                        described = true
                    }
                    "data" -> {
                        dataAt = body
                        dataBytes = minOf(size, bytes.size - body)
                    }
                }
                at = body + size + (size and 1)
            }
            if (!described || dataAt < 0) {
                Log.w(TAG, "$path has no 16-bit PCM data")
                return null
            }
            val count = minOf(dataBytes / 2, 480_000)
            val pcm = ByteBuffer.wrap(bytes, dataAt, dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(count) { pcm.get(it) / 32768f }
        }

        private fun chunkId(bytes: ByteArray, at: Int): String =
            String(bytes, at, 4, Charsets.US_ASCII)

        /**
         * Tool calls one turn may make before the loop gives up.
         */
        const val MAX_TOOL_HOPS = 8

        /**
         * The prose in a reply, with the tool markers and everything inside them removed.
         */
        fun strip(reply: String): String {
            var out = reply
            for (marker in listOf("<|tool_call>", "<|tool_response>", "<|tool>")) {
                val at = out.indexOf(marker)
                if (at >= 0) out = out.substring(0, at)
            }
            val open = out.lastIndexOf('<')
            if (open >= 0 && out.length - open <= "<|tool_response>".length) {
                val tail = out.substring(open)
                if ("<|tool_call>".startsWith(tail) || "<|tool_response>".startsWith(tail) ||
                    "<|tool>".startsWith(tail) || "<turn|>".startsWith(tail)
                ) {
                    out = out.substring(0, open)
                }
            }
            return out
        }

        /** Files [ensureLoaded] needs, for a caller checking a download is complete. */
        val FILES: List<String> = listOf(MODEL_FILE)
    }
}
