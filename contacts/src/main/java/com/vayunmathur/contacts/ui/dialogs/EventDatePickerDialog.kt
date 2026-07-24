package com.vayunmathur.contacts.ui.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.hasYear
import com.vayunmathur.library.util.LocalNavResultRegistry
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.datetime.format.MonthNames
import com.vayunmathur.library.util.localizedMonthNames
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.time.Clock

@Composable
fun EventDatePickerDialog(id: String, initialDate: LocalDate?, onDismiss: () -> Unit) {
    val registry = LocalNavResultRegistry.current
    val resultScope = rememberCoroutineScope()
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    val baseDate = initialDate ?: today
    
    var includeYear by remember { mutableStateOf(initialDate?.hasYear ?: true) }
    
    val years = remember { (1900..2100).toList() }
    val months = remember { (1..12).toList() }
    
    var selectedYear by remember { mutableStateOf(baseDate.year) }
    var selectedMonth by remember { mutableStateOf(baseDate.month.number) }
    var selectedDay by remember { mutableStateOf(baseDate.day) }

    val daysInMonth = remember(selectedMonth, selectedYear) {
        val nextMonth = if (selectedMonth == 12) 1 else selectedMonth + 1
        val nextYear = if (selectedMonth == 12) selectedYear + 1 else selectedYear
        try { LocalDate(nextYear, nextMonth, 1).minus(1, DateTimeUnit.DAY).day } catch (_: Exception) { 31 }
    }
    
    LaunchedEffect(daysInMonth) {
        if (selectedDay > daysInMonth) {
            selectedDay = daysInMonth
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val result = LocalDate(if (includeYear) selectedYear else 1604, selectedMonth, selectedDay)
                resultScope.launch {
                    registry.dispatchResult(id, result)
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Date Preview
                val previewText = try {
                    val date = LocalDate(selectedYear, selectedMonth, selectedDay)
                    date.format(LocalDate.Format {
                        monthName(MonthNames(localizedMonthNames(TextStyle.FULL)))
                        chars(" ")
                        day()
                        if (includeYear) {
                            chars(", ")
                            year()
                        }
                    })
                } catch (_: Exception) { "" }
                
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Spinners
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPicker(
                        items = months.map { localizedMonthNames(TextStyle.SHORT)[it - 1] },
                        initialIndex = selectedMonth - 1,
                        onIndexSelected = { selectedMonth = it + 1 },
                        modifier = Modifier.weight(1f)
                    )
                    WheelPicker(
                        items = (1..daysInMonth).map { it.toString() },
                        initialIndex = selectedDay - 1,
                        onIndexSelected = { selectedDay = it + 1 },
                        modifier = Modifier.weight(1f)
                    )
                    WheelPicker(
                        items = years.map { it.toString() },
                        initialIndex = years.indexOf(selectedYear).coerceAtLeast(0),
                        onIndexSelected = { selectedYear = years[it] },
                        modifier = Modifier.weight(1f),
                        enabled = includeYear
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Include Year Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.include_year))
                    Switch(
                        checked = includeYear,
                        onCheckedChange = { includeYear = it }
                    )
                }
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    modifier: Modifier = Modifier,
    items: List<String>,
    initialIndex: Int,
    onIndexSelected: (Int) -> Unit,
    enabled: Boolean = true
) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.size - 1)) { items.size }
    
    LaunchedEffect(pagerState.currentPage) {
        onIndexSelected(pagerState.currentPage)
    }
    
    // Support dynamic items count (e.g. days in month changing)
    LaunchedEffect(items.size) {
        if (pagerState.currentPage >= items.size) {
            pagerState.scrollToPage(items.size - 1)
        }
    }

    Box(modifier = modifier.alpha(if (enabled) 1f else 0.3f)) {
        // Selection Highlighters
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.Center)
                .background(Color.Transparent)
        ) {
            HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.outlineVariant)
            HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.outlineVariant)
        }

        VerticalPager(
            state = pagerState,
            modifier = Modifier.height(160.dp),
            contentPadding = PaddingValues(vertical = 60.dp),
            userScrollEnabled = enabled
        ) { page ->
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val absOffset = abs(pageOffset)
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (absOffset * 0.2f).coerceIn(0f, 0.4f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (absOffset * 0.5f).coerceIn(0f, 0.7f)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = items.getOrNull(page) ?: "",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (absOffset < 0.5f) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}