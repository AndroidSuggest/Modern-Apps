package com.vayunmathur.auto.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * What the phone status screen shows, driven by the projection session.
 *
 * The session runs on its own worker thread and publishes to [AutoSessionState]; this
 * ViewModel only re-exposes those flows on a scope the UI can collect. It never writes.
 */
class AutoViewModel : ViewModel() {
    val connection: StateFlow<AutoConnectionState> = AutoSessionState.connection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AutoConnectionState.Disconnected)

    val video: StateFlow<VideoInfo?> = AutoSessionState.video
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val focusMode: StateFlow<String?> = AutoSessionState.focusMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val framesSent: StateFlow<Long> = AutoSessionState.framesSent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    val acksSeen: StateFlow<Long> = AutoSessionState.acksSeen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    val ackMismatches: StateFlow<Long> = AutoSessionState.ackMismatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** Encode-side frames per second over a sliding window; never decoded/rendered. */
    val encodedFps: StateFlow<Double> = AutoSessionState.encodedFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0.0)

    /** Head-unit acks per second over a sliding window; receipt, not visibility. */
    val ackFps: StateFlow<Double> = AutoSessionState.ackFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0.0)

    /** Millis since the last 0x8004 ack; null before the first. */
    val lastAckAgeMs: StateFlow<Long?> = AutoSessionState.lastAckAgeMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Wall-clock millis of the last ack; the UI derives a live age from this. */
    val lastAckAt: StateFlow<Long?> = AutoSessionState.lastAckAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Highest 0x8004 ack counter seen; null until an ack carries the field. */
    val lastAckSeq: StateFlow<Long?> = AutoSessionState.lastAckSeq
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Rolling mean encode-to-send latency in microseconds, or null with no frames. */
    val avgEncodeLatencyUs: StateFlow<Long?> = AutoSessionState.avgEncodeLatencyUs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Encoder drains so far; frames ride out via [framesSent]. */
    val encoderDrains: StateFlow<Long> = AutoSessionState.encoderDrains
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** Whether the virtual display + encoder input surface pair is currently up. */
    val surfaceValid: StateFlow<Boolean> = AutoSessionState.surfaceValid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val sessionStartedAt: StateFlow<Long?> = AutoSessionState.sessionStartedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
