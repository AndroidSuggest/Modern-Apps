package com.vayunmathur.auto.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.gal.AudioFocusState
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

    /**
     * Arbitrated video focus: NONE until the first 0x8008, NATIVE while the car
     * holds the screen, PROJECTED while we stream. Drives the focus row and the
     * input/audio gates downstream.
     */
    val videoFocus: StateFlow<VideoFocus> = AutoSessionState.videoFocus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), VideoFocus.NONE)

    /** Last audio focus state the head unit reported; INVALID until the first 0x13. */
    val audioFocus: StateFlow<AudioFocusState> = AutoSessionState.audioFocus
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            AudioFocusState.AUDIO_FOCUS_STATE_INVALID,
        )

    /** Whether head-unit input may currently be injected. */
    val inputAllowed: StateFlow<Boolean> = AutoSessionState.inputAllowed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

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

    /** Whether the virtual display + encoder input surface pair is up. */
    val surfaceValid: StateFlow<Boolean> = AutoSessionState.surfaceValid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Latest now-playing snapshot; null until the media session reports. */
    val nowPlaying: StateFlow<NowPlayingInfo?> = AutoSessionState.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** ch14 thread snapshots posted this session. */
    val threadsPosted: StateFlow<Long> = AutoSessionState.threadsPosted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch14 message bodies posted this session. */
    val messagesPosted: StateFlow<Long> = AutoSessionState.messagesPosted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** Head-unit replies (typed or voice) received this session. */
    val repliesReceived: StateFlow<Long> = AutoSessionState.repliesReceived
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch8 touch frames injected this session. */
    val touchesInjected: StateFlow<Long> = AutoSessionState.touchesInjected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch8 key presses/releases injected this session. */
    val keysInjected: StateFlow<Long> = AutoSessionState.keysInjected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch8 scroll ticks injected this session. */
    val scrollsInjected: StateFlow<Long> = AutoSessionState.scrollsInjected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch8 reports dropped for lack of input focus this session. */
    val inputDropped: StateFlow<Long> = AutoSessionState.inputDropped
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** Per-role sink status (ch4 SYS, ch5 MEDIA); empty until a channel opens. */
    val sinkStatus: StateFlow<Map<AudioSinkRole, AudioSinkStatus>> = AutoSessionState.sinkStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** PCM bytes framed out on ch4/5 this session. */
    val audioBytesSent: StateFlow<Long> = AutoSessionState.audioBytesSent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** Music PCM bytes captured into the ch5 sink this session. */
    val musicBytesCaptured: StateFlow<Long> = AutoSessionState.musicBytesCaptured
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch6 mic turns that yielded retained PCM this session. */
    val micTurns: StateFlow<Long> = AutoSessionState.micTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** ch6 mic chunks acked upstream this session. */
    val micAcks: StateFlow<Long> = AutoSessionState.micAcks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** TTS utterances that reached the car this session. */
    val ttsSpoken: StateFlow<Long> = AutoSessionState.ttsSpoken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    /** Whole days until the shipped GAL leaf expires; null until the service seeds it. */
    val credentialDaysLeft: StateFlow<Long?> = AutoSessionState.credentialDaysLeft
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val sessionStartedAt: StateFlow<Long?> = AutoSessionState.sessionStartedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
