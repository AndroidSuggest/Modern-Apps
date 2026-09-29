package com.vayunmathur.web.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.findActivity

/**
 * Hosts HTML5 fullscreen video (e.g. YouTube fullscreen) for a WebView.
 *
 * Without [WebChromeClient.onShowCustomView] the page's fullscreen request dies
 * silently. The host attaches the video view to the activity decor with a black
 * background, and detaches it when the page exits fullscreen. Compose-observable
 * [isFullscreen] lets back-press handlers exit fullscreen first.
 */
class WebFullscreenHost(private val activityFrom: () -> Activity?) {
    var isFullscreen by mutableStateOf(false)
        private set

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        val activity = activityFrom() ?: return
        if (isFullscreen) {
            callback.onCustomViewHidden()
            return
        }
        previousOrientation = activity.requestedOrientation
        customView = view
        customViewCallback = callback
        view.setBackgroundColor(Color.BLACK)
        (activity.window.decorView as? ViewGroup)?.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        isFullscreen = true
    }

    fun onHideCustomView(): Boolean {
        val view = customView ?: return false
        val activity = activityFrom()
        (activity?.window?.decorView as? ViewGroup)?.removeView(view)
        customView = null
        try {
            customViewCallback?.onCustomViewHidden()
        } catch (_: Exception) {}
        customViewCallback = null
        activity?.let { it.requestedOrientation = previousOrientation }
        isFullscreen = false
        return true
    }
}

/** Creates a host bound to the activity behind [android.content.Context]. */
fun WebFullscreenHost(context: android.content.Context): WebFullscreenHost =
    WebFullscreenHost { context.findActivity() }
