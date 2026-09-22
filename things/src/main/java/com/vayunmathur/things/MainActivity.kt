package com.vayunmathur.things

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconBluetooth
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.DeviceService
import com.vayunmathur.things.platform.HealthConnectHelper

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceController.init(applicationContext)
        // The service keeps the link alive in the background and drives auto-connect; in the
        // foreground reconnect still works because init() already built the managers.
        if (DeviceController.hasRememberedDevice()) DeviceService.start(this)
        setContent {
            DynamicTheme {
                // First-run gate: without Bluetooth the app cannot read anything
                // and without Health Connect it has nowhere to put what it reads.
                AppPermissionsGate(
                    spec = AppPermissionsSpec(
                        title = stringResource(R.string.permissions_title),
                        subtitle = stringResource(R.string.permissions_rationale),
                        icon = { IconBluetooth() },
                        requirements = bleRequirements() +
                            PermissionRequirement.HealthConnect(HealthConnectHelper.requiredPermissions)
                    )
                ) {
                    Navigation(
                        onScanClick = { DeviceController.startBottleScan() },
                        onDeviceClick = {
                            DeviceService.start(this)
                            DeviceController.connectBottle(it.address)
                        },
                        onForgetBottle = {
                            DeviceController.disconnectBottle()
                            if (!DeviceController.hasRememberedDevice()) DeviceService.stop(this)
                        },
                        onScaleScanClick = { DeviceController.startScaleScan() },
                        onScaleDeviceClick = {
                            DeviceService.start(this)
                            DeviceController.connectScale(it.address)
                        },
                        onForgetScale = {
                            DeviceController.disconnectScale()
                            if (!DeviceController.hasRememberedDevice()) DeviceService.stop(this)
                        },
                        onHealthConnectClick = {},
                    )
                }
            }
        }
    }

    private companion object {
        fun bleRequirements(): List<PermissionRequirement> = buildList {
            add(
                PermissionRequirement.Runtime(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionRequirement.notifications())
            }
        }
    }
}
