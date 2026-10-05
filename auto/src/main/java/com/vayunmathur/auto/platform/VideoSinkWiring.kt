package com.vayunmathur.auto.platform

import android.view.Surface
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.library.carhost.HostNavState

/**
 * Everything a [VideoSinkChannel] delegates to its [CarDisplay]: stored feeds,
 * card actions and per-update pushes.
 *
 * Split from the channel for the 25-function cap: the channel keeps the wire
 * protocol (setup/focus/ack/streaming/teardown) while this owns the display
 * surface. Reads the live display through [current], so teardown (which nulls
 * the channel's display) needs no call here -- every push no-ops on null like
 * before. Created once per channel; the initial wiring for a fresh display
 * goes through [applyInitial] from the channel's streaming start.
 */
internal class VideoSinkWiring(
    private val current: () -> CarDisplay?,
) {
    /**
     * Where the car card's now-playing comes from and where its taps go.
     * [NowPlayingSource.get] is read when the render pair comes up (plus every
     * snapshot pushed via [setNowPlaying]); `onTap` toggles phone playback.
     * `null` until the service wires the media monitor -- unset means the card
     * shows empty.
     */
    private var nowPlayingSource: NowPlayingSource? = null

    /** Prev/next transport callbacks; see [setTransportCallbacks]. */
    private var transportCallbacks: TransportCallbacks? = null

    /** Map palette applier; see [setMapDarkApplier]. */
    private var mapDarkApplier: ((Boolean) -> Unit)? = null

    /** Map-touch hookup for displays created later; see [setMapTouchForwarder]. */
    private var mapTouchForwarder: ((Int, Float, Float) -> Boolean)? = null

    /** Phone-status getter; see [setPhoneStatusSource]. */
    private var phoneStatusSource: (() -> PhoneStatus?)? = null

    /** Latest call snapshot getter; see [setCallSource]. */
    private var callSource: (() -> ActiveCallInfo?)? = null

    /** Stored call actions for displays created later; see [setCallSource]. */
    private var storedCallActions: CallActions? = null

    /**
     * Forwards nav-card map surfaces to the session mirror when the render
     * pair comes up after this was set. Cached like [nowPlayingSource] for
     * displays created later.
     */
    private var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)? = null

    /**
     * Wires a fresh display with every stored feed, action and listener: the
     * same block the channel ran inline on streaming start. Called once per
     * render pair with the display under construction.
     */
    fun applyInitial(display: CarDisplay) {
        val source = nowPlayingSource
        display.onMediaTap = source?.onTap
        transportCallbacks?.let { callbacks ->
            display.onPreviousTap = callbacks.onPrevious
            display.onNextTap = callbacks.onNext
        }
        mapDarkApplier?.let { applier -> display.mapDarkApplier = applier }
        mapTouchForwarder?.let { forward -> display.mapTouchForwarder = forward }
        phoneStatusSource?.let { source -> display.phoneStatusSource = source }
        callSource?.let { source ->
            display.setActiveCall(source())
        }
        storedCallActions?.let { actions ->
            display.onAnswerCall = actions.onAnswer
            display.onEndCall = actions.onEnd
            display.onHoldToggle = actions.onHold
            display.onMuteToggle = actions.onMute
        }
        source?.get()?.let { info ->
            if (info.shouldShowCard()) display.setNowPlaying(info)
        }
        mapSurfaceListener?.let { forward ->
            display.setMapSurfaceListener { surface, w, h -> forward(surface, w, h) }
        }
    }

    /** Wires the now-playing feed from the media monitor; see [nowPlayingSource]. */
    fun setNowPlayingSource(get: () -> NowPlayingInfo?, onTap: () -> Unit) {
        nowPlayingSource = NowPlayingSource(get, onTap)
        nowPlayingSource?.get()?.let { info ->
            if (info.shouldShowCard()) current()?.setNowPlaying(info)
        }
        // Late transport wiring must reach an already-up display too.
        transportCallbacks?.let { callbacks ->
            current()?.onPreviousTap = callbacks.onPrevious
            current()?.onNextTap = callbacks.onNext
        }
    }

    /**
     * Wires the media transport callbacks (prev/next) from the media monitor.
     * Stored like [nowPlayingSource] so a display created later still gets
     * them; applied immediately when the render pair is already up.
     */
    fun setTransportCallbacks(onPrevious: () -> Unit, onNext: () -> Unit) {
        transportCallbacks = TransportCallbacks(onPrevious, onNext)
        current()?.onPreviousTap = onPrevious
        current()?.onNextTap = onNext
    }

    /**
     * Wires the map palette applier (`CarAppHost.setNight`) so one
     * [setNightDark] call restyles the rail/cards/drawer and the hosted map.
     * Stored for displays created later; applied immediately when up.
     */
    fun setMapDarkApplier(applier: (Boolean) -> Unit) {
        mapDarkApplier = applier
        current()?.mapDarkApplier = applier
    }

    /**
     * Pushes night + parked state to the car UI. Night restyles the rail,
     * cards, drawer and (via [setMapDarkApplier]) the map palette; parked
     * raises the driving-restriction gate. No-ops with no display up; the
     * display caches both for presentations created later.
     */
    fun setNightDark(dark: Boolean) {
        current()?.setNight(dark)
    }

    /** Pushes parked state to the driving-restriction gate; see [setNightDark]. */
    fun setParkedBrowsingGate(parked: Boolean) {
        current()?.setParkedBrowsingGate(parked)
    }

    /** Closes the drawer at trip end; see `CarDisplay.setParked`. */
    fun closeDrawerOnPark() {
        current()?.setParked(true)
    }

    /**
     * Wires the phone-status feed for the rail cluster. Stored for displays
     * created later; the cluster pulls the latest snapshot on its 1Hz tick.
     * The source lambda is enough -- the display polls it, so nothing needs
     * pushing here.
     */
    fun setPhoneStatusSource(get: () -> PhoneStatus?) {
        phoneStatusSource = get
        current()?.phoneStatusSource = get
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
        current()?.onAnswerCall = onAnswer
        current()?.onEndCall = onEnd
        current()?.onHoldToggle = onHold
        current()?.onMuteToggle = onMute
        current()?.setActiveCall(get())
    }

    /**
     * Pushes one active-call snapshot into the car card, if the render pair
     * is up. The service calls this on every InCall callback so the card
     * tracks ringing/active/held without waiting for a poll.
     */
    fun setActiveCall(info: ActiveCallInfo?) {
        current()?.setActiveCall(info)
    }

    /**
     * Forwards the map-surface touch hookup to the display. Stored for
     * displays created later; applied immediately when the render pair is
     * already up.
     */
    fun setMapTouchForwarder(forward: (Int, Float, Float) -> Boolean) {
        mapTouchForwarder = forward
        current()?.mapTouchForwarder = forward
    }

    fun setNavSource(
        get: () -> NavSnapshot?,
        onMapSurface: (Surface?, Int, Int) -> Unit,
    ) {
        current()?.setMapSurfaceListener(onMapSurface)
        mapSurfaceListener = onMapSurface
        get()?.let { current()?.setNavSnapshot(it) }
    }

    /**
     * Pushes one hosted template state into the car card, if the render pair
     * is up. The session host calls this on every template invalidate so the
     * header tracks the app without waiting for a poll.
     */
    fun setHostNavState(state: HostNavState) {
        current()?.setHostNavState(state)
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
            current()?.setNowPlaying(info)
        } else {
            // Second gate: never hand an idle snapshot to a render pair that
            // came up later via [setNowPlayingSource] either. The cached source
            // still updates so resume re-shows instantly.
            current()?.hideNowPlaying()
        }
    }

    /**
     * Pushes one guidance snapshot to the nav banner, if the render pair is
     * up. The service calls this on every guidance-monitor update; the banner
     * itself decides show vs GONE.
     */
    fun setNavSnapshot(snapshot: NavSnapshot) {
        current()?.setNavSnapshot(snapshot)
    }
}
