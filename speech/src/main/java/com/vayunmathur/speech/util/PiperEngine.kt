package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.ncnn.Vits
import java.io.File

/**
 * Wrapper around the ncnn AAR's [Vits] running offline **Piper (VITS)** voices.
 *
 * The original implementation held a single [Vits] instance (en-US Amy medium,
 * 22050 Hz) with a `loadFailed` latch. After the multilingual expansion we keep a
 * bounded LRU cache (max 2 voices, ~50-100 MB per instance on arm64-v8a) keyed by
 * [PiperVoiceDef.id] so switching languages doesn't pay a cold-load each time and
 * RAM stays bounded.
 *
 * Not thread-safe beyond the `@Synchronized` methods; the TTS framework calls
 * [com.vayunmathur.speech.service.PiperTtsService.onSynthesizeText] serially, so
 * single-thread contract holds.
 *
 * Backward-compat overloads without a voice id still work, delegating to the default
 * English voice (`en_US-amy-medium`) for existing callers.
 */
class PiperEngine(private val context: Context) {

    /** LRU cache: voice id -> loaded Vits, access-order for eviction. */
    private val cache = LinkedHashMap<String, Vits>(2, 0.75f, true)
    private val failed = mutableMapOf<String, Boolean>()

    /** Load now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = ensure()

    /** Load a specific language/code/id, e.g. "de" or "de-DE" or "en_US-amy-medium". */
    fun preload(code: String): Boolean = ensure(code)

    /** Warm only the default voice to avoid RAM blow (per plan). */
    fun preloadAllInstalled(): Boolean = ensure(PiperVoiceRegistry.DEFAULT.id)

    /** Native sample rate of the default loaded voice (Hz); 0 if not loaded. */
    fun sampleRate(): Int {
        val defId = PiperVoiceRegistry.DEFAULT.id
        return cache[defId]?.sampleRate() ?: cache.values.firstOrNull()?.sampleRate() ?: 0
    }

    /** Sample rate of a specific loaded voice, 0 if not in cache. */
    fun sampleRate(voiceIdOrCode: String): Int {
        val def = resolveDef(voiceIdOrCode) ?: return 0
        return cache[def.id]?.sampleRate() ?: 0
    }

    /** Backward-compat: synthesize with default voice. */
    @Synchronized
    fun synthesize(text: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean =
        synthesize(text, PiperVoiceRegistry.DEFAULT.id, speed, onChunk)

    /**
     * Synthesize [text] into PCM float chunks using [voiceIdOrCode] (code, BCP-47,
     * ISO3, or full id). [onChunk] receives each chunk and returns false to abort.
     */
    @Synchronized
    fun synthesize(text: String, voiceIdOrCode: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean {
        if (!ensure(voiceIdOrCode)) return false
        val def = resolveDef(voiceIdOrCode) ?: return false
        val engine = cache[def.id] ?: return false
        return try {
            // speakerId from def (0 for single-speaker, >0 for multi)
            val samples = engine.generate(text, def.speakerId, speed)
            if (samples.isEmpty()) return true
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 4096, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                if (!onChunk(chunk)) return false
                offset = end
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed for ${def.id}", t)
            false
        }
    }

    @Synchronized
    fun close() {
        closeAll()
    }

    @Synchronized
    fun close(voiceIdOrCode: String) {
        val def = resolveDef(voiceIdOrCode) ?: run {
            // If voiceIdOrCode is raw id not resolvable, try direct cache remove
            cache[voiceIdOrCode]?.let { v ->
                try { v.close() } catch (_: Throwable) {}
                cache.remove(voiceIdOrCode)
            }
            return
        }
        try {
            cache[def.id]?.close()
        } catch (_: Throwable) {
        }
        cache.remove(def.id)
    }

    @Synchronized
    fun closeAll() {
        for ((_, v) in cache) {
            try {
                v.close()
            } catch (_: Throwable) {
            }
        }
        cache.clear()
    }

    @Synchronized
    private fun ensure(): Boolean = ensure(PiperVoiceRegistry.DEFAULT.id)

    @Synchronized
    private fun ensure(voiceIdOrCode: String): Boolean {
        val def = resolveDef(voiceIdOrCode) ?: run {
            Log.d(TAG, "resolve failed for $voiceIdOrCode")
            return false
        }
        val id = def.id
        if (failed[id] == true) return false

        // Cache hit — LinkedHashMap accessOrder moves it to MRU on get.
        if (cache.containsKey(id)) {
            // Touch to update LRU order
            cache[id]?.let { v ->
                // Re-insert to enforce access order if needed (get already does)
                cache[id] = v
            }
            return true
        }

        // Check extraction; for default en also accept legacy dir via PiperModel shim.
        val extracted = PiperVoiceRegistry.isExtracted(context, def) ||
            (def.code == "en" && PiperModel.isExtracted(context))
        if (!extracted) return false

        return try {
            PiperVoiceRegistry.migrateLegacyIfNeeded(context)
            val dir = voiceDirForDef(def)
            val engine = Vits(dir.absolutePath)
            if (!engine.isAvailable) {
                Log.e(TAG, "Vits could not load the voice in $dir for $id")
                try { engine.close() } catch (_: Throwable) {}
                failed[id] = true
                return false
            }
            // Evict LRU if at capacity
            if (cache.size >= MAX_CACHED) {
                val eldestKey = cache.keys.firstOrNull()
                eldestKey?.let { key ->
                    try { cache[key]?.close() } catch (_: Throwable) {}
                    cache.remove(key)
                    Log.d(TAG, "evicted $key (LRU)")
                }
            }
            cache[id] = engine
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Piper load failed for $id", t)
            failed[id] = true
            false
        }
    }

    /** Resolve a def from code, BCP-47, ISO3, id, or voice name extension. */
    private fun resolveDef(voiceIdOrCode: String): PiperVoiceDef? {
        if (voiceIdOrCode.isBlank()) return PiperVoiceRegistry.DEFAULT
        // Use registry's smart resolver first
        PiperVoiceRegistry.resolve(
            lang = voiceIdOrCode,
            voiceName = voiceIdOrCode,
        )?.let { return it }

        // Direct lookups as fallback
        PiperVoiceRegistry.byId(voiceIdOrCode)?.let { return it }
        PiperVoiceRegistry.byCode(voiceIdOrCode)?.let { return it }
        PiperVoiceRegistry.byBcp47(voiceIdOrCode)?.let { return it }
        PiperVoiceRegistry.byBcp47(voiceIdOrCode.replace('_', '-'))?.let { return it }
        PiperVoiceRegistry.byIso3(voiceIdOrCode)?.let { return it }

        // Legacy: "en" prefix heuristics handled by registry.resolve already; try 2-char prefix
        if (voiceIdOrCode.length >= 2) {
            PiperVoiceRegistry.byCode(voiceIdOrCode.take(2))?.let { return it }
        }

        return null
    }

    private fun voiceDirForDef(def: PiperVoiceDef): File {
        val newDir = PiperVoiceRegistry.voiceDir(context, def)
        if (newDir.isDirectory) return newDir
        if (def.code == "en") {
            val legacy = PiperVoiceRegistry.legacyVoiceDir(context)
            if (legacy.isDirectory) return legacy
            // Also check PiperModel's view
            val shim = PiperModel.voiceDir(context)
            if (shim.isDirectory) return shim
        }
        return newDir
    }

    companion object {
        private const val TAG = "PiperEngine"
        private const val MAX_CACHED = 2
    }
}
