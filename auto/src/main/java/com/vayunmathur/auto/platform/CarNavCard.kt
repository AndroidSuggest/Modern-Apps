package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vayunmathur.auto.R
import com.vayunmathur.auto.protocol.NavSnapshot
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.MAPS_LABEL
import com.vayunmathur.auto.platform.CarUiMetrics.MAPS_PACKAGE
import com.vayunmathur.auto.platform.CarUiMetrics.MATCH
import com.vayunmathur.auto.platform.CarUiMetrics.WRAP

/**
 * The nav half of the split: the maps app's own surface (hosted through
 * [CarAppHost] into a `TextureView`) with the guidance step header driven by
 * the app's real `NavigationTemplate`.
 *
 * How the card works:
 * - [mapSurfaceListener] forwards the `TextureView` surface to [CarAppHost],
 *   which hands it to Maps as a `SurfaceContainer`; Maps draws its own map
 *   (same renderer, archive and tile cache as the phone) into it.
 * - [updateHost] renders the `NavigationTemplate` fields: cue/road banner,
 *   distance, lanes, ETA, and the app's own action strip (Search, End).
 *   Every field is GONE-if-empty; the "no lane art, no ETA" stubs are gone
 *   because the template carries them.
 * - [update] (the `NavSnapshot` path) is the pre-host fallback: banner +
 *   distance only, shown only while the host is not connected.
 * - only when no map backend exists at all -- maps missing, or the bind
 *   failed -- does the launch tile show.
 *
 * Touches on the map surface forward to the host (pan/zoom/click through the
 * app's own `SurfaceCallback`); the launch tile opens Maps on the phone.
 *
 * Owns its views; the presentation drives state through [updateHost]/[update]
 * and recolors through [setNight]. Views, not Compose (private display, no
 * lifecycle owner).
 */
