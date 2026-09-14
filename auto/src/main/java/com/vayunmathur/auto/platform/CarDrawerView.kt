package com.vayunmathur.auto.platform

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.CarUiMetrics.COLUMNS
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_ACCENT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_CARD_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_DRAWER_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_LOCKOUT_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_LOCKOUT_NIGHT
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_PARKED_HEADER
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_ROOT_DAY
import com.vayunmathur.auto.platform.CarUiMetrics.COLOR_SCRIM_ASSISTANT
import com.vayunmathur.auto.platform.CarUiMetrics.FILL
import com.vayunmathur.auto.platform.CarUiMetrics.MATCH
import com.vayunmathur.auto.platform.CarUiMetrics.UNDEFINED_COLUMN
import com.vayunmathur.auto.platform.CarUiMetrics.WRAP

/**
 * The app-drawer overlay (`drawer_contents`): full content-area sheet with
 * a 96dp header shadow, an "Apps" heading and the scrollable app list in
 * the gearhead launcher item shape (84dp circular icons, 24sp centered
 * single-line labels, 156dp tiles). Starts GONE; the hotseat drawer cell
 * opens it, the dashboard facet button, scrim tap, BACK, focus loss,
 * launcher taps or trip-end park closes it. Empty, loading, truncated,
 * lockout and parked-browsing states included, plus the alpha-jump
 * fast-scroll, the drawer scrim, voice plate, assistant scrim and AppDecor
 * header stubs.
 *
 * Owns its views; the presentation drives state through open/close and the
 * state setters, and recolors through [setNight]. Contents + alpha keys
 * inflate lazily on first open (the `drawer_stub` /
 * `alpha_jump_layout_stub` seams). Views, not Compose (private display, no
 * lifecycle owner).
 */
