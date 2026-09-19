package com.vayunmathur.auto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The home grid: every discovered car-capable app. Tap opens its hosted
 * template screen; an empty grid gets the explicit empty state.
 */
@Composable
fun LauncherHomeGrid(
    apps: List<DiscoveredApp>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.car_no_apps),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(apps, key = { it.id }) { app ->
            LauncherGridCell(app = app, onOpen = { onOpen(app.id) })
        }
    }
}

@Composable
private fun LauncherGridCell(app: DiscoveredApp, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onOpen).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CarAppIcon(icon = app.icon, label = app.label.toString())
        Text(
            text = app.label.toString(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
