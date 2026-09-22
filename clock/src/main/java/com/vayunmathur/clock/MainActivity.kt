package com.vayunmathur.clock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.res.stringResource
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.ClockViewModelFactory
import com.vayunmathur.clock.platform.createNotificationChannels
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.util.DataStoreUtils
class MainActivity : ComponentActivity() {
    private val clockViewModel: ClockViewModel by viewModels {
        ClockViewModelFactory(application, ClockRepository.get(application))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        createNotificationChannels(this)
        val ds = DataStoreUtils.getInstance(this)

        val initialRoute = clockViewModel.handleIncomingIntent(intent)

        setContent {
            DynamicTheme {
                AppPermissionsGate(
                    spec = AppPermissionsSpec(
                        title = stringResource(R.string.grant_notifications_permission),
                        requirements = listOf(
                            PermissionRequirement.notifications(),
                            PermissionRequirement.ExactAlarms,
                            PermissionRequirement.FullScreenIntent,
                        )
                    )
                ) {
                    Navigation(ds, clockViewModel, initialRoute)
                }
            }
        }
    }
}