internal class CarNavCardView(
    private val context: Context,
    private val apps: List<CarApp>,
) {
    private fun dp(value: Int): Int = context.carDp(value)

    private var navCardView: LinearLayout? = null
    private var stepContainer: LinearLayout? = null
    private var navDistance: TextView? = null
    private var navBanner: TextView? = null
    private var navLanes: TextView? = null
    private var navEta: TextView? = null
    private var actionRow: LinearLayout? = null
    private var tileView: LinearLayout? = null

    /** Whether a hosted template is currently driving the header. */
    private var hostConnected = false

    /**
     * Receives the nav-card map surface: (surface, w, h), or (null, 0, 0)
     * when the TextureView is destroyed. The session's host attaches on
     * non-null and releases on null; unset means no map backend.
     */
    var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)? = null

    /** Forwards ch8 touches on the map to the hosted app; unset means no host yet. */
    var mapTouchForwarder: ((action: Int, x: Float, y: Float) -> Boolean)? = null

    /** The assembled card; add once to the split. */
    val view: LinearLayout = navCard()

    /** Recolors the card for night. Main thread only. */
    fun setNight(dark: Boolean) {
        navCardView?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_CARD_NIGHT else COLOR_CARD_DAY),
        )
    }

    /** Clears view refs; call from `onStop`. */
    fun clear() {
        navCardView = null
        stepContainer = null
        navDistance = null
        navBanner = null
        navLanes = null
        navEta = null
        actionRow = null
        tileView = null
    }

    /**
     * Renders the hosted app's `NavigationTemplate`. Main thread only.
     *
     * `mapsPresent=false` (or `connected=false`) shows the launch tile; the
     * snapshot path keeps driving the banner until then. Otherwise the header
     * shows the template's cue/road, distance, lanes, ETA and action strip --
     * every row GONE-if-empty, exactly like the template views they mirror.
     */
    fun updateHost(state: HostNavState) {
        hostConnected = state.mapsPresent && state.connected
        tileView?.visibility = if (hostConnected) View.GONE else View.VISIBLE
        if (!hostConnected) {
            stepContainer?.visibility = View.GONE
            return
        }
        setText(navBanner, state.cue?.let { cue ->
            val road = state.road?.takeIf { it.isNotBlank() }
            if (road != null) "$cue · $road" else cue
        })
        setText(navDistance, state.distanceText)
        setText(navLanes, state.lanesText)
        setText(navEta, state.etaText)
        renderActions(state.actions)
        val anyVisible = listOf(navBanner, navDistance, navLanes, navEta).any { it?.visibility == View.VISIBLE } ||
            (actionRow?.visibility == View.VISIBLE)
        val container = stepContainer
        if (container != null) {
            container.visibility = if (anyVisible || state.loading || state.navigating) View.VISIBLE else View.GONE
        }
    }

    /**
     * Pushes one guidance snapshot to the nav banner (pre-host fallback).
     * Main thread only. Ignored once a hosted template is driving the header;
     * the banner shows only while guidance is active with a road or maneuver
     * to announce, otherwise GONE (idle map, no fake banner).
     */
    fun update(snapshot: NavSnapshot) {
        if (hostConnected) return
        val banner = navBanner ?: return
        val road = snapshot.nextRoad?.takeIf { it.isNotBlank() }
        val turn = snapshot.maneuver?.takeIf { it.isNotBlank() }
        val text = when {
            turn != null && road != null -> "$turn · $road"
            turn != null -> turn
            road != null -> road
            else -> null
        }
        if (snapshot.guidanceActive && text != null) {
            banner.text = text
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
        }
        val distance = navDistance
        if (distance != null) {
            val meters = if (snapshot.guidanceActive) snapshot.nextTurnDistanceM else null
            if (meters != null) {
                distance.text = context.getString(R.string.car_nav_distance_meters, meters)
                distance.visibility = View.VISIBLE
            } else {
                distance.visibility = View.GONE
            }
        }
        stepContainer?.visibility =
            if (banner.visibility == View.VISIBLE || distance?.visibility == View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun setText(view: TextView?, text: String?) {
        if (view == null) return
        if (text != null) {
            view.text = text
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
        }
    }

    private fun renderActions(actions: List<HostAction>) {
        val row = actionRow ?: return
        row.removeAllViews()
        if (actions.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        for (action in actions.take(MAX_ACTIONS)) {
            row.addView(
                TextView(context).apply {
                    text = action.title
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    isClickable = true
                    isFocusable = true
                    background = context.carFocusRingBackground()
                    setOnClickListener { action.onClick() }
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                },
            )
        }
    }

    private fun navCard(): LinearLayout {
        val maps = apps.firstOrNull {
            it.launch.`package` == MAPS_PACKAGE ||
                it.label.toString().contains(MAPS_LABEL, ignoreCase = true)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(COLOR_CARD_DAY))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        bottomMargin = dp(8)
                    }
                    stepContainer = this
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            addView(
                                ImageView(context).apply {
                                    visibility = View.GONE
                                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                                },
                            )
                            addView(
                                LinearLayout(context).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                                    addView(
                                        TextView(context).apply {
                                            textSize = 24f
                                            setTextColor(Color.WHITE)
                                            gravity = Gravity.CENTER
                                            visibility = View.GONE
                                            navBanner = this
                                        },
                                    )
                                    addView(
                                        TextView(context).apply {
                                            textSize = 24f
                                            setTextColor(Color.WHITE)
                                            gravity = Gravity.CENTER
                                            visibility = View.GONE
                                            navDistance = this
                                        },
                                    )
                                    // Lane arrows from the template's `Step.lanes`
                                    // (recommended direction per `LaneDirection`).
                                    addView(
                                        TextView(context).apply {
                                            textSize = 20f
                                            setTextColor(Color.WHITE)
                                            gravity = Gravity.CENTER
                                            visibility = View.GONE
                                            navLanes = this
                                        },
                                    )
                                    // Travel estimate (`time_and_distance_text`):
                                    // remaining time from `TravelEstimate`.
                                    addView(
                                        TextView(context).apply {
                                            textSize = 18f
                                            setTextColor(Color.WHITE)
                                            gravity = Gravity.CENTER
                                            visibility = View.GONE
                                            navEta = this
                                        },
                                    )
                                },
                            )
                        },
                    )
                    // Template action strip (Search, End navigation): the app's
                    // own actions through its `OnClickDelegate`.
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER
                            visibility = View.GONE
                            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            actionRow = this
                        },
                    )
                },
            )
            addView(
                TextureView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            mapSurfaceListener?.invoke(Surface(surface), width, height)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            mapSurfaceListener?.invoke(null, 0, 0)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                    setOnTouchListener { _, event ->
                        if (mapTouchForwarder?.invoke(event.action, event.x, event.y) == true) true
                        else false
                    }
                },
            )
            // Empty tile: only visible when no map backend exists at all
            // (maps missing or the host bind failed). Opens Maps on the phone.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
                    tileView = this
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_nav_open_maps)
                            textSize = 20f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        },
                    )
                    if (maps != null) {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { context.startActivity(maps.launch) }
                    }
                },
            )
            navCardView = this
        }
    }

    private companion object {
        /** Template strips carry at most a couple of actions; never overflow the card. */
        const val MAX_ACTIONS = 2
    }
}
