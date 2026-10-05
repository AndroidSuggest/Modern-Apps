package com.vayunmathur.clock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.StopwatchUiState
/** Binds [ClockViewModel] to the stateless [StopwatchScreen]. */
@Composable
fun StopwatchPage(clockViewModel: ClockViewModel) {
    val isRunning by clockViewModel.stopwatchRunning.collectAsState()
    val countingTime by clockViewModel.stopwatchCountingTime.collectAsState()
    val lapTimes by clockViewModel.lapTimes.collectAsState()

    StopwatchScreen(
        state = StopwatchUiState(isRunning = isRunning, countingTime = countingTime, lapTimes = lapTimes),
        actions = clockViewModel,
    )
}
