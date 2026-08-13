package com.vayunmathur.taxi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
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
import com.vayunmathur.taxi.notifications.RideLiveUpdate
import com.vayunmathur.taxi.ui.AccountsScreen
import com.vayunmathur.taxi.ui.CurrentRideScreen
import com.vayunmathur.taxi.ui.LyftSignInScreen
import com.vayunmathur.taxi.ui.RideScreen
import com.vayunmathur.taxi.ui.RideTrackingScreen
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Ride : Route
    @Serializable data object CurrentRide : Route
    @Serializable data object Accounts : Route
    @Serializable data object LyftSignIn : Route
    @Serializable data class RideTracking(val rideId: String) : Route
}

class MainActivity : ComponentActivity() {
    // The ride to open on the tracking screen, set from a notification tap. Held as Compose
    // state so onNewIntent can push a new deep link into the running UI.
    private val trackRideId = mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.STANDARD)
        enableEdgeToEdge()

        trackRideId.value = intent.trackRideIdOrNull()
        requestNotificationPermissionIfNeeded()

        setContent {
            DynamicTheme {
                TaxiApp(trackRideId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.trackRideIdOrNull()?.let { trackRideId.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Intent.trackRideIdOrNull(): String? =
    getStringExtra(RideLiveUpdate.EXTRA_TRACK_RIDE_ID)?.takeIf { it.isNotBlank() }

@Composable
private fun TaxiApp(trackRideId: MutableState<String?>) {
    val backStack = rememberNavBackStack<Route>(Route.Ride)
    val currentPage = backStack.backStack.last()

    // A notification tap deep-links to that ride's tracking screen.
    LaunchedEffect(trackRideId.value) {
        val id = trackRideId.value ?: return@LaunchedEffect
        backStack.add(Route.RideTracking(id))
        trackRideId.value = null
    }

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
        entry<Route.RideTracking> { route -> RideTrackingScreen(rideId = route.rideId) }
    }
}
