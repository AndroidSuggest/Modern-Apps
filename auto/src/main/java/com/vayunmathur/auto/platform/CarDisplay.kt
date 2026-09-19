package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vayunmathur.auto.BuildConfig
import com.vayunmathur.auto.protocol.NavSnapshot
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * The car's screen, as a virtual display rendered into the encoder's input surface.
 *
 * Private by default, trusted with the role: a trusted display needs
 * `ADD_TRUSTED_DISPLAY`, which arrives with `SYSTEM_AUTOMOTIVE_PROJECTION`, and is
 * only required to launch other apps' activities onto the display. A [Presentation]
 * owned by this app renders onto a private display perfectly well, which is enough to
 * get pixels onto a head unit and prove the video path end to end — so the DHU
 * loopback path never needs the flag.
 *
 * Compose, with its own lifecycle: the private virtual display has no activity
 * lifecycle, so the display owns a [CarDisplayLifecycle] stepped manually and
 * sets its three `ViewTree` owners on the decor view before content goes in.
 * The hierarchy inside is a single `ComposeView` (see [CarPresentation]).
 */
class CarDisplay(
    private val context: Context,
    private var width: Int,
    private var height: Int,
    private var densityDpi: Int,
    /**
     * Phase 9 display-route hook: true requests `VIRTUAL_DISPLAY_FLAG_TRUSTED` so
     * other apps' activities may be launched onto the car screen. Driven by
     * [DisplayRoutePolicy] from the MAOS role (see `VideoSinkChannel`); defaults
     * off so stock phones and the loopback path keep working permission-free.
     */
    private val trusted: Boolean = false,
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var carLifecycle: CarDisplayLifecycle? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fires when the now-playing card is tapped, locally or from the head unit
     * via [handleCarTap]. Wired to the media monitor's transport toggle.
     */
    var onMediaTap: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnMediaTap(v) } }
        }

    /** Fires when the media card's previous-track button is tapped. Null until wired. */
    var onPreviousTap: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnPreviousTap(v) } }
        }

    /** Fires when the media card's next-track button is tapped. Null until wired. */
    var onNextTap: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnNextTap(v) } }
        }

    /**
     * Optional night hook for the hosted map: invoked from [setNight] on the
     * calling thread. The service sets this to `CarAppHost.setNight`, which
     * forwards night as a configuration change so the app restyles its own
     * palette; unset means the hosted map keeps its own last palette.
     */
    var mapDarkApplier: ((Boolean) -> Unit)? = null

    /** Phone status feed for the rail cluster; the service sets its monitor. */
    var phoneStatusSource: (() -> PhoneStatus?)? = null
        set(value) {
            field = value
            // Late wiring must reach an already-up presentation too: the
            // ticker pulls through the inner field, never this one.
            value?.let { v -> mainHandler.post { presentation?.setInnerPhoneStatusSource(v) } }
        }

    /**
     * Forwards ch8 touches that land on the map surface to the hosted app.
     * Wired by the session host to `CarAppHost.injectMapTouch`; cached for
     * presentations created later like every other late-wired feed.
     */
    var mapTouchForwarder: ((Int, Float, Float) -> Boolean)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setMapTouchForwarder(v) } }
        }

    /** Call-card actions; the service wires these to the bound InCallService. */
    var onAnswerCall: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnAnswerCall(v) } }
        }
    var onEndCall: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnEndCall(v) } }
        }
    var onHoldToggle: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnHoldToggle(v) } }
        }
    var onMuteToggle: (() -> Unit)? = null
        set(value) {
            field = value
            value?.let { v -> mainHandler.post { presentation?.setOnMuteToggle(v) } }
        }

    /**
     * Active-call feed for the projected call card; the service sets the
     * latest [ActiveCallInfo] (null with no live call) through [setActiveCall].
     * Backing field only -- the `var` setter would clash on the JVM with the
     * explicit setter, so all writes go through the function.
     */
    @Volatile
    private var activeCall: ActiveCallInfo? = null

    /**
     * Latest now-playing snapshot: applied to the card when set, and to any
     * presentation created afterwards. Written on the projection worker thread
     * and handed to the main thread by reference through the posted update.
     */
    @Volatile private var nowPlaying: NowPlayingInfo? = null

    /**
     * Latest driving-restriction gate: applied to the card when set, and to
     * any presentation created afterwards. Cached like [nowPlaying].
     */
    @Volatile private var drivingRestricted: Boolean = false

    /**
     * Latest night palette: applied to the car UI when set, and to any
     * presentation created afterwards. Cached like [nowPlaying].
     */
    @Volatile private var nightDark: Boolean = false

    /** The encoder input surface [show] was last called with; kept for [updateConfig]. */
    private var cachedSurface: Surface? = null

    /** Last-known now-playing card bounds in display pixels; null until laid out. */
    @Volatile private var mediaCardBounds: TapBounds? = null

    /**
     * `downTime` of the gesture in progress, for the synthesized touch stream.
     * Main thread only: written and read inside the posted [dispatchTouch].
     */
    private var gestureDownTime: Long = 0

    private val isMainThread get() = Looper.myLooper() == Looper.getMainLooper()

    /**
     * Creates the display against [surface] and shows the car UI on it.
     *
     * The [Presentation] is created on the main thread: it builds a [Handler]
     * internally, so constructing it on the `ma-auto-projection` worker thread (which
     * has no [Looper]) crashes. That worker thread must stay Looper-free -- one
     * `SSLEngine` backs the connection and an engine is not thread-safe -- so this
     * hops to the main thread and waits, keeping the video bring-up order unchanged.
     */
    fun show(surface: Surface) {
        cachedSurface = surface
        val displayManager = context.getSystemService(DisplayManager::class.java)
        // PRESENTATION alone is a private display: no permission needed. TRUSTED
        // additionally requires ADD_TRUSTED_DISPLAY (the MAOS role); requesting it
        // without the permission throws, so it rides only on the trusted route.
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        if (trusted) flags = flags or trustedDisplayFlag()
        val display = displayManager.createVirtualDisplay(
            DISPLAY_NAME,
            width,
            height,
            densityDpi,
            surface,
            flags,
        ) ?: error("could not create the car virtual display")
        virtualDisplay = display

        presentation = showPresentation(
            display.display,
            initialNowPlaying = nowPlaying,
            initialDrivingRestricted = drivingRestricted,
            initialNight = nightDark,
            initialActiveCall = activeCall,
            initialHostNav = hostNavState,
            initialPhoneStatusSource = phoneStatusSource,
            onMediaTap = { onMediaTap?.invoke() },
            onPreviousTap = { onPreviousTap?.invoke() },
            onNextTap = { onNextTap?.invoke() },
            onAnswerCall = { onAnswerCall?.invoke() },
            onEndCall = { onEndCall?.invoke() },
            onHoldToggle = { onHoldToggle?.invoke() },
            onMuteToggle = { onMuteToggle?.invoke() },
            onCardBounds = { left, top, right, bottom ->
                mediaCardBounds = TapBounds(left, top, right, bottom)
            },
        ).also { shown ->
            mapSurfaceForwarder?.let { forward ->
                shown.mapSurfaceListener = { s, w, h -> forward(s, w, h) }
            }
            mapTouchForwarder?.let { forward ->
                shown.setMapTouchForwarder(forward)
            }
        }
        dumpVirtualDisplay(display.display, surface)
    }

    /**
     * Rebuilds the virtual display when the negotiated size changes.
     *
     * The car path has no `onConfigurationChanged` of its own (phone config
     * lives in `MainActivity`); whoever observes a new video config calls
     * here. Same size is a no-op; otherwise the display and presentation are
     * torn down and re-shown on the cached surface, which rebuilds every
     * programmatic view (including the alpha-jump keyboard) at the new size.
     * Density and encoder settings are untouched. Safe from any thread.
     */
    fun updateConfig(width: Int, height: Int, densityDpi: Int) {
        mainHandler.post {
            if (width == this.width && height == this.height && densityDpi == this.densityDpi) return@post
            val surface = cachedSurface ?: return@post
            this.width = width
            this.height = height
            this.densityDpi = densityDpi
            tearDownPresentation()
            show(surface)
        }
    }

    /**
     * Refreshes the car now-playing card. Safe from any thread: the snapshot is
     * pushed into the shared state (cached for presentations created later),
     * and collection hops to main through the presentation lifecycle.
     *
     * The snapshot is always cached, even when the card hides: a pause still
     * pushes `playing=false`, which is what clears a stale "Playing" label.
     */
    fun setNowPlaying(info: NowPlayingInfo) {
        nowPlaying = info
        CarLauncherState.setNowPlaying(info)
        mainHandler.post { presentation?.updateNowPlaying(info) }
    }

    /**
     * Hides the car now-playing card without touching the cached snapshot.
     * Safe from any thread; the card re-shows on the next visible snapshot.
     */
    fun hideNowPlaying() {
        CarLauncherState.hideNowPlaying()
        mainHandler.post { presentation?.hideNowPlaying() }
    }

    /**
     * Gates driving-restricted pixels. Safe from any thread.
     *
     * Hides the media card's action row (gearhead `MediaPlaybackView.n()`
     * auto-hide) and raises the drawer lockout scrim when the drawer is
     * open. There is no `nrd` truth table in MA: the service drives this
     * from the sensor DRIVING_STATUS stream / `NavSnapshot.parked`, and the
     * cached value replays onto presentations created later.
     */
    fun setDrivingRestricted(restricted: Boolean) {
        drivingRestricted = restricted
        CarLauncherState.setDrivingRestricted(restricted)
        mainHandler.post { presentation?.setDrivingRestricted(restricted) }
    }

    /**
     * Closes the drawer at trip end. Parked is the motion heuristic from
     * `NavSnapshot.parked`, not a gear reading; the parked-browsing exit
     * header covers the parked-browsing affordance separately. Safe from any
     * thread.
     */
    fun setParked(parked: Boolean) {
        if (parked) mainHandler.post { presentation?.closeDrawer() }
    }

    /**
     * Pushes parked state into the driving-restriction gate. The parked bit
     * is the `NavSnapshot.parked` motion heuristic: parked clears the gate
     * (media row visible, lockout down); not-parked raises it. Separated from
     * [setParked] (which closes the drawer at trip end) so both consumers of
     * the same snapshot field stay explicit. Safe from any thread.
     */
    fun setParkedBrowsingGate(parked: Boolean) {
        setDrivingRestricted(!parked)
    }

    /**
     * Switches the car UI between day and night palettes. Safe from any thread.
     *
     * Recolors root/rail/cards/drawer per the §2.3 night deltas and forwards
     * to the hosted map through [mapDarkApplier] (the service wires it
     * to `CarAppHost.setNight`). Night
     * sources already in the tree: `ProjectionService.isNightNow` and the
     * `SensorChannel` `NightSource` -- the service picks one and calls here,
     * and the cached value replays onto presentations created later.
     */
    fun setNight(dark: Boolean) {
        nightDark = dark
        CarLauncherState.setNight(dark)
        mapDarkApplier?.invoke(dark)
        mainHandler.post { presentation?.setNight(dark) }
    }

    /**
     * Forwards a night config change into the Compose tree. Called when the
     * night source changes underneath the virtual display; the presentation
     * re-reads uiMode into the flow so `DynamicTheme` follows.
     */
    fun onNightConfigChanged(newConfig: Configuration) {
        mainHandler.post { presentation?.onNightConfigChanged(newConfig) }
    }

    /**
     * Pushes one active-call snapshot into the car card; null hides it.
     * Safe from any thread; cached for presentations created later.
     */
    fun setActiveCall(info: ActiveCallInfo?) {
        activeCall = info
        CarLauncherState.setActiveCall(info)
        mainHandler.post { presentation?.updateCallCard(info) }
    }

    /**
     * Pushes one hosted template state to the nav card. Safe from any thread;
     * cached for presentations created later. The card shows the template's
     * cue/road, distance, lanes, ETA and actions; the map surface itself
     * arrives separately through [setMapSurfaceListener].
     */
    fun setHostNavState(state: HostNavState) {
        hostNavState = state
        CarLauncherState.setHostNav(state)
        mainHandler.post { presentation?.updateHostNav(state) }
    }

    /** Latest hosted template state; cached for presentations created later. */
    @Volatile private var hostNavState: HostNavState? = null

    /**
     * Pushes one guidance snapshot to the nav banner, if the render pair is
     * up. The service calls this on every guidance-monitor update; the banner
     * itself decides show vs GONE.
     */
    fun setNavSnapshot(snapshot: NavSnapshot) {
        CarLauncherState.setNavSnapshot(snapshot)
        mainHandler.post { presentation?.updateNavSnapshot(snapshot) }
    }

    /**
     * Forwards nav-card map surfaces to the session mirror when the render
     * pair comes up after this was set. Cached like [nowPlaying] for
     * presentations created later.
     */
    @Volatile private var mapSurfaceForwarder: ((Surface?, Int, Int) -> Unit)? = null

    /**
     * Wires the nav-card map surface to the session mirror. The surface
     * arrives when the TextureView is ready (or null on destroy); unset means
     * no map backend and the nav card stays a launch tile.
     */
    fun setMapSurfaceListener(listener: (Surface?, Int, Int) -> Unit) {
        mapSurfaceForwarder = listener
        CarLauncherState.mapSurfaceListener = { surface, w, h -> listener(surface, w, h) }
        mainHandler.post {
            presentation?.mapSurfaceListener = { surface, w, h ->
                listener(surface, w, h)
            }
        }
    }

    /**
     * Starts continuously invalidating the car UI at [fps] frames per second.
     * Safe from any thread; hops to main like [setNowPlaying].
     *
     * Without this the virtual display only re-composites when a view changes
     * on its own (the 1Hz clock tick), so the encoder sees ~1fps. Invalidating
     * the decor view at the negotiated rate keeps pixels flowing; the encoder
     * still paces output to its configured rate.
     */
    fun startFrameInvalidation(fps: Int) {
        mainHandler.post { presentation?.startFrameInvalidation(fps) }
    }

    /** Stops the continuous invalidation started by [startFrameInvalidation]. */
    fun stopFrameInvalidation() {
        mainHandler.post { presentation?.stopFrameInvalidation() }
    }

    /**
     * Routes a head-unit tap at the now-playing card.
     *
     * [x] and [y] are display pixels (what ch8 scales to), compared against the
     * card's on-screen bounds on the virtual display. Returns whether the tap
     * hit the card; a hit posts the toggle to the main thread because views may
     * only be touched there. Called from [dispatchTouch] for single-pointer
     * DOWN inside the card bounds -- previously zero callers, now the card-tap
     * path for ch8 touch.
     */
    fun handleCarTap(x: Float, y: Float): Boolean {
        val bounds = mediaCardBounds ?: return false
        if (!bounds.contains(x, y)) return false
        mainHandler.post { onMediaTap?.invoke() }
        return true
    }

    /**
     * Injects one head-unit touch frame into the car UI. Safe from any thread.
     *
     * [pointers] are display pixels (what ch8 scales to): (x, y, pointer id).
     * [action] is the `MotionEvent` action verbatim -- the head unit numbers
     * touch actions exactly as `MotionEvent` does -- with the pointer index
     * for POINTER_DOWN/UP shifted in at build time. Posts to the main thread
     * because views may only be touched there; order is preserved (one FIFO
     * queue, posted in arrival order), so down/move/up stay a gesture. Returns
     * false when the presentation is not up yet.
     */
    fun injectTouch(action: Int, pointers: List<Triple<Float, Float, Int>>, actionIndex: Int): Boolean {
        if (presentation == null) return false
        mainHandler.post { dispatchTouch(action, pointers, actionIndex) }
        return true
    }

    /**
     * Injects one head-unit key press or release. Safe from any thread; the
     * dispatch hops to main like [injectTouch]. Returns false with no UI up.
     */
    fun injectKey(keycode: Int, down: Boolean): Boolean {
        if (presentation == null) return false
        mainHandler.post {
            val event = KeyEvent(
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keycode,
            )
            presentation?.window?.decorView?.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * Injects one head-unit scroll tick. Safe from any thread; the dispatch
     * hops to main like [injectTouch]. Returns false with no UI up.
     */
    fun injectScroll(delta: Int): Boolean {
        if (presentation == null) return false
        mainHandler.post {
            val now = android.os.SystemClock.uptimeMillis()
            val coords = MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_VSCROLL, delta.toFloat())
            }
            val event = MotionEvent.obtain(
                now, now,
                MotionEvent.ACTION_SCROLL,
                1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(coords),
                0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0,
            )
            presentation?.window?.decorView?.dispatchTouchEvent(event)
            event.recycle()
        }
        return true
    }

    /** Main thread only: builds one multi-pointer event and dispatches it. */
    private fun dispatchTouch(
        action: Int,
        pointers: List<Triple<Float, Float, Int>>,
        actionIndex: Int,
    ) {
        val view = presentation?.window?.decorView ?: return
        // A tap on the now-playing card toggles playback directly: the tap
        // coordinates are display pixels and the card bounds are too, so a
        // DOWN inside them is an unambiguous card hit. Other gestures (and
        // taps elsewhere) still dispatch normally so app tiles stay tappable.
        if (action == MotionEvent.ACTION_DOWN && pointers.size == 1) {
            val (x, y, _) = pointers[0]
            if (handleCarTap(x, y)) return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val downTime = if (action == MotionEvent.ACTION_DOWN) {
            gestureDownTime = now
            now
        } else {
            gestureDownTime
        }
        val fullAction = when (action) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> action or (actionIndex.coerceIn(0, pointers.size - 1) shl
                MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }
        val props = pointers.map { (_, _, id) ->
            MotionEvent.PointerProperties().apply { this.id = id }
        }.toTypedArray()
        val coords = pointers.map { (x, y, _) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, now, fullAction, pointers.size, props, coords,
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
        if (action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL
        ) {
            gestureDownTime = 0
        }
    }

    /**
     * Dismisses the car UI and releases the virtual display. The dismiss hops to the
     * main thread to match [show]; it is fire-and-forget since nothing after it
     * depends on the window being gone.
     */
    fun release() {
        val shown = presentation
        presentation = null
        if (shown != null) {
            if (isMainThread) {
                destroyPresentation(shown)
            } else {
                mainHandler.post { destroyPresentation(shown) }
            }
        } else {
            mainHandler.post { tearDownLifecycle() }
        }
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /**
     * Tears the presentation down through the lifecycle: the Compose tree
     * moves to DESTROYED first (disposing composition), then the window
     * dismisses and the owners are cleared. Main thread only.
     */
    private fun destroyPresentation(shown: CarPresentation) {
        shown.dismiss()
        tearDownPresentation()
    }

    /** Tears the presentation + virtual display down. Main thread only. */
    private fun tearDownPresentation() {
        presentation?.dismiss()
        presentation = null
        tearDownLifecycle()
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /** Moves the car lifecycle to DESTROYED and drops it. Main thread only. */
    private fun tearDownLifecycle() {
        val lifecycle = carLifecycle
        carLifecycle = null
        lifecycle?.moveToDestroyed()
    }

    /** Creates and shows the [CarPresentation] for [display] on the main thread. */
    private fun showPresentation(
        display: android.view.Display,
        initialNowPlaying: NowPlayingInfo?,
        initialDrivingRestricted: Boolean,
        initialNight: Boolean,
        initialActiveCall: ActiveCallInfo?,
        initialHostNav: HostNavState?,
        initialPhoneStatusSource: (() -> PhoneStatus?)?,
        onMediaTap: () -> Unit,
        onPreviousTap: () -> Unit,
        onNextTap: () -> Unit,
        onAnswerCall: () -> Unit,
        onEndCall: () -> Unit,
        onHoldToggle: () -> Unit,
        onMuteToggle: () -> Unit,
        onCardBounds: (Int, Int, Int, Int) -> Unit,
    ): CarPresentation {
        if (isMainThread) {
            return buildPresentation(
                display,
                initialNowPlaying,
                initialDrivingRestricted,
                initialNight,
                initialActiveCall,
                initialHostNav,
                initialPhoneStatusSource,
                onMediaTap,
                onPreviousTap,
                onNextTap,
                onAnswerCall,
                onEndCall,
                onHoldToggle,
                onMuteToggle,
                onCardBounds,
            )
        }
        val show = FutureTask<CarPresentation> {
            buildPresentation(
                display,
                initialNowPlaying,
                initialDrivingRestricted,
                initialNight,
                initialActiveCall,
                initialHostNav,
                initialPhoneStatusSource,
                onMediaTap,
                onPreviousTap,
                onNextTap,
                onAnswerCall,
                onEndCall,
                onHoldToggle,
                onMuteToggle,
                onCardBounds,
            )
        }
        mainHandler.post(show)
        try {
            return show.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            virtualDisplay?.release()
            virtualDisplay = null
            throw RuntimeException("interrupted while showing the car display", e)
        } catch (e: ExecutionException) {
            virtualDisplay?.release()
            virtualDisplay = null
            throw e.cause ?: e
        }
    }

    /**
     * Builds the presentation: seeds the shared state from the cached
     * snapshots, wires the late-wired callbacks through the actions, creates
     * the car lifecycle, sets the three `ViewTree` owners on the decor view,
     * and steps to RESUMED. Main thread only.
     */
    private fun buildPresentation(
        display: android.view.Display,
        initialNowPlaying: NowPlayingInfo?,
        initialDrivingRestricted: Boolean,
        initialNight: Boolean,
        initialActiveCall: ActiveCallInfo?,
        initialHostNav: HostNavState?,
        initialPhoneStatusSource: (() -> PhoneStatus?)?,
        onMediaTap: () -> Unit,
        onPreviousTap: () -> Unit,
        onNextTap: () -> Unit,
        onAnswerCall: () -> Unit,
        onEndCall: () -> Unit,
        onHoldToggle: () -> Unit,
        onMuteToggle: () -> Unit,
        onCardBounds: (Int, Int, Int, Int) -> Unit,
    ): CarPresentation {
        initialNowPlaying?.let { CarLauncherState.setNowPlaying(it) }
        CarLauncherState.setDrivingRestricted(initialDrivingRestricted)
        CarLauncherState.setNight(initialNight)
        CarLauncherState.setActiveCall(initialActiveCall)
        initialHostNav?.let { CarLauncherState.setHostNav(it) }
        CarLauncherState.updateActions {
            it.copy(
                onMediaTap = onMediaTap,
                onPreviousTap = onPreviousTap,
                onNextTap = onNextTap,
                onAnswerCall = onAnswerCall,
                onEndCall = onEndCall,
                onHoldToggle = onHoldToggle,
                onMuteToggle = onMuteToggle,
            )
        }
        val lifecycle = CarDisplayLifecycle()
        carLifecycle = lifecycle
        return CarPresentation(
            context,
            display,
            onCardBounds,
        ).also { shown ->
            shown.setInnerPhoneStatusSource(initialPhoneStatusSource ?: { phoneStatusSource?.invoke() })
            shown.show()
            shown.window?.decorView?.let { decor ->
                decor.setViewTreeLifecycleOwner(lifecycle)
                decor.setViewTreeViewModelStoreOwner(lifecycle)
                decor.setViewTreeSavedStateRegistryOwner(lifecycle)
            }
            lifecycle.moveToResumed()
        }
    }

    /**
     * Now-playing card bounds in display pixels, for ch8 tap routing.
     * Compared in presentation root-view coordinates, which are 1:1 with
     * display pixels on a virtual display.
     */
    internal data class TapBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    private companion object {
        const val DISPLAY_NAME = "MA Auto"
        const val TAG = "MaAuto.Display"

        /**
         * `VIRTUAL_DISPLAY_FLAG_TRUSTED` by value: hidden before API 36, so read
         * reflectively. Falls back to PRESENTATION-only (private display) where
         * absent, preserving the private-by-default intent; the [Presentation]
         * owned by this app renders fine without it.
         */
        fun trustedDisplayFlag(): Int = try {
            DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED").getInt(null)
        } catch (_: Exception) {
            0
        }

        /**
         * Dev-build validity dump of the virtual display for the Phase 0 probe:
         * display id, real size, and whether the encoder input surface is live.
         * Gated on [BuildConfig.DEV_BUILD] so release logcat stays quiet.
         */
        fun dumpVirtualDisplay(display: android.view.Display, surface: Surface) {
            if (!BuildConfig.DEV_BUILD) return
            val size = android.graphics.Point().also { display.getRealSize(it) }
            Log.i(
                TAG,
                "virtual display up: id=${display.displayId} real=${size.x}x${size.y} " +
                    "surface valid=${surface.isValid}",
            )
        }
    }
}
