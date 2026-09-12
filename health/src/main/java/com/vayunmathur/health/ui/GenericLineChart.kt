package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.LocalContentColor

@Composable
fun GenericLineChart(
    data: List<Pair<String, Double?>>,
    secondaryData: List<Pair<String, Double?>>? = null,
    goalValue: Double,
    secondaryGoal: Double? = null,
    lineColor: Color,
    secondaryLineColor: Color,
    goalColor: Color
) {
    if (data.all { it.second == null }) return

    val allValues =
        (data.mapNotNull { it.second } + (secondaryData?.mapNotNull { it.second } ?: emptyList()))
    val maxChartValue =
        (allValues.maxOrNull() ?: goalValue).coerceAtLeast(goalValue * 1.2).coerceAtLeast(10.0)
    val minSeriesValue = allValues.minOrNull() ?: 0.0
    val avgSeriesValue = if (allValues.isNotEmpty()) allValues.average() else 0.0
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    val chartHeight = 200.dp
    val xAxisHeight = 24.dp
    val sideLabelWidth = 44.dp

    val resources = LocalResources.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sideLabelWidthPx = with(density) { sideLabelWidth.toPx() }
        val chartWidthPx = fullWidthPx - sideLabelWidthPx
        val actualChartHeightPx = with(density) { (chartHeight - xAxisHeight).toPx() }

        val spacing = chartWidthPx / (data.size - 1).coerceAtLeast(1)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = sideLabelWidth, bottom = xAxisHeight)
        ) {
            val height = size.height
            val width = size.width

            gridLines(labelColor)

            val drawSeries = { series: List<Pair<String, Double?>>, color: Color, isPrimary: Boolean ->
                // Filled area under the primary line
                if (isPrimary) {
                    val path = Path()
                    var started = false
                    var lastX = 0f
                    series.forEachIndexed { index, pair ->
                        val value = pair.second ?: return@forEachIndexed
                        val x = index * spacing
                        val y = height - (value.toFloat() / maxChartValue.toFloat() * height)
                            .coerceIn(0f, height)
                        if (!started) {
                            path.moveTo(x, height)
                            path.lineTo(x, y)
                            started = true
                        } else {
                            path.lineTo(x, y)
                        }
                        lastX = x
                    }
                    if (started) {
                        path.lineTo(lastX, height)
                        path.close()
                        drawPath(path, color = color.copy(alpha = 0.15f))
                    }
                }
                var lastValidPoint: Offset? = null
                series.forEachIndexed { index, pair ->
                    val value = pair.second
                    val x = index * spacing
                    if (value != null) {
                        val y =
                            height - (value.toFloat() / maxChartValue.toFloat() * height).coerceIn(
                                0f, height
                            )
                        val currentPoint = Offset(x, y)
                        if (lastValidPoint != null) {
                            drawLine(
                                color = color,
                                start = lastValidPoint,
                                end = currentPoint,
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                        drawCircle(color = color, radius = 2.5.dp.toPx(), center = currentPoint)
                        lastValidPoint = currentPoint
                    }
                }
            }

            val drawGoal = { goal: Double, color: Color, isSecondary: Boolean ->
                val gy = height - (goal.toFloat() / maxChartValue.toFloat() * height).coerceIn(
                    0f, height
                )
                drawLine(
                    color = color,
                    start = Offset(0f, gy),
                    end = Offset(width, gy),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (isSecondary) PathEffect.dashPathEffect(
                        floatArrayOf(10f, 10f), 0f
                    ) else PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }

            drawGoal(goalValue, goalColor, false)
            secondaryGoal?.let { drawGoal(it, goalColor.copy(alpha = 0.5f), true) }

            drawSeries(data, lineColor, true)
            secondaryData?.let { drawSeries(it, secondaryLineColor, false) }
        }

        // Decimated X-axis labels
        XAxisLabels(
            labels = data.map { it.first },
            totalCount = data.size,
            density = density,
            color = labelColor,
        ) { index -> index * spacing }

        // Y-axis labels: max, avg, min, 0
        val format = { v: Double -> chartValueFormat(resources, v) }

        YAxisLabel(format(maxChartValue), labelColor, sideLabelWidth, 0f, density)
        if (allValues.isNotEmpty()) {
            val avgY = (1f - (avgSeriesValue.toFloat() / maxChartValue.toFloat())) * actualChartHeightPx
            YAxisLabel("avg ${format(avgSeriesValue)}", lineColor, sideLabelWidth, avgY, density)
            val minY = (1f - (minSeriesValue.toFloat() / maxChartValue.toFloat())) * actualChartHeightPx
            YAxisLabel("min ${format(minSeriesValue)}", labelColor, sideLabelWidth, minY, density)
        }
        YAxisLabel("0", labelColor, sideLabelWidth, actualChartHeightPx, density)
    }
}
