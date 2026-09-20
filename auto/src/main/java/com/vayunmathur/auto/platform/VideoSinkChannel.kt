package com.vayunmathur.auto.platform

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import com.vayunmathur.library.carhost.HostNavState
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.vayunmathur.auto.BuildConfig
import com.vayunmathur.auto.protocol.AudioCodec
import com.vayunmathur.auto.protocol.DisplayRouteKind
import com.vayunmathur.auto.protocol.DisplayRoutePolicy
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.protocol.VideoCodec
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
     * Whether START went out this session. Gates the STOP in [release]: a
     * channel that never started (setup refused, teardown before START) must
     * not send STOP for a stream the head unit never opened. Written on the
     * pump thread in [startStreaming], read on whatever thread calls
     * [release]; volatile for visibility, no lock needed for one flag.
     */
    @Volatile
    private var streamStarted = false

    /**
     * Forwards nav-card map surfaces to the session mirror when the render
     * pair comes up after this was set. Cached like [nowPlayingSource] for
     * displays created later.
     */
    @Volatile
    private var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)? = null

    /**
     * Where the car card's now-playing comes from and where its taps go.
     * [get] is read when the render pair comes up (plus every snapshot pushed
     * via [setNowPlaying]); [onTap] toggles phone playback. `null` until the
     * service wires the media monitor -- unset means the card shows empty.
     */
    private var nowPlayingSource: NowPlayingSource? = null

    /** Prev/next transport callbacks; see [setTransportCallbacks]. */
    private var transportCallbacks: TransportCallbacks? = null

    /** Map palette applier; see [setMapDarkApplier]. */
    private var mapDarkApplier: ((Boolean) -> Unit)? = null

    /**
     * Frames emitted since the last vsync drain. `drain()` invokes [sendFrame]
     * synchronously, so snapshotting after it returns counts exactly this drain's
     * yield — which is what tells "encoder idle" from "flowing". Main thread only.
     */
    private var framesOutSinceDrain = 0

    /**
     * Chosen from what the head unit advertised in discovery. Starts at entry 0
     * and is re-indexed by the head unit's answer in [onSetupResponse]: the
     * accepted `configurationIndicesList` entry selects into `videoConfigsList`,
     * and the head unit may pick any index -- entry 0 is only the pre-answer
     * placeholder. Written on the pump thread, read on the input thread.
     */
    @Volatile
    private var configuration: VideoConfiguration? =
        service.mediaSink.videoConfigsList.firstOrNull()

    /**
     * The frame rate the stream runs at, set in [startStreaming] from the
     * accepted config (forced to 30 when the entry reports none). Restarting
     * the frame invalidator on a focus flap reuses this, never re-reads discovery.
     */
    private var negotiatedFps = DEFAULT_FRAME_RATE

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
        nowPlayingSource?.get()?.let { info ->
            if (info.shouldShowCard()) display?.setNowPlaying(info)
        }
        // Late transport wiring must reach an already-up display too.
        transportCallbacks?.let { callbacks ->
            display?.onPreviousTap = callbacks.onPrevious
            display?.onNextTap = callbacks.onNext
        }
    }

    /**
     * Wires the media transport callbacks (prev/next) from the media monitor.
     * Stored like [nowPlayingSource] so a display created later still gets
     * them; applied immediately when the render pair is already up.
     */
    fun setTransportCallbacks(onPrevious: () -> Unit, onNext: () -> Unit) {
        transportCallbacks = TransportCallbacks(onPrevious, onNext)
        display?.onPreviousTap = onPrevious
        display?.onNextTap = onNext
    }

    /**
     * Wires the map palette applier (`CarAppHost.setNight`) so one
     * [setNightDark] call restyles the rail/cards/drawer and the hosted map.
     * Stored for displays created later; applied immediately when up.
     */
    fun setMapDarkApplier(applier: (Boolean) -> Unit) {
        mapDarkApplier = applier
        display?.mapDarkApplier = applier
    }

    /**
     * Pushes night + parked state to the car UI. Night restyles the rail,
     * cards, drawer and (via [setMapDarkApplier]) the map palette; parked
     * raises the driving-restriction gate. No-ops with no display up; the
     * display caches both for presentations created later.
     */
    fun setNightDark(dark: Boolean) {
        display?.setNight(dark)
    }

    /** Pushes parked state to the driving-restriction gate; see [setNightDark]. */
    fun setParkedBrowsingGate(parked: Boolean) {
        display?.setParkedBrowsingGate(parked)
    }

    /** Closes the drawer at trip end; see `CarDisplay.setParked`. */
    fun closeDrawerOnPark() {
        display?.setParked(true)
    }

    /**
     * Wires the phone-status feed for the rail cluster. Stored for displays
     * created later; the cluster pulls the latest snapshot on its 1Hz tick.
     * The source lambda is enough -- the display polls it, so nothing needs
     * pushing here.
     */
    fun setPhoneStatusSource(get: () -> PhoneStatus?) {
        phoneStatusSource = get
        display?.phoneStatusSource = get
    }

    /**
     * Wires the active-call feed + card actions. The snapshot pushes
     * immediately when the render pair is up; actions route to the bound
     * InCallService via the service. Stored for displays created later.
     */
    fun setCallSource(
        get: () -> ActiveCallInfo?,
        onAnswer: () -> Unit,
        onEnd: () -> Unit,
        onHold: () -> Unit,
        onMute: () -> Unit,
    ) {
        callSource = get
        storedCallActions = CallActions(onAnswer, onEnd, onHold, onMute)
        display?.onAnswerCall = onAnswer
        display?.onEndCall = onEnd
        display?.onHoldToggle = onHold
        display?.onMuteToggle = onMute
        display?.setActiveCall(get())
    }

    /** Latest call snapshot getter; see [setCallSource]. */
    private var callSource: (() -> ActiveCallInfo?)? = null

    /**
     * Pushes one active-call snapshot into the car card, if the render pair
     * is up. The service calls this on every InCall callback so the card
     * tracks ringing/active/held without waiting for a poll.
     */
    fun setActiveCall(info: ActiveCallInfo?) {
        display?.setActiveCall(info)
    }

    /** Stored call actions for displays created later; see [setCallSource]. */
    private var storedCallActions: CallActions? = null

    /**
     * Forwards the map-surface touch hookup to the display. Stored for
     * displays created later; applied immediately when the render pair is
     * already up.
     */
    fun setMapTouchForwarder(forward: (Int, Float, Float) -> Boolean) {
        mapTouchForwarder = forward
        display?.mapTouchForwarder = forward
    }

    /** Map-touch hookup for displays created later; see [setMapTouchForwarder]. */
    private var mapTouchForwarder: ((Int, Float, Float) -> Boolean)? = null

    /** Phone-status getter; see [setPhoneStatusSource]. */
    private var phoneStatusSource: (() -> PhoneStatus?)? = null
    fun setNavSource(
        get: () -> NavSnapshot?,
        onMapSurface: (Surface?, Int, Int) -> Unit,
    ) {
        display?.setMapSurfaceListener(onMapSurface)
        mapSurfaceListener = onMapSurface
        get()?.let { display?.setNavSnapshot(it) }
    }

    /**
     * Pushes one hosted template state into the car card, if the render pair
     * is up. The session host calls this on every template invalidate so the
     * header tracks the app without waiting for a poll.
     */
    fun setHostNavState(state: HostNavState) {
        display?.setHostNavState(state)
    }

    /**
     * Pushes one snapshot to the car card, if the render pair is up. The
     * service calls this on every media-monitor update so the card tracks
     * playback without waiting for the encoder pump.
     *
     * The [NowPlayingInfo.shouldShowCard] gate lives in the card itself; this
     * forwards unconditionally so a pause still clears a stale "Playing".
     */
    fun setNowPlaying(info: NowPlayingInfo) {
        if (info.shouldShowCard()) {
            display?.setNowPlaying(info)
        } else {
            // Second gate: never hand an idle snapshot to a render pair that
            // came up later via [setNowPlayingSource] either. The cached source
            // still updates so resume re-shows instantly.
            display?.hideNowPlaying()
        }
    }

    /**
     * Pushes one guidance snapshot to the nav banner, if the render pair is
     * up. The service calls this on every guidance-monitor update; the banner
     * itself decides show vs GONE.
     */
    fun setNavSnapshot(snapshot: NavSnapshot) {
        display?.setNavSnapshot(snapshot)
    }

    /**
     * Injects one scaled head-unit touch frame into the car UI, if the render
     * pair is up. The ch8 owner scales first; this only forwards. False means
     * "no display yet", and the frame is dropped rather than queued.
     *
     * Single-pointer DOWN on the now-playing card is consumed as a media
     * toggle via [handleCarTap]; everything else dispatches into the view
     * tree (app tiles, scroll) like before.
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
            // The head unit's answer to our 0x800A UI-config update: no payload
            // semantics were recovered (see VideoCodec), so it is observed like
            // the 0x800B sync pulse -- never answered, never fatal.
            GalMessage.Video.UPDATE_UI_CONFIG_REQUEST ->
                Log.d(TAG, "update-ui-config response (${payload.size}B); observed")
            else -> Log.d(TAG, "unhandled video message 0x${type.toString(16)}")
        }
    }

    private fun onSetupResponse(payload: ByteArray) {
        val response = MediaSetupResponse.parseFrom(payload)
        configurationIndex = response.configurationIndicesList.firstOrNull() ?: 0
        // The answer selects INTO the offered list: re-index so geometry and
        // rate come from the entry the head unit actually accepted, not entry 0.
        // Out-of-range answers keep the discovery placeholder (entry 0).
        val offered = service.mediaSink.videoConfigsList
        logOfferedConfigs(offered, configurationIndex)
        val accepted = offered.getOrNull(configurationIndex)
        if (accepted != null) {
            configuration = accepted
        } else {
            Log.w(
                TAG,
                "head unit accepted video config index $configurationIndex " +
                    "of ${offered.size}; keeping entry 0",
            )
        }
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
        // Prefer a 30fps entry when the head unit offers one: several HUs
        // advertise 60 but only ack ~30, and the negotiated rate caps the frame
        // invalidator below. A non-positive entry falls back to 30.
        val frameRate = negotiateFrameRate(config)
        negotiatedFps = frameRate
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
            transportCallbacks?.let { callbacks ->
                it.onPreviousTap = callbacks.onPrevious
                it.onNextTap = callbacks.onNext
            }
            mapDarkApplier?.let { applier -> it.mapDarkApplier = applier }
            mapTouchForwarder?.let { forward -> it.mapTouchForwarder = forward }
            phoneStatusSource?.let { source -> it.phoneStatusSource = source }
            callSource?.let { source ->
                // Call actions are service-owned; re-read the current
                // callbacks from the stored source wiring (see setCallSource).
                it.setActiveCall(source())
            }
            storedCallActions?.let { actions ->
                it.onAnswerCall = actions.onAnswer
                it.onEndCall = actions.onEnd
                it.onHoldToggle = actions.onHold
                it.onMuteToggle = actions.onMute
            }
            source?.get()?.let { info ->
                if (info.shouldShowCard()) it.setNowPlaying(info)
            }
            mapSurfaceListener?.let { forward ->
                it.setMapSurfaceListener { surface, w, h -> forward(surface, w, h) }
            }
            it.show(surface)
            it.startFrameInvalidation(frameRate)
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
        streamStarted = true
        // The stream is live: tell the head unit the UI config it should
        // render into (0x800A). The xop interior is unrecovered, so the honest
        // empty envelope goes out (see VideoCodec); a stub that answers nothing
        // is observed in onMessage and ignored, never fatal.
        sendUpdateUiConfig()
    }

    /**
     * Sends the 0x800A UI-config update for the live stream. Best-effort like
     * every post-START send: the socket may already be going away, and an
     * unanswered update is courtesy, not protocol -- never fail the session.
     */
    private fun sendUpdateUiConfig() {
        val (type, payload) = runCatching { VideoCodec.encodeUpdateUiConfig() }.getOrNull()
            ?: run {
                Log.d(TAG, "update-ui-config stub; skipping 0x800A")
                return
            }
        runCatching { connection.send(channelId, type, payload) }
            .onFailure { Log.d(TAG, "update-ui-config send dropped", it) }
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
     * reads while the connection's single I/O thread owns net/SSL, and sends queue
     * there without blocking the caller -- so no socket write ever runs on this
     * (main) thread.
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
     *
     * Sends the shared 0x8002 STOP first (same empty `MediaStopRequest` the
     * audio sinks send via `AudioCodec.encodeStop` -- the `jdk` sink shape is
     * one wire message on every media channel), so the head unit releases a
     * live stream rather than timing it out. Gated on [streamStarted]: a
     * never-started channel parts with no STOP, like a never-bound input.
     */
    fun release() {
        if (streamStarted) {
            streamStarted = false
            val (type, payload) = AudioCodec.encodeStop()
            runCatching { connection.send(channelId, type, payload) }
        }
        mainHandler.post {
            choreographer?.removeFrameCallback(vsyncCallback)
            choreographer = null
            display?.stopFrameInvalidation()
            display?.release()
            display = null
            encoder?.stop()
            encoder = null
            onEvent(VideoEvent.SurfaceChanged(valid = false))
        }
    }

    /**
     * Rate selection for the accepted config: its own rate wins when sane;
     * otherwise a 30fps sibling from the same offered list wins over the
     * flat 30 fallback, so a rate-less entry still streams at a sane cadence.
     * Wire truth: `VideoConfiguration.frame_rate = 2` (gal/services.proto).
     *
     * The sanity floor matters: DHU 2.0 offers `VIDEO_800x480 @1`, a bogus
     * rate it does not actually ack at. Honoring it paces both the encoder
     * hint and the frame invalidator to 1fps -- the ~1fps bug by negotiation
     * rather than starvation. Anything below [MIN_SANE_FPS] is treated as
     * "no usable rate" and overridden to 30 with a log.
     */
    private fun negotiateFrameRate(accepted: VideoConfiguration?): Int {
        accepted?.frameRate?.takeIf { it >= MIN_SANE_FPS }?.let { return it }
        val sibling = service.mediaSink.videoConfigsList.firstOrNull { it.frameRate == 30 }
        if (sibling != null) {
            Log.i(TAG, "accepted config reports no usable rate; using offered 30fps entry")
            configuration = sibling
            return 30
        }
        Log.i(
            TAG,
            "accepted config reports rate ${accepted?.frameRate}; forcing 30fps " +
                "(no 30fps sibling offered)",
        )
        return DEFAULT_FRAME_RATE
    }

    /**
     * One-line dump of every offered `video_configs[]` entry for the Phase 0
     * probe: resolution enum, rate, density, and which index the head unit
     * picked. Dev builds only; release logcat stays quiet like [dumpSurfaces].
     */
    private fun logOfferedConfigs(offered: List<VideoConfiguration>, accepted: Int) {
        if (!BuildConfig.DEV_BUILD) return
        val entries = offered.mapIndexed { index, config ->
            "$index:${config.codecResolution}:@${config.frameRate}:dpi${config.density}" +
                if (index == accepted) "*" else ""
        }
        Log.i(TAG, "offered video configs [${entries.joinToString(", ")}]")
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

        /**
         * Floor for an offered rate we will honor. Below this the head unit
         * is not telling the truth about its cadence (DHU 2.0 offers @1fps
         * and acks far faster), so the stream forces 30 instead.
         */
        const val MIN_SANE_FPS = 15
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

/**
 * Prev/next transport callbacks for the media card's action row. Stored
 * beside [NowPlayingSource] so late wiring reaches an already-up display.
 */
data class TransportCallbacks(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
)

/**
 * Call-card actions for the projected call card. Stored beside the call
 * source so displays created later still get them.
 */
data class CallActions(
    val onAnswer: () -> Unit,
    val onEnd: () -> Unit,
    val onHold: () -> Unit,
    val onMute: () -> Unit,
)
