package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import com.vayunmathur.health.R
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.round
import com.vayunmathur.library.util.toStringCommas
import androidx.compose.ui.unit.Density

/** Subtle horizontal grid lines at 25/50/75/100% of the canvas height. */
internal fun DrawScope.gridLines(color: Color) {
    listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
        drawLine(
            color = color.copy(alpha = 0.10f),
            start = Offset(0f, size.height * (1f - frac)),
            end = Offset(size.width, size.height * (1f - frac)),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/** Shared "12,345" / "12k" axis value formatter for both chart types. */
internal fun chartValueFormat(resources: android.content.res.Resources, v: Double): String =
    if (v >= 10000) resources.getString(R.string.k_format, (v / 1000).round(0).toString())
    else v.toLong().toStringCommas()

/** Decimated, center-aligned X-axis labels shared by both chart types. */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.XAxisLabels(
    labels: List<String>,
    totalCount: Int,
    density: Density,
    color: Color,
    xForIndex: (Int) -> Float,
) {
    val labelStep = decimationStep(totalCount)
    labels.forEachIndexed { index, label ->
        if (label.isNotEmpty() && index % labelStep == 0) {
            val x = xForIndex(index)
            Text(
                text = label,
                color = color,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = with(density) { x.toDp() })
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-(placeable.width / 2), 0)
                        }
                    }
            )
        }
    }
}

private fun decimationStep(count: Int): Int = when {
    count <= 8 -> 1
    count <= 16 -> 2
    count <= 24 -> 3
    else -> 5
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.YAxisLabel(
    text: String,
    color: Color,
    sideLabelWidth: Dp,
    yPx: Float,
    density: Density,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(sideLabelWidth)
            .offset(y = with(density) { yPx.toDp() })
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, -(placeable.height / 2))
                }
            }
            .padding(start = 4.dp)
    )
}
