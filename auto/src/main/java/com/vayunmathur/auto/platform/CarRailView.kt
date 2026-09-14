package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.CarUiMetrics.COLUMNS
import com.vayunmathur.auto.platform.CarUiMetrics.MATCH
import com.vayunmathur.auto.platform.CarUiMetrics.MAX_DOCK_APPS
import com.vayunmathur.auto.platform.CarUiMetrics.WRAP
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_RAIL_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_RAIL_NIGHT

/**
 * The bottom facet bar (`gh_coolwalk_facet_bar`) + rail status cluster
 * (`rail_statusbar`): dashboard button, assistant slot, ongoing-widget
 * slots, hotseat dock with guidelines, scrim slot, status cluster (icon row
 * over clock), edge spacer, resize + native-rail stubs, and the `app_bar`
 * equivalent.
 *
 * Touch targets match gearhead (`facet_bar_touch_target_size` 68dp,
 * `coolwalk_launcher_dashboard_margin` 10dp, `rail_coolwalk_rail_margin`
 * 6dp, 145dp hotseat guidelines, 2dp edge). Owns its views; the
 * presentation reads state through [tickClock]/[updatePhoneStatus] and
 * recolors through [setNight]. Views, not Compose (private display, no
 * lifecycle owner).
 */
internal class CarRailView(
    private val context: Context,
    private val apps: List<CarApp>,
    private val onOpenDrawer: () -> Unit,
    private val onCloseDrawer: () -> Unit,
) {
    private fun dp(value: Int): Int = context.carDp(value)

    private var railBar: LinearLayout? = null
    private var dockContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var signalOverlay: SignalBarsView? = null
    private var signalView: SignalBarsView? = null
    private var batteryView: BatteryLevelView? = null
    private var dndView: TextView? = null
    private var badgeView: TextView? = null
    private var statusCardView: LinearLayout? = null
    private var assistantSlot: FrameLayout? = null
    private var assistantIcon: TextView? = null
    private val timeFormat: java.text.DateFormat =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)

    /** The assembled rail; add once to the root. */
    val view: LinearLayout = facetBar()

    /** 1Hz tick: clock text + status pull + returns the clock for posting. */
    fun tickClock(statusSource: (() -> PhoneStatus?)?): TextView? {
        val view = clockView ?: return null
        view.text = timeFormat.format(java.util.Date())
        statusSource?.let { source ->
            runCatching { source()?.let { updatePhoneStatus(it) } }
        }
        return view
    }

    /** Stops ticker callbacks; call from `onStop`. */
    fun clear() {
        railBar = null
        dockContainer = null
        clockView = null
        signalOverlay = null
        signalView = null
        batteryView = null
        dndView = null
        badgeView = null
        statusCardView = null
        assistantSlot = null
        assistantIcon = null
    }

    /**
     * Pushes one phone-status snapshot into the cluster. Main thread
     * only; called from the 1Hz tick and on demand. Absent sources stay
     * gone, never faked; hero mode (badge visible) makes the card
     * unfocusable/unclickable.
     */
    fun updatePhoneStatus(status: PhoneStatus) {
        val bars = status.signalBars
        signalOverlay?.visibility = if (bars != null) View.VISIBLE else View.GONE
        signalView?.visibility = if (bars != null) View.VISIBLE else View.GONE
        signalOverlay?.setBars(if ((bars ?: 0) > 2) 4 else 0)
        signalView?.setBars(bars ?: 0)
        val pct = status.batteryPercent
        batteryView?.visibility = if (pct != null) View.VISIBLE else View.GONE
        batteryView?.setLevel(pct ?: 0, status.batteryCharging)
        dndView?.visibility = if (status.doNotDisturb) View.VISIBLE else View.GONE
        val count = status.notificationCount
        badgeView?.visibility = if (count > 0) View.VISIBLE else View.GONE
        // Hero (minimized-widget) suppression: with a live badge the
        // card is unfocusable/unclickable; otherwise clickable with the
        // focus foreground, per `RailStatusBarFragment.onViewCreated`.
        val hero = count > 0
        statusCardView?.isFocusable = !hero
        statusCardView?.isClickable = !hero
    }

    /** Recolors rail + clock alpha for night. Main thread only. */
    fun setNight(dark: Boolean) {
        railBar?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_RAIL_NIGHT else COLOR_RAIL_DAY),
        )
        clockView?.alpha = if (dark) 224 / 255f else 1f
        val statusTint = if (dark) Color.argb(224, 0xFF, 0xFF, 0xFF) else Color.WHITE
        signalOverlay?.setTint(statusTint)
        signalView?.setTint(statusTint)
        batteryView?.setTint(statusTint)
    }

    private fun facetBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_RAIL_DAY))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            railBar = this
            val dash = facetButton(context.getString(R.string.car_home_glyph), "Home") {
                onCloseDrawer()
            }
            addView(dash)
            addView(assistantSlot())
            // Ongoing-widget slots (`ongoing_widget_container` +
            // `RailWidgetView`s): margins 6dp, maxWidth 750dp, gone with
            // no widget backend. The pill transition/focus/ripple
            // behavior (`RailWidgetView`) is unmapped -- stubbed gone.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        marginStart = dp(6)
                        marginEnd = dp(6)
                    }
                },
            )
            // `etc` affordance: gone, marginStart 8dp
            // (`gearhead_baseline_grid_1x`), alpha 0.6
            // (`coolwalk_etc_icon_opacity`).
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.car_etc_glyph)
                    setTextColor(Color.WHITE)
                    alpha = 0.6f
                    textSize = 28f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        marginStart = dp(8)
                    }
                },
            )
            // Hotseat guidelines: 145dp start/end
            // (`coolwalk_rail_hotseat_guideline`) framing the dock.
            addView(
                Space(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(145), MATCH)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    // Invisible until bound, like the
                    // `sys_ui_rail_hotseat` include.
                    visibility = View.INVISIBLE
                    apps.take(MAX_DOCK_APPS).forEach { app ->
                        addView(hotseatCell(app))
                    }
                    addView(
                        hotseatCell(null) {
                            onOpenDrawer()
                        },
                    )
                    dockContainer = this
                    post { visibility = View.VISIBLE }
                },
            )
            addView(
                Space(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(145), MATCH)
                },
            )
            // Transparent rail scrim (`rail_invisible_scrim` 0dp): a
            // layout-only slot; zero width, never drawn.
            addView(
                View(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(0, MATCH)
                },
            )
            addView(facetStatus())
            // 2dp edge spacer (`rail_edge_margin`) closing the rail.
            addView(
                Space(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(2), MATCH)
                },
            )
            // Resize control: 36x56 ghost (`resize_button_short_side_size`
            // x `resize_button_long_side_size`, marginEnd 10dp gutter) +
            // 64dp tap target (`resize_tap_target_size`), gone/clickable.
            // The inner `ResizeButton` (Compose Exit/Smaller/Larger) is
            // unmapped -- stubbed gone.
            addView(
                Space(context).apply {
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(56)).apply {
                        marginEnd = dp(10)
                    }
                },
            )
            addView(
                FrameLayout(context).apply {
                    visibility = View.GONE
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(dp(64), MATCH)
                },
            )
            // Native-rail back/exit mode (`native_rail`): gone; MA never
            // enters the native rail, so both 68dp buttons stay gone.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
                    addView(
                        facetButton(context.getString(R.string.car_back_glyph), context.getString(R.string.car_back_label)) {},
                    )
                    addView(
                        facetButton(context.getString(R.string.car_exit_glyph), context.getString(R.string.car_browsing_exit)) {},
                    )
                },
            )
        }
    }

    /** One 68dp facet-bar button with a decorative glyph label. */
    private fun facetButton(glyph: String, description: String, onTap: () -> Unit): TextView {
        return TextView(context).apply {
            text = glyph
            contentDescription = description
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            background = context.carFocusRingBackground()
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
    }

    /**
     * One hotseat cell (`sys_ui_rail_hotseat` item): a 68dp touch target
     * with the app icon at 52dp and 8dp padding, untinted, no label --
     * labels live in the drawer grid (`app_launcher_item`). A null app
     * renders the drawer glyph cell instead. The 68/56/8 overflow in the
     * old code (56 + 2x8 = 72 > 68) is fixed to the 52dp dock icon
     * (`coolwalk_rail_dock_button_icon_size`).
     */
    private fun hotseatCell(app: CarApp?, onDrawerTap: (() -> Unit)? = null): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
            background = context.carFocusRingBackground()
            isClickable = true
            isFocusable = true
            if (app != null) {
                addView(
                    android.widget.ImageView(context).apply {
                        setImageDrawable(app.icon)
                        contentDescription = app.label.toString()
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        layoutParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER)
                    },
                )
                setOnClickListener { context.startActivity(app.launch) }
            } else {
                addView(
                    TextView(context).apply {
                        text = context.getString(R.string.car_drawer_glyph)
                        setTextColor(Color.WHITE)
                        textSize = 28f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER)
                    },
                )
                setOnClickListener { onDrawerTap?.invoke() }
            }
        }
    }

    /**
     * The assistant slot: 68dp container (`assistant_icon_container`,
     * `clipChildren=false`) with the 56dp live-fragment
     * (`coolwalk_rail_hotseat_button_icon_size`) + circle overlay, both
     * gone, and the 68dp assistant icon, gone without an assistant
     * backend (`CarApps.assistant` skip-if-missing).
     */
    private fun assistantSlot(): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
            clipChildren = false
            clipToPadding = false
            // Live fragment + circle overlay slots: gone with no assistant.
            addView(
                FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
                    visibility = View.GONE
                },
            )
            addView(
                TextView(context).apply {
                    visibility = View.GONE
                    layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
                }.also { assistantIcon = it },
            )
            val assistant = CarApps.assistant(context)
            if (assistant != null) {
                addView(
                    TextView(context).apply {
                        text = context.getString(R.string.car_assistant_glyph)
                        contentDescription = context.getString(R.string.car_assistant_label)
                        setTextColor(Color.WHITE)
                        textSize = 28f
                        gravity = Gravity.CENTER
                        background = context.carFocusRingBackground()
                        layoutParams = FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { context.startActivity(assistant.launch) }
                    }.also { assistantIcon = it },
                )
            }
            assistantSlot = this
        }
    }

    /**
     * The facet status slot (`rail_statusbar`): vertical packed chain of
     * the icon row over the clock, matching the layout exactly -- icon
     * row (signal overlay + cell icons + battery) constrained above the
     * clock, notification badge + DND beside the start barrier, all
     * `importantForAccessibility=no` except the clock.
     */
    private fun facetStatus(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(WRAP, MATCH)
            setPadding(dp(4), 0, dp(4), 0)
            isFocusable = true
            isClickable = true
            foreground = context.carFocusRingBackground()
            statusCardView = this
            // Icon row: 6dp horizontal padding, 4dp top padding, packed
            // above the clock (`layout_goneMarginBottom` keeps the 4dp
            // when the row is gone).
            addView(iconRowView())
            addView(
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(WRAP, dp(68))
                    setPadding(dp(6), 0, dp(6), 0)
                }.also { clockView = it },
            )
        }
    }

    /**
     * The icon row: signal overlay (36dp) + cell signal (24dp) in an end-
     * aligned frame, battery (24dp + 6dp sides, 2dp top/bottom), DND
     * (24dp, gone unless interruption is filtered), notification badge
     * (8dp, gone at zero). All `colorOnSurface` tinted (white day,
     * 224/255 night per `gearhead_rail_icon_alpha`).
     */
    private fun iconRowView(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(4)
            }
            setPadding(dp(6), dp(4), dp(6), 0)
            addView(
                FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                    addView(
                        SignalBarsView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(36), dp(24), Gravity.END)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }.also { signalOverlay = it },
                    )
                    addView(
                        SignalBarsView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }.also { signalView = it },
                    )
                },
            )
            addView(
                BatteryLevelView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        marginStart = dp(6)
                        marginEnd = dp(6)
                    }
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { batteryView = it },
            )
            addView(
                TextView(context).apply {
                    // DND glyph slot (gearhead `gs_do_not_disturb_on_vd_theme_24`,
                    // 24dp, gone): text glyph avoids an app-module drawable.
                    text = context.getString(R.string.car_dnd_glyph)
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { dndView = it },
            )
            addView(
                TextView(context).apply {
                    // Notification badge: 8dp count dot, gone at zero
                    // (`status_bar_small_notification_badge_size`).
                    setBackgroundColor(Color.WHITE)
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        marginStart = dp(12)
                        marginEnd = dp(4)
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { badgeView = it },
            )
        }
    }

    /**
     * The app bar (`app_bar`): match/wrap top container with 8dp side
     * margins, a 72dp widget row (68dp header button + tap target with
     * oval focus, 44dp header icon, 24sp title, tab + aux strips). Gone
     * with no template host.
     */
    fun appBarView(): FrameLayout {
        return FrameLayout(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(MATCH, dp(72))
                    addView(
                        FrameLayout(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(68), MATCH)
                            addView(
                                TextView(context).apply {
                                    gravity = Gravity.CENTER
                                    background = context.carFocusRingBackground()
                                    layoutParams = FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER)
                                    isClickable = true
                                    isFocusable = true
                                },
                            )
                        },
                    )
                    addView(
                        android.widget.ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                gravity = Gravity.CENTER_VERTICAL
                            }
                        },
                    )
                    addView(
                        TextView(context).apply {
                            textSize = 24f
                            setTextColor(Color.WHITE)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                                marginStart = dp(8)
                                marginEnd = dp(8)
                            }
                        },
                    )
                },
            )
        }
    }
}

