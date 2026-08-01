package com.vayunmathur.calculator.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.calculator.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.util.Expression
import com.vayunmathur.calculator.util.FeatureKind
import com.vayunmathur.calculator.util.GraphAnalysis
import com.vayunmathur.calculator.util.GraphPoint
import com.vayunmathur.calculator.util.formatResult
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun GraphPage(viewModel: CalculatorViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graph)) },
                actions = {
                    AssistChip(
                        onClick = { viewModel.toggleAngleMode() },
                        label = { Text(if (viewModel.angleMode == AngleMode.DEGREES) "DEG" else "RAD") },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            GraphCanvas(viewModel, Modifier.fillMaxWidth().weight(1f))
            HorizontalDivider()
            FunctionEditors(viewModel)
        }
    }
}

/** How close a touch has to land, in dp, for a notable point to be revealed. */
private val TouchSlop = 28.dp

@Composable
private fun GraphCanvas(viewModel: CalculatorViewModel, modifier: Modifier) {
    val axisColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    // Marker text uses the theme's foreground colour, not the curve's, so it stays legible
    // against the background whatever colour the curve happens to be.
    val markerTextColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    // Colour lookup for markers, and the localised kind names, resolved outside the draw scope.
    val colorOf = viewModel.functions.associate { it.id to it.color }
    val kindLabels = mapOf(
        FeatureKind.ROOT to stringResource(R.string.feature_root),
        FeatureKind.Y_INTERCEPT to stringResource(R.string.feature_y_intercept),
        FeatureKind.MINIMUM to stringResource(R.string.feature_minimum),
        FeatureKind.MAXIMUM to stringResource(R.string.feature_maximum),
        FeatureKind.INTERSECTION to stringResource(R.string.feature_intersection),
    )

    // Compile each function once per edit, not once per frame; null means it doesn't parse yet.
    val compiled = viewModel.functions.map { fn ->
        fn to if (fn.enabled && fn.text.isNotBlank()) runCatching { Expression.parse(fn.text) }.getOrNull() else null
    }

    Canvas(
        modifier
            .background(surface)
            .onSizeChanged {
                viewModel.viewWidthPx = it.width.toFloat()
                viewModel.viewHeightPx = it.height.toFloat()
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val gx = viewModel.centerX + (offset.x - size.width / 2) / viewModel.scale
                    val gy = viewModel.centerY + (size.height / 2 - offset.y) / viewModel.scale
                    viewModel.tapGraph(GraphPoint(gx, gy), TouchSlop.toPx() / viewModel.scale)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldScale = viewModel.scale
                    val newScale = (viewModel.scale * zoom).coerceIn(2.0, 400000.0)
                    val gx = viewModel.centerX + (centroid.x - size.width / 2) / oldScale
                    val gy = viewModel.centerY + (size.height / 2 - centroid.y) / oldScale
                    viewModel.scale = newScale
                    viewModel.centerX = gx - (centroid.x - size.width / 2) / newScale
                    viewModel.centerY = gy - (size.height / 2 - centroid.y) / newScale
                    viewModel.centerX -= pan.x / newScale
                    viewModel.centerY += pan.y / newScale
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val scale = viewModel.scale
        val cx = viewModel.centerX
        val cy = viewModel.centerY
        fun px(gx: Double) = ((gx - cx) * scale + w / 2).toFloat()
        fun py(gy: Double) = (h / 2 - (gy - cy) * scale).toFloat()

        val minX = cx - (w / 2) / scale
        val maxX = cx + (w / 2) / scale
        val minY = cy - (h / 2) / scale
        val maxY = cy + (h / 2) / scale
        val step = niceStep((w / 2) / scale)

        // ---- Grid + tick labels ----
        var gx = ceil(minX / step) * step
        while (gx <= maxX) {
            val x = px(gx)
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            if (abs(gx) > step / 2) {
                val layout = textMeasurer.measure(formatResult(gx), TextStyle(color = labelColor, fontSize = 10.sp))
                drawText(layout, topLeft = Offset(x + 3f, (py(0.0) + 4f).coerceIn(0f, h - layout.size.height)))
            }
            gx += step
        }
        var gy = ceil(minY / step) * step
        while (gy <= maxY) {
            val y = py(gy)
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            if (abs(gy) > step / 2) {
                val layout = textMeasurer.measure(formatResult(gy), TextStyle(color = labelColor, fontSize = 10.sp))
                drawText(layout, topLeft = Offset((px(0.0) + 3f).coerceIn(0f, w - layout.size.width), y + 3f))
            }
            gy += step
        }

        // ---- Axes ----
        drawLine(axisColor, Offset(px(0.0), 0f), Offset(px(0.0), h), strokeWidth = 2.5f)
        drawLine(axisColor, Offset(0f, py(0.0)), Offset(w, py(0.0)), strokeWidth = 2.5f)

        // ---- Curves ----
        // Drawn from the same sampler the analysis uses, so markers land on the drawn line.
        for ((fn, expr) in compiled) {
            if (expr == null) continue
            val curve = GraphAnalysis.sample(
                fn.id, expr, fn.polar, viewModel.angleMode, minX, maxX, minY, maxY, w.toInt(),
            )
            val path = Path()
            for (run in curve.runs) {
                run.forEachIndexed { i, p ->
                    val sx = px(p.x)
                    val sy = py(p.y)
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
            }
            drawPath(path, fn.color, style = Stroke(width = 3f))
        }

        // ---- Markers the user has revealed ----
        for (m in viewModel.markers) {
            val mx = px(m.point.x)
            val my = py(m.point.y)
            if (mx < -20 || mx > w + 20 || my < -20 || my > h + 20) continue
            // An intersection belongs to two curves, so it takes the neutral theme colour.
            val ringColor = m.curveIds.singleOrNull()?.let { colorOf[it] } ?: markerTextColor
            drawCircle(surface, radius = 7f, center = Offset(mx, my))
            drawCircle(ringColor, radius = 7f, center = Offset(mx, my), style = Stroke(width = 3f))

            val text = "${kindLabels[m.kind]} (${formatResult(m.point.x)}, ${formatResult(m.point.y)})"
            val layout = textMeasurer.measure(text, TextStyle(color = markerTextColor, fontSize = 11.sp))
            val tx = (mx + 10f).coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f))
            val ty = (my - layout.size.height - 6f).coerceIn(0f, (h - layout.size.height).coerceAtLeast(0f))
            // A surface-coloured plate keeps the label readable where it overlaps a curve.
            drawRect(
                surface.copy(alpha = 0.85f),
                topLeft = Offset(tx - 3f, ty - 2f),
                size = Size(layout.size.width + 6f, layout.size.height + 4f),
            )
            drawText(layout, topLeft = Offset(tx, ty))
        }
    }
}

