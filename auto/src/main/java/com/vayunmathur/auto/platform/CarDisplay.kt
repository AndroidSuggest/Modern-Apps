package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vayunmathur.auto.BuildConfig
import com.vayunmathur.auto.R
import java.text.DateFormat
import java.util.Date
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
 */
class CarDisplay(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
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
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fires when the now-playing card is tapped, locally or from the head unit
     * via [handleCarTap]. Wired to the media monitor's transport toggle.
     */
    var onMediaTap: (() -> Unit)? = null

    /**
     * Latest now-playing snapshot: applied to the card when set, and to any
     * presentation created afterwards. Written on the projection worker thread
     * and handed to the main thread by reference through the posted update.
     */
    @Volatile private var nowPlaying: NowPlayingInfo? = null

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
            onMediaTap = { onMediaTap?.invoke() },
            onCardBounds = { left, top, right, bottom ->
                mediaCardBounds = TapBounds(left, top, right, bottom)
            },
        )
        dumpVirtualDisplay(display.display, surface)
    }

    /**
     * Refreshes the car now-playing card. Safe from any thread: the snapshot is
     * cached for presentations created later, and the view update hops to main.
     *
     * The snapshot is always cached, even when the card hides: a pause still
     * pushes `playing=false`, which is what clears a stale "Playing" label.
     */
    fun setNowPlaying(info: NowPlayingInfo) {
        nowPlaying = info
        mainHandler.post { presentation?.updateNowPlaying(info) }
    }

    /**
     * Hides the car now-playing card without touching the cached snapshot.
     * Safe from any thread; the card re-shows on the next visible snapshot.
     */
    fun hideNowPlaying() {
        mainHandler.post { presentation?.hideNowPlaying() }
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
            val event = android.view.KeyEvent(
                if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
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
            val coords = android.view.MotionEvent.PointerCoords().apply {
                setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, delta.toFloat())
            }
            val event = android.view.MotionEvent.obtain(
                now, now,
                android.view.MotionEvent.ACTION_SCROLL,
                1,
                arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0 }),
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
        if (action == android.view.MotionEvent.ACTION_DOWN && pointers.size == 1) {
            val (x, y, _) = pointers[0]
            if (handleCarTap(x, y)) return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val downTime = if (action == android.view.MotionEvent.ACTION_DOWN) {
            gestureDownTime = now
            now
        } else {
            gestureDownTime
        }
        val fullAction = when (action) {
            android.view.MotionEvent.ACTION_POINTER_DOWN,
            android.view.MotionEvent.ACTION_POINTER_UP,
            -> action or (actionIndex.coerceIn(0, pointers.size - 1) shl
                android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }
        val props = pointers.map { (_, _, id) ->
            android.view.MotionEvent.PointerProperties().apply { this.id = id }
        }.toTypedArray()
        val coords = pointers.map { (x, y, _) ->
            android.view.MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
            }
        }.toTypedArray()
        val event = android.view.MotionEvent.obtain(
            downTime, now, fullAction, pointers.size, props, coords,
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
        if (action == android.view.MotionEvent.ACTION_UP ||
            action == android.view.MotionEvent.ACTION_CANCEL
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
            if (isMainThread) shown.dismiss() else mainHandler.post { shown.dismiss() }
        }
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /** Creates and shows the [CarPresentation] for [display] on the main thread. */
    private fun showPresentation(
        display: android.view.Display,
        initialNowPlaying: NowPlayingInfo?,
        onMediaTap: () -> Unit,
        onCardBounds: (Int, Int, Int, Int) -> Unit,
    ): CarPresentation {
        if (isMainThread) {
            return CarPresentation(context, display, initialNowPlaying, onMediaTap, onCardBounds)
                .also { it.show() }
        }
        val show = FutureTask<CarPresentation> {
            CarPresentation(context, display, initialNowPlaying, onMediaTap, onCardBounds)
                .also { it.show() }
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
     * The car launcher: a driving-safe home screen with a status bar and big app tiles.
     *
     * Deliberately Views, not Compose: a `Presentation` on a private virtual display has
     * no Compose lifecycle owner, and Views render into the encoder surface with no
     * extra plumbing. Large tiles, high contrast, and nothing that needs reading at
     * a glance beyond a label.
     */
    private class CarPresentation(
        context: Context,
        display: android.view.Display,
        initialNowPlaying: NowPlayingInfo?,
        private val onMediaTap: () -> Unit,
        private val onCardBounds: (Int, Int, Int, Int) -> Unit,
    ) : Presentation(context, display) {

        /**
         * Snapshot that arrived before `onCreate` built the card views.
         * Consumed once in `onCreate`; after that the card exists and updates
         * apply directly.
         */
        private var pendingNowPlaying: NowPlayingInfo? = initialNowPlaying

        private var clockView: TextView? = null
        private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        private var mediaTitle: TextView? = null
        private var mediaSubtitle: TextView? = null
        private var mediaState: TextView? = null
        private var mediaProgress: View? = null
        private var mediaCard: LinearLayout? = null
        private var drawerView: View? = null

        // Real wall-clock time, ticking every second. The old placeholder showed a
        // session counter here; a driver needs to know what time it is instead, and the
        // moving digits still prove frames are flowing rather than one stale image.
        private val ticker = object : Runnable {
            override fun run() {
                val view = clockView ?: return
                view.text = timeFormat.format(Date())
                view.postDelayed(this, 1000)
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
            // content area (split nav/media cards, drawer overlay) above a
            // bottom facet bar (dashboard button, hotseat dock, status).
            // Views, not Compose (see the class KDoc).
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#101418"))
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            }
            val apps = CarApps.query(context)
            val content = android.widget.FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addView(splitCards(apps))
                addView(appDrawer(apps).also { drawerView = it })
            }
            root.addView(content)
            root.addView(facetBar(apps))
            setContentView(root)

            pendingNowPlaying?.let { updateNowPlaying(it) }
            pendingNowPlaying = null

            clockView?.let {
                it.text = timeFormat.format(Date())
                it.post(ticker)
            }
        }

        /** Hides the now-playing card; the cached snapshot is untouched. Main thread only. */
        fun hideNowPlaying() {
            mediaCard?.visibility = View.GONE
        }

        override fun onStop() {
            clockView?.removeCallbacks(ticker)
            stopFrameInvalidation()
            clockView = null
            mediaTitle = null
            mediaSubtitle = null
            mediaState = null
            mediaProgress = null
            mediaCard = null
            drawerView = null
            super.onStop()
        }

        /**
         * Applies a snapshot to the now-playing card. Main thread only: called
         * from the posted update in [setNowPlaying], or from `onCreate` above
         * for a snapshot that arrived before the views existed.
         *
         * Also owns the card's visibility: `GONE` when idle (not playing and
         * no title to show) so the car display shows no card with nothing
         * playing; `VISIBLE` while playing, and while paused with a title so
         * resume keeps its context. GAL 11/12 stay unspoken by design, so no
         * head-unit contract drives this -- it is purely phone-side.
         */
        fun updateNowPlaying(info: NowPlayingInfo) {
            val card = mediaCard ?: return
            card.visibility = if (info.shouldShowCard()) View.VISIBLE else View.GONE
            val title = mediaTitle ?: return
            val subtitle = mediaSubtitle ?: return
            val state = mediaState ?: return
            title.text = info.title ?: context.getString(R.string.car_now_playing_unknown)
            subtitle.text = info.artist ?: context.getString(R.string.car_now_playing_unknown_artist)
            state.text = context.getString(
                if (info.playing) R.string.car_now_playing_playing
                else R.string.car_now_playing_paused,
            )
            val progress = mediaProgress ?: return
            val fraction = progressFraction(info.positionMs, info.durationMs)
            val bar = mediaCard ?: return
            // Resize the fill once laid out: the bar width is only known after
            // the layout pass, and the encoder surface picks the re-layout up on
            // the next frame.
            progress.post {
                val total = bar.width
                if (total > 0) {
                    progress.layoutParams = progress.layoutParams.apply {
                        width = (total * fraction).toInt().coerceIn(0, total)
                    }
                    progress.requestLayout()
                }
            }
        }

        /**
         * The single now-playing layout, matching `frag_dash_media` structure:
         * title (28sp, 1 line) + subtitle (24sp), 4dp progress bar, whole card
         * tappable to toggle playback. Album art and the prev/next button row
         * are omitted: no art backend exists, and transport beyond toggle has
         * no monitor path yet -- the card never shows what it cannot do.
         */
        private fun nowPlayingCard(): LinearLayout {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1B2430"))
                setPadding(dp(24), dp(16), dp(24), dp(16))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                isClickable = true
                isFocusable = true
                // Starts hidden: the first snapshot decides, and with nothing
                // playing there is nothing to show.
                visibility = View.GONE
            }
            card.addView(
                TextView(context).apply {
                    text = context.getString(R.string.car_now_playing)
                    setTextColor(Color.parseColor("#8AB4F8"))
                    textSize = 14f
                },
            )
            card.addView(
                TextView(context).apply {
                    textSize = 28f
                    maxLines = 1
                    setTextColor(Color.WHITE)
                    mediaTitle = this
                },
            )
            card.addView(
                TextView(context).apply {
                    textSize = 24f
                    setTextColor(Color.parseColor("#C7CFD9"))
                    mediaSubtitle = this
                },
            )
            card.addView(
                TextView(context).apply {
                    textSize = 14f
                    setTextColor(Color.parseColor("#8AB4F8"))
                    mediaState = this
                },
            )
            val track = View(context).apply {
                setBackgroundColor(Color.parseColor("#2A3138"))
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(4)).apply {
                    topMargin = dp(12)
                }
            }
            card.addView(track)
            val fill = View(context).apply {
                setBackgroundColor(Color.parseColor("#8AB4F8"))
                layoutParams = LinearLayout.LayoutParams(0, dp(4)).apply {
                    topMargin = dp(-4)
                }
                mediaProgress = this
            }
            card.addView(fill)
            card.setOnClickListener { onMediaTap() }
            // Report layout bounds for ch8 tap routing once the view is placed.
            card.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val loc = IntArray(2)
                        card.getLocationOnScreen(loc)
                        onCardBounds(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
                    }
                },
            )
            mediaCard = card
            return card
        }

        private fun statusBar(): LinearLayout {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(16))
            }
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            titles.addView(
                TextView(context).apply {
                    text = context.getString(R.string.app_name)
                    setTextColor(Color.WHITE)
                    textSize = 28f
                },
            )
            titles.addView(
                TextView(context).apply {
                    text = context.getString(R.string.car_connected)
                    setTextColor(Color.parseColor("#8AB4F8"))
                    textSize = 16f
                },
            )
            bar.addView(titles)
            val clock = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.END
            }
            clockView = clock
            bar.addView(clock)
            return bar
        }

        private fun divider(): View = View(context).apply {
            setBackgroundColor(Color.parseColor("#2A3138"))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
        }

        /**
         * The bottom facet bar (`gh_coolwalk_facet_bar`): dashboard button,
         * centered hotseat dock, and the status clock at the end. Touch
         * targets match gearhead (`facet_bar_touch_target_size` 68dp,
         * `coolwalk_launcher_dashboard_margin` 10dp, `rail_coolwalk_rail_margin`
         * 6dp). No assistant button: there is no assistant backend, and the
         * launcher never shows what the phone cannot open.
         */
        private fun facetBar(apps: List<CarApp>): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#161C24"))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                setPadding(dp(10), dp(6), dp(6), dp(6))
                addView(
                    facetButton(context.getString(R.string.car_home_glyph), "Home") {
                        drawerView?.visibility = View.GONE
                    },
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        apps.take(MAX_DOCK_APPS).forEach { app ->
                            addView(hotseatCell(app))
                        }
                        addView(
                            hotseatCell(null) {
                                drawerView?.visibility = View.VISIBLE
                            },
                        )
                    },
                )
                addView(facetStatus())
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
                layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
                isClickable = true
                isFocusable = true
                setOnClickListener { onTap() }
            }
        }

        /**
         * One hotseat cell (`sys_ui_rail_hotseat` item): a 68dp touch target
         * with the app icon at 56dp and 8dp padding, untinted, no label --
         * labels live in the drawer grid (`app_launcher_item`). A null app
         * renders the drawer glyph cell instead.
         */
        private fun hotseatCell(app: CarApp?, onDrawerTap: (() -> Unit)? = null): android.widget.FrameLayout {
            return android.widget.FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
                isClickable = true
                isFocusable = true
                if (app != null) {
                    addView(
                        ImageView(context).apply {
                            setImageDrawable(app.icon)
                            contentDescription = app.label.toString()
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
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
                            layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
                        },
                    )
                    setOnClickListener { onDrawerTap?.invoke() }
                }
            }
        }

        /**
         * The facet status slot (`rail_statusbar`): clock only. Gearhead shows
         * signal/battery icons here; the phone cannot know the car's radio
         * state, so nothing is faked.
         */
        private fun facetStatus(): TextView {
            return TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(WRAP, dp(68))
                setPadding(dp(6), 0, dp(6), 0)
            }.also { clockView = it }
        }

        /**
         * The Coolwalk split: nav card beside the media card, sharing the
         * content area. The media card is the existing now-playing layout
         * (visibility-gated as before); the nav card deep-links Maps.
         */
        private fun splitCards(apps: List<CarApp>): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Fills the content frame: weights only work in a LinearLayout
                // parent, and this lives in a FrameLayout (split + drawer
                // overlay), so MATCH/MATCH, not 0-weight.
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(
                    navCard(apps).apply {
                        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                            marginEnd = dp(8)
                        }
                    },
                )
                addView(
                    nowPlayingCard().apply {
                        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                            marginStart = dp(8)
                        }
                    },
                )
            }
        }

        /**
         * The nav half of the split: Maps icon + label, tapping launches it.
         * Without Maps installed this shows the empty state instead of a dead
         * tile, so the split never offers what the phone cannot open.
         */
        private fun navCard(apps: List<CarApp>): LinearLayout {
            val maps = apps.firstOrNull {
                it.launch.`package` == MAPS_PACKAGE ||
                    it.label.toString().contains(MAPS_LABEL, ignoreCase = true)
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#1B2430"))
                setPadding(dp(24), dp(16), dp(24), dp(16))
                if (maps != null) {
                    maps.icon?.let { icon ->
                        addView(
                            ImageView(context).apply {
                                setImageDrawable(icon)
                                contentDescription = maps.label.toString()
                                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                            },
                        )
                    }
                    addView(
                        TextView(context).apply {
                            text = maps.label.toString()
                            setTextColor(Color.WHITE)
                            textSize = 20f
                            gravity = Gravity.CENTER
                            setPadding(0, dp(8), 0, 0)
                        },
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { context.startActivity(maps.launch) }
                } else {
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_no_apps)
                            setTextColor(Color.parseColor("#8AB4F8"))
                            textSize = 18f
                            gravity = Gravity.CENTER
                        },
                    )
                }
            }
        }

        /**
         * The app-drawer overlay (`drawer_contents`): full content-area sheet
         * with an "Apps" heading and the app grid in the gearhead launcher
         * item shape (84dp circular icons, 24sp centered single-line labels,
         * 156dp tiles). Starts GONE; the hotseat drawer cell opens it, the
         * dashboard facet button or a launch closes it.
         */
        private fun appDrawer(apps: List<CarApp>): View {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#101418"))
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                visibility = View.GONE
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(
                    TextView(context).apply {
                        text = context.getString(R.string.car_drawer_apps)
                        setTextColor(Color.WHITE)
                        textSize = 28f
                        setPadding(0, 0, 0, dp(16))
                    },
                )
                if (apps.isEmpty()) {
                    addView(
                        TextView(context).apply {
                            text = context.getString(R.string.car_no_apps)
                            setTextColor(Color.parseColor("#8AB4F8"))
                            textSize = 24f
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                        },
                    )
                } else {
                    addView(
                        GridLayout(context).apply {
                            columnCount = COLUMNS
                            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                            apps.forEach { app ->
                                addView(
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
                                            drawerView?.visibility = View.GONE
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        /**
         * One launcher item (`app_launcher_item`): 84dp circular icon over a
         * 24sp centered single-line label. Circular crop via outline clipping
         * (no CardView dependency); focusable for rotary/dpad parity.
         */
        private fun launcherItem(app: CarApp): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                addView(
                    ImageView(context).apply {
                        setImageDrawable(app.icon)
                        contentDescription = app.label.toString()
                        layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
                        clipToOutline = true
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                    },
                )
                addView(
                    TextView(context).apply {
                        text = app.label.toString()
                        setTextColor(Color.WHITE)
                        textSize = 24f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        layoutParams = LinearLayout.LayoutParams(MATCH, dp(28)).apply {
                            topMargin = dp(16)
                        }
                    },
                )
            }
        }

        private fun appGrid(apps: List<CarApp>): GridLayout {
            return GridLayout(context).apply {
                columnCount = COLUMNS
                setPadding(dp(16), dp(8), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                apps.forEach { app ->
                    addView(
                        appCell(app).apply {
                            layoutParams = GridLayout.LayoutParams().apply {
                                width = 0
                                columnSpec = GridLayout.spec(UNDEFINED_COLUMN, 1, FILL, 1f)
                            }
                        },
                    )
                }
            }
        }

        private fun appCell(app: CarApp): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
                isClickable = true
                isFocusable = true
                addView(
                    ImageView(context).apply {
                        setImageDrawable(app.icon)
                        contentDescription = app.label.toString()
                        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                    },
                )
                addView(
                    TextView(context).apply {
                        text = app.label.toString()
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                setOnClickListener { context.startActivity(app.launch) }
            }
        }

        private fun emptyState(): TextView {
            return TextView(context).apply {
                text = context.getString(R.string.car_no_apps)
                setTextColor(Color.parseColor("#8AB4F8"))
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
        }

        private fun dp(value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        private companion object {
            const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
            const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
            const val COLUMNS = 3
            const val UNDEFINED_COLUMN = GridLayout.UNDEFINED
            val FILL: GridLayout.Alignment = GridLayout.FILL

            /** Hotseat shows this many apps before the drawer cell; the rest live in the drawer. */
            const val MAX_DOCK_APPS = 3

            /** Package + label fallback identifying the Maps slot for the nav card. */
            const val MAPS_PACKAGE = "com.vayunmathur.maps"
            const val MAPS_LABEL = "map"

            /**
             * Ceiling for the continuous frame invalidator: the encoder paces
             * output to its own configured rate, so invalidating faster only
             * burns CPU compositing frames the encoder drops.
             */
            const val MAX_INVALIDATE_FPS = 60

            /** Progress fraction in [0,1]; half (indeterminate-ish) with no duration. */
            fun progressFraction(positionMs: Long, durationMs: Long?): Float {
                val duration = durationMs?.takeIf { it > 0 } ?: return 0.5f
                return (positionMs.coerceAtLeast(0).toFloat() / duration).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Now-playing card bounds in display pixels, for ch8 tap routing.
     * Compared in presentation root-view coordinates, which are 1:1 with
     * display pixels on a virtual display.
     */
    private data class TapBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
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