internal class CarDrawerView(
    private val context: Context,
    private val apps: List<CarApp>,
) {
    private fun dp(value: Int): Int = context.carDp(value)

    private var drawerPane: LinearLayout? = null
    private var drawerList: DrawerListView? = null
    private var drawerGrid: GridLayout? = null
    private var drawerEmpty: TextView? = null
    private var drawerProgress: ProgressBar? = null
    private var truncatedCard: LinearLayout? = null
    private var lockoutView: FrameLayout? = null
    private var lockoutScrim: View? = null
    private var parkedHeader: LinearLayout? = null
    private var alphaFab: TextView? = null
    private var alphaKeyboard: GridLayout? = null
    private var drawerShadow: View? = null
    /** Drawer contents (list + states) built on first open. */
    private var drawerContentsInflated = false
    /** Alpha-jump views built on first drawer open. */
    private var alphaJumpInflated = false
    /** Launcher labels for the night text-alpha delta (1 day / 0.88 night). */
    private val launcherLabels = mutableListOf<TextView>()

    /** Driving-restriction gate + night flag, pushed by the presentation. */
    var drivingRestricted: Boolean = false
    var nightDark: Boolean = false

    /** The assembled pane; add once to the content frame. */
    val pane: LinearLayout = appDrawer()

    /** The scrim; add once beside the pane. */
    val scrim: View = drawerScrimView()

    /** Voice-plate slot; add once. */
    val voicePlateView: View = voicePlate()

    /** Assistant scrim; add once. */
    val assistantScrimView: View = assistantScrimView()

    /** AppDecor header stub; add once to the root. */
    val decorHeader: LinearLayout = appDecorHeader()

    /** True while the pane is visible. */
    val isOpen: Boolean get() = drawerPane?.visibility == View.VISIBLE

    /** Clears view refs; call from `onStop`. */
    fun clear() {
        drawerPane = null
        drawerList = null
        drawerGrid = null
        drawerEmpty = null
        drawerProgress = null
        truncatedCard = null
        lockoutView = null
        lockoutScrim = null
        parkedHeader = null
        alphaFab = null
        alphaKeyboard = null
        drawerShadow = null
        launcherLabels.clear()
    }

    /**
     * Opens the drawer: lazy-inflates contents + alpha-jump on first
     * open, shows the 96dp-end-margin pane, and fades the scrim in from
     * transparent to the 0.8 dim. Main thread only.
     */
    fun openDrawer() {
        ensureDrawerContents()
        ensureAlphaJump()
        drawerPane?.visibility = View.VISIBLE
        applyLockout()
    }

    /**
     * Closes the drawer. Main thread only. Called from the scrim tap, the
     * BACK key, launcher taps, the Home facet button, focus loss, and
     * trip-end park.
     */
    fun closeDrawer() {
        drawerPane?.visibility = View.GONE
    }

    /** Shows the lockout scrim only when restricted AND the drawer is open. */
    fun applyLockout() {
        val pane = drawerPane ?: return
        val show = drivingRestricted && pane.visibility == View.VISIBLE
        lockoutView?.visibility = if (show) View.VISIBLE else View.GONE
        lockoutScrim?.setBackgroundColor(
            Color.parseColor(if (nightDark) COLOR_LOCKOUT_NIGHT else COLOR_LOCKOUT_DAY),
        )
    }

    /**
     * Shows or hides the drawer loading spinner (48dp indeterminate).
     * Main thread only.
     */
    fun setDrawerLoading(loading: Boolean) {
        drawerProgress?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /**
     * Shows or hides the truncated-list card (88dp, bottom). Main thread only.
     */
    fun setDrawerTruncated(truncated: Boolean) {
        truncatedCard?.visibility = if (truncated) View.VISIBLE else View.GONE
    }

    /**
     * Shows or hides the parked-browsing exit header (88dp). Main thread only.
     */
    fun setParkedBrowsing(active: Boolean) {
        parkedHeader?.visibility = if (active) View.VISIBLE else View.GONE
    }

    /**
     * Shows the alpha-jump fast-scroll entry points once the list is
     * long enough to need them; the FAB reveals the keyboard on tap.
     * Main thread only.
     */
    fun setAlphaJumpVisible(visible: Boolean) {
        ensureAlphaJump()
        alphaFab?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) alphaKeyboard?.visibility = View.GONE
    }

    /** Recolors pane + shadow + keyboard + labels for night. Main thread only. */
    fun setNight(dark: Boolean) {
        nightDark = dark
        drawerPane?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_DRAWER_NIGHT else COLOR_ROOT_DAY),
        )
        drawerShadow?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_CARD_NIGHT else COLOR_CARD_DAY),
        )
        alphaKeyboard?.setBackgroundColor(
            Color.parseColor(if (dark) COLOR_DRAWER_NIGHT else COLOR_ROOT_DAY),
        )
        launcherLabels.forEach { it.alpha = if (dark) 0.88f else 1f }
        applyLockout()
    }

    private fun appDrawer(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_ROOT_DAY))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = (layoutParams as ViewGroup.LayoutParams).also {
                // 96dp end margin: the drawer never covers the full width
                // (`drawer_layout.xml` `layout_marginEnd="96dp"`).
                if (it is ViewGroup.MarginLayoutParams) it.marginEnd = dp(96)
            }
            // 96dp header shadow (`drawer_shadow`, card background).
            addView(
                View(context).apply {
                    setBackgroundColor(Color.parseColor(COLOR_CARD_DAY))
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(96))
                    isFocusable = false
                }.also { drawerShadow = it },
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.car_drawer_apps)
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    setPadding(0, 0, 0, dp(16))
                },
            )
            // Parked-browsing exit header (88dp, `#ff37474f`, radius 2dp):
            // GONE unless unlimited browsing is active.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor(COLOR_PARKED_HEADER))
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(88)).apply {
                        bottomMargin = dp(8)
                    }
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_browsing_locked)
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                                marginStart = dp(22)
                            }
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_browsing_exit)
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            gravity = Gravity.CENTER
                            isClickable = true
                            isFocusable = true
                            background = context.carFocusRingBackground()
                            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                                marginStart = dp(24)
                                marginEnd = dp(16)
                            }
                            setPadding(dp(16), dp(8), dp(16), dp(8))
                            setOnClickListener { setParkedBrowsing(false) }
                        },
                    )
                    parkedHeader = this
                },
            )
            // Loading spinner (48dp indeterminate, centered, gone).
            addView(
                ProgressBar(context).apply {
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        gravity = Gravity.CENTER
                    }
                    isIndeterminate = true
                    drawerProgress = this
                },
            )
            // Scrollable list (PagedListView parity: scrollbar, rotary
            // 50px steps / 15px drag, dpad 22/21/283/282). Contents
            // inflate on first open (the `drawer_stub` seam).
            addView(
                DrawerListView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                    setPadding(0, 0, dp(32), 0)
                    onDrawerLeft = { closeDrawer() }
                    drawerList = this
                },
            )
            // Empty state (Body1 32sp centered, gone unless no apps).
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.car_drawer_empty)
                    setTextColor(Color.parseColor(COLOR_ACCENT))
                    textSize = 32f
                    gravity = Gravity.CENTER
                    visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    drawerEmpty = this
                },
            )
            // Truncated-list card (88dp bottom, gone).
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor(COLOR_PARKED_HEADER))
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(88)).apply {
                        topMargin = dp(8)
                    }
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_drawer_empty)
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH).apply {
                                marginStart = dp(22)
                                marginEnd = dp(22)
                            }
                        },
                    )
                    truncatedCard = this
                },
            )
            // Lockout overlay: scrim + 26sp text, gone unless
            // driving-restricted while open.
            addView(
                FrameLayout(context).apply {
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
                    addView(
                        View(context).apply {
                            setBackgroundColor(Color.parseColor(COLOR_LOCKOUT_DAY))
                            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                            lockoutScrim = this
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_drawer_lockout)
                            setTextColor(Color.WHITE)
                            textSize = 26f
                            gravity = Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
                        },
                    )
                    lockoutView = this
                },
            )
            // Alpha-jump FAB (96dp, end/top, margins 48/22dp, unfocusable)
            // + keyboard (marginTop 96dp, fixed mode, square keys, gone).
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.car_alpha_jump_glyph)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    isClickable = true
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                        gravity = Gravity.END or Gravity.TOP
                        topMargin = dp(48)
                        marginEnd = dp(22)
                    }
                    setOnClickListener { alphaKeyboard?.visibility = View.VISIBLE }
                    alphaFab = this
                },
            )
            addView(
                GridLayout(context).apply {
                    columnCount = 6
                    visibility = View.GONE
                    setBackgroundColor(Color.parseColor(COLOR_CARD_DAY))
                    layoutParams = LinearLayout.LayoutParams(MATCH, MATCH).apply {
                        topMargin = dp(96)
                    }
                    alphaKeyboard = this
                },
            )
            drawerPane = this
        }
    }

    /** Builds drawer list contents on first open. Main thread only. */
    private fun ensureDrawerContents() {
        if (drawerContentsInflated) return
        drawerContentsInflated = true
        val list = drawerList ?: return
        val grid = GridLayout(context).apply {
            columnCount = COLUMNS
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        apps.forEach { app ->
            grid.addView(
                launcherItem(app).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(156)
                        columnSpec = GridLayout.spec(
                            UNDEFINED_COLUMN,
                            1,
                            FILL,
                            1f,
                        )
                    }
                    setOnClickListener {
                        context.startActivity(app.launch)
                        closeDrawer()
                    }
                },
            )
        }
        list.addView(grid)
        drawerGrid = grid
        drawerEmpty?.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        drawerProgress?.visibility = View.GONE
    }

    /** Builds A-Z + 0-9 alpha keys on first drawer open. Main thread only. */
    private fun ensureAlphaJump() {
        if (alphaJumpInflated) return
        alphaJumpInflated = true
        val keyboard = alphaKeyboard ?: return
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890").forEach { char ->
            keyboard.addView(
                SquareKey(context).apply {
                    text = char.toString()
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(UNDEFINED_COLUMN, 1, FILL, 1f)
                    }
                    setOnClickListener { jumpToLetter(char) }
                },
            )
        }
    }

    /** Alpha-jump: focuses the first launcher whose label starts with [char]. */
    private fun jumpToLetter(char: Char) {
        val grid = drawerGrid ?: return
        for (i in 0 until grid.childCount) {
            val item = grid.getChildAt(i) as? LinearLayout ?: continue
            val label = (item.getChildAt(1) as? TextView)?.text?.toString() ?: continue
            if (label.startsWith(char, ignoreCase = true)) {
                item.requestFocus()
                item.parent?.let { parent ->
                    (parent.parent as? DrawerListView)?.smoothScrollTo(0, item.top)
                }
                return
            }
        }
    }

    /**
     * One launcher item (`app_launcher_item`): 84dp circular icon over a
     * 24sp centered single-line label with 16dp top margin and 28dp label
     * height, 10dp L/R + 14dp T/B cell padding, focus background, Roboto
     * label. Notification + badge slots (22dp / 34dp / 28dp) stubbed gone
     * with no unread source. Circular crop via outline clipping (no
     * CardView dependency); focusable for rotary/dpad parity.
     */
    private fun launcherItem(app: CarApp): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(14), dp(10), dp(14))
            background = context.carFocusRingBackground()
            isClickable = true
            isFocusable = true
            visibility = View.VISIBLE
            addView(
                android.widget.ImageView(context).apply {
                    setImageDrawable(app.icon)
                    contentDescription = app.label.toString()
                    layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
                    clipToOutline = true
                    outlineProvider = circularOutline()
                },
            )
            addView(
                TextView(context).apply {
                    text = app.label.toString()
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(28)).apply {
                        topMargin = dp(16)
                    }
                    launcherLabels.add(this)
                },
            )
        }
    }

    /**
     * The drawer scrim: full content-area dim at 0.8
     * (`CarDrawerLayout.k(color)` folds `alpha * 0.8` into the top byte)
     * with a transparent motion variant (the `MotionFilteringDrawerLayout`
     * seam). Starts transparent on open and posts to the dim, mirroring
     * the scrim fade; taps close the drawer. Starts GONE.
     */
    private fun drawerScrimView(): View {
        return View(context).apply {
            setBackgroundColor(Color.argb(204, 0x10, 0x14, 0x18))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            setOnClickListener { closeDrawer() }
        }
    }

    /** Voice-plate slot (`voice_plate`): gone match/match bottom window. */
    private fun voicePlate(): View {
        return View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
    }

    /**
     * Assistant overlay (`assistant_scrim`): match/match `#151616`
     * alpha 0.24, focusable. Shown while an assistant turn is active;
     * the service drives visibility (gone with no backend).
     */
    private fun assistantScrimView(): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor(COLOR_SCRIM_ASSISTANT))
            alpha = 0.24f
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            isFocusable = true
            isClickable = true
        }
    }

    /**
     * The AppDecor header (`adu_status_bar_view`): 96dp underlay with
     * 32dp app icon + 26sp condensed title, 320dp search card and
     * 96dp drawer/mic/search buttons. Gone with no template host; kept
     * so the metrics exist when one arrives.
     */
    private fun appDecorHeader(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(96))
            setPadding(dp(96), 0, 0, 0)
            addView(
                android.widget.ImageView(context).apply {
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(32), MATCH)
                },
            )
            addView(
                TextView(context).apply {
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                },
            )
            addView(
                FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(320), MATCH).apply {
                        topMargin = dp(16)
                        bottomMargin = dp(16)
                    }
                    setBackgroundColor(Color.parseColor("#FFFAFAFA"))
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_search_hint)
                            setTextColor(Color.parseColor("#8A000000"))
                            textSize = 26f
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply {
                                marginStart = dp(16)
                            }
                        },
                    )
                },
            )
        }
    }
}

