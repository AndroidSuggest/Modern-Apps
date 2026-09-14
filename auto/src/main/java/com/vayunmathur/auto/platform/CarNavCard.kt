package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
 * The nav half of the split: live map (`CarMapsMirror` into a
 * `TextureView`) with the guidance step header only while guidance
 * is active. Without Maps data this still draws the basemap + puck
 * from phone GPS; the step views stay GONE idle (never faked), and
 * only when no map backend exists at all does the launch tile show.
 *
 * The step header maps `DetailedNavigationStepView` +
 * `NavigationTravelEstimateView`: turn symbol / distance /
 * description + lanes image / containers + arrival / time-distance,
 * every field GONE-if-empty. `NavSnapshot` carries no lane art, ETA,
 * or arrival time, so those slots stay GONE (stubbed, not guessed);
 * the banner + distance it does carry render live.
 *
 * Owns its views; the presentation drives state through [update] and
 * recolors through [setNight]. The map surface forwards to the session
 * mirror through [mapSurfaceListener]. Views, not Compose (private
 * display, no lifecycle owner).
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

    /**
     * Receives the nav-card map surface: (surface, w, h), or (null, 0, 0)
     * when the TextureView is destroyed. The session's mirror attaches on
     * non-null and releases on null; unset means no map backend.
     */
    var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)? = null

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
    }

    /**
     * Pushes one guidance snapshot to the nav banner. Main thread only.
     * The banner shows only while guidance is active with a road or
     * maneuver to announce; otherwise GONE (idle map, no fake banner).
     * The step container mirrors the banner gate and additionally drives
     * the distance field from `nextTurnDistanceM`.
     */
    fun update(snapshot: NavSnapshot) {
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
                            // Turn symbol (`turn_symbol`): no maneuver
                            // drawable exists in MA -- GONE stub.
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
                                },
                            )
                        },
                    )
                    // Lanes (`lanes_image` + `lanes_container`): no lane
                    // data in `NavSnapshot` -- GONE stubs.
                    addView(
                        ImageView(context).apply {
                            visibility = View.GONE
                            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                        },
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            visibility = View.GONE
                            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        },
                    )
                    // Travel estimate (`arrival_time_text` /
                    // `time_and_distance_text`): no ETA in `NavSnapshot`
                    // -- GONE stubs.
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            visibility = View.GONE
                            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            addView(
                                TextView(context).apply {
                                    visibility = View.GONE
                                },
                            )
                            addView(
                                TextView(context).apply {
                                    visibility = View.GONE
                                },
                            )
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
                },
            )
            if (maps != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { context.startActivity(maps.launch) }
            }
            navCardView = this
        }
    }
}
