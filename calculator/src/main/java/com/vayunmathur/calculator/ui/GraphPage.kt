package com.vayunmathur.calculator.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.calculator.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.util.AnalysisKind
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.util.Expression
import com.vayunmathur.calculator.util.formatResult
import com.vayunmathur.library.ui.AlertDialog
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
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

@Composable
fun GraphPage(viewModel: CalculatorViewModel) {
    var showResults by remember { mutableStateOf(false) }
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
            AnalysisBar(viewModel) { showResults = true }
            GraphCanvas(viewModel, Modifier.fillMaxWidth().weight(1f))
            HorizontalDivider()
            FunctionEditors(viewModel)
        }
    }
    if (showResults) {
        AlertDialog(
            onDismissRequest = { showResults = false },
            title = { Text(stringResource(R.string.analysis)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(viewModel.analysisSummary) { line -> Text(line, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            },
            confirmButton = { TextButton({ showResults = false }) { Text(stringResource(R.string.done)) } },
            dismissButton = { TextButton({ viewModel.clearAnalysis(); showResults = false }) { Text(stringResource(R.string.clear_markers)) } },
        )
    }
}

/** Horizontally-scrolling row of numeric-analysis actions over the visible window. */
@Composable
private fun AnalysisBar(viewModel: CalculatorViewModel, onResults: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnalysisKind.entries.forEach { kind ->
            val short = when (kind) {
                AnalysisKind.ROOTS -> "Roots"
                AnalysisKind.MINIMA -> "Min"
                AnalysisKind.MAXIMA -> "Max"
                AnalysisKind.INTERSECTIONS -> "Intersect"
                AnalysisKind.Y_INTERCEPT -> "y-int"
            }
            AssistChip(onClick = { viewModel.runAnalysis(kind); onResults() }, label = { Text(short) })
        }
        AssistChip(onClick = { viewModel.clearAnalysis() }, label = { Text(stringResource(R.string.clear)) })
    }
}

@Composable
private fun GraphCanvas(viewModel: CalculatorViewModel, modifier: Modifier) {
    val axisColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()

    // Compile each function once per edit; null means it doesn't parse yet.
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
        val jumpLimit = h * 4
        for ((fn, expr) in compiled) {
            if (expr == null) continue
            val path = Path()
            var penDown = false
            var prevSy = 0f
            if (fn.polar) {
                // r = f(θ): sample θ over [0, 2π] in radians (polar is inherently radian).
                val samples = 2000
                var k = 0
                while (k <= samples) {
                    val theta = 2 * PI * k / samples
                    val r = runCatching { expr.eval(theta, AngleMode.RADIANS) }.getOrNull()
                    if (r == null || r.isNaN() || r.isInfinite()) { penDown = false } else {
                        val sx = px(r * cos(theta)); val sy = py(r * sin(theta))
                        if (!penDown) { path.moveTo(sx, sy); penDown = true } else path.lineTo(sx, sy)
                    }
                    k++
                }
            } else {
                // y = f(x): one sample per horizontal pixel; break at undefined points / asymptotes.
                var pxCol = 0
                while (pxCol <= w.toInt()) {
                    val graphX = cx + (pxCol - w / 2) / scale
                    val yVal = runCatching { expr.eval(graphX, viewModel.angleMode) }.getOrNull()
                    if (yVal == null || yVal.isNaN() || yVal.isInfinite()) { penDown = false } else {
                        val sy = py(yVal)
                        if (!penDown) { path.moveTo(pxCol.toFloat(), sy); penDown = true }
                        else if (abs(sy - prevSy) > jumpLimit) path.moveTo(pxCol.toFloat(), sy)
                        else path.lineTo(pxCol.toFloat(), sy)
                        prevSy = sy
                    }
                    pxCol++
                }
            }
            drawPath(path, fn.color, style = Stroke(width = 3f))
        }

        // ---- Analysis markers ----
        for (p in viewModel.analysisPoints) {
            val cxp = px(p.x); val cyp = py(p.y)
            if (cxp < -20 || cxp > w + 20 || cyp < -20 || cyp > h + 20) continue
            drawCircle(surface, radius = 7f, center = Offset(cxp, cyp))
            drawCircle(p.color, radius = 7f, center = Offset(cxp, cyp), style = Stroke(width = 3f))
            val layout = textMeasurer.measure(p.label, TextStyle(color = p.color, fontSize = 11.sp))
            drawText(layout, topLeft = Offset((cxp + 8f).coerceAtMost(w - layout.size.width), cyp - layout.size.height - 4f))
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
