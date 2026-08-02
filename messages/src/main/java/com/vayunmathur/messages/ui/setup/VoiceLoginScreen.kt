package com.vayunmathur.messages.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.gvoice.CookieParser
import com.vayunmathur.messages.gvoice.GVoiceClient
import com.vayunmathur.messages.gvoice.VoiceEndpoints
import kotlinx.coroutines.launch

/**
 * Cookie-paste sign-in for Google Voice.
 *
 * History (so future-me doesn't re-tread the wrong paths):
 *  - System WebView gets the "Couldn't sign you in, this browser may
 *    not be secure" block. We tried X-Requested-With stripping + UA
 *    spoofing + third-party cookies enablement; still blocked.
 *  - Embedded GeckoView (real Firefox engine) bypasses the block, but
 *    its public StorageController API has no `getCookies` method and
 *    the WebExtension background-script lifecycle is broken for
 *    extensions installed via `ensureBuiltIn` — we couldn't get our
 *    cookie-harvester extension's background.js to actually execute.
 *  - No on-device path for Voice sign-in without Google Play Services
 *    (AccountManager weblogin is GMS-gated).
 *
 * What works: the user signs in to voice.google.com in a browser they
 * already trust, extracts the Google session cookies, and pastes them
 * here. On mobile this means using a browser with cookie-inspection
 * tooling — Kiwi Browser + Cookie-Editor is the cleanest path; Firefox
 * Mobile's about:preferences is a fallback that doesn't require any
 * extension install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLoginScreen(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val state by GVoiceClient.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state is GVoiceClient.State.Connected) backStack.pop()
    }

    AppScaffold(
        title = stringResource(R.string.setup_voice_title),
        backStack = backStack,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // High-level explainer.
            Text(
                stringResource(R.string.setup_voice_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Cards for each viable sign-in path. Listed easiest-first.
            InstructionCard(
                title = "Mobile: Kiwi Browser + cookies.txt",
                steps = listOf(
                    "Install Kiwi Browser (sideload from kiwibrowser.com or grab from F-Droid).",
                    "Open Kiwi → ⋮ → Extensions → from store. Search “cookies.txt” (or “Cookie-Editor”) and install one of them.",
                    "Open voice.google.com in Kiwi and sign in normally.",
                    "Tap the extension icon → export. cookies.txt exports a Netscape-format file; Cookie-Editor → Export → Header String.",
                    "Long-press the field below and paste whichever you got.",
                ),
                primaryActionLabel = "Open voice.google.com",
                onPrimaryAction = { openInBrowser(context, "https://voice.google.com/signup") },
            )
            InstructionCard(
                title = "Mobile: Firefox Mobile (no extension)",
                steps = listOf(
                    "Open Firefox on your phone and sign in to voice.google.com.",
                    "In Firefox: address bar → about:preferences#privacy → Cookies and Site Data → Manage Data → search “google.com”.",
                    "Tap each of SID, HSID, SSID, APISID, SAPISID in turn. Copy the value and assemble a JSON object below.",
                ),
                primaryActionLabel = "Open voice.google.com",
                onPrimaryAction = { openInBrowser(context, "https://voice.google.com/signup") },
            )
            InstructionCard(
                title = "Desktop: any browser with devtools",
                steps = listOf(
                    "Sign in to voice.google.com in your desktop browser.",
                    "Open DevTools (⌘+Opt+I / F12) → Application → Cookies → https://voice.google.com.",
                    "Copy the values for SID, HSID, SSID, APISID, SAPISID and paste as JSON below.",
                ),
            )

            // The actual paste field — accepts JSON, cURL, or raw Cookie header.
            Text(stringResource(R.string.paste_cookies), fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                placeholder = { Text(stringResource(R.string.setup_voice_placeholder)) },
                shape = RoundedCornerShape(12.dp),
                enabled = !submitting,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = {
                    when {
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        state is GVoiceClient.State.Connecting -> Text(stringResource(R.string.validating_with_google))
                        else -> Text(stringResource(R.string.setup_voice_field_hint))
                    }
                },
                isError = error != null,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val parsed = CookieParser.parse(input)
                    if (parsed == null) {
                        error = "Couldn't find any Google cookies in that text. " +
                            "Try a JSON object, a cURL command, or a raw Cookie header."
                        return@Button
                    }
                    val missing = CookieParser.missingRequired(parsed)
                    if (missing.isNotEmpty()) {
                        error = "Missing required cookies: " + missing.joinToString(", ") +
                            ". (Required set: " + VoiceEndpoints.RequiredCookies.joinToString(", ") + ".)"
                        return@Button
                    }
                    submitting = true
                    error = null
                    coroutineScope.launch {
                        val errMsg = GVoiceClient.submitCookies(parsed)
                        submitting = false
                        if (errMsg != null) error = errMsg
                        // On success the LaunchedEffect above pops us.
                    }
                },
                enabled = input.isNotBlank() && !submitting,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.setup_voice_sign_in), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun InstructionCard(
    title: String,
    steps: List<String>,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            steps.forEachIndexed { idx, step ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    Text(
                        "${idx + 1}.",
                        modifier = Modifier.padding(end = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryActionLabel)
                }
            }
        }
    }
}

/** Launch [url] in whatever browser the user has set as default.
 *  No reliance on a specific package, no Google-services dependencies. */
private fun openInBrowser(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