@Composable
private fun FunctionEditors(viewModel: CalculatorViewModel) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(horizontal = 12.dp)) {
        items(viewModel.functions, key = { it.id }) { fn ->
            val error = fn.text.isNotBlank() && runCatching { Expression.parse(fn.text) }.isFailure
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(fn.color))
                    OutlinedTextField(
                        value = fn.text,
                        onValueChange = { viewModel.updateFunction(fn.id, it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        prefix = { Text(if (fn.polar) "r = " else "y = ") },
                        singleLine = true,
                        isError = error,
                        placeholder = { Text(if (fn.polar) "f(θ)" else "f(x)") },
                    )
                    IconButton({ viewModel.toggleFunction(fn.id) }) {
                        if (fn.enabled) IconVisible() else IconVisibilityOff()
                    }
                    IconButton({ viewModel.removeFunction(fn.id) }) { IconClose() }
                }
                FilterChip(
                    selected = fn.polar,
                    onClick = { viewModel.togglePolar(fn.id) },
                    label = { Text(if (fn.polar) "Polar r(θ)" else "Cartesian y(x)") },
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                AssistChip(
                    onClick = { viewModel.addFunction() },
                    label = { Text(stringResource(R.string.add_function)) },
                    leadingIcon = { IconAdd() },
                )
            }
        }
    }
}

/** Chooses a "nice" axis step (1, 2 or 5 × 10ⁿ) near [target] units. */
private fun niceStep(target: Double): Double {
    if (target <= 0 || target.isNaN() || target.isInfinite()) return 1.0
    val magnitude = 10.0.pow(floor(log10(target)))
    val normalized = target / magnitude
    val nice = when {
        normalized < 1.5 -> 1.0
        normalized < 3.5 -> 2.0
        normalized < 7.5 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}
