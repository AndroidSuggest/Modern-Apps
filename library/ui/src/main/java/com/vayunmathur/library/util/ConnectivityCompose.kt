package com.vayunmathur.library.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconWarning

/**
 * Live "are we online?" state for Compose. Starts [ConnectivityMonitor] on first use.
 *
 * Requires `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>`.
 */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    remember(context) { ConnectivityMonitor.start(context); true }
    return ConnectivityMonitor.isOnline.collectAsState()
}

/**
 * Drop-in banner shown while offline. Adopting an offline indicator is then ~2 lines:
 * `val online by rememberIsOnline(); OfflineBanner(online)`.
 */
@Composable
fun OfflineBanner(online: Boolean, modifier: Modifier = Modifier) {
    if (online) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWarning(tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            text = "No internet connection",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Wraps a screen so a full-width offline banner slides in above it whenever validated internet is
 * lost, and the wrapped content fills the rest. Adopting the offline indicator in an app is then a
 * one-line wrap around the existing top-level navigation:
 *
 * ```
 * DynamicTheme {
 *     OfflineAware {
 *         Navigation(viewModel)
 *     }
 * }
 * ```
 *
 * The banner carries [statusBarsPadding] so it clears the status bar under edge-to-edge; the content
 * keeps drawing edge-to-edge and manages its own insets exactly as before.
 */
@Composable
fun OfflineAware(content: @Composable () -> Unit) {
    val online by rememberIsOnline()
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(online, Modifier.statusBarsPadding())
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
