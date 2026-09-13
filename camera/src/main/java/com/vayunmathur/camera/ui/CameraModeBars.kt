package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.util.CameraMode

@Composable
internal fun ModeSelector(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    sloMoSupported: Boolean?,
    onModeSelected: (CameraMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val modes = if (isPhotoType) {
            listOf(
                CameraMode.PORTRAIT to "Portrait",
                CameraMode.PHOTO to "Photo",
                CameraMode.PANORAMA to "Pano",
                CameraMode.PHOTOSPHERE to "Sphere"
            )
        } else {
            // Only include Slo-Mo when the device's back camera actually supports true HFR.
            buildList {
                if (sloMoSupported != false) {
                    add(CameraMode.SLOW_MO to "Slo-Mo")
                }
                add(CameraMode.VIDEO to "Video")
                add(CameraMode.CINEMATIC to "Cinematic")
                add(CameraMode.TIMELAPSE to "Timelapse")
            }
        }
        modes.forEach { (mode, label) ->
            val isSelected = cameraMode == mode
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .selectedPill(isSelected, RoundedCornerShape(20.dp))
                    .clickable { if (!isSelected) onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
internal fun BottomBar(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    iconRotation: Float,
    onPickerChanged: (Boolean) -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF3C3C3C), CircleShape)
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            IconSettings(
                tint = Color.White,
                modifier = Modifier.size(22.dp).rotate(iconRotation)
            )
        }

        Row(
            modifier = Modifier
                .background(Color(0xFF3C3C3C), RoundedCornerShape(20.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isPhotoType, CircleShape, Color(0xFF5C5C5C))
                    .clickable { if (!isPhotoType) onPickerChanged(true) },
                contentAlignment = Alignment.Center
            ) {
                IconCamera(Modifier.size(20.dp).rotate(iconRotation), Color.White)
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(!isPhotoType, CircleShape, Color(0xFF5C5C5C))
                    .clickable { if (isPhotoType) onPickerChanged(false) },
                contentAlignment = Alignment.Center
            ) {
                IconVideoCamera(Modifier.size(20.dp).rotate(iconRotation), Color.White)
            }
        }

        Spacer(Modifier.size(44.dp))
    }
}
