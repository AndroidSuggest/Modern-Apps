package com.vayunmathur.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vayunmathur.auto.R
import com.vayunmathur.auto.network.HeadUnitServer
import com.vayunmathur.auto.network.TransportIntake
import com.vayunmathur.auto.platform.AudioSinkChannel
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.CarTts
import com.vayunmathur.auto.platform.GuidanceChannel
import com.vayunmathur.auto.platform.InputChannel
import com.vayunmathur.auto.platform.InputEvent
import com.vayunmathur.auto.platform.MediaEvent
import com.vayunmathur.auto.platform.MediaPlaybackMonitor
import com.vayunmathur.auto.platform.MessagingEvent
import com.vayunmathur.auto.platform.MicPermission
import com.vayunmathur.auto.platform.MicSourceChannel
import com.vayunmathur.auto.platform.CallCardPush
import com.vayunmathur.auto.platform.MusicCaptureSinkHolder
import com.vayunmathur.auto.platform.NavGuidanceMonitor
import com.vayunmathur.auto.platform.CarMapsMirror
import com.vayunmathur.auto.platform.NavStatusChannel
import com.vayunmathur.auto.platform.NightSource
import com.vayunmathur.auto.platform.PhoneStatusMonitor
import com.vayunmathur.auto.platform.SensorChannel
import com.vayunmathur.auto.platform.VideoSinkChannel
import com.vayunmathur.auto.telephony.CarProjectionInCallService
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalCredential
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.MapsGuidance
import com.vayunmathur.auto.protocol.ReconnectBackoff
import com.vayunmathur.auto.protocol.SessionState
import com.vayunmathur.auto.protocol.StreamTransport
import com.vayunmathur.auto.protocol.isMediaBrowserChannel
import kotlin.concurrent.thread

/**
 * Runs a projection session for as long as a head unit is attached.
 *
 * Concurrency, by deliberate split: one [javax.net.ssl.SSLEngine] backs the
 * connection and an engine is not thread-safe, so every wrap/unwrap and every
 * socket write runs on the connection's single I/O thread -- one thread, many
 * senders. The `ma-auto-projection` thread owns reads (the [GalConnection.pump]
 * loop below); the main thread owns media (the [VideoSinkChannel] vsync drain);
 * audio sinks, mic, TTS and input injection only ever enqueue sends, which the
 * I/O thread flushes without blocking the caller -- so no socket write ever runs
 * on the main thread. See `ProjectionService` + `CarDisplay` KDoc for the
 * matching discipline on the UI side.
 */