/**
 * Cell-signal bars (`cell_signal` 24dp / `cell_info_overlay` 36dp
 * parity): 0-4 filled bars, `colorOnSurface` (white day, 224/255 night).
 * Drawn, not drawabled -- the app module must not add icon assets for
 * what four rects express exactly.
 */
internal class SignalBarsView(context: Context) : View(context) {
    private var bars: Int = 0
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }

    fun setBars(level: Int) {
        val clamped = level.coerceIn(0, 4)
        if (clamped == bars) return
        bars = clamped
        invalidate()
    }

    fun setTint(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val n = 4
        val gap = width / (n * 2f)
        val barWidth = gap
        for (i in 0 until n) {
            val fraction = (i + 1) / n.toFloat()
            val barHeight = height * fraction
            val left = gap * (i * 2) + gap / 2
            if (i < bars) {
                canvas.drawRect(left, height - barHeight, left + barWidth, height.toFloat(), paint)
            }
        }
    }
}

/**
 * Battery level (`battery_level` 24dp parity): rounded body with fill
 * fraction by percent, nub when charging. Drawn for the same reason as
 * [SignalBarsView].
 */
internal class BatteryLevelView(context: Context) : View(context) {
    private var percent: Int = 0
    private var charging: Boolean = false
    private val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }

    fun setLevel(percent: Int, charging: Boolean) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == this.percent && charging == this.charging) return
        this.percent = clamped
        this.charging = charging
        invalidate()
    }

    fun setTint(color: Int) {
        outline.color = color
        fill.color = color
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val nubWidth = width * 0.12f
        val bodyRight = width - nubWidth - 2f
        val rect = android.graphics.RectF(2f, 4f, bodyRight, height - 4f)
        canvas.drawRoundRect(rect, 3f, 3f, outline)
        val fillWidth = (bodyRight - 4f) * (percent / 100f)
        if (fillWidth > 0) {
            canvas.drawRect(4f, 6f, 4f + fillWidth, height - 6f, fill)
        }
        if (charging) {
            canvas.drawRect(bodyRight + 1f, height * 0.3f, width - 1f, height * 0.7f, fill)
        }
    }
}
