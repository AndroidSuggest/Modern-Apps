package com.vayunmathur.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.camera.R
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.CodecSupport
import com.vayunmathur.camera.util.VideoCodec
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : NavKey> SettingsPage(backStack: NavBackStack<T>, viewModel: CameraViewModel) {
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val context = LocalContext.current

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setLocationEnabled(granted)
        if (granted) viewModel.updateLocation()
    }

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            val availableCodecs = remember {
                buildList {
                    add(VideoCodec.AVC)
                    if (CodecSupport.isHevcEncoderAvailable) add(VideoCodec.HEVC)
                    if (CodecSupport.isHardwareAv1EncoderAvailable) add(VideoCodec.AV1)
                }
            }
            if (availableCodecs.size > 1) {
                SettingsSection(title = stringResource(R.string.settings_video_codec)) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${stringResource(videoCodec.labelRes)} — ${stringResource(videoCodec.descriptionRes)}",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                            label = { Text(stringResource(R.string.settings_video_codec_label)) }
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableCodecs.forEach { codec ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(codec.labelRes))
                                            Text(
                                                stringResource(codec.descriptionRes),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setVideoCodec(codec)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_location)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_location_description),
                    checked = locationEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                viewModel.setLocationEnabled(true)
                                viewModel.updateLocation()
                            } else {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        } else {
                            viewModel.setLocationEnabled(false)
                        }
                    },
                )
            }
        }
    }
}
