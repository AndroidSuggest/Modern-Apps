package com.vayunmathur.communicate.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import java.util.Date

@Composable
fun PermissionGate(
    permission: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val context = LocalContext.current
    var grantRevision by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { grantRevision++ }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        content(grantRevision)
    } else {
        EmptyState(
            title = stringResource(R.string.permission_title),
            message = message,
            icon = { IconCall() },
            action = {
                Button(onClick = { launcher.launch(permission) }) {
                    Text(stringResource(R.string.grant_permission))
                }
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
fun RoleGate(
    roleName: String,
    message: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    content: @Composable (Int) -> Unit,
) {
    val context = LocalContext.current
    val roleManager = remember(context) { context.getSystemService(RoleManager::class.java)!! }
    var roleRevision by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { roleRevision++ }
    val available = roleManager.isRoleAvailable(roleName)
    val held = roleManager.isRoleHeld(roleName)

    if (held) {
        content(roleRevision)
    } else {
        EmptyState(
            title = stringResource(R.string.default_app_title),
            message = if (available) message else stringResource(R.string.default_role_unavailable),
            icon = icon,
            action = if (available) {
                {
                    Button(onClick = { launcher.launch(roleManager.createRequestRoleIntent(roleName)) }) {
                        Text(actionLabel)
                    }
                }
            } else {
                null
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
fun DefaultSmsGate(
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    RoleGate(
        roleName = RoleManager.ROLE_SMS,
        message = stringResource(R.string.default_sms_message),
        actionLabel = stringResource(R.string.become_default_sms),
        modifier = modifier,
        icon = { IconSms() },
        content = content,
    )
}

@Composable
fun DefaultDialerGate(
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    RoleGate(
        roleName = RoleManager.ROLE_DIALER,
        message = stringResource(R.string.default_dialer_message),
        actionLabel = stringResource(R.string.become_default_dialer),
        modifier = modifier,
        icon = { IconCall() },
        content = content,
    )
}

fun Context.hasCommunicatePermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasContactsPermission(): Boolean = hasCommunicatePermission(Manifest.permission.READ_CONTACTS)

fun formatDateTime(context: Context, timestampMillis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(timestampMillis) -> DateFormat.getTimeFormat(context).format(Date(timestampMillis))
        now - timestampMillis < DateUtils.WEEK_IN_MILLIS -> DateUtils.formatDateTime(
            context,
            timestampMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
        else -> DateFormat.getMediumDateFormat(context).format(Date(timestampMillis))
    }
}

fun initialsFor(text: String): String = text
    .split(' ', '+', '-', '_')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifBlank { "?" }

/**
 * Small pill that tags a row with the line (SIM vs Google Voice) it belongs to, so the
 * merged inbox / call log stays legible. Only Google Voice is badged; SIM is the implicit
 * default and left unbadged to avoid noise.
 */
@Composable
fun LineBadge(line: CommunicateLine, modifier: Modifier = Modifier) {
    if (line != CommunicateLine.GoogleVoice) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.line_gv),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
