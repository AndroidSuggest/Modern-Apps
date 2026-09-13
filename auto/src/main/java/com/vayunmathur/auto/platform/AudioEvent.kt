package com.vayunmathur.auto.platform

/**
 * Something an audio sink or the mic source observed, forwarded to
 * [AutoSessionState] for the phone UI.
 *
 * Streaming never waits on these: they are fire-and-forget observations,
 * mirroring [VideoEvent] and [InputEvent]. The phone status screen counts
 * bytes, syncs, turns and TTS so the operator can tell "channel alive" from
 * "silent" without a live run.
 */
sealed interface AudioEvent {
    /** A sink claimed its channel; [detail] names the role and config. */
    data class SinkSetup(val role: String, val detail: String) : AudioEvent

    /** A sink changed bring-up state or arbitrated gain; the session card reads these. */
    data class SinkStatus(val status: AudioSinkStatus) : AudioEvent

    /** A sink started streaming; [configIndex] is the confirmed discovery index. */
    data class SinkStarted(val role: String, val configIndex: Int) : AudioEvent

    /** A sink stopped; the session is tearing down or focus was lost for good. */
    data class SinkStopped(val role: String) : AudioEvent

    /** PCM went out on [role]; [bytes] is the framed payload size. */
    data class FramesSent(val role: String, val bytes: Long) : AudioEvent

    /** The head unit answered a sink with the no-payload 0x800B sync pulse. */
    data class SyncReceived(val role: String) : AudioEvent

    /** The head unit acked sink frames; [ackSeq] is the mod-256 counter, unsigned. */
    data class AckReceived(val role: String, val ackSeq: Long?) : AudioEvent

    /** One TTS utterance reached the car; [chars] is its PCM sample count. */
    data class TtsSpoken(val chars: Int) : AudioEvent

    /** A TTS utterance was dropped; [reason] is short and human-readable. */
    data class TtsDropped(val reason: String) : AudioEvent

    /** One mic turn completed; [bytes] is buffered PCM, zero when unretained. */
    data class MicTurn(val bytes: Long) : AudioEvent

    /** One mic chunk was acked upstream. */
    data object MicAcked : AudioEvent

    /** Mic audio arrived but retention is off, so only the ack went out. */
    data object MicIdle : AudioEvent

    /** Captured music PCM went to the media sink; [bytes] is the chunk size. */
    data class MusicCaptured(val bytes: Long) : AudioEvent

    /** A music chunk was dropped (no sink, not started); [reason] is short. */
    data class MusicDropped(val reason: String) : AudioEvent
}
