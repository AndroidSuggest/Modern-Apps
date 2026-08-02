package com.vayunmathur.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.ui.BypassListPage
import com.vayunmathur.vpn.ui.ConfigDetailPage
import com.vayunmathur.vpn.ui.ConfigListPage
import com.vayunmathur.vpn.ui.LoggingPage
import com.vayunmathur.vpn.ui.SettingsPage
import com.vayunmathur.vpn.util.VpnNative
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.vpn.util.VpnViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var configDao: VpnConfigDao
    private lateinit var logDao: ConnectionLogDao
    private val vm: VpnViewModel by viewModels {
        VpnViewModelFactory(application, configDao, logDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SYSTEM: user-supplied VPN endpoint dynamic host, cannot pin
        NetworkClient.init(this, TrustBundle.SYSTEM)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { VpnNative.init() }
            val db = VpnDatabase.get(this@MainActivity)
            configDao = db.vpnConfigDao()
            logDao = db.connectionLogDao()
            withContext(Dispatchers.Main) {
                ready.value = true
                handleIntent(intent)
            }
        }

        setContent {
            DynamicTheme {
                if (ready.value) Navigation(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Accept VIEW intents for .conf files from Files / Downloads etc.
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            vm.importFromUri(this, uri)
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object List : Route
    @Serializable
    data class Detail(val id: Long) : Route
    @Serializable
    data object Logging : Route
    @Serializable
    data object Settings : Route
    @Serializable
    data object BypassList : Route
}

@Composable
fun Navigation(vm: VpnViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.List)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    val current = backStack.last()
    MainNavigation(
        backStack,
        bottomBar = {
            // Show bottom bar on top-level destinations only
            if (current is Route.List || current is Route.Logging || current is Route.Settings) {
                BottomNavBar(
                    backStack,
                    listOf(
                        BottomBarItem("Tunnels", Route.List) { IconDashboard() },
                        BottomBarItem("Logging", Route.Logging) { IconHistory() },
                        BottomBarItem("Settings", Route.Settings) { IconSettings() },
                    ),
                    current,
                )
            }
        },
    ) {
        entry<Route.List> { ConfigListPage(backStack, vm) }
        entry<Route.Detail> { ConfigDetailPage(backStack, vm, it.id) }
        entry<Route.Logging> { LoggingPage(backStack, vm) }
        entry<Route.Settings> { SettingsPage(backStack, vm) }
        entry<Route.BypassList> { BypassListPage(backStack) }
    }
}
