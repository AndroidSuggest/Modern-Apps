package com.vayunmathur.screentime.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.screentime.platform.UsageBar
import kotlin.math.max

/**
 * A thin-bar usage chart with Y-axis labels, a light dashed grid, an average line and sparse
 * X-axis labels - the look of the reference Screen Time app, drawn locally with [Canvas]
 * (no shared chart component, per the redesign decision).
 *
 * [labelEvery] draws every Nth X label (1 for a 7-day week, 4 for a 24-hour day).
 */
@Composable
fun UsageBarChart(
    bars: List<UsageBar>,
    averageMillis: Long,
    labelEvery: Int,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val averageColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val axisTextPx = with(density) { 11.sp.toPx() }
    val sideGutter = with(density) { 44.dp.toPx() }
    val bottomGutter = with(density) { 22.dp.toPx() }
    val barWidth = with(density) { 7.dp.toPx() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 8.dp),
    ) {
        if (bars.isEmpty()) return@Canvas
        val niceMax = max(bars.maxOf { it.millis }, averageMillis).coerceAtLeast(1L)
        val plotLeft = sideGutter
        val plotTop = 0f
        val plotBottom = size.height - bottomGutter
        val plotHeight = plotBottom - plotTop
        val plotWidth = size.width - plotLeft

        val labelPaint = Paint().apply {
            color = labelColor.toArgb()
            textSize = axisTextPx
            isAntiAlias = true
        }

        // Horizontal grid + Y labels at 0, 1/3, 2/3, full.
        for (t in 0..3) {
            val fraction = t / 3f
            val value = (niceMax * fraction).toLong()
            val y = plotBottom - plotHeight * fraction
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            labelPaint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                formatAxis(value),
                plotLeft - 6f,
                y + axisTextPx / 3f,
                labelPaint,
            )
        }

        // Bars, one per column.
        val columnWidth = plotWidth / bars.size
        bars.forEachIndexed { index, bar ->
            val centerX = plotLeft + columnWidth * (index + 0.5f)
            val fraction = bar.millis.toFloat() / niceMax.toFloat()
            val barHeight = if (bar.millis <= 0L) 0f else max(fraction * plotHeight, barWidth)
            if (barHeight > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(centerX - barWidth / 2f, plotBottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
            if (index % labelEvery == 0) {
                labelPaint.textAlign = Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(
                    bar.label,
                    centerX,
                    size.height - 4f,
                    labelPaint,
                )
            }
        }

        // Average line over the bars.
        if (averageMillis > 0L) {
            val y = plotBottom - plotHeight * (averageMillis.toFloat() / niceMax.toFloat())
            drawLine(
                color = averageColor,
                start = Offset(plotLeft, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
            )
        }
    }
}
