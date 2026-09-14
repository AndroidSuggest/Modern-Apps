package com.vayunmathur.library.ml

/**
 * Google's always-on music gate.
 *
 * The cheap half of Now Playing. Feed it microphone PCM and it answers "is this music?"
 * once every 10 ms, from 8,200 int8 parameters. [NnfpHandle] is the other half - the
 * expensive fingerprinter you only run once this has said yes - and the two are different
 * networks with different shapes, not two entry points to one model.
 *
 * # Backend deferred
 *
 * The gate ran on the CPU half of the deleted Vulkan crate (`gate.rs`). Until it is ported
 * (pure integer arithmetic — no model file, no GPU), this handle is always unavailable:
 * construction succeeds, [isAvailable] is false, and [push] returns null so callers degrade.
 * The API is unchanged so the port drops in without touching callers.
 *
 * # Streaming, and the warm-up
 *
 * Stateful: six four-deep circular buffers carry 230 ms of context between calls, so
 * scores depend on everything pushed since the last [reset]. The first ~23 hops after a
 * reset are computed from cold buffers, so they are withheld rather than reported - a
 * [push] can legitimately return nothing at all. A caller starting a fresh listening
 * session must [reset] or its first scores describe the previous session's audio.
 *
 * # Not thread-safe
 *
 * One [push] at a time, and no [push] concurrent with [close]. Hold a lock across both.
 */
class MusicGate private constructor() : AutoCloseable {
    /** Always false until the CPU port lands. See the class docs. */
    val isAvailable: Boolean get() = false

    /**
     * Feed [count] samples from the front of [pcm] and collect one probability in `0f..1f`
     * per completed hop, oldest first.
     *
     * Currently always null: the backend is deferred.
     */
    fun push(pcm: ShortArray, count: Int = pcm.size): FloatArray? {
        if (count < 0 || count > pcm.size) return null
        return null
    }

    /** Discard the streaming state so the next [push] starts a fresh session. */
    fun reset() {
        // Nothing held.
    }

    /** Free the gate. Idempotent. */
    override fun close() {
        // Nothing held.
    }

    override fun toString(): String = "Now Playing music gate (CPU)"

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 10 ms. One probability comes out per hop. */
        const val HOP_SAMPLES = 160

        /** The front end's analysis window. The first hop needs this many samples. */
        const val FRAME_SAMPLES = 400

        /** Hops of context behind a reported score: `1 + 6*(4-1) + (5-1)`, so 230 ms. */
        const val RECEPTIVE_FIELD_HOPS = 23

        /** Samples after a [reset] before the first probability appears. */
        const val WARMUP_SAMPLES = FRAME_SAMPLES + HOP_SAMPLES * (RECEPTIVE_FIELD_HOPS - 1)

        /**
         * Build a gate. Never throws; check [isAvailable].
         *
         * Named for the fact that there is nothing to open - no asset, no descriptor - to
         * distinguish it from [NnfpHandle.inAssets], which does have a file behind it.
         */
        fun inProcess(): MusicGate = MusicGate()
    }
}
