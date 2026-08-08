package com.vayunmathur.taxi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconMap
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.taxi.ui.AccountsScreen
import com.vayunmathur.taxi.ui.CurrentRideScreen
import com.vayunmathur.taxi.ui.LyftSignInScreen
import com.vayunmathur.taxi.ui.RideScreen
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Ride : Route
    @Serializable data object CurrentRide : Route
    @Serializable data object Accounts : Route
    @Serializable data object LyftSignIn : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()

        setContent {
            DynamicTheme {
                TaxiApp()
            }
        }
    }
}

@Composable
private fun TaxiApp() {
    val backStack = rememberNavBackStack<Route>(Route.Ride)
    val currentPage = backStack.backStack.last()

    val pages = listOf(
        BottomBarItem(stringResource(R.string.nav_ride), Route.Ride) { IconMap() },
        BottomBarItem(stringResource(R.string.nav_current_ride), Route.CurrentRide) { IconNavigationArrow() },
        BottomBarItem(stringResource(R.string.nav_settings), Route.Accounts) { IconSettings() },
    )

    MainNavigation(
        backStack = backStack,
        bottomBar = { BottomNavBar(backStack, pages, currentPage) },
    ) {
        entry<Route.Ride> { RideScreen() }
        entry<Route.CurrentRide> { CurrentRideScreen() }
        entry<Route.Accounts> {
            AccountsScreen(
                onConnectLyft = { backStack.add(Route.LyftSignIn) },
            )
        }
        entry<Route.LyftSignIn> { LyftSignInScreen(onBack = { backStack.pop() }) }
    }
}
