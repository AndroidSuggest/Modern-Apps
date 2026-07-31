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
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.data.DB_NAME
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.ui.ConfigDetailPage
import com.vayunmathur.vpn.ui.ConfigListPage
import com.vayunmathur.vpn.ui.SettingsPage
import com.vayunmathur.vpn.util.VpnViewModel
import com.vayunmathur.vpn.util.VpnViewModelFactory
import com.vayunmathur.vpn.util.VpnNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var dao: VpnConfigDao
    private val vm: VpnViewModel by viewModels {
        VpnViewModelFactory(application, dao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { VpnNative.init() }
            dao = buildDatabase<VpnDatabase>(dbName = DB_NAME).vpnConfigDao()
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
    data object Settings : Route
}

@Composable
fun Navigation(vm: VpnViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.List)
    MainNavigation(backStack) {
        entry<Route.List> { ConfigListPage(backStack, vm) }
        entry<Route.Detail> { ConfigDetailPage(backStack, vm, it.id) }
        entry<Route.Settings> { SettingsPage(backStack, vm) }
    }
}
