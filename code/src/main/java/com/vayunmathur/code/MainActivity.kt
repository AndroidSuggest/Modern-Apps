package com.vayunmathur.code

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.ui.EditorPage
import com.vayunmathur.code.ui.FolderBrowserPage
import com.vayunmathur.code.ui.GitPage
import com.vayunmathur.code.ui.SearchPage
import com.vayunmathur.code.ui.SettingsPage
import com.vayunmathur.code.ui.TerminalPage
import com.vayunmathur.code.util.EditorPrefs
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.PermissionWall
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    /** All-files-access grant state. Re-checked in [onResume] since it is toggled in Settings. */
    private var hasStoragePermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasStoragePermission = Environment.isExternalStorageManager()
        handleIntent(intent)
        setContent {
            val darkTheme = when (viewModel.themeMode) {
                EditorPrefs.THEME_LIGHT -> false
                EditorPrefs.THEME_DARK -> true
                else -> null
            }
            DynamicTheme(darkTheme = darkTheme) {
                if (hasStoragePermission) {
                    Navigation(viewModel)
                } else {
                    StoragePermissionGate(onRequest = ::launchManageAllFilesAccess)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Deep-links to the per-app "All files access" system screen; falls back to the list screen. */
    private fun launchManageAllFilesAccess() {
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        )
        val ok = runCatching { startActivity(perApp) }.isSuccess
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** VIEW/EDIT opens from other apps carry the file in [Intent.getData]; open it in a tab. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            intent.data?.let { viewModel.openExternal(it) }
        }
    }
}

/** Shown until the user grants All-files access, which the whole editor depends on. */
@Composable
private fun StoragePermissionGate(onRequest: () -> Unit) {
    Scaffold { padding ->
        PermissionWall(
            title = stringResource(R.string.storage_permission_title),
            rationale = stringResource(R.string.storage_permission_rationale),
            actionLabel = stringResource(R.string.grant_access),
            onRequest = onRequest,
            icon = { IconFolderOpen() },
            modifier = Modifier.padding(padding),
        )
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

    @Serializable
    data object FolderBrowser : Route

    @Serializable
    data object Git : Route

    @Serializable
    data object Terminal : Route
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
                onOpenFolder = { backStack.add(Route.FolderBrowser) },
                onOpenGit = { backStack.add(Route.Git) },
                onOpenTerminal = { backStack.add(Route.Terminal) },
            )
        }
        entry<Route.Settings> { SettingsPage(viewModel, backStack) }
        entry<Route.Search> { SearchPage(viewModel, backStack) }
        entry<Route.FolderBrowser> { FolderBrowserPage(viewModel, backStack) }
        entry<Route.Git> { GitPage(viewModel, backStack) }
        entry<Route.Terminal> { TerminalPage(viewModel, backStack) }
    }
}
