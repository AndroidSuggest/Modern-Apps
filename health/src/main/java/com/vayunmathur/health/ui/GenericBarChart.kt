package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.Text

@Composable
fun GenericBarChart(
    data: List<Pair<String, Double>>,
    totalBarCount: Int,
    goalValue: Double,
    barColor: Color,
    goalColor: Color
) {
    val maxValueFound = data.maxOfOrNull { it.second } ?: 0.0
    val avgValueFound = if (data.isNotEmpty()) data.map { it.second }.average() else 0.0
    val maxChartValue = (maxValueFound.toFloat() * 1.2f).coerceAtLeast(goalValue.toFloat() * 1.2f)
        .coerceAtLeast(10f)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)
    var selectedIndex by remember(data) { mutableIntStateOf(-1) }

    val chartHeight = 200.dp
    val xAxisHeight = 24.dp
    val sideLabelWidth = 44.dp
    val tooltipHeight = 20.dp

    val resources = LocalResources.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight + tooltipHeight)
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sideLabelWidthPx = with(density) { sideLabelWidth.toPx() }
        val chartWidthPx = fullWidthPx - sideLabelWidthPx

        val barWidth = with(density) { 6.dp.toPx() }
        val spacing =
            (chartWidthPx - (totalBarCount * barWidth)) / (totalBarCount + 1).coerceAtLeast(1)
        val format = { v: Double -> chartValueFormat(resources, v) }

        // Tooltip above selected bar
        if (selectedIndex in data.indices) {
            val pair = data[selectedIndex]
            val barCenterX = spacing + selectedIndex * (barWidth + spacing) + barWidth / 2
            Text(
                text = format(pair.second),
                color = barColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = with(density) { barCenterX.toDp() })
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-(placeable.width / 2), 0)
                        }
                    }
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .align(Alignment.BottomStart)
                .padding(end = sideLabelWidth, bottom = xAxisHeight)
                .pointerInput(data, totalBarCount) {
                    detectTapGestures { offset ->
                        val tapX = offset.x
                        val matched = data.indices.firstOrNull { idx ->
                            val left = spacing + idx * (barWidth + spacing)
                            tapX >= left - spacing / 2 && tapX <= left + barWidth + spacing / 2
                        } ?: -1
                        selectedIndex = if (matched == selectedIndex) -1 else matched
                    }
                }
        ) {
            val height = size.height
            val width = size.width

            gridLines(labelColor)

            val goalY = height - (goalValue.toFloat() / maxChartValue * height).coerceIn(0f, height)
            drawLine(
                color = goalColor,
                start = Offset(0f, goalY),
                end = Offset(width, goalY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )

            data.forEachIndexed { index, pair ->
                val barHeight =
                    (pair.second.toFloat() / maxChartValue * height).coerceIn(
                        if (pair.second > 0) 4f else 0f, height
                    )
                val x = spacing + index * (barWidth + spacing)
                val y = height - barHeight
                val drawColor = if (selectedIndex < 0 || selectedIndex == index) barColor
                else barColor.copy(alpha = 0.6f)
                drawRoundRect(
                    color = drawColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }

        // Decimated X-axis labels
        XAxisLabels(
            labels = data.map { it.first },
            totalCount = totalBarCount,
            density = density,
            color = labelColor,
        ) { index -> spacing + index * (barWidth + spacing) + barWidth / 2 }

        // Y-axis labels: max, avg, 0
        val actualChartHeightPx = with(density) { (chartHeight - xAxisHeight).toPx() }
        val topOffsetPx = with(density) { tooltipHeight.toPx() }
        YAxisLabel(format(maxChartValue.toDouble()), labelColor, sideLabelWidth, topOffsetPx, density)
        if (data.isNotEmpty()) {
            val avgY = topOffsetPx + (1f - (avgValueFound.toFloat() / maxChartValue)) * actualChartHeightPx
            YAxisLabel("avg ${format(avgValueFound)}", barColor, sideLabelWidth, avgY, density)
        }
        YAxisLabel("0", labelColor, sideLabelWidth, topOffsetPx + actualChartHeightPx, density)
    }
}
