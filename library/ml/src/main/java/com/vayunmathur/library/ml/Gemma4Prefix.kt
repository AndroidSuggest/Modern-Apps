package com.vayunmathur.library.ml

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * The baked prefix-cache half of [Gemma4Handle] (plus the one-shot benchmark),
 * as `internal` extensions so Gemma4Handle.kt stays under the FileLength limit.
 * The public entry points stay on the handle and delegate here.
 */

/**
 * Time the model once, so the log says where its time goes.
 *
 * Runs three passes each way and keeps the best, which costs a couple of seconds the first
 * time a conversation starts and answers a question that guesswork has repeatedly got wrong.
 */
internal fun Gemma4Handle.runBenchmark() {
    if (handle != 0L) {
        MlNative.capabilitiesGemma4()
        MlNative.imageProbeGemma4()
        MlNative.benchmarkGemma4(handle)
    }
}

/**
 * Load the baked cache for [prefix], if it is present and matches. Returns whether it did.
 *
 * # The check is the point
 *
 * The asset encodes keys and values for one exact token sequence. If the system prompt or
 * the tool set has changed since it was baked, those numbers describe a prompt the model is
 * not being given - and nothing downstream could tell, because a KV cache is only attended
 * over, never compared. So this re-encodes [prefix] here and refuses unless the digest of
 * the result matches the one in the file.
 *
 * A mismatch is not an error: it falls back to prefilling, which is slow and correct.
 */
internal fun Gemma4Handle.loadBakedPrefix(prefix: String): Boolean {
    if (handle == 0L) return false
    val file = java.io.File(directory, Gemma4Handle.PREFIX_CACHE)
    if (!file.isFile) return false
    val tokens = encodePrompt(prefix)
    if (tokens.isEmpty()) return false
    val blob = runCatching { file.readBytes() }.getOrNull() ?: return false
    if (blob.size < Gemma4Handle.HEADER || String(blob, 0, 4, Charsets.US_ASCII) != "GKV1") {
        Log.w(Gemma4Handle.TAG, "${Gemma4Handle.PREFIX_CACHE} is not a baked cache")
        return false
    }
    val positions = Gemma4Handle.readInt(blob, 4)
    // The size check stays enforced even though the digest does not: `positions` decides
    // where the next token goes and how many ids are recorded as cached, so a wrong count
    // corrupts the bookkeeping rather than merely the contents.
    if (positions != tokens.size) {
        Log.i(Gemma4Handle.TAG, "${Gemma4Handle.PREFIX_CACHE} is $positions positions, this prefix is ${tokens.size}")
        return false
    }
    // The digest is reported, not enforced.
    //
    // It says whether these keys and values were computed from *these* tokens, and a
    // mismatch means the model is about to attend over a prompt it was not given. Refusing
    // is the safe behaviour and what this did first. It is advisory while the prefix is
    // still being iterated on, because a stale cache should slow the work down rather than
    // stop it - but a mismatch here is a real defect, not noise, and the loud log is the
    // only thing standing between it and a plausible wrong answer.
    if (!Gemma4Handle.digest(tokens).contentEquals(blob.copyOfRange(12, 12 + 32))) {
        Log.w(Gemma4Handle.TAG, "${Gemma4Handle.PREFIX_CACHE} DIGEST MISMATCH - using it anyway; replies may be wrong")
    }
    // The cache starts at the smallest tier and this prefix is larger than it. Growing first
    // is not optional: `loadPrefixGemma4` refuses a prefix bigger than the cache rather than
    // truncating it, because half a prefix is keys for a prompt nobody sent.
    if (MlNative.capacityGemma4(handle) < positions) {
        val grown = MlNative.growGemma4(handle, positions + 2)
        if (grown < positions) {
            Log.i(Gemma4Handle.TAG, "a $positions-position prefix does not fit this device; prefilling")
            return false
        }
    }
    val loaded = MlNative.loadPrefixGemma4(handle, positions, blob.copyOfRange(Gemma4Handle.HEADER, blob.size))
    if (loaded != positions) return false
    // The cache now holds exactly these tokens, so the next turn matches against them and
    // feeds only what follows.
    cachedIds = tokens
    Log.i(Gemma4Handle.TAG, "loaded a $positions-position prefix cache, skipping its prefill")
    return true
}