class ProjectionService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        startForeground(NOTIFICATION_ID, notification())
        publishCredentialExpiry()
        // The now-playing feed outlives any one session: the phone card shows it
        // between connections, and the car card picks the latest up on bring-up.
        mediaMonitor = MediaPlaybackMonitor(this) { info ->
            AutoSessionState.onMediaEvent(MediaEvent.NowPlayingChanged(info))
            video?.setNowPlaying(info)
        }.also { it.start() }
        worker = thread(name = "ma-auto-projection") { serve() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        // Unblocks the worker if it is sitting in accept(): closing the
        // ServerSocket raises there, which runSession reports as a failure the
        // loop then drops because running is false. Interrupt alone cannot do
        // this -- accept() ignores thread interruption.
        server?.close()
        worker?.interrupt()
        mediaMonitor?.stop()
        mediaMonitor = null
        super.onDestroy()
    }

    /** The listening socket; kept so [onDestroy] can unblock [serve]. */
    @Volatile
    private var server: HeadUnitServer? = null

    /** Backoff between sessions; owns no threads, just the delay policy. */
    private val reconnectBackoff = ReconnectBackoff()

    private fun serve() {
        HeadUnitServer().use { server ->
            this.server = server
            try {
                while (running) {
                    // A clean parting goes straight back to listening; a failed session
                    // backs off with jitter so a flapping head unit does not spin the
                    // accept loop at 100% CPU. The sleep below is interruptible, so
                    // destroy() never hangs.
                    val failed = runSession(server)
                    if (!running) return
                    if (failed) {
                        val delayMs = reconnectBackoff.onFailure()
                        Log.i(TAG, "session failed; retrying in ${delayMs}ms")
                        sleepInterruptibly(delayMs)
                    } else {
                        reconnectBackoff.onSuccess()
                    }
                }
            } finally {
                this.server = null
            }
        }
    }

    /**
     * Runs one head-unit session. Returns true when it ended abnormally (back the
     * loop off) rather than with a clean parting.
     *
     * Non-TCP transports park already-opened transports in [TransportIntake]
     * (USB accessory fds, wireless sockets); those drain BEFORE blocking in
     * TCP accept(), so a plugged-in cable never waits behind a DHU that never
     * dials. Ownership stays in this serve loop either way: every transport
     * runs the same session body, and TLS/GAL downstream never learn which
     * intake won.
     */
    private fun runSession(server: HeadUnitServer): Boolean {
        TransportIntake.take()?.let { pending ->
            var failed = false
            runCatching { sessionOnTransport(pending.transport, pending.carName) }
                .onFailure {
                    failed = true
                    // The transport may never have reached the session body
                    // (credential parse threw before GalConnection existed):
                    // close it here so a stillborn intake leaks nothing. A
                    // live session's transport closes with its connection.
                    runCatching { pending.transport.close() }
                    if (running) Log.e(TAG, "projection session ended", it)
                }
            return failed
        }
        var failed = false
        runCatching { session(server) }
            .onFailure {
                failed = true
                if (running) Log.e(TAG, "projection session ended", it)
            }
        return failed
    }

    /** Sleeps [delayMs], returning early on interrupt with the flag restored. */
    private fun sleepInterruptibly(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * The phone's own night state, seeding the sensor stub until the first
     * NIGHT_MODE batch (see [SensorChannel.onChannelOpen]).
     *
     * Reads the car UiMode bit first -- a phone docked in a car holder at
     * night reports car mode -- and falls back to the system night flag.
     * Best-effort: any failure answers "day" rather than failing the
     * bring-up, and the head unit's stream overrides it anyway.
     */
    private fun isNightNow(): Boolean = runCatching {
        val uiMode = getSystemService(UiModeManager::class.java)?.nightMode
            ?: resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        when (uiMode) {
            UiModeManager.MODE_NIGHT_YES -> true
            UiModeManager.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }.getOrDefault(false)

    private fun session(server: HeadUnitServer) {
        server.accept().use { socket ->
            // The TCP loopback path wraps its socket as an intake pending so
            // it runs the same body as USB/wireless: one session shape, three
            // intakes. The socket `use` still owns the socket itself; the
            // session body owns the streams through the connection.
            sessionOnTransport(
                StreamTransport(socket.getInputStream(), socket.getOutputStream()),
                CAR_NAME,
            )
        }
    }

    /**
     * One head-unit session over an already-opened transport. Transport-
     * agnostic: version negotiation, TLS and the whole GAL bring-up are
     * identical for TCP loopback, USB accessory fds and wireless sockets.
     * The caller owns the raw resource (socket or parked transport); this
     * owns everything GAL.
     */
    private fun sessionOnTransport(transport: GalTransport, carName: String) {
        // A head unit is on the wire; the phone status screen leaves Disconnected.
        AutoSessionState.onSocketAccepted()
        val connection = GalConnection(
            transport = transport,
            sslContext = GalCredential.fromAssets(assets),
            deviceModel = Build.MODEL,
            deviceManufacturer = Build.MANUFACTURER,
            onChannelMessage = { message ->
                if (isMediaBrowserChannel(message.channelId)) {
                    // GAL 11/12 gap: the channel message IDs are unmapped, so
                    // these are observed and ignored, never answered. Now-playing
                    // rides the ch2 video stream instead.
                    Log.d(TAG, "ignoring media-browser message on ch${message.channelId}")
                } else if (message.channelId == GalService.NOTIFICATION.id) {
                    messaging?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == GalService.INPUT_SOURCE.id) {
                    if (input == null) {
                        // ch8 traffic with no owner: the channel was never
                        // advertised with an input source, or openNext has not
                        // run yet. Log, don't crash -- the owner binds on its
                        // grant once advertised.
                        Log.w(
                            TAG,
                            "ch8 input traffic with no input owner " +
                                "(0x${message.type.toString(16)}); dropping",
                        )
                        AutoSessionState.onInputEvent(InputEvent.DroppedNoFocus)
                    } else {
                        input?.onMessage(message.channelId, message.type, message.payload)
                    }
                } else if (message.channelId == GalService.SENSOR_SOURCE.id) {
                    sensors?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == GalService.AUDIO_SINK_GUIDANCE.id) {
                    guidance?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == GalService.NAVIGATION_STATUS.id) {
                    navStatus?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == AudioSinkRole.SYSTEM.serviceId) {
                    audioSys?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == AudioSinkRole.MEDIA.serviceId) {
                    audioMedia?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == GalService.AUDIO_SOURCE.id) {
                    mic?.onMessage(message.channelId, message.type, message.payload)
                } else if (message.channelId == GalService.VIDEO_SINK.id) {
                    video?.onMessage(message.channelId, message.type, message.payload)
                } else {
                    // Unowned service (BT, phone-status, radio, vendor, wifi,
                    // car-control and anything future): the channel opened
                    // generically in openNext but no app owner exists -- see
                    // observeUnownedService. Observed and ignored, never
                    // answered and never crashed on; the bring-up moves on.
                    Log.d(
                        TAG,
                        "ignoring message on unowned service ch${message.channelId} " +
                            "(0x${message.type.toString(16)}); no owner",
                    )
                }
            },
            trace = { Log.d(TAG, it) },
        )

        // The session owns focus arbitration; mirror accepted changes to the
        // phone flows so the status screen, input gate and audio sinks all
        // read one source of truth. Sinks recompute their arbitrated gain
        // off the same snapshot -- duck, mute and unmute follow the 0x13.
        connection.session.onFocusChange = {
            AutoSessionState.onFocusChanged(connection.session.focus)
            audioSys?.onFocusChanged()
            audioMedia?.onFocusChanged()
            // Flapping back to input-allowed re-binds ch8: a head unit that
            // parked input on NO_INPUT_FOCUS may need the echo to resume
            // sending reports. requestBinding is idempotent.
            if (connection.session.focus.inputAllowed) {
                input?.requestBinding()
            }
        }

        // Call snapshots push into the car card like focus mirrors into the
        // phone flows: added/changed replace wholesale, removed clears.
        // The card itself decides incoming vs active rows (see updateCallCard).
        // Installed on the process bus (the InCall binding is platform-owned
        // and cannot reference this session directly); cleared on teardown.
        CallCardPush.push = { info -> video?.setActiveCall(info) }

        // Phone-side TTS starts with the session, not with any channel
        // grant: the first notification may arrive before ch4 opens, and
        // the sink resolves lazily -- early utterances drop with a count.
        // It also owns the ch3 guidance stream lifecycle (arms on start,
        // parks on stop); the owner appears on the ch3 grant, after this.
        tts = CarTts(
            this,
            systemSink = { audioSys },
            guidance = { guidance },
            onEvent = AutoSessionState::onAudioEvent,
        ).also { it.start() }

        // Guidance + map mirror start with the session too: fixes flow
        // from the first tick, and the mirror attaches when the nav
        // card's TextureView is ready (or never, if the card is gone).
        mapsMirror = CarMapsMirror(this)
        guidanceMonitor = NavGuidanceMonitor(this) { snapshot ->
            mapsMirror?.render(snapshot)
            video?.setNavSnapshot(snapshot)
            // ch7 live values: the fix folds into the same last-known
            // snapshot the head-unit batches fold into (HANDOFF.md section
            // 10); ch10 turn update as the route advances, deduped inside
            // postUpdate so a stationary fix rate stays quiet.
            sensors?.postLiveEvents(MapsGuidance.toSensorEvents(snapshot))
            navStatus?.postUpdate(MapsGuidance.toNavStatus(snapshot))
            // Car pixels follow the same snapshot: night restyles the rail,
            // cards and drawer plus the map palette; parked clears the
            // driving-restriction gate (drawer lockout down, media row
            // visible). Both are last-known-wins with the ch7 NIGHT_MODE
            // batch (the head unit owns the car truth; the phone only seeds
            // it). Trip-end drawer close stays on setParked.
            video?.setNightDark(snapshot.isNight ?: isNightNow())
            video?.setParkedBrowsingGate(snapshot.parked)
            if (snapshot.parked) video?.closeDrawerOnPark()
        }.also { it.start() }

        // Music capture starts with the session too, in its own
        // mediaProjection-typed service (the projection type cannot ride
        // on this service -- see MusicCaptureService). No stored grant
        // means fail-closed silence on ch5.
        MusicCaptureService.startIfGranted(this)

        // Phone status starts with the session too: the rail cluster pulls
        // signal/battery/DND/badge from it on its 1Hz tick, and the call
        // card pushes come from the InCall owners below.
        phoneStatusMonitor = PhoneStatusMonitor(this).also { it.start() }

        try {
            pumpUntilGone(connection, carName)
        } finally {
            // A pump exception must not leak the session: the render pair tears
            // down, the phone UI parts cleanly, and the error still propagates
            // to runSession so the backoff loop sees the failure.
            Log.i(TAG, "head unit disconnected: ${connection.session.failure ?: "cleanly"}")
            AutoSessionState.onSessionEnd(connection.session.failure)
            // Park the connection's I/O thread first: closing the socket
            // unblocks the pump read, and late sends drop rather than racing
            // the shutdown. The socket `use` below closes the streams anyway.
            // (On the intake path the parked transport closes with this
            // connection too; a stillborn intake that never built one closes
            // in runSession instead.)
            runCatching { connection.close() }
            video?.release()
            video = null
            messaging?.release()
            messaging = null
            input = null
            audioSys?.release()
            audioSys = null
            audioMedia?.release()
            audioMedia = null
            MusicCaptureSinkHolder.sink = null
            mic?.release()
            mic = null
            tts?.stop()
            tts = null
            guidanceMonitor?.stop()
            guidanceMonitor = null
            mapsMirror?.release()
            mapsMirror = null
            MusicCaptureService.stop(this)
            MusicCaptureSinkHolder.sink = null
            // Unowned services need no teardown: their channels opened
            // generically with no app owner (see openNext), so closing the
            // connection above already released everything they hold.
            sensors?.release()
            sensors = null
            guidance?.release()
            guidance = null
            navStatus?.release()
            navStatus = null
            phoneStatusMonitor?.stop()
            phoneStatusMonitor = null
            CallCardPush.push = null
        }
    }

    /**
     * The bring-up + streaming loop. Pump owns reads only -- never the encoder and
     * never socket writes: the sink drains itself on the main thread at vsync
     * cadence (see VideoSinkChannel.startVsyncDrain) and the connection's single
     * I/O thread owns every wrap/unwrap/write. Channel opens go out sequentially
     * in HU-discovery wire order, one in flight at a time; see [openNext].
     */
    private fun pumpUntilGone(connection: GalConnection, carName: String) {
        var setupVideo = false
        var reportedActive = false
        var setupMessaging = false
        var setupInput = false
        var setupSensors = false
        var setupGuidance = false
        var setupNavStatus = false
        var setupAudioSys = false
        var setupAudioMedia = false
        var setupMic = false
        while (running && connection.pump()) {
            // Open every advertised service in HU-discovery wire order, one at a
            // time: the next 0x7 goes out only once the previous channel is
            // granted (openChannels) or refused (refusedChannels, or a bare
            // 0xff with no 0x8 -- DHU 2.0 answers that way). gearhead opens in
            // this same order (`jbp.i[]` follows the `xob.c` wire order).
            // Video setup additionally waits for its own channel grant.
            if (connection.session.state == SessionState.ACTIVE) {
                if (!reportedActive) {
                    reportedActive = true
                    AutoSessionState.onActive(carName)
                    // The control-24 call-availability verdict (parsed
                    // protocol-side into session.callAvailable) mirrors into
                    // the phone flows with the session, so the call UI reads
                    // one source of truth like focus above.
                    connection.session.callAvailable?.let {
                        AutoSessionState.onCallAvailability(it)
                    }
                }
                openNext(connection)
            }
            if (!setupVideo && GalService.VIDEO_SINK.id in connection.session.openChannels) {
                setupVideo = true
                video?.requestSetup()
            }
            // ch14 starts mirroring on its own grant, like video setup waits
            // for its grant: posting before the HU opens the channel earns a
            // bare 0xff.
            if (!setupMessaging && GalService.NOTIFICATION.id in connection.session.openChannels) {
                setupMessaging = true
                messaging?.onChannelOpen()
            }
            // ch8 binds on its own grant, like video setup and ch14
            // mirroring wait for theirs: binding before the HU opens the
            // channel earns a bare 0xff.
            if (!setupInput && GalService.INPUT_SOURCE.id in connection.session.openChannels) {
                setupInput = true
                input?.requestBinding()
            }
            // ch7 subscribes to the stub set on its own grant: subscribing
            // before the HU opens the channel earns a bare 0xff.
            if (!setupSensors && GalService.SENSOR_SOURCE.id in connection.session.openChannels) {
                setupSensors = true
                sensors?.onChannelOpen()
            }
            // ch3 claims the sink on its own grant, then stays idle: a
            // started stream the phone never feeds is worse than an idle
            // sink the head unit expects no frames from.
            if (!setupGuidance && GalService.AUDIO_SINK_GUIDANCE.id in connection.session.openChannels) {
                setupGuidance = true
                guidance?.onChannelOpen()
            }
            // ch10 posts the inactive stub on its own grant, like the rest:
            // posting before the HU opens the channel earns a bare 0xff.
            if (!setupNavStatus && GalService.NAVIGATION_STATUS.id in connection.session.openChannels) {
                setupNavStatus = true
                navStatus?.onChannelOpen()
            }
            // ch4/ch5 claim their sinks on their own grants: setup before the
            // HU opens the channel earns a bare 0xff.
            if (!setupAudioSys && AudioSinkRole.SYSTEM.serviceId in connection.session.openChannels) {
                setupAudioSys = true
                audioSys?.requestSetup()
            }
            if (!setupAudioMedia && AudioSinkRole.MEDIA.serviceId in connection.session.openChannels) {
                setupAudioMedia = true
                audioMedia?.requestSetup()
            }
            // ch6 starts acking on its own grant: the head unit opens it and
            // starts talking, and we ack from the first chunk.
            if (!setupMic && GalService.AUDIO_SOURCE.id in connection.session.openChannels) {
                setupMic = true
                mic?.onChannelOpen()
            }
        }
    }

    private var video: VideoSinkChannel? = null

    /**
     * The Phase 2 input owner, created when ch8 opens in [openNext] like the
     * video sink. Outlives nothing: released with the session above. Injection
     * gates on the arbitrated input flag and scales into the video sink's
     * display size; the sinks forward into the car UI and count as consumed
     * when the render pair is up.
     */
    private var input: InputChannel? = null

    /**
     * The Phase 7 messaging owner, created when ch14 opens in [openNext] like
     * the video sink. Outlives nothing: released with the session above.
     *
     * Audio seam: [CarMessagingAudio] (TTS on the ch4 system sink, voice
     * replies on the ch6 mic source); [MessagingAudio.NoOp] remains the seam's
     * pre-audio holder for tests. Assumptions live in `MessagingAudio.kt`.
     */
    private var messaging: MessagingCarAppService? = null

    /**
     * The Phase 3 audio owners: system sink (ch4, transient) and media sink
     * (ch5, music), created when their channels open in [openNext] like the
     * video sink. Outlive nothing: released with the session above. Each
     * writes samples on its own thread; the pump thread never touches audio.
     * The sinks gate on the arbitrated focus snapshot, refreshed on every
     * 0x13 via the session's focus listener. Ch3 guidance stays with
     * sensors-dev's [GuidanceChannel] stub -- this owner answers only for 4/5.
     */
    private var audioSys: AudioSinkChannel? = null
    private var audioMedia: AudioSinkChannel? = null

    /**
     * The Phase 3 mic owner (ch6, upstream), created when the source channel
     * opens in [openNext]. Outlives nothing: released with the session above.
     * Retention is permission-gated ([MicPermission]) -- without the grant
     * chunks are acked and counted only.
     */
    private var mic: MicSourceChannel? = null

    /**
     * Phone-side TTS feeding the system sink. Outlives nothing: started with
     * the session (before any channel grant -- the first notification may
     * arrive before ch4 opens), stopped with it. Feeds through the sink's
     * thread-safe `feedWav`; never blocks the caller.
     */
    private var tts: CarTts? = null

    /**
     * The Phase 5 sensor owner, created when ch7 opens in [openNext] like the
     * video sink. Outlives nothing: released with the session above. Subscribes
     * to the stub set on its grant (parked, night-follows-phone, last-known
     * location, speed 0); live values land with Phase 6 maps-dev through the
     * [SensorChannel] seams.
     */
    private var sensors: SensorChannel? = null

    /**
     * The Phase 5 guidance owner, created when ch3 opens in [openNext] like
     * the video sink. Outlives nothing: released with the session above.
     * Claims the sink on its grant through audio-dev's shared AudioCodec sink
     * shape and stays idle; live TTS starts the stream through the
     * [GuidanceChannel] seam.
     */
    private var guidance: GuidanceChannel? = null

    /**
     * The Phase 5 nav-status owner, created when ch10 opens in [openNext]
     * like the video sink. Outlives nothing: released with the session above.
     * Posts the inactive stub on its grant; live turn updates land with Phase
     * 6 maps-dev through [NavStatusChannel.postStatus].
     */
    private var navStatus: NavStatusChannel? = null

    /** Outlives sessions; owned by the service, not the pump thread. */
    private var mediaMonitor: MediaPlaybackMonitor? = null

    /**
     * Session-scoped phone status (signal/battery/DND/badge) for the rail
     * cluster. Started with the session like TTS; the cluster pulls the
     * latest snapshot on its 1Hz tick. Stopped with the session.
     */
    private var phoneStatusMonitor: PhoneStatusMonitor? = null

    /**
     * Session-scoped guidance + map mirror, like TTS: started with the
     * session (position/puck are live from the first fix), stopped with it.
     * Snapshots render into the mirror and push the nav banner; with no
     * location permission the monitor holds last-known and the map stays put.
     */
    private var guidanceMonitor: NavGuidanceMonitor? = null
    private var mapsMirror: CarMapsMirror? = null

    /**
     * Seeds the credential-expiry flow once per service start, off the pump thread.
     *
     * Reads the shipped leaf through the public asset constant, so a rotation that
     * swaps the PEMs updates the session card with no code change. On parse
     * failure the flow stays null (unknown) instead of failing the service.
     */
    private fun publishCredentialExpiry() {
        runCatching {
            assets.open(GalCredential.CERT_ASSET).use { it.readBytes().decodeToString() }
        }.onSuccess { certPem ->
            AutoSessionState.onCredentialExpiry(GalCredential.daysRemaining(certPem))
        }.onFailure {
            Log.w(TAG, "could not read GAL leaf expiry", it)
        }
    }

    /**
     * Opens the next not-yet-attempted advertised service, in the HU's wire order.
     *
     * Only one open is ever in flight: while `pendingChannels` is non-empty the
     * head unit still owes us an answer, so wait.
     *
     * Service 1 IS sent, first: gearhead opens channel 0 locally and never sends
     * a 0x7 for it, but DHU 2.0 rejects a first-0x7 for service 2 with an empty
     * 0xff and no 0x8 (Run 4) -- so the HU-side may expect the wire-order
     * sequence to start at 1. If the HU 0xffs/0x8-refuses service 1 as well, it
     * lands in refusedChannels like any other refusal and the bring-up moves on
     * to service 2; the experiment then distinguishes "first-open must be 1"
     * (0x8 arrives for 1) from "video refused regardless" (both refused).
     */
    private fun openNext(connection: GalConnection) {
        val session = connection.session
        if (session.pendingChannels.isNotEmpty()) return
        val next = session.services.firstOrNull {
            it.id !in session.openChannels &&
                it.id !in session.refusedChannels
        } ?: return
        connection.send(session.openChannel(next))
        Log.i(TAG, "requesting channel open for service ${next.id}")
        if (next.id == GalService.VIDEO_SINK.id && next.hasMediaSink()) {
            video = VideoSinkChannel(this, next, connection, AutoSessionState::onVideoEvent)
                .also { sink ->
                    sink.setNowPlayingSource(
                        get = { AutoSessionState.nowPlaying.value },
                        onTap = { mediaMonitor?.toggle() },
                    )
                    // Prev/next ride the same monitor as the card tap; unset
                    // (null monitor) means the buttons show but stay disabled.
                    sink.setTransportCallbacks(
                        onPrevious = { mediaMonitor?.seekToPrevious() },
                        onNext = { mediaMonitor?.seekToNext() },
                    )
                    // Night reaches the map palette through the same call
                    // that restyles the rail/cards/drawer (see setNightDark).
                    sink.setMapDarkApplier { dark -> mapsMirror?.setDark(dark) }
                    // Rail cluster + call card feeds: the display caches
                    // both for presentations created later.
                    sink.setPhoneStatusSource { phoneStatusMonitor?.snapshot }
                    sink.setCallSource(
                        get = { AutoSessionState.activeCall.value },
                        onAnswer = { CarProjectionInCallService.answerCall() },
                        onEnd = { CarProjectionInCallService.endCall() },
                        onHold = { CarProjectionInCallService.toggleHold() },
                        onMute = { CarProjectionInCallService.toggleMute() },
                    )
                    sink.setNavSource(
                        get = { guidanceMonitor?.snapshots?.value },
                        onMapSurface = { surface, w, h ->
                            val mirror = mapsMirror ?: return@setNavSource
                            if (surface != null) mirror.setSurface(surface, w, h)
                            else mirror.release()
                        },
                    )
                }
        }
        // ch14 advertises in the same wire-order pass; the owner starts
        // mirroring on its grant (see MessagingCarAppService.onChannelOpen).
        // The audio seam resolves channels lazily -- ch14 opens before ch4/6
        // in wire order, and TTS started with the session above, so early
        // utterances wait for no grant.
        if (next.id == GalService.NOTIFICATION.id) {
            messaging = MessagingCarAppService(
                connection,
                audio = CarMessagingAudio(
                    tts = { tts },
                    mic = { mic },
                    onEvent = AutoSessionState::onAudioEvent,
                ),
                onEvent = AutoSessionState::onMessagingEvent,
                onReply = { threadId, text ->
                    Log.i(TAG, "head-unit reply for $threadId (${text.length} chars)")
                },
                context = { this },
            )
        }
        // ch8 advertises in the same wire-order pass; the owner binds on its
        // grant (see InputChannel.requestBinding).
        if (next.id == GalService.INPUT_SOURCE.id && next.hasInputSource()) {
            input = InputChannel(
                service = next,
                connection = connection,
                isInputAllowed = { connection.session.focus.inputAllowed },
                displaySize = { video?.displaySize() },
                onEvent = AutoSessionState::onInputEvent,
                touchSink = { touch -> video?.injectTouch(touch) ?: false },
                keySink = { key -> video?.injectKey(key.keycode, key.down) ?: false },
                scrollSink = { delta -> video?.injectScroll(delta) ?: false },
            )
        }
        // ch7 advertises in the same wire-order pass; the owner subscribes
        // to the full 26-type set on its grant (see SensorChannel.onChannelOpen).
        // Night follows the phone until the first NIGHT_MODE batch; the
        // UiModeManager seam stays out of the service -- maps-dev owns the
        // live night source next.
        if (next.id == GalService.SENSOR_SOURCE.id) {
            sensors = SensorChannel(
                connection = connection,
                night = NightSource { isNightNow() },
                onEvent = AutoSessionState::onSensorEvent,
            )
        }
        // ch3 advertises in the same wire-order pass; the owner claims the
        // sink on its grant (see GuidanceChannel). The stream itself is
        // TTS-owned: the session predates ch3, so the grant arms it when the
        // TTS session is already running.
        if (next.id == GalService.AUDIO_SINK_GUIDANCE.id && next.hasMediaSink()) {
            guidance = GuidanceChannel(
                service = next,
                connection = connection,
                ttsActive = { tts != null },
                onEvent = AutoSessionState::onSensorEvent,
            )
        }
        // ch10 advertises in the same wire-order pass; the owner posts the
        // inactive stub on its grant (see NavStatusChannel.onChannelOpen).
        if (next.id == GalService.NAVIGATION_STATUS.id) {
            navStatus = NavStatusChannel(
                connection = connection,
                onEvent = AutoSessionState::onSensorEvent,
            )
        }
        // ch4/ch5 advertise in the same wire-order pass; the owners claim
        // their sinks on the grant (see AudioSinkChannel.requestSetup).
        // Focus asks ride control 0x18 right after setup -- the head unit
        // answers with 0x13 notifications, which refresh the sink gains.
        if (next.id == AudioSinkRole.SYSTEM.serviceId && next.hasMediaSink()) {
            audioSys = AudioSinkChannel(
                role = AudioSinkRole.SYSTEM,
                service = next,
                connection = connection,
                focus = { connection.session.focus },
                onEvent = AutoSessionState::onAudioEvent,
            )
        }
        if (next.id == AudioSinkRole.MEDIA.serviceId && next.hasMediaSink()) {
            audioMedia = AudioSinkChannel(
                role = AudioSinkRole.MEDIA,
                service = next,
                connection = connection,
                focus = { connection.session.focus },
                onEvent = AutoSessionState::onAudioEvent,
            )
            // The music-capture service resolves this lazily per feed; publish
            // it now so capture starts flowing once the sink starts.
            MusicCaptureSinkHolder.sink = audioMedia
        }
        // ch6 advertises in the same wire-order pass; the owner starts
        // acking on the grant (see MicSourceChannel.onChannelOpen).
        // Retention needs RECORD_AUDIO; without it chunks are acked and
        // counted only, so the head unit still sees a live endpoint.
        if (next.id == GalService.AUDIO_SOURCE.id) {
            mic = MicSourceChannel(
                connection = connection,
                retentionAllowed = { MicPermission.isGranted(this) },
                onEvent = AutoSessionState::onAudioEvent,
            )
        }
        observeUnownedService(next)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.projection_running))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MaAuto.Service"
        private const val CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 1

        /**
         * Interim car name until the head unit reports its own. The GAL services the DHU
         * advertises carry no human-readable name, so the status screen says where the
         * pixels are going rather than guessing a make.
         */
        private const val CAR_NAME = "Desktop Head Unit"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }
    }
}
