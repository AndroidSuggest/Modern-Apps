package com.vayunmathur.office.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.office.R

@Composable
fun ChartEditorDialog(initial: OdfChart?, onConfirm: (OdfChart) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(initial?.type ?: ChartType.BAR) }
    var categories by remember { mutableStateOf((initial?.categories ?: listOf("Category 1", "Category 2", "Category 3")).joinToString(", ")) }
    var seriesText by remember {
        mutableStateOf(
            (initial?.series ?: listOf(OdfChartSeries("Series 1", listOf(3f, 5f, 2f))))
                .joinToString("\n") { s -> s.name + ": " + s.values.joinToString(", ") { formatAxis(it) } }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.insert_chart) else stringResource(R.string.edit_chart)) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.type), modifier = Modifier.padding(end = 4.dp))
                    for (t in ChartType.entries) {
                        TextButton(onClick = { type = t }) {
                            Text(t.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (type == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                TextField(value = categories, onValueChange = { categories = it }, label = { Text(stringResource(R.string.categories_comma_separated)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(value = seriesText, onValueChange = { seriesText = it }, label = { Text(stringResource(R.string.series_name_v1_v2)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cats = categories.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val series = seriesText.lines().mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val name = (if (line.contains(":")) line.substringBefore(":") else "Series").trim().ifEmpty { "Series" }
                    val valsPart = if (line.contains(":")) line.substringAfter(":") else line
                    val vals = valsPart.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (vals.isEmpty()) null else OdfChartSeries(name, vals)
                }
                if (cats.isNotEmpty() && series.isNotEmpty()) { onConfirm(OdfChart(type, cats, series)); onDismiss() }
            }) { Text(stringResource(UiR.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
