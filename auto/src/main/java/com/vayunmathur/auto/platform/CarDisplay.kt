package com.vayunmathur.auto.platform

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

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

    /** Creates the display against [surface] and shows the car UI on it. */
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

        presentation = CarPresentation(context, display.display).also { it.show() }
    }

    fun release() {
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
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
