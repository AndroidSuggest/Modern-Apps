package com.vayunmathur.passwords.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.util.PasswordUiState
import com.vayunmathur.passwords.util.PasswordsActions
import com.vayunmathur.passwords.util.PasswordsViewModel
import com.vayunmathur.passwords.util.TOTP

@Composable
fun PasswordPage(
    backStack: NavBackStack<Route>,
    id: Long,
    viewModel: PasswordsViewModel,
) {
    val password by viewModel.passwordState(id)
    val now by viewModel.tickerFlow.collectAsState()
    PasswordScreen(
        state = PasswordUiState(password, now),
        actions = viewModel,
        onBack = { backStack.pop() },
        onEdit = { backStack.add(Route.PasswordEditPage(id)) },
    )
}

/**
 * The credential detail screen, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from. That also keeps the screenshot generator away from a real vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordScreen(
    state: PasswordUiState,
    actions: PasswordsActions,
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    /**
     * Seed for the screen's own UI-only state. The app always takes the default; a preview
     * can set it to capture the revealed form without driving the reveal button.
     */
    initialShowPassword: Boolean = false,
) {
    val password = state.password
    val now = state.now
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(initialShowPassword) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        actions.copyEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(password.name.ifBlank { stringResource(R.string.section_password) }) },
                actions = {
                    IconButton(onClick = { actions.delete(password); onBack() }) {
                        IconDelete()
                    }
                },
                navigationIcon = {
                    IconNavigation(onBack)
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onEdit) {
                IconEdit()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Avatar with initial
                    val initial = password.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(password.name.ifBlank { stringResource(R.string.no_name) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(password.userId.ifBlank { stringResource(R.string.no_user) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Password card
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.section_password), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (showPassword) password.password else "•".repeat(password.password.length),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { showPassword = !showPassword }) {
                            if (showPassword) IconVisibilityOff()
                            else IconVisible()
                        }

                        IconButton(onClick = {
                            actions.copyToClipboard("password", password.password)
                        }) {
                            IconCopy()
                        }
                    }
                }
            }

            // TOTP card: show generated code and circular timer
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.section_totp), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    val secret = password.totpSecret
                    if (secret.isNullOrBlank()) {
                        Text(stringResource(R.string.totp_not_configured))
                    } else {
                        val timeStep = now / 1000 / 30
                        val currentCode = remember(secret, timeStep) {
                            TOTP.generate(secret, timeStep * 30)
                        }
                        val millisIntoStep = now % 30000
                        val millisRemaining = 30000 - millisIntoStep
                        val secondsRemaining = (millisRemaining / 1000).toInt()
                        val progress = millisRemaining / 30000f

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(currentCode, style = MaterialTheme.typography.displayMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(R.string.totp_refreshes_in, secondsRemaining), style = MaterialTheme.typography.bodySmall)
                            }

                            // Circular progress showing proportion of time remaining
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator({ progress }, Modifier.size(56.dp))
                                IconButton({
                                    actions.copyToClipboard("totp", currentCode, "TOTP copied")
                                }) {
                                    IconCopy()
                                }
                            }
                        }
                    }
                }
            }

            // Websites
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.section_websites), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (password.websites.isEmpty()) {
                        Text(stringResource(R.string.websites_none))
                    } else {
                        for (w in password.websites) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // open link
                                    val intent = Intent(Intent.ACTION_VIEW, sanitizeUrl(w).toUri())
                                    context.startActivity(intent)
                                }
                                .padding(vertical = 6.dp)) {
                                Text(w, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconLink()
                            }
                        }
                    }
                }
            }
        }
    }
}

fun sanitizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
