package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.Text
import com.vayunmathur.communicate.R
import java.util.Date

@Composable
fun PermissionGate(
    permission: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
