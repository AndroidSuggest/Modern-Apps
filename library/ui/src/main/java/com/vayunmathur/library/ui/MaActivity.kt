package com.vayunmathur.library.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity

/**
 * Shared activity shell for every Modern App.
 *
 * Every app used to repeat the same `onCreate`: `enableEdgeToEdge()` plus
 * `setContent { DynamicTheme { ... } }`. That shell lives here now, so an
 * app's `MainActivity` keeps only what is truly per-app: ViewModel wiring,
 * intent handling, permission gates and its navigation call.
 *
 * Apps override [Content] with what used to sit inside the theme wrapper,
 * and optionally [onCreateBeforeContent] with what used to run between
 * `super.onCreate` and `setContent` (network init, worker scheduling,
 * notification channels, intent capture). `onNewIntent` / `onStart` /
 * `onStop` / `onDestroy` overrides stay in the app untouched.
 *
 * Apps with their own theme (a game theme, office's dark chrome, the TV
 * receiver theme) override [AppTheme]; the one TV activity without
 * edge-to-edge sets [edgeToEdge] to false.
 */
abstract class MaActivity : ComponentActivity() {
    /** False for the one TV activity that never drew edge-to-edge. */
    protected open val edgeToEdge: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (edgeToEdge) applyEdgeToEdge()
        onCreateBeforeContent()
        showContent()
    }

    /**
     * Applies edge-to-edge. Override for a custom style — the camera forces a
     * black navigation bar because its UI is always dark.
     */
    protected open fun applyEdgeToEdge() {
        enableEdgeToEdge()
    }

    /**
     * Runs after edge-to-edge setup and before composition. Default is a
     * no-op; override for per-app startup work that must precede composition.
     */
    protected open fun onCreateBeforeContent() {
    }

    /** The theme wrapper. Override for apps with their own theme. */
    @Composable
    protected open fun AppTheme(content: @Composable () -> Unit) {
        DynamicTheme(content = content)
    }

    @Composable
    protected abstract fun Content()

    private fun showContent() {
        setContent {
            AppTheme {
                Content()
            }
        }
    }
}

/**
 * Same shell as [MaActivity] on `FragmentActivity`, for the two apps whose
 * biometric unlock needs a fragment host (passwords, photos).
 *
 * passwords additionally defers composition until its biometric gate
 * succeeds, so automatic composition is opt-out: set [autoSetContent] to
 * false and call [showContent] from the unlock callback.
 */
abstract class MaFragmentActivity : FragmentActivity() {
    protected open val edgeToEdge: Boolean = true

    /** False when the app calls [showContent] itself once it is ready. */
    protected open val autoSetContent: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (edgeToEdge) applyEdgeToEdge()
        onCreateBeforeContent()
        if (autoSetContent) showContent()
    }

    /** Applies edge-to-edge; override for a custom style. See [MaActivity.applyEdgeToEdge]. */
    protected open fun applyEdgeToEdge() {
        enableEdgeToEdge()
    }

    protected open fun onCreateBeforeContent() {
    }

    @Composable
    protected open fun AppTheme(content: @Composable () -> Unit) {
        DynamicTheme(content = content)
    }

    @Composable
    protected abstract fun Content()

    protected fun showContent() {
        setContent {
            AppTheme {
                Content()
            }
        }
    }
}
