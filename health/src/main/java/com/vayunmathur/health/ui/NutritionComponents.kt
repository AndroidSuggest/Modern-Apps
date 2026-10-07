package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.ui.components.MetricRing
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.round

// Nutrition macro/nutrient row components. Split from NutritionPage.kt to keep that file
// under the 350-line UI limit and one public composable per file.

@Composable
internal fun CompactMacroRing(
    label: String,
    value: Double,
    goal: Double,
    unit: String,
    color: Color,
) {
    val progress = (value / goal).toFloat().coerceIn(0f, 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MetricRing(
            progress = progress,
            label = "",
            value = "${value.round(0).toInt()}",
            modifier = Modifier.size(72.dp),
            color = color,
            strokeWidth = 6.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.95f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${value.round(0).toInt()}/${goal.toInt()}$unit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Single nutrient row used by NutritionDetailsPage. */
@Composable
internal fun NutrientProgressRow(nutrient: NutrientDV, currentAmount: Double) {
    val progress = (currentAmount / nutrient.dailyValue).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = nutrient.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${currentAmount.round(1)} / ${nutrient.dailyValue.round(0).toInt()}${nutrient.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = HealthColors.Nutrition,
            trackColor = HealthColors.Nutrition.copy(alpha = 0.18f),
            strokeCap = StrokeCap.Round,
        )
    }
}
