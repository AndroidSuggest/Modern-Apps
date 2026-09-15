package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.library.ui.Text

/**
 * Physical-lens picker: one pill per enumerated lens of the current facing
 * (UW / wide / tele / mono), cloned from [ZoomBar] (row container, 36dp
 * [selectedPill], 13sp labels). Hidden when fewer than two lenses are
 * available. Stateless: the caller passes the family plus the selection.
 */
@Composable
fun LensBar(
    lenses: List<PhysicalLens>,
    selected: PhysicalLens?,
    onSelect: (PhysicalLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size < 2) return
    val pickerDescription = stringResource(R.string.lens_picker_description)
    Row(
        modifier = modifier
            .semantics { contentDescription = pickerDescription }
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lenses.forEach { lens ->
            val isSelected = lens == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isSelected, CircleShape)
                    .clip(CircleShape)
                    .clickable { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lensPillLabel(lens),
                    color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Pill text for one lens: localized short label plus the focal-length readout. */
@Composable
internal fun lensPillLabel(lens: PhysicalLens): String {
    val short = when (lens.labelKey) {
        "ultrawide" -> stringResource(R.string.lens_ultrawide)
        "tele" -> stringResource(R.string.lens_tele)
        "mono" -> stringResource(R.string.lens_mono)
        "front" -> stringResource(R.string.lens_front)
        else -> stringResource(R.string.lens_wide)
    }
    val focal = LensSelectionLogic.displayLabel(lens)
    return if (focal == short || lens.labelKey == "wide" || lens.labelKey == "front") short
    else "$short · $focal"
}
