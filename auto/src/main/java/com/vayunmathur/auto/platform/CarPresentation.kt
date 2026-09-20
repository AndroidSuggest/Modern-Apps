package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.vayunmathur.auto.ui.LauncherRoot
import com.vayunmathur.library.carhost.HostNavState
import com.vayunmathur.library.ui.DynamicTheme

/**
 * The car launcher presentation: a driving-safe home screen with a bottom bar
 * (home | pins | status) and a body (home grid vs hosted app screen).
 *
 * The presentation owns assembly + lifecycle only: the hierarchy inside is a
 * single [ComposeView] rendering [LauncherRoot], driven by [CarLauncherState]
 * flows. The private virtual display has no activity lifecycle, so the display
 * pairs this with a [CarDisplayLifecycle] whose owners are set on the decor
 * view before content goes in (see [CarDisplay.show]).
 *
 * The hosted map `Surface` stays a `TextureView` interop island inside the
 * navigation renderer: Compose cannot host a third-party map surface
 * otherwise. Input injection keeps working because touches still dispatch
 * through the decor view.
 */
internal class CarPresentation(
    private val appContext: Context,
    display: android.view.Display,
    private val onCardBounds: (Int, Int, Int, Int) -> Unit,
) : Presentation(appContext, display) {

    // Real wall-clock time, ticking every second. A driver needs to know
    // what time it is; the status cluster pulls the phone snapshot on the
    // same tick. Composition-friendly: the clock lives here, not in a flow.
    private val ticker = object : Runnable {
        override fun run() {
            tickClock()
            window?.decorView?.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: the Presentation's own context, never the app context: the
        // Presentation wraps a display-scoped context whose density matches
        // the virtual display (160dpi on DHU = 1dp:1px). A ComposeView built
        // on the app context inherits the phone density (~2.6x) and renders
        // everything zoomed-in on the 800x480 car screen.
        val content = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                DynamicTheme(darkTheme = null) {
                    LauncherRoot(
                        modifier = Modifier.fillMaxSize(),
                        onCardBounds = onCardBounds,
                    )
                }
            }
        }
        setContentView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        applyNight(appContext.resources.configuration.uiMode)
    }

    override fun onStart() {
        super.onStart()
        window?.decorView?.post(ticker)
    }

    override fun onStop() {
        window?.decorView?.removeCallbacks(ticker)
        super.onStop()
    }

    /**
     * Receives the nav renderer's map surface: (surface, w, h), or (null, 0, 0)
     * when the TextureView is destroyed. The session's mirror attaches on
     * non-null and releases on null; unset means no map backend.
     */
    var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)?
        get() = CarLauncherState.mapSurfaceListener
        set(value) {
            CarLauncherState.mapSurfaceListener = value
        }

    /** Forwards ch8 touches on the map to the hosted app. */
    fun setMapTouchForwarder(forward: (Int, Float, Float) -> Boolean) {
        CarLauncherState.mapTouchForwarder = forward
    }

    /**
     * Pushes one hosted template state to the nav renderer. Main thread only.
     * Kept (delegating to the state bus) so [CarDisplay.setHostNavState]
     * compiles unchanged; Phase B replaces this with the generic template flow.
     */
    fun updateHostNav(state: HostNavState) {
        CarLauncherState.setHostNav(state)
    }

    /** Late-wired media tap; writes into the shared actions. Main thread only. */
    fun setOnMediaTap(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onMediaTap = tap) }
    }

    /** Late-wired previous tap; see [setOnMediaTap]. Main thread only. */
    fun setOnPreviousTap(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onPreviousTap = tap) }
    }

    /** Late-wired next tap; see [setOnMediaTap]. Main thread only. */
    fun setOnNextTap(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onNextTap = tap) }
    }

    /** Late-wired answer action; see [setOnMediaTap]. Main thread only. */
    fun setOnAnswerCall(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onAnswerCall = tap) }
    }

    /** Late-wired end action; see [setOnMediaTap]. Main thread only. */
    fun setOnEndCall(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onEndCall = tap) }
    }

    /** Late-wired hold action; see [setOnMediaTap]. Main thread only. */
    fun setOnHoldToggle(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onHoldToggle = tap) }
    }

    /** Late-wired mute action; see [setOnMediaTap]. Main thread only. */
    fun setOnMuteToggle(tap: () -> Unit) {
        CarLauncherState.updateActions { it.copy(onMuteToggle = tap) }
    }

    /** Late-wired phone-status feed; ticked into the state. Main thread only. */
    fun setInnerPhoneStatusSource(source: () -> PhoneStatus?) {
        phoneStatusSource = source
    }

    private var phoneStatusSource: (() -> PhoneStatus?)? = null

    /** 1Hz tick: clock is composition-local; the phone snapshot pushes here. */
    private fun tickClock() {
        phoneStatusSource?.let { source ->
            runCatching { source()?.let { CarLauncherState.setPhoneStatus(it) } }
        }
    }

    /**
     * Switches the day/night palette. Main thread only.
     *
     * The Compose tree reads night from [CarLauncherState.nightDark]; this
     * keeps the setter so [CarDisplay.setNight] compiles unchanged. Phase B/C
     * replace the imperative push with a flow collect.
     */
    fun setNight(dark: Boolean) {
        CarLauncherState.setNight(dark)
    }

    /** Applies one uiMode int into the night flow. */
    private fun applyNight(uiMode: Int) {
        CarLauncherState.setNight(uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }

    /**
     * The night config changed underneath the virtual display: re-read it into
     * the flow so `DynamicTheme` follows. Called from [CarDisplay] on the
     * night source change; the `DynamicTheme(darkTheme = null)` above also
     * follows the display context on its own.
     */
    fun onNightConfigChanged(newConfig: Configuration) {
        applyNight(newConfig.uiMode)
    }

    // --- Compatibility shims ---
    //
    // The old Views assembly exposed per-view updates; the Compose tree
    // collects flows instead. These stay so CarDisplay compiles unchanged
    // until Phase C replaces the imperative pushes with flow collects.

    fun hideNowPlaying() = Unit

    fun updateNowPlaying(info: NowPlayingInfo) = Unit

    fun updateNavSnapshot(snapshot: com.vayunmathur.auto.protocol.NavSnapshot) =
        CarLauncherState.setNavSnapshot(snapshot)

    fun updateCallCard(info: ActiveCallInfo?) = Unit

    fun openDrawer() = Unit

    fun closeDrawer() = Unit

    fun setDrivingRestricted(restricted: Boolean) {
        CarLauncherState.setDrivingRestricted(restricted)
    }

    fun setDrawerLoading(loading: Boolean) = Unit

    fun setDrawerTruncated(truncated: Boolean) = Unit

    fun setParkedBrowsing(active: Boolean) = Unit

    fun setAlphaJumpVisible(visible: Boolean) = Unit

    fun startFrameInvalidation(fps: Int) = Unit

    fun stopFrameInvalidation() = Unit
}
