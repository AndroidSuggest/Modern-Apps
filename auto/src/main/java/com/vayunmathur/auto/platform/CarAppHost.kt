package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.IAppHost
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.OnDoneCallback
import androidx.car.app.SessionInfo
import androidx.car.app.SessionInfoIntentEncoder
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.serialization.Bundleable
import java.security.InvalidParameterException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The car-app host: binds the `:maps` car-app service and implements the host
 * binders so the nav card renders whatever Maps already publishes.
 *
 * How Android car apps actually work: the app (`MapsCarAppService` ->
 * `MapsSession` -> `NavMapScreen`) draws its own map into a **host-owned
 * [Surface]** (`AppManager.setSurfaceCallback` -> `CarMapRenderer.onSurfaceAvailable`)
 * and publishes a [NavigationTemplate] (maneuvers, lanes, ETA) through
 * `IAppManager.getTemplate`. The host supplies the surface, fetches the
 * template, and renders it. MA Auto is that host; the head unit only ever sees
 * our ch2 H.264, which composites the hosted surface like any other view.
 *
 * Binder contract (all from `app-1.4.0` bytecode: `CarAppService`,
 * `CarAppBinder`, `HostDispatcher`, `RemoteUtils`, `ScreenManager`):
 * - bind with `SERVICE_INTERFACE` + `SessionInfo(main, MAIN)` encoded; the
 *   service keys one `CarAppBinder` per session id.
 * - `onHandshakeCompleted(HandshakeInfo(pkg, 1))`: level 1 always passes the
 *   min/max gate (maps declares min 1), so the `ALLOW_ALL` validator and the
 *   range check both succeed.
 * - `onAppCreate(carHost, intent, config, cb)` -> `onAppStart` -> `onAppResume`,
 *   each acked through [IOnDoneCallback]; the app builds its session and screen
 *   off `onAppCreate` on its own main thread.
 * - `getManager("app")` returns the `IAppManager` binder inside the success
 *   `Bundleable` (binders ride `Bundle.putBinder`); `getTemplate` on it returns
 *   the `TemplateWrapper` for the top screen.
 * - the app pushes `setSurfaceCallback(ISurfaceCallback)` through our
 *   [IAppHost]; we answer with `onSurfaceAvailable(Bundleable(SurfaceContainer))`
 *   from the nav card's `TextureView`, and `onSurfaceDestroyed` on teardown.
 *   The service owns the `Surface`; the app never releases it.
 * - `invalidate()` means "template changed": re-pull via getManager/getTemplate
 *   and forward to [onTemplate].
 * - `getHost("navigation")` / `getHost("constraints")` serve the managers
 *   `MapsSession`/`NavMapScreen` actually request; anything else throws
 *   `InvalidParameterException` exactly like `HostDispatcher.getHost` does.
 *
 * Threading: `bind()` may be called from any thread; every blocking binder
 * round-trip runs on a background thread, never main. `setSurface` hops off
 * the caller (the `TextureView` callback is main, and the app's answer may
 * block); `clearSurface` stays synchronous so the service can release the
 * surface on return. Template callbacks post to main.
 */
