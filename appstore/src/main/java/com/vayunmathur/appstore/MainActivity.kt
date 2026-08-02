package com.vayunmathur.appstore

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.DB_NAME
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.ui.AppDetailPage
import com.vayunmathur.appstore.ui.InstalledPage
import com.vayunmathur.appstore.ui.ReposPage
import com.vayunmathur.appstore.ui.UpdatesPage
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.AppStoreViewModelFactory
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    private val readyState = mutableStateOf(false)
    private var factoryState by mutableStateOf<AppStoreViewModelFactory?>(null)
    private var externalPkg: String? = null
    private var vmRef: AppStoreViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // STANDARD includes GTS R1-R4 (needed for Play Store -> play.google.com / android.clients.google.com)
        // + ISRG X1/X2 (F-Droid) + DigiCert G2/G3 (GitHub). Covers all appstore hosts without full system ~140 roots.
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()

        lifecycleScope.launch(Dispatchers.IO) {
            val db = buildDatabase<AppDatabase>(dbName = DB_NAME, migrations = AppDatabase.migrations)
            val factory = AppStoreViewModelFactory(applicationContext, db)
            withContext(Dispatchers.Main) {
                factoryState = factory
                handleIntentUrl(intent)
                readyState.value = true
            }
        }

        setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    if (!readyState.value || factoryState == null) {
                        Box(Modifier.fillMaxSize())
                    } else {
                        val vm: AppStoreViewModel = viewModel(factory = factoryState!!)
                        vmRef = vm
                        LaunchedEffect(externalPkg) {
                            externalPkg?.let { pkg ->
                                val cached = vm.cachedApps.value.find { it.packageName == pkg }
                                if (cached != null) {
                                    vm.selectApp(
                                        UnifiedApp(
                                            packageName = cached.packageName,
                                            source = try { com.vayunmathur.appstore.data.AppSource.valueOf(cached.source) } catch (_: Exception) { com.vayunmathur.appstore.data.AppSource.FDROID },
                                            name = cached.name,
                                            summary = cached.summary,
                                            description = cached.description,
                                            iconUrl = cached.iconUrl,
                                            versionName = cached.versionName,
                                            versionCode = cached.versionCode,
                                            apkUrl = cached.apkUrl
                                        )
                                    )
                                }
                            }
                        }
                        AppRoot(vm, onAppClick = { app -> vm.selectApp(app) })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh installed after uninstall or install from system dialog
        vmRef?.refreshInstalled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentUrl(intent)
    }

    private fun handleIntentUrl(intent: Intent?) {
        intent ?: return
        val data = intent.dataString ?: return
        val pkg = when {
            data.startsWith("market://") -> Regex("[?&]id=([^&]+)").find(data)?.groupValues?.get(1)
            data.contains("play.google.com") -> Regex("[?&]id=([^&]+)").find(data)?.groupValues?.get(1)
            data.contains("f-droid.org") -> data.substringAfterLast('/').substringBefore('?')
            else -> null
        }
        if (pkg != null) externalPkg = pkg
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Browse : Route
    @Serializable data object Installed : Route
    @Serializable data object Updates : Route
    @Serializable data object Repos : Route
    @Serializable data object Detail : Route
}

@Composable
private fun AppRoot(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit
) {
    val selected by viewModel.selectedApp.collectAsState()
    val initial: Route = if (selected != null) Route.Detail else Route.Browse
    val backStack = rememberNavBackStack<Route>(initial)
    val current = backStack.last()

    LaunchedEffect(selected) {
        if (selected != null && current !is Route.Detail) {
            backStack.add(Route.Detail)
        }
    }

    MainNavigation(
        backStack,
        bottomBar = {
            if (current !is Route.Detail) {
                BottomNavBar(
                    backStack,
                    listOf(
                        BottomBarItem(stringResource(R.string.nav_browse), Route.Browse) { IconHome() },
                        BottomBarItem(stringResource(R.string.nav_installed), Route.Installed) { IconPackage() },
                        BottomBarItem(stringResource(R.string.nav_updates), Route.Updates) { IconDownload() },
                        BottomBarItem(stringResource(R.string.nav_sources), Route.Repos) { IconSettings() },
                    ),
                    current
                )
            }
        }
    ) {
        entry<Route.Browse> {
            com.vayunmathur.appstore.ui.SearchAndBrowsePage(viewModel = viewModel, onAppClick = { app ->
                onAppClick(app)
                backStack.add(Route.Detail)
            })
        }
        entry<Route.Installed> {
            InstalledPage(viewModel = viewModel, onAppClick = { app ->
                viewModel.selectApp(app)
                backStack.add(Route.Detail)
            })
        }
        entry<Route.Updates> {
            UpdatesPage(viewModel = viewModel, onAppClick = { app ->
                viewModel.selectApp(app)
                backStack.add(Route.Detail)
            })
        }
        entry<Route.Repos> {
            ReposPage(viewModel = viewModel)
        }
        entry<Route.Detail> {
            AppDetailPage(
                viewModel = viewModel,
                onBack = { backStack.pop() },
            )
        }
    }
}
