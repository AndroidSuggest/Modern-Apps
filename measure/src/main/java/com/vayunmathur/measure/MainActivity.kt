package com.vayunmathur.measure

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.measure.ui.MeasureViewModel
import com.vayunmathur.measure.ui.pages.ArMeasurePage
import com.vayunmathur.measure.ui.pages.CompassPage
import com.vayunmathur.measure.ui.pages.DiagnosticsPage
import com.vayunmathur.measure.ui.pages.LevelPage
import com.vayunmathur.measure.ui.pages.RulerPage
import com.vayunmathur.measure.ui.pages.SavedMeasurementsPage
import com.vayunmathur.measure.ui.pages.SettingsPage
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: MeasureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }

    // Sensors run only while the app is in front. The compass and level are useless
    // in the background, and the magnetometer is not free to keep polling.
    override fun onStart() {
        super.onStart()
        viewModel.startSensors()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopSensors()
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Compass : Route
    @Serializable data object Level : Route
    @Serializable data object Ruler : Route

    /**
     * Camera is requested here rather than at app launch: the sensor tools are fully
     * usable without it, so gating the whole app on CAMERA would be a permission prompt
     * most sessions never need.
     */
    @Serializable data object ArMeasure : Route

    @Serializable data object Saved : Route
    @Serializable data object Settings : Route
    @Serializable data object Diagnostics : Route
}

@Composable
fun Navigation(viewModel: MeasureViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Compass)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        // No ListPage metadata: these are single full-screen tools, not list-detail
        // pairs. Marking them as list panes makes the adaptive strategy split a wide
        // window in two and squeeze the tool into the left third with an empty pane
        // beside it, which is what landscape looked like before.
        entry<Route.Compass> { CompassPage(backStack, viewModel) }
        entry<Route.Level> { LevelPage(backStack, viewModel) }
        entry<Route.Ruler> { RulerPage(backStack, viewModel) }
        entry<Route.ArMeasure> { ArMeasurePage(backStack, viewModel) }
        entry<Route.Saved> { SavedMeasurementsPage(backStack, viewModel) }
        entry<Route.Settings>(metadata = DialogPage()) { SettingsPage(backStack, viewModel) }
        entry<Route.Diagnostics>(metadata = DialogPage()) { DiagnosticsPage(backStack, viewModel) }
    }
}