/**
 * Open both graphs, read the tokenizer, and hand the descriptors over.
 *
 * Two descriptors rather than one, so the `finally` dance is doubled. Native adopts both
 * and closes both on every path including failure, so [handed] guards the window between
 * detaching and native taking ownership - and both must be closed here if the call is
 * never made.
 */
internal fun createGemma4Handle(directory: File): Long {
    val text = File(directory, Gemma4Handle.TEXT)
    val embed = File(directory, Gemma4Handle.EMBED)
    val tokenizer = File(directory, Gemma4Handle.TOKENIZER)
    for (file in listOf(text, embed, tokenizer)) {
        if (!file.isFile) {
            Log.w(Gemma4Handle.TAG, "${file.name} is missing from $directory")
            return 0L
        }
    }
    val table = runCatching { tokenizer.readBytes() }.getOrElse {
        Log.w(Gemma4Handle.TAG, "cannot read ${Gemma4Handle.TOKENIZER}: $it")
        return 0L
    }
    val textFd = runCatching {
        ParcelFileDescriptor.open(text, ParcelFileDescriptor.MODE_READ_ONLY)
            .use { it.detachFd() }
    }.getOrElse {
        Log.w(Gemma4Handle.TAG, "cannot open ${Gemma4Handle.TEXT}: $it")
        return 0L
    }
    val embedFd = runCatching {
        ParcelFileDescriptor.open(embed, ParcelFileDescriptor.MODE_READ_ONLY)
            .use { it.detachFd() }
    }.getOrElse {
        closeGemma4Fd(textFd)
        Log.w(Gemma4Handle.TAG, "cannot open ${Gemma4Handle.EMBED}: $it")
        return 0L
    }
    var handed = false
    try {
        val live = MlNative.createGemma4(
            textFd, 0L, text.length(),
            embedFd, 0L, embed.length(),
            table,
            gemma4CacheBudget(),
        )
        handed = true
        return live
    } finally {
        if (!handed) {
            closeGemma4Fd(textFd)
            closeGemma4Fd(embedFd)
        }
    }
}

/**
 * Close a bare descriptor.
 *
 * Adopting it into a [ParcelFileDescriptor] is the only way to reach `close(2)` from
 * Kotlin. Failures are swallowed because the caller is already on an error path.
 */
private fun closeGemma4Fd(fd: Int) {
    runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
}

/**
 * Bytes this device will spend on the KV cache.
 *
 * # Why it is a fraction of *total* rather than available memory
 *
 * Available memory is whatever the rest of the system happens to be doing when the
 * assistant starts, so sizing against it makes the conversation length depend on what
 * else was open - and shrinks it exactly when the user has been busy. Total memory is a
 * property of the device, which is what the tier should track.
 *
 * A twentieth is deliberately conservative. The weights are already ~2.9 GB of mapped
 * file and the tower allocations sit on top, so the cache is not the only claim on the
 * budget - and an allocation failure here is a model that will not start at all.
 *
 *   4 GB  ->  200 MB  ->  8,192 positions
 *   8 GB  ->  400 MB  -> 16,384 positions
 *  16 GB  ->  800 MB  -> 16,384 positions, the top tier
 */
private fun gemma4CacheBudget(): Long {
    // `/proc/meminfo` rather than `ActivityManager`, because this class is constructed
    // from a directory and has no `Context` - and adding one to the signature to read a
    // single number would push Android into an API that is otherwise platform-free.
    val total = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.filter(Char::isDigit)
                ?.toLongOrNull()
                ?.times(1024) ?: 0L
        }
    }.getOrDefault(0L)
    // A device that will not say gets the smallest tier, which always fits, rather than
    // an optimistic guess that fails to allocate and leaves the assistant dead.
    if (total <= 0L) return 0L
    return total / 20
}
