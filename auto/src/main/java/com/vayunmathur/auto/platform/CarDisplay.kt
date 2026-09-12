package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.graphics.Color
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
 * Deliberately a *private* virtual display for now: a trusted one needs
 * `ADD_TRUSTED_DISPLAY`, which arrives with the role work, and is only required to launch
 * other apps' activities onto the display. A [Presentation] owned by this app renders onto a
 * private display perfectly well, which is enough to get pixels onto a head unit and prove
 * the video path end to end.
 */
class CarDisplay(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
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
        val display = displayManager.createVirtualDisplay(
            DISPLAY_NAME,
            width,
            height,
            densityDpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
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
     */
    fun setNowPlaying(info: NowPlayingInfo) {
        nowPlaying = info
        mainHandler.post { presentation?.updateNowPlaying(info) }
    }

    /**
     * Routes a head-unit tap at the now-playing card.
     *
     * [x] and [y] are display pixels (what ch8 scales to), compared against the
     * card's on-screen bounds on the virtual display. Returns whether the tap
     * hit the card; a hit posts the toggle to the main thread because views may
     * only be touched there. The ch8 owner calls this after scaling -- see the
     * Phase 4 handoff to input-dev.
     */
    fun handleCarTap(x: Float, y: Float): Boolean {
        val bounds = mediaCardBounds ?: return false
        if (!bounds.contains(x, y)) return false
        mainHandler.post { onMediaTap?.invoke() }
        return true
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

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#101418"))
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            }
            root.addView(statusBar())
            root.addView(divider())
            root.addView(nowPlayingCard())
            val apps = CarApps.query(context)
            root.addView(if (apps.isEmpty()) emptyState() else appGrid(apps))
            setContentView(root)

            pendingNowPlaying?.let { updateNowPlaying(it) }
            pendingNowPlaying = null

            clockView?.let {
                it.text = timeFormat.format(Date())
                it.post(ticker)
            }
        }

        override fun onStop() {
            clockView?.removeCallbacks(ticker)
            clockView = null
            mediaTitle = null
            mediaSubtitle = null
            mediaState = null
            mediaProgress = null
            mediaCard = null
            super.onStop()
        }

        /**
         * Applies a snapshot to the now-playing card. Main thread only: called
         * from the posted update in [setNowPlaying], or from `onCreate` above
         * for a snapshot that arrived before the views existed.
         */
        fun updateNowPlaying(info: NowPlayingInfo) {
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
         * The single now-playing layout (Phase 4 time-box): card, title, artist,
         * state, progress bar. Tapping anywhere on the card toggles playback.
         */
        private fun nowPlayingCard(): LinearLayout {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1B2430"))
                setPadding(dp(24), dp(16), dp(24), dp(16))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                isClickable = true
                isFocusable = true
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
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    mediaTitle = this
                },
            )
            card.addView(
                TextView(context).apply {
                    textSize = 16f
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
