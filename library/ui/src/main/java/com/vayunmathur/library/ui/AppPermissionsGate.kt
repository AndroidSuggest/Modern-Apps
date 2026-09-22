package com.vayunmathur.library.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.R as LibraryUiR

/**
 * The single consolidated initial-permission gate.
 *
 * Takes the app's navigation [content] as a parameter, performs all permission
 * checks itself, and shows either one permission screen or [content]. All
 * requirements are blocking: until every [PermissionRequirement] in
 * [spec.requirements] is granted, [content] is not composed.
 *
 * Behaviour:
 * - Synchronous requirements (runtime permissions, [SpecialAccess] entries,
 *   manage-media) are checked on launch and on every ON_RESUME (covers grants
 *   made in system Settings, which produce no result callback).
 * - [PermissionRequirement.HealthConnect] is probed async via
 *   `getGrantedPermissions`; until the first probe completes a loading state
 *   is shown rather than flashing the wall.
 * - Only the first missing [PermissionRequirement.Runtime] batch auto-launches
 *   (one `LaunchedEffect`, guarded against loops). Special-access intents and
 *   the Health Connect contract fire only on row tap — the OS requires a
 *   gesture for those.
 * - Permanent denial recovers via the shared
 *   [rememberMultiplePermissionRequest] path, which opens app settings when a
 *   re-request would silently do nothing.
 */
@Composable
fun AppPermissionsGate(
    spec: AppPermissionsSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var resumeEpoch by remember { mutableIntStateOf(0) }
    var runtimeEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val healthRequirements = remember(spec) {
        spec.requirements.filterIsInstance<PermissionRequirement.HealthConnect>()
    }
    var healthProbed by remember(spec) { mutableStateOf(healthRequirements.isEmpty()) }
    LaunchedEffect(spec, resumeEpoch) {
        if (healthRequirements.isNotEmpty()) {
            healthProbed = try {
                val client = HealthConnectClient.getOrCreate(context)
                HealthConnectGrantCache.granted =
                    client.permissionController.getGrantedPermissions()
                true
            } catch (_: Exception) {
                // Health Connect missing/broken: keep the last known grants (usually
                // null, i.e. not granted) so the wall explains the state instead of
                // crashing or silently passing.
                true
            }
        }
    }

    // Snapshot so a grant flapping mid-frame cannot show content and wall together.
    val missing = remember(spec, resumeEpoch, runtimeEpoch, healthProbed) {
        if (!healthProbed) emptyList() else spec.requirements.filterNot { it.isGranted(context) }
    }
    if (!healthProbed) {
        SetupScaffold(
            title = spec.title,
            modifier = modifier,
            subtitle = spec.subtitle,
            icon = spec.icon,
            scrollBehavior = appBarScrollBehavior(),
        ) {
            LoadingIndicator(Modifier.padding(top = 32.dp))
        }
        return
    }
    if (missing.isEmpty()) {
        content()
        return
    }

    GateBody(spec = spec, missing = missing, onRuntimeResult = { runtimeEpoch++ })
}

@Composable
private fun GateBody(
    spec: AppPermissionsSpec,
    missing: List<PermissionRequirement>,
    onRuntimeResult: () -> Unit,
) {
    val context = LocalContext.current
    var autoLaunched by remember(spec) { mutableStateOf(false) }
    val firstRuntime = missing.filterIsInstance<PermissionRequirement.Runtime>().firstOrNull()
    // Hoisted out of RequirementRow: hooks cannot run conditionally, so the
    // auto-launch requester lives at this level while per-row requesters stay
    // in their rows (which always compose once the gate is showing).
    val autoRequest = rememberMultiplePermissionRequest(
        firstRuntime?.permissions ?: emptyArray()
    ) { onRuntimeResult() }
    LaunchedEffect(spec, firstRuntime) {
        if (firstRuntime != null && !autoLaunched &&
            firstRuntime.permissions.isNotEmpty()
        ) {
            autoLaunched = true
            autoRequest()
        }
    }

    SetupScaffold(
        title = spec.title,
        subtitle = spec.subtitle,
        icon = spec.icon,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            missing.forEach { requirement ->
                RequirementRow(
                    requirement = requirement,
                    onRuntimeResult = onRuntimeResult,
                )
            }
            if (missing.any { it is PermissionRequirement.Runtime }) {
                TextButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(LibraryUiR.string.permission_open_settings))
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(
    requirement: PermissionRequirement,
    onRuntimeResult: () -> Unit,
) {
    val context = LocalContext.current
    when (requirement) {
        is PermissionRequirement.Runtime -> {
            // Empty batch (e.g. notifications() pre-Tiramisu) always reports
            // granted, so it never reaches here as missing; no empty guard needed.
            val request = rememberMultiplePermissionRequest(requirement.permissions) {
                onRuntimeResult()
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    PermissionWall(
                        title = stringResource(LibraryUiR.string.permission_grant_required_title),
                        actionLabel = stringResource(LibraryUiR.string.permission_grant),
                        onRequest = request,
                        rationale = stringResource(LibraryUiR.string.permission_runtime_rationale),
                    )
                }
            }
        }
        PermissionRequirement.ExactAlarms -> {
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_exact_alarms_rationale,
                onRequest = { SpecialAccess.requestExactAlarms(context) },
            )
        }
        PermissionRequirement.AllFiles -> {
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_all_files_rationale,
                onRequest = { SpecialAccess.requestAllFilesAccess(context) },
            )
        }
        PermissionRequirement.NotificationListener -> {
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_notification_listener_rationale,
                onRequest = { SpecialAccess.requestNotificationListener(context) },
            )
        }
        PermissionRequirement.Vpn -> {
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_vpn_rationale,
                onRequest = { SpecialAccess.requestVpnConsent(context) },
            )
        }
        PermissionRequirement.FullScreenIntent -> {
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_full_screen_intent_rationale,
                onRequest = { SpecialAccess.requestFullScreenIntent(context) },
            )
        }
        PermissionRequirement.ManageMedia -> {
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_manage_media_rationale,
                onRequest = { SpecialAccess.requestManageMedia(context) },
            )
        }
        is PermissionRequirement.HealthConnect -> {
            val launcher = rememberLauncherForActivityResult(
                contract = PermissionController.createRequestPermissionResultContract(),
            ) { granted ->
                if (granted.containsAll(requirement.permissions)) {
                    HealthConnectGrantCache.granted = granted
                }
                // Refusals surface on the next resume probe; nothing cached here.
            }
            GateActionCard(
                rationaleRes = LibraryUiR.string.permission_health_connect_rationale,
                onRequest = { launcher.launch(requirement.permissions) },
            )
        }
    }
}

@Composable
private fun GateActionCard(rationaleRes: Int, onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            PermissionWall(
                title = stringResource(LibraryUiR.string.permission_grant_required_title),
                actionLabel = stringResource(LibraryUiR.string.permission_grant),
                onRequest = onRequest,
                rationale = stringResource(rationaleRes),
            )
        }
    }
}
