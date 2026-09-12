package com.vayunmathur.auto.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The video stream the head unit accepted, for the phone status screen. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val configIndex: Int,
)

/**
 * Something the video channel observed, forwarded to [AutoSessionState] for the phone UI.
 *
 * Streaming never waits on these: they are fire-and-forget observations, and the service
 * forwards them without gating any protocol step on the UI.
 */
sealed interface VideoEvent {
    /** The head unit accepted video; these are the parameters it chose. */
    data class Setup(val info: VideoInfo) : VideoEvent

    /** Video focus moved, e.g. to `VIDEO_FOCUS_NATIVE` when the car takes the screen back. */
    data class FocusChanged(val mode: String) : VideoEvent

    /** The first encoded frame went out. */
    data object FirstFrame : VideoEvent

    /** One encoded frame went out. */
    data object FrameSent : VideoEvent

    /** The head unit acknowledged frames. */
    data object AckReceived : VideoEvent

    /** An ack named a session that is not ours; the session id lines up otherwise. */
    data class AckMismatch(val expected: Int, val actual: Int) : VideoEvent
}

/**
 * The bridge between the projection session and the phone UI.
 *
 * The session runs on the single `ma-auto-projection` worker thread and the ViewModel lives
 * on the main thread, so they share nothing but these flows. Setting a [MutableStateFlow]
 * value is thread-safe, which is what makes worker-thread emission safe here; the encoder
 * pump itself stays single-threaded exactly as before.
 */
object AutoSessionState {
    private val _connection =
        MutableStateFlow<AutoConnectionState>(AutoConnectionState.Disconnected)
    val connection: StateFlow<AutoConnectionState> = _connection.asStateFlow()

    private val _video = MutableStateFlow<VideoInfo?>(null)
    val video: StateFlow<VideoInfo?> = _video.asStateFlow()

    private val _focusMode = MutableStateFlow<String?>(null)
    val focusMode: StateFlow<String?> = _focusMode.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    val framesSent: StateFlow<Long> = _framesSent.asStateFlow()

    private val _acksSeen = MutableStateFlow(0L)
    val acksSeen: StateFlow<Long> = _acksSeen.asStateFlow()

    private val _ackMismatches = MutableStateFlow(0L)
    val ackMismatches: StateFlow<Long> = _ackMismatches.asStateFlow()

    /** Wall-clock millis when the session became active, for the phone's elapsed counter. */
    private val _sessionStartedAt = MutableStateFlow<Long?>(null)
    val sessionStartedAt: StateFlow<Long?> = _sessionStartedAt.asStateFlow()

    /** A socket was accepted; version negotiation and the TLS handshake are next. */
    fun onSocketAccepted() {
        _video.value = null
        _focusMode.value = null
        _framesSent.value = 0
        _acksSeen.value = 0
        _ackMismatches.value = 0
        _sessionStartedAt.value = null
        _connection.value = AutoConnectionState.Connecting
    }

    /** Discovery completed and the bring-up is running against [carName]. */
    fun onActive(carName: String) {
        _sessionStartedAt.value = System.currentTimeMillis()
        _connection.value = AutoConnectionState.Projecting(carName)
    }

    /** The link came up but authentication failed, or the session otherwise refused us. */
    fun onRejected() {
        _connection.value = AutoConnectionState.Rejected
    }

    /** The head unit went away cleanly. */
    fun onDisconnected() {
        _connection.value = AutoConnectionState.Disconnected
        _video.value = null
        _focusMode.value = null
        _sessionStartedAt.value = null
    }

    /** End of a session: a recorded failure means refusal, anything else a clean parting. */
    fun onSessionEnd(failure: String?) {
        if (failure != null) onRejected() else onDisconnected()
    }

    fun onVideoEvent(event: VideoEvent) {
        when (event) {
            is VideoEvent.Setup -> _video.value = event.info
            is VideoEvent.FocusChanged -> _focusMode.value = event.mode
            VideoEvent.FirstFrame -> Unit
            VideoEvent.FrameSent -> _framesSent.value++
            VideoEvent.AckReceived -> _acksSeen.value++
            is VideoEvent.AckMismatch -> {
                _acksSeen.value++
                _ackMismatches.value++
            }
        }
    }
}
