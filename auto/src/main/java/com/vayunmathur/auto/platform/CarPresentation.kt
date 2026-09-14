package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_DRAWER_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_RAIL_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_ROOT_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.MATCH
import com.vayunmathur.auto.platform.CarUiMetrics.MAX_INVALIDATE_FPS
import com.vayunmathur.auto.platform.CarUiMetrics.WRAP
import java.text.DateFormat
import java.util.Date

/**
 * The car launcher presentation: a driving-safe home screen with Coolwalk
 * split cards (nav + media + call), a facet rail, an app drawer,
 * template-decor stubs and scrims, composed from the `Car*View` components.
 *
 * The presentation owns assembly + lifecycle only: every surface lives in
 * its component (`CarRailView`, `CarMediaCardView`, `CarNavCardView`,
 * `CarCallCardView`, `CarDrawerView`), and every update fans out to the
 * owning component. Views, not Compose (private virtual display, no
 * lifecycle owner).
 */
internal class CarPresentation(
    private val context: Context,
    display: android.view.Display,
    initialNowPlaying: NowPlayingInfo?,
    initialDrivingRestricted: Boolean,
    initialNight: Boolean,
    initialActiveCall: ActiveCallInfo?,
    initialPhoneStatusSource: (() -> PhoneStatus?)?,
    onMediaTap: () -> Unit,
    onPreviousTap: () -> Unit,
    onNextTap: () -> Unit,
    onAnswerCallTap: () -> Unit,
    onEndCallTap: () -> Unit,
    onHoldToggleTap: () -> Unit,
    onMuteToggleTap: () -> Unit,
    private val onCardBounds: (Int, Int, Int, Int) -> Unit,
) : Presentation(context, display) {

    /**
     * Snapshot that arrived before `onCreate` built the card views.
     * Consumed once in `onCreate`; after that the card exists and updates
     * apply directly.
     */
    private var pendingNowPlaying: NowPlayingInfo? = initialNowPlaying

    /** Driving-restriction gate that arrived before `onCreate`. */
    private var pendingDrivingRestricted: Boolean = initialDrivingRestricted

    /** Night palette that arrived before `onCreate`. */
    private var pendingNight: Boolean = initialNight

    /** Active call that arrived before `onCreate` built the card. */
    private var pendingActiveCall: ActiveCallInfo? = initialActiveCall

    /**
     * Phone status feed captured from the outer display at construction:
     * [CarDisplay.phoneStatusSource] may be wired after `show()` starts
     * the presentation build, so the outer setter forwards late wiring
     * here. The ticker pulls through this, never the outer.
     */
    private var outerPhoneStatusSource: (() -> PhoneStatus?)? = initialPhoneStatusSource

    /**
     * Media/call callbacks captured from the outer display at
     * construction, with late-wiring setters below (the service may wire
     * them after `show()` starts the build).
     */
    private var outerOnMediaTap: () -> Unit = onMediaTap
    private var outerOnPreviousTap: () -> Unit = onPreviousTap
    private var outerOnNextTap: () -> Unit = onNextTap
    private var outerOnAnswerCall: () -> Unit = onAnswerCallTap
    private var outerOnEndCall: () -> Unit = onEndCallTap
    private var outerOnHoldToggle: () -> Unit = onHoldToggleTap
    private var outerOnMuteToggle: () -> Unit = onMuteToggleTap

    private var rootView: LinearLayout? = null
    private var rail: CarRailView? = null
    private var mediaCard: CarMediaCardView? = null
    private var navCard: CarNavCardView? = null
    private var callCard: CarCallCardView? = null
    private var drawer: CarDrawerView? = null
    private var assistantScrim: View? = null
    private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private var drivingRestricted = false
    private var nightDark = false

    /**
     * Receives the nav-card map surface: (surface, w, h), or (null, 0, 0)
     * when the TextureView is destroyed. The session's mirror attaches on
     * non-null and releases on null; unset means no map backend.
     */
    var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)? = null

    // Real wall-clock time, ticking every second. A driver needs to know
    // what time it is, and the moving digits still prove frames are
    // flowing rather than one stale image. The same tick refreshes the
    // phone status cluster (signal/battery/DND/badge) from the service
    // monitor and the call duration while active.
    private val ticker = object : Runnable {
        override fun run() {
            rail?.tickClock(outerPhoneStatusSource) ?: return
            callCard?.tickDuration()
            rail?.view?.postDelayed(this, 1000)
        }
    }

    /**
     * Continuous re-render at [fps] so the encoder surface produces frames
     * even when no view changes on its own. Main thread only. Backs off to
     * a Choreographer-frame chain when [fps] is at/above display cadence.
     */
    private var invalidator: Runnable? = null
    private var invalidatorFps = 0

    fun startFrameInvalidation(fps: Int) {
        stopFrameInvalidation()
        val decor = window?.decorView ?: return
        val intervalMs = (1000L / fps.coerceIn(1, MAX_INVALIDATE_FPS)).coerceAtLeast(0)
        invalidatorFps = fps
        val tick = object : Runnable {
            override fun run() {
                decor.invalidate()
                decor.postDelayed(this, intervalMs)
            }
        }
        invalidator = tick
        decor.post(tick)
    }

    fun stopFrameInvalidation() {
        val decor = window?.decorView
        invalidator?.let { decor?.removeCallbacks(it) }
        invalidator = null
        invalidatorFps = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Coolwalk facet structure, matching gearhead's
        // `gh_coolwalk_facet_bar` + dashboard cards + drawer:
        // content area (call card, split nav/media cards, scrims, drawer
        // pane, voice plate) above a bottom facet bar (dashboard button,
        // assistant slot, ongoing-widget slots, hotseat dock, status,
        // resize + native-rail stubs). Views, not Compose (see the class
        // KDoc).
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_ROOT_DAY))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        rootView = root
        val apps = CarApps.query(context)
        val railView = CarRailView(
            context,
            apps,
            onOpenDrawer = { openDrawer() },
            onCloseDrawer = { closeDrawer() },
        )
        rail = railView
        val media = CarMediaCardView(
            context,
            onMediaTap = { outerOnMediaTap() },
            onPreviousTap = { outerOnPreviousTap() },
            onNextTap = { outerOnNextTap() },
            onCardBounds = onCardBounds,
        )
        mediaCard = media
        val nav = CarNavCardView(context, apps)
        navCard = nav
        nav.mapSurfaceListener = mapSurfaceListener
        val call = CarCallCardView(
            context,
            onAnswer = { outerOnAnswerCall() },
            onEnd = { outerOnEndCall() },
            onHold = { outerOnHoldToggle() },
            onMute = { outerOnMuteToggle() },
        )
        callCard = call
        val drawerView = CarDrawerView(context, apps)
        drawer = drawerView
        val content = DrawerContainer(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            onDrawerBack = { closeDrawer() }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                    setPadding(
                        context.carDp(16),
                        context.carDp(8),
                        context.carDp(16),
                        context.carDp(8),
                    )
                    addView(call.view)
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                            addView(
                                nav.view.apply {
                                    layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                                        marginEnd = context.carDp(8)
                                    }
                                },
                            )
                            addView(
                                media.view.apply {
                                    layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                                        marginStart = context.carDp(8)
                                    }
                                },
                            )
                        },
                    )
                },
            )
            addView(drawerView.scrim)
            addView(drawerView.pane)
            addView(drawerView.voicePlateView)
            addView(
                drawerView.assistantScrimView.also { assistantScrim = it },
            )
        }
        root.addView(content)
        root.addView(railView.view)
        // Template-decor structures (AppDecor header + app bar): exact
        // gearhead metrics, stubbed gone with no template host.
        root.addView(drawerView.decorHeader)
        root.addView(railView.appBarView())
        setContentView(root)

        pendingNowPlaying?.let { media.update(it) }
        pendingNowPlaying = null
        setDrivingRestricted(pendingDrivingRestricted)
        setNight(pendingNight)
        pendingActiveCall?.let { call.update(it) }
        pendingActiveCall = null

        railView.tickClock(outerPhoneStatusSource)
        window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener { _, newFocus ->
            // Focus-loss close: when the drawer is open and focus lands
            // outside the drawer subtree (and not on the hotseat drawer
            // cell that opened it), the drawer dismisses.
            val pane = drawerView.pane
            if (!drawerView.isOpen || newFocus == null) return@addOnGlobalFocusChangeListener
            var node: View? = newFocus
            while (node != null) {
                if (node === pane) return@addOnGlobalFocusChangeListener
                node = node.parent as? View
            }
            closeDrawer()
        }
    }

    /** Hides the now-playing card; the cached snapshot is untouched. Main thread only. */
    fun hideNowPlaying() {
        mediaCard?.hide()
    }

    /**
     * Applies a snapshot to the now-playing card. Main thread only: called
     * from the posted update in `CarDisplay.setNowPlaying`, or from
     * `onCreate` above for a snapshot that arrived before the views existed.
     */
    fun updateNowPlaying(info: NowPlayingInfo) {
        mediaCard?.update(info)
    }

    /**
     * Pushes one guidance snapshot to the nav card. Main thread only.
     * The card shows the banner only while guidance is active with a road
     * or maneuver to announce; otherwise GONE (idle map, no fake banner).
     */
    fun updateNavSnapshot(snapshot: NavSnapshot) {
        navCard?.update(snapshot)
    }

    /**
     * Pushes one active-call snapshot into the card. Main thread only;
     * null hides the card.
     */
    fun updateCallCard(info: ActiveCallInfo?) {
        callCard?.update(info)
    }

    /**
     * Opens the drawer: lazy scrim fade to the 0.8 dim. Main thread only.
     */
    fun openDrawer() {
        val scrim = drawer?.scrim
        if (scrim != null) {
            scrim.setBackgroundColor(Color.argb(0, 0x10, 0x14, 0x18))
            scrim.visibility = View.VISIBLE
            scrim.post { scrim.setBackgroundColor(Color.argb(204, 0x10, 0x14, 0x18)) }
        }
        drawer?.openDrawer()
    }

    /**
     * Closes the drawer and hides its scrim. Main thread only. Called
     * from the scrim tap, the BACK key (see [DrawerContainer]), launcher
     * taps, the Home facet button, focus loss, and trip-end park.
     */
    fun closeDrawer() {
        drawer?.closeDrawer()
        drawer?.scrim?.visibility = View.GONE
    }

    /**
     * Driving-restriction gate: hides the media action row and raises
     * the drawer lockout scrim while the drawer is open. Main thread only.
     */
    fun setDrivingRestricted(restricted: Boolean) {
        drivingRestricted = restricted
        mediaCard?.setButtonsVisible(!restricted)
        drawer?.drivingRestricted = restricted
        drawer?.applyLockout()
    }

    /**
     * Shows or hides the drawer loading spinner (48dp indeterminate).
     * Main thread only.
     */
    fun setDrawerLoading(loading: Boolean) {
        drawer?.setDrawerLoading(loading)
    }

    /**
     * Shows or hides the truncated-list card (88dp, bottom). Main thread only.
     */
    fun setDrawerTruncated(truncated: Boolean) {
        drawer?.setDrawerTruncated(truncated)
    }

    /**
     * Shows or hides the parked-browsing exit header (88dp). Main thread only.
     */
    fun setParkedBrowsing(active: Boolean) {
        drawer?.setParkedBrowsing(active)
    }

    /**
     * Shows the alpha-jump fast-scroll entry points. Main thread only.
     */
    fun setAlphaJumpVisible(visible: Boolean) {
        drawer?.setAlphaJumpVisible(visible)
    }

    /** Late-wired media tap; see the outer setter. Main thread only. */
    fun setOnMediaTap(tap: () -> Unit) {
        outerOnMediaTap = tap
    }

    /** Late-wired previous tap; see the outer setter. Main thread only. */
    fun setOnPreviousTap(tap: () -> Unit) {
        outerOnPreviousTap = tap
    }

    /** Late-wired next tap; see the outer setter. Main thread only. */
    fun setOnNextTap(tap: () -> Unit) {
        outerOnNextTap = tap
    }

    /** Late-wired answer action; see the outer setter. Main thread only. */
    fun setOnAnswerCall(tap: () -> Unit) {
        outerOnAnswerCall = tap
    }

    /** Late-wired end action; see the outer setter. Main thread only. */
    fun setOnEndCall(tap: () -> Unit) {
        outerOnEndCall = tap
    }

    /** Late-wired hold action; see the outer setter. Main thread only. */
    fun setOnHoldToggle(tap: () -> Unit) {
        outerOnHoldToggle = tap
    }

    /** Late-wired mute action; see the outer setter. Main thread only. */
    fun setOnMuteToggle(tap: () -> Unit) {
        outerOnMuteToggle = tap
    }

    /** Late-wired phone-status feed; see the outer setter. Main thread only. */
    fun setInnerPhoneStatusSource(source: () -> PhoneStatus?) {
        outerPhoneStatusSource = source
    }

    /**
     * Switches the day/night palette. Main thread only. Deltas follow
     * §2.3 exactly where gearhead tokens exist (see `CarDrawerView` /
     * `CarRailView` for the per-surface values). MA-only choices, reported
     * in PARITY.md: root/drawer day `#101418` (night takes the exact
     * `#ff172026` card token), rail day `#161C24` -> night `#101418`.
     */
    fun setNight(dark: Boolean) {
        nightDark = dark
        rootView?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_DRAWER_NIGHT else COLOR_ROOT_DAY),
        )
        rail?.setNight(dark)
        mediaCard?.setNight(dark)
        navCard?.setNight(dark)
        drawer?.setNight(dark)
    }

    override fun onStop() {
        rail?.view?.removeCallbacks(ticker)
        stopFrameInvalidation()
        rootView = null
        rail?.clear()
        rail = null
        mediaCard?.clear()
        mediaCard = null
        navCard?.clear()
        navCard = null
        callCard?.clear()
        callCard = null
        drawer?.clear()
        drawer = null
        assistantScrim = null
        super.onStop()
    }

    private fun dp(value: Int): Int = context.carDp(value)
}