class CarAppHost(
    context: Context,
    private val onTemplate: (HostNavState) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var carApp: ICarApp? = null
    @Volatile private var surfaceCallback: ISurfaceCallback? = null
    @Volatile private var bound = false
    @Volatile private var handshakeDone = false

    private var localSurface: Surface? = null
    private var localWidth = 0
    private var localHeight = 0
    private var localDpi = DEFAULT_DPI

    private var connection: ServiceConnection? = null

    /** Whether the maps car-app service resolves (empty tile when false). */
    fun hasMapsService(): Boolean = runCatching {
        val component = ComponentName(MAPS_PACKAGE, MAPS_CAR_SERVICE_CLASS)
        val info = appContext.packageManager.getServiceInfo(component, 0)
        info.enabled
    }.getOrDefault(false)

    /** Binds the maps service; no-op when already bound or when maps is missing. */
    fun bind() {
        if (bound) return
        if (!hasMapsService()) {
            Log.i(TAG, "maps car service missing; nav card stays a launch tile")
            mainHandler.post { onTemplate(HostNavState(mapsPresent = false)) }
            return
        }
        val intent = Intent(CarAppService.SERVICE_INTERFACE).apply {
            component = ComponentName(MAPS_PACKAGE, MAPS_CAR_SERVICE_CLASS)
            addCategory(CarAppService.CATEGORY_NAVIGATION_APP)
            SessionInfoIntentEncoder.encode(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, SESSION_ID), this)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) return
                thread(name = "ma-auto-carhost", isDaemon = true) {
                    runHandshake(ICarApp.Stub.asInterface(binder))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                carApp = null
                bound = false
                handshakeDone = false
                mainHandler.post { onTemplate(HostNavState(mapsPresent = true, connected = false)) }
            }
        }
        connection = conn
        val ok = runCatching {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bound = ok
        if (!ok) {
            Log.w(TAG, "bindService to maps failed; nav card stays a launch tile")
            mainHandler.post { onTemplate(HostNavState(mapsPresent = true, connected = false)) }
        }
    }

    /**
     * Hands the nav card's `TextureView` surface to the maps app.
     * Safe from any thread; the binder answer hops off the caller.
     */
    fun setSurface(surface: Surface, widthPx: Int, heightPx: Int) {
        localSurface = surface
        localWidth = widthPx
        localHeight = heightPx
        localDpi = appContext.resources.displayMetrics.densityDpi
        thread(name = "ma-auto-carhost-surface", isDaemon = true) { pushSurface() }
    }

    /**
     * The `TextureView` went away; tells the app synchronously so the service
     * can release the surface on return (same contract as `CarMapRenderer`:
     * the host releases the `Surface` as soon as this returns).
     */
    fun clearSurface() {
        val cb = surfaceCallback
        val last = localSurface
        localSurface = null
        if (cb != null && last != null) {
            runCatching {
                val container = SurfaceContainer(last, localWidth, localHeight, localDpi)
                bundleOf(container)?.let { cb.onSurfaceDestroyed(it, doneCallback()) }
            }.onFailure { Log.w(TAG, "onSurfaceDestroyed failed", it) }
        }
    }

    /** Pushes night as a configuration change; the app restyles its own map. */
    fun setNight(dark: Boolean) {
        val app = carApp ?: return
        if (!handshakeDone) return
        thread(name = "ma-auto-carhost-night", isDaemon = true) {
            runCatching {
                val config = Configuration(appContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                app.onConfigurationChanged(config, doneCallback())
            }.onFailure { Log.w(TAG, "onConfigurationChanged failed", it) }
        }
    }

    /**
     * Forwards one head-unit touch frame on the map surface to the app.
     * Main thread only (called from the nav card's touch listener).
     *
     * The car-app `SurfaceCallback` speaks gestures, not raw touches: a press
     * that moves beyond slop drags the map (`onScroll` with the delta), and a
     * press that releases without moving lands a click (`onClick`). Returns
     * whether the app consumed the frame (false with no surface callback
     * registered yet).
     */
    fun injectMapTouch(action: Int, xPx: Float, yPx: Float): Boolean {
        val cb = surfaceCallback ?: return false
        return runCatching {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = xPx
                    lastTouchY = yPx
                    touchMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = xPx - lastTouchX
                    val dy = yPx - lastTouchY
                    if (!touchMoved && (dx * dx + dy * dy) < TOUCH_SLOP_PX_SQ) {
                        return true
                    }
                    touchMoved = true
                    lastTouchX = xPx
                    lastTouchY = yPx
                    cb.onScroll(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    lastTouchX = xPx
                    lastTouchY = yPx
                    if (!touchMoved) cb.onClick(xPx, yPx)
                    touchMoved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchMoved = false
                }
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    @Volatile private var lastTouchX = 0f
    @Volatile private var lastTouchY = 0f
    @Volatile private var touchMoved = false

    /** Tears the session down; the service still owns the `Surface`. */
    fun unbind() {
        clearSurface()
        val app = carApp
        carApp = null
        handshakeDone = false
        if (app != null) {
            runCatching { app.onAppPause(doneCallback()) }
            runCatching { app.onAppStop(doneCallback()) }
        }
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        bound = false
        surfaceCallback = null
    }

    // ----------------------------------------------------------------
    // Handshake (background thread only)
    // ----------------------------------------------------------------

    private fun runHandshake(app: ICarApp) {
        carApp = app
        val handshake = bundleOf(HandshakeInfo(appContext.packageName, HOST_API_LEVEL)) ?: return
        if (!roundTrip("onHandshakeCompleted") { cb -> app.onHandshakeCompleted(handshake, cb) }) return
        val config = Configuration(appContext.resources.configuration)
        val intent = Intent(Intent.ACTION_MAIN)
        if (!roundTrip("onAppCreate") { cb -> app.onAppCreate(carHostBinder, intent, config, cb) }) return
        if (!roundTrip("onAppStart") { cb -> app.onAppStart(cb) }) return
        if (!roundTrip("onAppResume") { cb -> app.onAppResume(cb) }) return
        handshakeDone = true
        mainHandler.post { onTemplate(HostNavState(mapsPresent = true, connected = true)) }
        fetchTemplate()
        // A surface that arrived before the app registered its callback still
        // needs its first push (already on this background thread).
        pushSurface()
    }

    /** Serializes one host object; null when the Bundler refuses it. */
    private fun bundleOf(value: Any): Bundleable? = runCatching {
        Bundleable.create(value)
    }.getOrNull()

    /** One blocking binder call with a 5s cap; false means the session is dead. */
    private fun roundTrip(name: String, call: (IOnDoneCallback) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val cb = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                latch.countDown()
            }

            override fun onFailure(response: Bundleable?) {
                failure.set(RuntimeException("$name failed: $response"))
                latch.countDown()
            }
        }
        runCatching { call(cb) }.onFailure {
            Log.w(TAG, "$name binder call threw", it)
            return false
        }
        val done = runCatching { latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) Log.w(TAG, "$name timed out")
        failure.get()?.let { Log.w(TAG, "$name rejected", it) }
        return done && failure.get() == null
    }

    /** Pulls the current template and forwards the nav state. Background thread. */
    private fun fetchTemplate() {
        val app = carApp ?: return
        // The success payload deserializes through `Bundler`: the manager
        // stub is an `IInterface`, so `deserializeIInterface` returns the
        // `IAppManager` proxy itself (via `Stub.asInterface`), never a raw
        // `IBinder`. Cast to the interface, not the binder.
        val managerRef = AtomicReference<androidx.car.app.IAppManager?>(null)
        val latch = CountDownLatch(1)
        val managerCb = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                managerRef.set(runCatching { response?.get() as? androidx.car.app.IAppManager }.getOrNull())
                latch.countDown()
            }

            override fun onFailure(response: Bundleable?) {
                latch.countDown()
            }
        }
        runCatching { app.getManager(CarContext.APP_SERVICE, managerCb) }
            .onFailure { Log.w(TAG, "getManager threw", it); return }
        latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val manager = managerRef.get() ?: run {
            Log.w(TAG, "getManager returned no manager")
            return
        }
        val templateRef = AtomicReference<Any?>(null)
        val templateLatch = CountDownLatch(1)
        val templateCb = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                templateRef.set(runCatching { response?.get() }.getOrNull())
                templateLatch.countDown()
            }

            override fun onFailure(response: Bundleable?) {
                templateLatch.countDown()
            }
        }
        runCatching { manager.getTemplate(templateCb) }
            .onFailure { Log.w(TAG, "getTemplate threw", it); return }
        templateLatch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val wrapper = templateRef.get() as? TemplateWrapper
        val state = wrapper?.let { parseTemplate(it) } ?: HostNavState(mapsPresent = true, connected = true)
        mainHandler.post { onTemplate(state) }
    }

    private fun parseTemplate(wrapper: TemplateWrapper): HostNavState {
        val template = runCatching { wrapper.template }.getOrNull()
            ?: return HostNavState(mapsPresent = true, connected = true)
        if (template !is NavigationTemplate) {
            return HostNavState(mapsPresent = true, connected = true)
        }
        val routing = runCatching { template.navigationInfo }.getOrNull() as? RoutingInfo
        val step = routing?.currentStep
        val loading = routing?.isLoading == true
        val navigating = routing != null && !loading
        val lanesText = step?.lanes
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("  ") { lane -> laneArrows(lane.directions) }
            ?.takeIf { it.isNotBlank() }
        val actions = runCatching { template.actionStrip?.actions }.getOrNull().orEmpty().mapNotNull { action ->
            val title = carText(runCatching { action.title }.getOrNull()) ?: return@mapNotNull null
            val delegate = runCatching { action.onClickDelegate }.getOrNull() ?: return@mapNotNull null
            HostAction(title) {
                thread(name = "ma-auto-carhost-click", isDaemon = true) {
                    // App-side callback, never the host binder one: the
                    // delegate reports back into the app's own dispatch.
                    runCatching { delegate.sendClick(object : OnDoneCallback {}) }
                        .onFailure { Log.w(TAG, "action click failed: $title", it) }
                }
            }
        }
        return HostNavState(
            mapsPresent = true,
            connected = true,
            navigating = navigating,
            loading = loading,
            cue = carText(step?.cue),
            road = carText(step?.road),
            distanceText = routing?.currentDistance?.let { formatDistance(it.displayDistance, it.displayUnit) },
            etaText = runCatching { template.destinationTravelEstimate }.getOrNull()?.let { formatEstimate(it) },
            lanesText = lanesText,
            actions = actions,
        )
    }

    private fun carText(text: CarText?): String? = runCatching {
        text?.toCharSequence()?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun laneArrows(directions: List<LaneDirection>): String = directions.joinToString("") { dir ->
        when (runCatching { dir.shape }.getOrNull()) {
            LaneDirection.SHAPE_STRAIGHT -> STRAIGHT_ARROW
            LaneDirection.SHAPE_SLIGHT_LEFT -> SLIGHT_LEFT_ARROW
            LaneDirection.SHAPE_SLIGHT_RIGHT -> SLIGHT_RIGHT_ARROW
            LaneDirection.SHAPE_NORMAL_LEFT -> LEFT_ARROW
            LaneDirection.SHAPE_NORMAL_RIGHT -> RIGHT_ARROW
            LaneDirection.SHAPE_SHARP_LEFT -> SHARP_LEFT_ARROW
            LaneDirection.SHAPE_SHARP_RIGHT -> SHARP_RIGHT_ARROW
            LaneDirection.SHAPE_U_TURN_LEFT -> UTURN_LEFT_ARROW
            LaneDirection.SHAPE_U_TURN_RIGHT -> UTURN_RIGHT_ARROW
            else -> UNKNOWN_ARROW
        }
    }

    private fun formatDistance(display: Double, unit: Int): String = when (unit) {
        Distance.UNIT_METERS -> "${display.toInt()} m"
        Distance.UNIT_KILOMETERS,
        Distance.UNIT_KILOMETERS_P1,
        -> "${((display * 10).toInt() / 10.0)} km"
        Distance.UNIT_MILES,
        Distance.UNIT_MILES_P1,
        -> "${((display * 10).toInt() / 10.0)} mi"
        Distance.UNIT_FEET -> "${display.toInt()} ft"
        else -> "${display.toInt()} m"
    }

    private fun formatEstimate(estimate: TravelEstimate): String? {
        val remaining = runCatching { estimate.remainingTimeSeconds }.getOrNull() ?: return null
        if (remaining <= 0 || remaining == Long.MAX_VALUE) return null
        val minutes = (remaining / 60).toInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private fun pushSurface() {
        val cb = surfaceCallback ?: return
        val surface = localSurface ?: return
        if (localWidth <= 0 || localHeight <= 0) return
        runCatching {
            bundleOf(SurfaceContainer(surface, localWidth, localHeight, localDpi))
                ?.let { cb.onSurfaceAvailable(it, doneCallback()) }
        }.onFailure { Log.w(TAG, "onSurfaceAvailable failed", it) }
    }

    private fun doneCallback(): IOnDoneCallback = object : IOnDoneCallback.Stub() {
        override fun onSuccess(response: Bundleable?) = Unit
        override fun onFailure(response: Bundleable?) {
            Log.w(TAG, "host call rejected: $response")
        }
    }

    // ----------------------------------------------------------------
    // Host binders (these ARE the host the maps app talks to)
    // ----------------------------------------------------------------

    private val carHostBinder = object : ICarHost.Stub() {
        override fun startCarApp(intent: Intent?) {
            if (intent == null) return
            runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }.onFailure { Log.w(TAG, "startCarApp failed", it) }
        }

        override fun getHost(hostType: String?): IBinder {
            return when (hostType) {
                CarContext.APP_SERVICE -> appHostBinder.asBinder()
                CarContext.NAVIGATION_SERVICE -> navHostBinder.asBinder()
                CarContext.CONSTRAINT_SERVICE -> constraintHostBinder.asBinder()
                else -> throw InvalidParameterException("Invalid host type: $hostType")
            }
        }

        override fun finish() = Unit
    }

    private val appHostBinder = object : IAppHost.Stub() {
        override fun invalidate() {
            thread(name = "ma-auto-carhost-invalidate", isDaemon = true) { fetchTemplate() }
        }

        override fun showToast(text: CharSequence?, duration: Int) {
            Log.i(TAG, "car toast: $text")
        }

        override fun setSurfaceCallback(callback: ISurfaceCallback?) {
            surfaceCallback = callback
            thread(name = "ma-auto-carhost-surface", isDaemon = true) { pushSurface() }
        }

        override fun sendLocation(location: android.location.Location?) = Unit

        override fun showAlert(alert: Bundleable?) {
            Log.i(TAG, "car alert shown (not rendered)")
        }

        override fun dismissAlert(alertId: Int) = Unit

        override fun openMicrophone(request: Bundleable?): Bundleable {
            // Voice replies ride the ch6 mic source, not the car-app mic path.
            throw RemoteException("openMicrophone not supported")
        }
    }

    private val navHostBinder = object : INavigationHost.Stub() {
        override fun navigationStarted() {
            Log.i(TAG, "maps navigation started")
        }

        override fun navigationEnded() {
            Log.i(TAG, "maps navigation ended")
            mainHandler.post { onTemplate(HostNavState(mapsPresent = true, connected = true)) }
        }

        override fun updateTrip(trip: Bundleable?) = Unit
    }

    private val constraintHostBinder = object : IConstraintHost.Stub() {
        override fun getContentLimit(contentLimitType: Int): Int = CONTENT_LIMIT_DEFAULT

        override fun isAppDrivenRefreshEnabled(): Boolean = false
    }

    private companion object {
        const val TAG = "MaAuto.CarHost"
        const val MAPS_PACKAGE = "com.vayunmathur.maps"
        const val MAPS_CAR_SERVICE_CLASS = "com.vayunmathur.maps.car.MapsCarAppService"
        const val SESSION_ID = "main"
        const val DEFAULT_DPI = 160

        /**
         * Host API level 1: always within [min, max] (maps declares min 1),
         * so the `CarAppBinder` range gate passes on any app version.
         */
        const val HOST_API_LEVEL = 1

        const val CALL_TIMEOUT_MS = 5_000L
        const val CONTENT_LIMIT_DEFAULT = 6

        /**
         * Squared touch slop for tap-vs-drag on the hosted map: a press that
         * moves less than ~8dp is a click, anything more drags. Matches the
         * platform `ViewConfiguration` scaled-touch-slop order (8dp at mdpi).
         */
        const val TOUCH_SLOP_PX_SQ = 64f

        const val STRAIGHT_ARROW = "↑"
        const val SLIGHT_LEFT_ARROW = "⬉"
        const val SLIGHT_RIGHT_ARROW = "⬊"
        const val LEFT_ARROW = "←"
        const val RIGHT_ARROW = "→"
        const val SHARP_LEFT_ARROW = "↰"
        const val SHARP_RIGHT_ARROW = "↱"
        const val UTURN_LEFT_ARROW = "↩"
        const val UTURN_RIGHT_ARROW = "↪"
        const val UNKNOWN_ARROW = "•"
    }
}

/** One template action (Search, End navigation): title plus the app's own click. */
data class HostAction(
    val title: String,
    val onClick: () -> Unit,
)

/**
 * What the maps app is currently publishing, as the nav card renders it.
 *
 * `mapsPresent=false` means the service itself is missing (empty tile);
 * `connected=false` means the bind/handshake failed (tile until the next
 * session). Otherwise the card shows the app's map surface plus these fields,
 * every one GONE-if-empty like the template rows they come from. Actions ride
 * the app's own `OnClickDelegate`, so Search/End behave exactly as in Maps.
 */
data class HostNavState(
    val mapsPresent: Boolean = true,
    val connected: Boolean = true,
    val navigating: Boolean = false,
    val loading: Boolean = false,
    val cue: String? = null,
    val road: String? = null,
    val distanceText: String? = null,
    val etaText: String? = null,
    val lanesText: String? = null,
    val actions: List<HostAction> = emptyList(),
)
