package com.vayunmathur.auto.platform

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.vayunmathur.auto.BuildConfig
import com.vayunmathur.auto.protocol.DisplayRouteKind
import com.vayunmathur.auto.protocol.DisplayRoutePolicy
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.gal.MediaAck
import com.vayunmathur.auto.protocol.gal.MediaSetupRequest
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaStartRequest
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import com.vayunmathur.auto.protocol.gal.VideoFocusRequest
import com.vayunmathur.auto.protocol.gal.VideoResolution
import java.nio.ByteBuffer

/**
 * The video sink channel: brings the display and encoder up once the head unit agrees.
 *
 * The bring-up is a four-step conversation and the order matters — a head unit that gets
 * frames before it has acknowledged the start request drops them silently:
 *
 *  1. → `MediaSetupRequest`      claim the channel for video
 *  2. ← `MediaSetupResponse`     the head unit says which configuration it will accept
 *  3. → `VideoFocusRequest`      ask for the screen
 *  4. → `MediaStartRequest`      then, and only then, start streaming
 */
class VideoSinkChannel(
    private val context: Context,
    private val service: Service,
    private val connection: GalConnection,
    /**
     * Fire-and-forget observations for the phone status screen. Never blocks and never
     * gates streaming: the service forwards these to the session state without waiting.
     */
    private val onEvent: (VideoEvent) -> Unit = {},
) {
    @Volatile
    private var display: CarDisplay? = null

    /** Posts teardown and vsync control onto the main thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The vsync source. Main thread only: armed in [startVsyncDrain], removed in
     * [release]. Null before streaming and after teardown.
     */
    private var choreographer: Choreographer? = null

    /**
     * Written on the pump thread in [startStreaming], drained and torn down on the
     * main thread by the vsync chain and [release]. Volatile for visibility; the
     * lifecycle is ordered (start posts before any drain, teardown removes callbacks
     * first), so no lock is needed and the pump thread never touches the codec.
     */
    @Volatile
    private var encoder: VideoEncoder? = null
    private var sessionId = -1
    private var configurationIndex = 0
    private var firstFrameSent = false

    /**
     * Where the car card's now-playing comes from and where its taps go.
     * [get] is read when the render pair comes up (plus every snapshot pushed
     * via [setNowPlaying]); [onTap] toggles phone playback. `null` until the
     * service wires the media monitor -- unset means the card shows empty.
     */
    private var nowPlayingSource: NowPlayingSource? = null

    /**
     * Frames emitted since the last vsync drain. `drain()` invokes [sendFrame]
     * synchronously, so snapshotting after it returns counts exactly this drain's
     * yield — which is what tells "encoder idle" from "flowing". Main thread only.
     */
    private var framesOutSinceDrain = 0

    /** Chosen from what the head unit advertised in discovery. */
    private val configuration: VideoConfiguration? =
        service.mediaSink.videoConfigsList.firstOrNull()

    val channelId: Int get() = service.id

    /**
     * Current car-display size in pixels, or null before streaming starts.
     * The input sink scales head-unit touch pixels into this space.
     */
    fun displaySize(): Pair<Int, Int>? {
        val config = configuration ?: return null
        if (display == null) return null
        return config.codecResolution.dimensions()
    }

    /** Wires the now-playing feed from the media monitor; see [nowPlayingSource]. */
    fun setNowPlayingSource(get: () -> NowPlayingInfo?, onTap: () -> Unit) {
        nowPlayingSource = NowPlayingSource(get, onTap)
        nowPlayingSource?.get()?.let { display?.setNowPlaying(it) }
    }

    /**
     * Pushes one snapshot to the car card, if the render pair is up. The
     * service calls this on every media-monitor update so the card tracks
     * playback without waiting for the encoder pump.
     */
    fun setNowPlaying(info: NowPlayingInfo) {
        display?.setNowPlaying(info)
    }

    /**
     * Injects one scaled head-unit touch frame into the car UI, if the render
     * pair is up. The ch8 owner scales first; this only forwards. False means
     * "no display yet", and the frame is dropped rather than queued.
     */
    fun injectTouch(touch: ScaledTouch): Boolean =
        display?.injectTouch(
            touch.action,
            touch.pointers.map { Triple(it.x.toFloat(), it.y.toFloat(), it.pointerId) },
            touch.actionIndex,
        ) ?: false

    /** Injects one head-unit key press or release; false with no display up. */
    fun injectKey(keycode: Int, down: Boolean): Boolean =
        display?.injectKey(keycode, down) ?: false

    /** Injects one head-unit scroll tick; false with no display up. */
    fun injectScroll(delta: Int): Boolean =
        display?.injectScroll(delta) ?: false

    /** Step 1. Called once the channel is open. */
    fun requestSetup() {
        connection.send(
            channelId,
            GalMessage.Media.SETUP_REQUEST,
            MediaSetupRequest.newBuilder().setMediaType(MEDIA_TYPE_VIDEO).build().toByteArray(),
        )
    }

    /**
     * Handles an inbound message for this channel.
     *
     * [channelId] scopes the parse: 0x8004 is aliased across services (MediaAck
     * on media channels, SensorError on the sensor channel), so a message for a
     * different channel is ignored rather than misparsed. The service routes
     * every service-channel message here today; a future sensor owner will take
     * its own channel's traffic.
     */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        when (type) {
            GalMessage.Media.CONFIG -> onSetupResponse(payload)
            GalMessage.Video.FOCUS_INDICATION -> onFocus(payload)
            GalMessage.Media.ACK -> onAck(payload)
            else -> Log.d(TAG, "unhandled video message 0x${type.toString(16)}")
        }
    }

    private fun onSetupResponse(payload: ByteArray) {
        val response = MediaSetupResponse.parseFrom(payload)
        configurationIndex = response.configurationIndicesList.firstOrNull() ?: 0
        Log.i(TAG, "head unit accepted video, config index $configurationIndex")

        // Step 3: ask for the screen before starting the stream.
        connection.send(
            channelId,
            GalMessage.Video.FOCUS_REQUEST,
            VideoFocusRequest.newBuilder()
                .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                .build()
                .toByteArray(),
        )
        startStreaming()
    }

    private fun onFocus(payload: ByteArray) {
        val indication = VideoFocusIndication.parseFrom(payload)
        // The session owns arbitration: it folds the mode -- or ignores an absent
        // one, since the lite runtime drops unknown enum values on parse -- and
        // notifies the service, which mirrors it to the phone UI and the
        // input/audio gates.
        connection.session.onVideoFocusIndication(indication)
        if (!indication.hasMode()) {
            Log.w(TAG, "video focus indication with no mode; arbitration unchanged")
            return
        }
        val mode = indication.mode
        Log.i(TAG, "video focus is now $mode")
        onEvent(VideoEvent.FocusChanged(mode.name))
        // Regaining the screen needs a fresh keyframe; the head unit has nothing to decode
        // against otherwise and would show garbage until the next scheduled one.
        if (mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED) encoder?.requestKeyFrame()
    }

    private fun onAck(payload: ByteArray) {
        // Flow control: the head unit has consumed frames. Nothing to do while we send
        // unthrottled, but parsing it confirms the session id lines up — and the ack
        // counter plus field3 entries are the sender-side sequence track of the stream.
        val ack = MediaAck.parseFrom(payload)
        if (ack.sessionId != sessionId) {
            Log.w(TAG, "ack for session ${ack.sessionId}, expected $sessionId")
            onEvent(VideoEvent.AckMismatch(expected = sessionId, actual = ack.sessionId))
        } else {
            onEvent(
                VideoEvent.AckReceived(
                    ackSeq = if (ack.hasAck()) Integer.toUnsignedLong(ack.ack) else null,
                    extraCount = ack.field3Count,
                ),
            )
        }
    }

    private fun startStreaming() {
        val config = configuration
        val (width, height) = config?.codecResolution.dimensions()
        val frameRate = config?.frameRate?.takeIf { it > 0 } ?: DEFAULT_FRAME_RATE
        val density = config?.density?.takeIf { it > 0 } ?: DEFAULT_DENSITY

        Log.i(TAG, "starting video ${width}x$height @${frameRate} dpi $density")
        onEvent(VideoEvent.Setup(VideoInfo(width, height, frameRate, configurationIndex)))

        sessionId = 0
        firstFrameSent = false
        val encoder = VideoEncoder(width, height, frameRate, onFrame = ::sendFrame)
        this.encoder = encoder
        encoder.start()

        val surface = checkNotNull(encoder.surface) { "encoder produced no input surface" }
        // Phase 9 display route: trusted only with the MAOS role (private otherwise),
        // so the loopback path keeps working permission-free on stock phones.
        val trusted = DisplayRoutePolicy.routeFor(
            holdsProjectionRole = MaosRoleStatus.isProjectionRoleHeld(context),
        ) == DisplayRouteKind.TRUSTED
        display = CarDisplay(context, width, height, density, trusted = trusted).also {
            val source = nowPlayingSource
            it.onMediaTap = source?.onTap
            source?.get()?.let(it::setNowPlaying)
            it.show(surface)
        }
        // The render pair is up: a private virtual display compositing straight into
        // the encoder's input surface. Validity dump for the Phase 0 probe.
        dumpSurfaces(width, height, density, surface)
        onEvent(VideoEvent.SurfaceChanged(valid = true))
        // The negotiated rate caps the drain, not the wire: Choreographer fires at
        // the display's vsync (usually 60Hz) and each tick drains whatever the
        // encoder produced, so a 30fps config sends every other vsync while a
        // 60fps config sends every one.
        mainHandler.post { startVsyncDrain(frameRate) }

        // Step 4.
        connection.send(
            channelId,
            GalMessage.Media.START_REQUEST,
            MediaStartRequest.newBuilder()
                .setSessionId(sessionId)
                .setConfigurationIndex(configurationIndex)
                .build()
                .toByteArray(),
        )
    }

    /**
     * Arms the vsync drain chain on the main thread. Each tick drains whatever the
     * encoder produced and re-arms, so frames ride out at display cadence up to the
     * negotiated rate -- 60fps at 60Hz vsync -- instead of once per network pump.
     *
     * [negotiatedFps] is logged, not gated: the encoder itself paces output to its
     * configured rate, so the drain cannot oversend.
     */
    private fun startVsyncDrain(negotiatedFps: Int) {
        val choreographer = Choreographer.getInstance()
        this.choreographer = choreographer
        Log.i(TAG, "draining encoder at vsync cadence for ${negotiatedFps}fps video")
        choreographer.postFrameCallback(vsyncCallback)
    }

    /** One vsync tick: drain, count, re-arm. Main thread only. */
    private val vsyncCallback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        pumpEncoder()
        choreographer?.postFrameCallback(vsyncCallback)
    }

    /**
     * Dev-build validity dump of the render pair for the Phase 0 probe: virtual-display
     * geometry plus whether the encoder input surface is still live. R8 strips the
     * whole call from release builds via [BuildConfig.DEV_BUILD]; production logcat
     * stays quiet.
     */
    private fun dumpSurfaces(width: Int, height: Int, density: Int, surface: Surface) {
        if (!BuildConfig.DEV_BUILD) return
        Log.i(
            TAG,
            "render pair up: virtual display ${width}x$height dpi $density, " +
                "encoder input surface valid=${surface.isValid}",
        )
    }

    private fun sendFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // Parked stream: keep draining so the codec never stalls, but hold the frames --
        // the car is showing its own UI. Regaining PROJECTED asks for a keyframe (see
        // [onFocus]) and the stream restarts cleanly.
        if (!connection.session.focus.shouldStreamVideo) return
        if (!firstFrameSent) {
            firstFrameSent = true
            onEvent(VideoEvent.FirstFrame)
        }
        framesOutSinceDrain++
        // Encode-to-send latency: codec timestamp (queue time, monotonic) vs now.
        // Clamped at zero — a late pump can drain a frame whose timestamp predates
        // the send by more than the stream age only on clock weirdness.
        val latencyUs = (SystemClock.elapsedRealtimeNanos() / 1_000 - info.presentationTimeUs)
            .coerceAtLeast(0)
        onEvent(
            VideoEvent.FrameSent(
                presentationTimeUs = info.presentationTimeUs,
                latencyUs = latencyUs,
            ),
        )
        val bytes = ByteArray(info.size)
        buffer.get(bytes)
        // Timestamped form: an 8-byte microsecond timestamp then the access unit. Codec
        // config (SPS/PPS) rides the same way, which is what a head unit expects first.
        val payload = ByteBuffer.allocate(Long.SIZE_BYTES + bytes.size)
            .putLong(info.presentationTimeUs)
            .put(bytes)
            .array()
        connection.send(channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, payload)
    }

    /**
     * Pushes any encoded frames out. Called on the main thread at vsync cadence (see
     * [startVsyncDrain]), never from the connection's pump loop: the pump thread owns
     * net/SSL while this owns media, and sends serialize under the connection's
     * engine lock.
     *
     * Emits a drain observation per vsync so the phone UI can tell "encoder idle"
     * (drains with nothing ready) from "frames flowing" ([VideoEvent.FrameSent]).
     */
    fun pumpEncoder() {
        // No encoder yet (channel open but setup/start still in flight): nothing to
        // drain and nothing to count — drains are scoped to the streaming session.
        if (encoder == null) return
        framesOutSinceDrain = 0
        encoder?.drain()
        onEvent(VideoEvent.EncoderDrained(framesOut = framesOutSinceDrain))
    }

    /**
     * Tears the render pair down on the main thread, ordered after the vsync chain:
     * callbacks are removed first, so no drain can run against a stopped codec, and
     * the pump thread never touches the codec. Fire-and-forget from any thread.
     */
    fun release() {
        mainHandler.post {
            choreographer?.removeFrameCallback(vsyncCallback)
            choreographer = null
            display?.release()
            display = null
            encoder?.stop()
            encoder = null
            onEvent(VideoEvent.SurfaceChanged(valid = false))
        }
    }

    private fun VideoResolution?.dimensions(): Pair<Int, Int> = when (this) {
        VideoResolution.VIDEO_1280x720 -> 1280 to 720
        VideoResolution.VIDEO_1920x1080 -> 1920 to 1080
        VideoResolution.VIDEO_2560x1440 -> 2560 to 1440
        VideoResolution.VIDEO_3840x2160 -> 3840 to 2160
        VideoResolution.VIDEO_720x1280 -> 720 to 1280
        VideoResolution.VIDEO_1080x1920 -> 1080 to 1920
        VideoResolution.VIDEO_1440x2560 -> 1440 to 2560
        VideoResolution.VIDEO_2160x3840 -> 2160 to 3840
        // 800x480 is the DHU default and a sane fallback for anything unrecognised.
        else -> 800 to 480
    }

    private companion object {
        const val TAG = "MaAuto.Video"

        /** `MediaSetupRequest.media_type`: 1 audio, 3 video. */
        const val MEDIA_TYPE_VIDEO = 3
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_DENSITY = 160
    }
}

/**
 * Where a video channel's car now-playing card reads from and writes to.
 * [get] returns the latest snapshot (or null before the session reports);
 * [onTap] toggles phone playback when the card is tapped, locally or via ch8.
 */
data class NowPlayingSource(
    val get: () -> NowPlayingInfo?,
    val onTap: () -> Unit,
)
