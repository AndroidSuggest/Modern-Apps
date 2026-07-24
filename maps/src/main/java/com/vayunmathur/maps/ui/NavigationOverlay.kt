package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.formatDistance
import com.vayunmathur.maps.util.formatEta
import kotlin.math.roundToInt

/**
 * Full-screen overlay drawn on top of [MaplibreMap] while navigation is
 * active. Renders the Google-Maps-style top maneuver card, bottom ETA strip,
 * a recenter FAB when the user has panned away from the snapped position,
 * and an arrival/failure card for terminal states.
 *
 * Hidden when state is [NavigationSessionManager.NavState.Idle] — caller is
 * responsible for only rendering this composable inside a `Box` overlay.
 */
@Composable
fun NavigationOverlay(
    navState: NavigationSessionManager.NavState,
    steps: List<RouteService.Step>,
    autoFollow: Boolean,
    onRecenter: () -> Unit,
    onEndTrip: () -> Unit,
    onDismissArrival: () -> Unit,
) {
    if (navState is NavigationSessionManager.NavState.Idle) return

    Box(Modifier.fillMaxSize()) {
        // ---- Top maneuver card ----
        when (navState) {
            is NavigationSessionManager.NavState.Navigating -> {
                ManeuverCard(
                    progress = navState.progress,
                    steps = steps,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            NavigationSessionManager.NavState.Starting -> {
                StatusCard(
                    text = stringResource(R.string.nav_status_starting),
                    showProgress = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            NavigationSessionManager.NavState.Recalculating -> {
                StatusCard(
                    text = stringResource(R.string.nav_off_route),
                    showProgress = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            is NavigationSessionManager.NavState.Failed -> {
                FailureCard(
                    reason = navState.reason,
                    onDismiss = onDismissArrival,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            else -> {}
        }

        // ---- Recenter FAB ----
        if (navState is NavigationSessionManager.NavState.Navigating && !autoFollow) {
            ExtendedFloatingActionButton(
                onClick = onRecenter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(16.dp),
                icon = {
                    IconLocationOn()
                },
                text = { Text(stringResource(R.string.nav_action_recenter)) }
            )
        }

        // ---- Bottom ETA strip / arrival card ----
        when (navState) {
            is NavigationSessionManager.NavState.Navigating -> {
                EtaStrip(
                    progress = navState.progress,
                    onEndTrip = onEndTrip,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                )
            }
            NavigationSessionManager.NavState.Arrived -> {
                ArrivalCard(
                    onDismiss = onDismissArrival,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(16.dp)
                )
            }
            else -> {}
        }
    }
}

@Composable
private fun ManeuverCard(
    progress: NavigationProgress,
    steps: List<RouteService.Step>,
    modifier: Modifier = Modifier,
) {
    val currentStep = steps.getOrNull(progress.currentStepIndex)
    val nextStep = steps.getOrNull(progress.currentStepIndex + 1)
    // Per plan: we count down to the NEXT step's maneuver. The instruction
    // shown is the next maneuver's instruction; the current step's text is
    // the "you're currently on" peek line below.
    val primary = nextStep ?: currentStep
    val primaryInstruction = primary?.navInstruction?.instructions.orEmpty()
    val primaryIcon = primary?.navInstruction?.maneuver?.iconContent()
    val distanceText = formatDistance(progress.distanceToNextManeuver)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (primaryIcon != null) {
                primaryIcon(
                    Modifier.size(56.dp),
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    distanceText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    primaryInstruction,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (nextStep != null && currentStep != null && currentStep !== primary) {
                    val secondary = steps.getOrNull(progress.currentStepIndex + 2)
                    if (secondary != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.then, secondary.navInstruction.instructions),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    text: String,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, fontWeight = FontWeight.Medium)
            if (showProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EtaStrip(
    progress: NavigationProgress,
    onEndTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val etaText = formatEta(progress.etaEpochMs)
    val remainingDistance = formatDistance(progress.distanceRemaining)
    val remainingMinutes = (((progress.etaEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)) / 60_000L).toInt()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    etaText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.min, remainingMinutes, remainingDistance),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onEndTrip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.nav_action_end))
            }
        }
    }
}

@Composable
private fun FailureCard(
    reason: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.nav_status_failed),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(reason)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onDismiss) {
                    Text(stringResource(R.string.nav_arrived_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ArrivalCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.nav_arrived_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onDismiss) {
                Text(stringResource(R.string.nav_arrived_dismiss))
            }
        }
    }
}
