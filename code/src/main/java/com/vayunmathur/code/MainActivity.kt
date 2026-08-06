package com.vayunmathur.code

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.code.ui.EditorPage
import com.vayunmathur.code.ui.SearchPage
import com.vayunmathur.code.ui.SettingsPage
import com.vayunmathur.code.util.EditorPrefs
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val darkTheme = when (viewModel.themeMode) {
                EditorPrefs.THEME_LIGHT -> false
                EditorPrefs.THEME_DARK -> true
                else -> null
            }
            DynamicTheme(darkTheme = darkTheme) {
                Navigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** VIEW/EDIT opens from other apps carry the file in [Intent.getData]; open it in a tab. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            intent.data?.let { viewModel.openExternal(it) }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Editor : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Search : Route
}

@Composable
fun Navigation(viewModel: EditorViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Editor)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Editor> {
            EditorPage(
                viewModel,
                onOpenSettings = { backStack.add(Route.Settings) },
                onOpenSearch = { backStack.add(Route.Search) },
            )
        }
        entry<Route.Settings> { SettingsPage(viewModel, backStack) }
        entry<Route.Search> { SearchPage(viewModel, backStack) }
    }
}
