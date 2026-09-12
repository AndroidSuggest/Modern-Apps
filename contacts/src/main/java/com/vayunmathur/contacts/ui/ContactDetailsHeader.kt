package com.vayunmathur.contacts.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.IconVideoCamera
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedContent
import com.vayunmathur.library.util.sharedText

@Composable
fun ProfileHeader(contact: Contact, decodePhoto: ((String) -> android.graphics.Bitmap?)? = null) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        // No whole-block shared key. The avatar, each name part and the company pair up individually
        // with their counterparts in the list row and in the editor, and a container morph on top of
        // those would drag the same content to a second destination at the same time.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        ContactAvatar(
            contact = contact,
            decodePhoto = decodePhoto,
            modifier = Modifier
                .size(100.dp)
                .sharedContent("contact-avatar-${contact.id}"),
            initialsStyle = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.size(16.dp))

        // Composed from the structured name rather than the joined display name, so that each part can
        // travel to the field that owns it when this page morphs into the editor. A single Text could
        // only ever land in one of them.
        val name = contact.name
        val parts = listOfNotNull(
            name.namePrefix.takeIf { it.isNotBlank() }?.let { "nameprefix" to it },
            name.firstName.takeIf { it.isNotBlank() }?.let { "firstname" to it },
            name.middleName.takeIf { it.isNotBlank() }?.let { "middlename" to it },
            name.lastName.takeIf { it.isNotBlank() }?.let { "lastname" to it },
            name.nameSuffix.takeIf { it.isNotBlank() }?.let { "namesuffix" to it },
        )
        FlowRow(
            // Full width so the arrangement always has room to centre within. Without it the row
            // shrinks to its content and relies on the parent Column to centre it, which silently
            // stops working once a name is wide enough to fill the width - the point at which each
            // wrapped line needs centring most.
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                6.dp,
                androidx.compose.ui.Alignment.CenterHorizontally,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (slot, text) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.sharedText("contact-$slot-${contact.id}"),
                )
            }
        }
        if (contact.nickname.nickname.isNotBlank()) {
            Text(
                text = contact.nickname.nickname,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.sharedText("contact-nickname-${contact.id}"),
            )
        }
        Text(
            text = contact.org.company,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.sharedText("contact-company-${contact.id}")
        )
    }
}

@Composable
fun ActionButtonsRow(
    number: String?,
    email: String?,
    platforms: ContactPlatforms,
    isGoogleMeetInstalled: Boolean
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        if (number != null) {
            var showCallDropdown by remember { mutableStateOf(false) }
            var showSmsDropdown by remember { mutableStateOf(false) }
            var showVideoDropdown by remember { mutableStateOf(false) }

            ActionButton(
                icon = { IconCall() },
                label = stringResource(R.string.action_call),
                action = {
                    if (platforms.hasAnyPlatform) {
                        showCallDropdown = true
                    } else {
                        placeCall(context, number)
                    }
                },
                dropdownContent = {
                    DropdownMenu(expanded = showCallDropdown, onDismissRequest = { showCallDropdown = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_default)) },
                            onClick = {
                                placeCall(context, number)
                                showCallDropdown = false
                            }
                        )
                        platforms.signalCallId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.signal)) },
                                onClick = { placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, fallbackDataRowId = id); showCallDropdown = false }
                            )
                        }
                        platforms.whatsAppCallId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.whatsapp)) },
                                onClick = { placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, fallbackDataRowId = id); showCallDropdown = false }
                            )
                        }
                        platforms.telegramCallId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.telegram)) },
                                onClick = { placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, fallbackDataRowId = id); showCallDropdown = false }
                            )
                        }
                    }
                }
            )
            ActionButton(
                icon = { IconSms() },
                label = stringResource(R.string.action_message),
                action = {
                    if (platforms.hasAnyPlatform) {
                        showSmsDropdown = true
                    } else {
                        ExternalIntents.sendSms(context, number)
                    }
                },
                dropdownContent = {
                    DropdownMenu(expanded = showSmsDropdown, onDismissRequest = { showSmsDropdown = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_default)) },
                            onClick = {
                                ExternalIntents.sendSms(context, number)
                                showSmsDropdown = false
                            }
                        )
                        platforms.signalMessageId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.signal)) },
                                onClick = { launchPlatformAction(context, id); showSmsDropdown = false }
                            )
                        }
                        platforms.whatsAppMessageId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.whatsapp)) },
                                onClick = { launchPlatformAction(context, id); showSmsDropdown = false }
                            )
                        }
                        platforms.telegramMessageId?.let { id ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.telegram)) },
                                onClick = { launchPlatformAction(context, id); showSmsDropdown = false }
                            )
                        }
                    }
                }
            )

            val hasVideoOptions = platforms.whatsAppVideoId != null ||
                    platforms.signalVideoId != null ||
                    platforms.telegramVideoId != null ||
                    isGoogleMeetInstalled
            if (hasVideoOptions) {
                val videoOptionCount = listOf(
                    isGoogleMeetInstalled,
                    platforms.whatsAppVideoId != null,
                    platforms.signalVideoId != null,
                    platforms.telegramVideoId != null
                ).count { it }

                ActionButton(
                    icon = { IconVideoCamera() },
                    label = stringResource(R.string.action_video),
                    action = {
                        if (videoOptionCount == 1) {
                            when {
                                isGoogleMeetInstalled -> launchGoogleMeet(context, number)
                                platforms.whatsAppVideoId != null -> placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = platforms.whatsAppVideoId)
                                platforms.signalVideoId != null -> placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true, fallbackDataRowId = platforms.signalVideoId)
                                platforms.telegramVideoId != null -> placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true, fallbackDataRowId = platforms.telegramVideoId)
                            }
                        } else {
                            showVideoDropdown = true
                        }
                    },
                    dropdownContent = {
                        DropdownMenu(expanded = showVideoDropdown, onDismissRequest = { showVideoDropdown = false }) {
                            if (isGoogleMeetInstalled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.google_meet)) },
                                    onClick = { launchGoogleMeet(context, number); showVideoDropdown = false }
                                )
                            }
                            platforms.whatsAppVideoId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.whatsapp)) },
                                    onClick = { placePlatformCall(context, number, PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = id); showVideoDropdown = false }
                                )
                            }
                            platforms.signalVideoId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.signal)) },
                                    onClick = { placePlatformCall(context, number, PackageUtils.SIGNAL_PACKAGE, isVideo = true, fallbackDataRowId = id); showVideoDropdown = false }
                                )
                            }
                            platforms.telegramVideoId?.let { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.telegram)) },
                                    onClick = { placePlatformCall(context, number, PackageUtils.TELEGRAM_PACKAGE, isVideo = true, fallbackDataRowId = id); showVideoDropdown = false }
                                )
                            }
                        }
                    }
                )
            }
        }
        if (email != null) {
            ActionButton(icon = { IconMail() }, label = stringResource(R.string.email)) {
                val intent = Intent(Intent.ACTION_SENDTO)
                intent.data = "mailto:$email".toUri()
                ExternalIntents.launch(context, intent)
            }
        }
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
