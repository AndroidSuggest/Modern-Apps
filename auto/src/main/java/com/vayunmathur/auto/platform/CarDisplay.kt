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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
     * A placeholder car UI.
     *
     * Something unmistakably ours and unmistakably live, so a successful projection is
     * obvious at a glance and a frozen one is obvious too. The real driving-safe interface
     * replaces this.
     */
    private class CarPresentation(context: Context, display: android.view.Display) :
        Presentation(context, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#101418"))
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            }
            root.addView(
                TextView(context).apply {
                    text = "MA Auto"
                    setTextColor(Color.WHITE)
                    textSize = 40f
                    gravity = Gravity.CENTER
                },
            )
            val clock = TextView(context).apply {
                setTextColor(Color.parseColor("#8AB4F8"))
                textSize = 20f
                gravity = Gravity.CENTER
            }
            root.addView(clock)
            setContentView(root)

            // A moving element proves frames are still flowing rather than one stale image.
            val ticker = object : Runnable {
                private var seconds = 0
                override fun run() {
                    clock.text = "projecting — ${seconds++}s"
                    clock.postDelayed(this, 1000)
                }
            }
            clock.post(ticker)
        }

        private companion object {
            const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private companion object {
        const val DISPLAY_NAME = "MA Auto"
    }
}
