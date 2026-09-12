package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.AudioGain
import com.vayunmathur.auto.protocol.AudioSinkRole

/** Where a sink is in its bring-up. */
enum class AudioSinkState {
    /** Channel granted but setup not yet answered, or never attempted. */
    IDLE,

    /** Setup sent; waiting on the head unit's 0x8003 config answer. */
    SETUP,

    /** Streaming: start sent with a confirmed config. */
    STARTED,

    /** Torn down with the session. */
    STOPPED,

    /** Discovery cannot carry PCM (non-PCM codec, no sane config): never started. */
    UNSUPPORTED,
}

/**
 * Phone-visible snapshot of one audio sink: which role, its bring-up state,
 * and the current arbitrated gain. Published through [AudioEvent.SinkStatus]
 * on every transition and every focus change, so the session card reads one
 * source of truth instead of polling the channel.
 */
data class AudioSinkStatus(
    val role: AudioSinkRole,
    val state: AudioSinkState,
    val gain: AudioGain,
)
