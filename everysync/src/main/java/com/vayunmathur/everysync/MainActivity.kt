package com.vayunmathur.everysync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.everysync.ui.AccountDetailScreen
import com.vayunmathur.everysync.ui.AccountsScreen
import com.vayunmathur.everysync.ui.AddAccountScreen
import com.vayunmathur.everysync.ui.DavLoginScreen
import com.vayunmathur.everysync.ui.EverySyncViewModel
import com.vayunmathur.everysync.ui.SettingsScreen
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: EverySyncViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                // Paint the theme background behind everything (incl. the status bar strip the
                // offline banner leaves transparent) so no white window background shows through
                // when offline. See issue #488.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    OfflineAware {
                        Navigation(viewModel)
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Accounts : Route
    @Serializable data object AddAccount : Route
    @Serializable data class DavLogin(val providerId: String) : Route
    @Serializable data class AccountDetail(val accountName: String) : Route
    @Serializable data object Settings : Route
}

@Composable
fun Navigation(viewModel: EverySyncViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Accounts)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.Accounts>(metadata = ListPage()) { AccountsScreen(backStack, viewModel) }
        entry<Route.AddAccount>(metadata = ListPage()) { AddAccountScreen(backStack, viewModel) }
        entry<Route.DavLogin>(metadata = DialogPage()) { DavLoginScreen(backStack, viewModel, it.providerId) }
        entry<Route.AccountDetail>(metadata = ListPage()) { AccountDetailScreen(backStack, viewModel, it.accountName) }
        entry<Route.Settings>(metadata = ListPage()) { SettingsScreen(backStack, viewModel) }
    }
}
