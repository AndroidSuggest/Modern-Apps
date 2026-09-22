package com.vayunmathur.astronomy

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionRequirement

class MainActivity : ComponentActivity() {
    private val viewModel: AstronomyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                AppPermissionsGate(
                    spec = AppPermissionsSpec(
                        title = "Grant location permission — astronomy needs your position for horizon",
                        requirements = listOf(
                            PermissionRequirement.Runtime(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        )
                    )
                ) {
                    Navigation(viewModel)
                }
            }
        }
    }
}