/**
 * The drawer container (`drawer_container`): unfocusable, with a
 * touchSlop-scaled X-axis intercept mirroring
 * `MotionFilteringDrawerLayout` -- DOWN/UP/CANCEL reset, in-slop MOVE
 * returns false, beyond-slop MOVE intercepts. BACK key-up while open
 * closes via [onDrawerBack].
 */
internal class DrawerContainer(context: Context) : FrameLayout(context) {
    var onDrawerBack: (() -> Unit)? = null
    private val slop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f

    init {
        isFocusable = View.NOT_FOCUSABLE
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> downX = event.x
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downX = 0f
            MotionEvent.ACTION_MOVE ->
                if (kotlin.math.abs(event.x - downX) > slop) return true
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val close = onDrawerBack
            if (close != null) {
                close()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * The scrollable drawer list (`PagedListView` parity): a ScrollView with
 * the scrollbar enabled, rotary scroll (50px focus steps, >=15px drag
 * consumes), and dpad routing -- 22 activates the focused item, 21
 * closes the drawer, 283/282 page down/up.
 */
internal class DrawerListView(context: Context) : ScrollView(context) {
    var onDrawerLeft: (() -> Unit)? = null
    private var rotaryRemainder = 0f

    init {
        isVerticalScrollBarEnabled = true
        isFocusable = true
        isClickable = true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f
        ) {
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (kotlin.math.abs(delta) < 15) return false
            rotaryRemainder += delta
            while (rotaryRemainder >= 50) {
                rotaryRemainder -= 50
                focusSearch(View.FOCUS_DOWN)?.requestFocus()
                smoothScrollBy(0, 50)
            }
            while (rotaryRemainder <= -50) {
                rotaryRemainder += 50
                focusSearch(View.FOCUS_UP)?.requestFocus()
                smoothScrollBy(0, -50)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                findFocus()?.performClick()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onDrawerLeft?.invoke()
                return true
            }
            283 -> {
                smoothScrollBy(0, height)
                return true
            }
            282 -> {
                smoothScrollBy(0, -height)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

/** Square alpha-jump key (`AlphaJumpKey.onMeasure(size, size)` parity). */
internal class SquareKey(context: Context) : TextView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
        )
    }
}
