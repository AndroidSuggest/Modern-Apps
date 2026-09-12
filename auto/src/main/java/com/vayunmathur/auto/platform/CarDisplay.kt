package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private var presentation: Presentation? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

        presentation = showPresentation(display.display)
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

    /** Creates and shows the [Presentation] for [display] on the main thread. */
    private fun showPresentation(display: android.view.Display): Presentation {
        if (isMainThread) return CarPresentation(context, display).also { it.show() }
        val show = FutureTask<CarPresentation> {
            CarPresentation(context, display).also { it.show() }
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
    private class CarPresentation(context: Context, display: android.view.Display) :
        Presentation(context, display) {

        private var clockView: TextView? = null
        private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

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
            val apps = CarApps.query(context)
            root.addView(if (apps.isEmpty()) emptyState() else appGrid(apps))
            setContentView(root)

            clockView?.let {
                it.text = timeFormat.format(Date())
                it.post(ticker)
            }
        }

        override fun onStop() {
            clockView?.removeCallbacks(ticker)
            clockView = null
            super.onStop()
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
        }
    }

    private companion object {
        const val DISPLAY_NAME = "MA Auto"
    }
}
