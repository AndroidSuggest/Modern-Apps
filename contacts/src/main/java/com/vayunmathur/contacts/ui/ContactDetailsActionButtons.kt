package com.vayunmathur.contacts.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Number-first picker shown by the Call/Message/Video buttons for multi-number contacts. */
@Composable
internal fun NumberPickerDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    phoneNumbers: List<PhoneNumber>,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        phoneNumbers.forEach { phone ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(formatPhoneNumber(phone.number))
                        Text(
                            phone.typeString(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { onPick(phone.number) }
            )
        }
    }
}

internal fun launchSingleVideoOption(
    context: Context,
    number: String,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean,
) {
    when {
        isGoogleMeetInstalled -> launchGoogleMeet(context, number)
        platforms.whatsAppVideoId != null -> placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = platforms.whatsAppVideoId)
        platforms.signalVideoId != null -> placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true, fallbackDataRowId = platforms.signalVideoId)
        platforms.telegramVideoId != null -> placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true, fallbackDataRowId = platforms.telegramVideoId)
    }
}

@Composable
fun ActionButton(
    icon: @Composable () -> Unit,
    label: String,
    dropdownContent: (@Composable () -> Unit)? = null,
    action: () -> Unit
) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BadgedBox(
            badge = {}
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { action() },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    icon()
                }
                dropdownContent?.invoke()
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
