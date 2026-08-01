package com.vayunmathur.translate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.translate.ui.CameraTranslateScreen
import com.vayunmathur.translate.ui.TextTranslatePage
import com.vayunmathur.translate.util.Small100Model
import com.vayunmathur.translate.util.TranslateViewModel
import kotlinx.serialization.Serializable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

class MainActivity : ComponentActivity() {
    private val viewModel: TranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reduced CA hardening: FIRST_PARTY covers api.vayunmathur.com / data.vayunmathur.com (ISRG+GTS)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        enableEdgeToEdge()
        // "Translate" from another app's text-selection menu prefills the input.
        val initialText = processTextFromIntent(intent)
        val ds = DataStoreUtils.getInstance(this)
        setContent {
            DynamicTheme {
                // Auto-install SMaLL-100 (~1.2 GB) on first open, like OpenAssistant's
                // InitialModelDownloadChecker for Gemma + SigLIP2. The checker gates on
                // actual disk presence and starts the DownloadManager loop immediately
                // in a LaunchedEffect, showing per-file progress + speed.
                InitialModelDownloadChecker(ds, Small100Model.FILES) {
                    Navigation(viewModel, initialText)
                }
            }
        }
    }

    private fun processTextFromIntent(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) return ""
        return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Text : Route

    @Serializable
    data object Camera : Route
}

@Composable
fun Navigation(viewModel: TranslateViewModel, initialText: String) {
    val backStack = rememberNavBackStack<Route>(Route.Text)
    MainNavigation(backStack) {
        entry<Route.Text> {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = initialText,
                onOpenCamera = { backStack.add(Route.Camera) },
            )
        }
        entry<Route.Camera> {
            CameraTranslateScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
            )
        }
    }
}
