package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.auto.platform.PhoneStatus
import com.vayunmathur.library.ui.Badge
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.IconBatteryCharging
import com.vayunmathur.library.ui.IconBatteryFull
import com.vayunmathur.library.ui.IconBatteryLow
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDoNotDisturb
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSignalFull
import com.vayunmathur.library.ui.IconSignalNone
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import java.text.DateFormat
import java.util.Date

/**
 * The car launcher's bottom bar: home | pins | status.
 *
 * Left: home returns to the grid. Center: pinned apps in stored order plus
 * the open app when it isn't pinned (transient trailing icon, gone on
 * home). The open app's icon carries a tonal pill so selection reads from
 * the bar itself — app screens render full-bleed with no title header.
 * Right: the status cluster (signal, battery, DND, badge, clock) from the
 * phone monitor's flow. Semantic glyphs come from `Icons.kt`; app artwork
 * renders through [CarAppIcon].
 */
@Composable
fun LauncherBottomBar(
    pinned: List<DiscoveredApp>,
    selectedId: String?,
    onHome: () -> Unit,
    onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by CarLauncherState.phoneStatus.collectAsStateWithLifecycle()
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onHome) {
                IconHome(tint = statusTint(selectedId == null))
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (app in pinned.take(MAX_DOCK_APPS)) {
                    DockCell(
                        app = app,
                        selected = app.id == selectedId,
                        onClick = { onPin(app.id) },
                    )
                }
            }
            StatusCluster(status = status)
        }
    }
}

/** One dock cell: the app artwork, pill-highlighted while its app is open. */
@Composable
private fun DockCell(app: DiscoveredApp, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        if (selected) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                CarAppIcon(
                    icon = app.icon,
                    label = app.label.toString(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else {
            CarAppIcon(icon = app.icon, label = app.label.toString())
        }
    }
}

@Composable
private fun statusTint(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current

/**
 * Phone status for the driver: cell signal, battery, DND, message badge, and
 * the clock. Absent sources stay gone, like the old rail cluster's `gone`
 * row — nothing is faked.
 */
@Composable
private fun StatusCluster(status: PhoneStatus?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val bars = status?.signalBars
        if (bars != null) {
            if (bars > 0) IconSignalFull(Modifier.size(20.dp))
            else IconSignalNone(Modifier.size(20.dp))
        }
        val battery = status?.batteryPercent
        val charging = status?.batteryCharging == true
        if (battery != null) {
            when {
                charging -> IconBatteryCharging(Modifier.size(20.dp))
                battery <= LOW_BATTERY_PERCENT -> IconBatteryLow(Modifier.size(20.dp))
                else -> IconBatteryFull(Modifier.size(20.dp))
            }
            Text(
                text = "$battery%",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (status?.doNotDisturb == true) {
            IconDoNotDisturb(Modifier.size(20.dp))
        }
        val badge = status?.notificationCount ?: 0
        if (badge > 0) {
            BadgedBox(badge = { Badge { Text(badge.coerceAtMost(99).toString()) } }) {
                Spacer(Modifier.width(0.dp))
            }
        }
        Text(
            text = rememberClockText(),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun rememberClockText(): String {
    val formatted = androidx.compose.runtime.remember {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    }
    return formatted
}

private const val MAX_DOCK_APPS = 5
private const val LOW_BATTERY_PERCENT = 20
