package com.vayunmathur.auto.platform

import android.os.SystemClock
import com.vayunmathur.auto.protocol.AckTracker
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

    /**
     * One encoded frame went out.
     *
     * [presentationTimeUs] is the codec timestamp; [latencyUs] is monotonic
     * queue + encode time for this frame (send time minus its timestamp).
     * Sender-side only: this counts frames handed to the socket, never frames
     * decoded or shown by the head unit.
     */
    data class FrameSent(val presentationTimeUs: Long, val latencyUs: Long) : VideoEvent

    /**
     * The head unit acknowledged frames (0x8004 on the video channel).
     *
     * [ackSeq] is the `ack` frame counter from the `MediaAck`, unsigned-extended;
     * null when the head unit omits the field. The counter is **mod 256** — it
     * wraps, so the session unwraps it rather than treating a lower value as a
     * regression. [extraCount] is how many `field3` entries rode along. An ack
     * confirms receipt, not visibility — the sender-side proxy for "visible" is
     * acks advancing in step with frames.
     */
    data class AckReceived(val ackSeq: Long?, val extraCount: Int) : VideoEvent

    /** An ack named a session that is not ours; the session id lines up otherwise. */
    data class AckMismatch(val expected: Int, val actual: Int) : VideoEvent

    /** One encoder drain produced [framesOut] frames (0 when it had nothing ready). */
    data class EncoderDrained(val framesOut: Int) : VideoEvent

    /** The virtual-display + encoder-input-surface pair became valid or went away. */
    data class SurfaceChanged(val valid: Boolean) : VideoEvent
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

    /**
     * Encode-side frames per second over a sliding window of send timestamps.
     * Sender-side: frames handed to the socket per second, not frames displayed.
     */
    private val _encodedFps = MutableStateFlow(0.0)
    val encodedFps: StateFlow<Double> = _encodedFps.asStateFlow()

    /**
     * Head-unit acks per second over a sliding window of ack timestamps.
     * The sender-side proxy for "visible" is this advancing in step with
     * [encodedFps], not the total in [acksSeen].
     */
    private val _ackFps = MutableStateFlow(0.0)
    val ackFps: StateFlow<Double> = _ackFps.asStateFlow()

    /** Millis since the last 0x8004 ack; null when none has arrived this session. */
    private val _lastAckAgeMs = MutableStateFlow<Long?>(null)
    val lastAckAgeMs: StateFlow<Long?> = _lastAckAgeMs.asStateFlow()

    /** Wall-clock millis of the last 0x8004 ack; the UI derives a live age from this. */
    private val _lastAckAt = MutableStateFlow<Long?>(null)
    val lastAckAt: StateFlow<Long?> = _lastAckAt.asStateFlow()

    /** Highest 0x8004 `ack` counter seen, unwrapped across mod-256 wraps; null until an ack carries the field. */
    private val _lastAckSeq = MutableStateFlow<Long?>(null)
    val lastAckSeq: StateFlow<Long?> = _lastAckSeq.asStateFlow()

    /** Rolling mean encode-to-send latency in microseconds over recent frames. */
    private val _avgEncodeLatencyUs = MutableStateFlow<Long?>(null)
    val avgEncodeLatencyUs: StateFlow<Long?> = _avgEncodeLatencyUs.asStateFlow()

    /** Encoder drains so far; frames ride out via [framesSent]. */
    private val _encoderDrains = MutableStateFlow(0L)
    val encoderDrains: StateFlow<Long> = _encoderDrains.asStateFlow()

    /** Whether the virtual display + encoder input surface pair is currently up. */
    private val _surfaceValid = MutableStateFlow(false)
    val surfaceValid: StateFlow<Boolean> = _surfaceValid.asStateFlow()

    /**
     * Send/ack timestamps backing the fps windows. Mutated only on the
     * `ma-auto-projection` worker thread (every entry point above runs there),
     * so no extra locking beyond the flows' own thread-safety.
     */
    private val frameTimes = ArrayDeque<Long>()
    private val ackTimes = ArrayDeque<Long>()

    /**
     * Last unwrapped 0x8004 ack counter. The wire `ack` field is a frame counter
     * mod 256, so it wraps: an [AckTracker] folds arrivals into a monotonically
     * non-decreasing sequence. Single-threaded owner is the `ma-auto-projection`
     * worker thread, like the timestamp windows above.
     */
    private val ackTracker = AckTracker()

    /** Wall-clock millis when the session became active, for the phone's elapsed counter. */
    private val _sessionStartedAt = MutableStateFlow<Long?>(null)
    val sessionStartedAt: StateFlow<Long?> = _sessionStartedAt.asStateFlow()

    /**
     * Whole days until the shipped GAL leaf expires, parsed at runtime from the
     * PEM so it stays correct across a rotation. Null until the service seeds it
     * (or when the leaf cannot be parsed). Deliberately outside [resetTelemetry]:
     * the credential outlives any one session.
     */
    private val _credentialDaysLeft = MutableStateFlow<Long?>(null)
    val credentialDaysLeft: StateFlow<Long?> = _credentialDaysLeft.asStateFlow()

    /** Records the days remaining on the shipped GAL leaf; see [credentialDaysLeft]. */
    fun onCredentialExpiry(daysLeft: Long) {
        _credentialDaysLeft.value = daysLeft
    }

    /** A socket was accepted; version negotiation and the TLS handshake are next. */
    fun onSocketAccepted() {
        _video.value = null
        _focusMode.value = null
        _framesSent.value = 0
        _acksSeen.value = 0
        _ackMismatches.value = 0
        resetTelemetry()
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
        resetTelemetry()
    }

    /**
     * Clears every Phase 0 telemetry counter. Called from [onSocketAccepted] and
     * [onDisconnected] alongside the existing counters — a new socket means a
     * new session, so stale fps/age/seq values must not leak across.
     */
    private fun resetTelemetry() {
        frameTimes.clear()
        ackTimes.clear()
        ackTracker.reset()
        _encodedFps.value = 0.0
        _ackFps.value = 0.0
        _lastAckAgeMs.value = null
        _lastAckAt.value = null
        _lastAckSeq.value = null
        _avgEncodeLatencyUs.value = null
        _encoderDrains.value = 0
        _surfaceValid.value = false
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
            is VideoEvent.FrameSent -> {
                _framesSent.value++
                val now = SystemClock.uptimeMillis()
                frameTimes.addLast(now)
                while (frameTimes.isNotEmpty() && now - frameTimes.first() > FPS_WINDOW_MS) {
                    frameTimes.removeFirst()
                }
                _encodedFps.value = frameTimes.size * 1_000.0 / FPS_WINDOW_MS
                // Blend this frame's encode-to-send latency into the rolling mean.
                val previous = _avgEncodeLatencyUs.value
                _avgEncodeLatencyUs.value =
                    if (previous == null) event.latencyUs else (previous + event.latencyUs) / 2
                // Keep the ack age live while frames flow: it is the gap since the
                // last ack, recomputed on every frame, not just when an ack lands.
                refreshAckAge(System.currentTimeMillis())
            }
            is VideoEvent.AckReceived -> {
                _acksSeen.value++
                recordAck(event.ackSeq)
            }
            is VideoEvent.AckMismatch -> {
                _acksSeen.value++
                _ackMismatches.value++
                // A mismatched ack still proves the head unit is alive and consuming.
                _lastAckAt.value = System.currentTimeMillis()
                _lastAckAgeMs.value = 0
            }
            is VideoEvent.EncoderDrained -> _encoderDrains.value++
            is VideoEvent.SurfaceChanged -> _surfaceValid.value = event.valid
        }
    }

    /** Shares the ack timestamp/rate bookkeeping between matching and mismatched acks. */
    private fun recordAck(ackSeq: Long?) {
        val nowWall = System.currentTimeMillis()
        val nowUptime = SystemClock.uptimeMillis()
        _lastAckAt.value = nowWall
        _lastAckAgeMs.value = 0
        ackTimes.addLast(nowUptime)
        while (ackTimes.isNotEmpty() && nowUptime - ackTimes.first() > FPS_WINDOW_MS) {
            ackTimes.removeFirst()
        }
        _ackFps.value = ackTimes.size * 1_000.0 / FPS_WINDOW_MS
        if (ackSeq != null) {
            _lastAckSeq.value = ackTracker.onAck(ackSeq)
        }
    }

    /** Recomputes the last-ack age against [nowWall]; a no-op until the first ack. */
    private fun refreshAckAge(nowWall: Long) {
        val at = _lastAckAt.value ?: return
        _lastAckAgeMs.value = (nowWall - at).coerceAtLeast(0)
    }

    private companion object {
        /** Sliding window the fps rates are computed over. */
        const val FPS_WINDOW_MS = 5_000L
    }
}
