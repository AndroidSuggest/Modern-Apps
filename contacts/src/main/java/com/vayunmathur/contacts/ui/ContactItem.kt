package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.pressedShape
import com.vayunmathur.library.util.sharedContent
import com.vayunmathur.library.util.sharedText

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactItem(
    contact: Contact,
    isSelected: Boolean,
    showAccountLabels: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accountLabels: Map<String, String> = emptyMap(),
    allGroups: List<ContactGroup> = emptyList(),
    decodePhoto: ((String) -> Bitmap?)? = null,
    onLongClick: (() -> Unit)? = null,
    dropdownList: List<String>? = null,
    dropdownListClick: (Int) -> Unit = {},
    embeddedInCard: Boolean = false,
    /**
     * Non-null makes this row the origin of the container transform into the contact's detail page.
     * Null for a row that is a second copy of a contact already shown elsewhere on screen - the
     * morph cannot choose between two origins for one destination.
     */
    sharedKey: Any? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val avatarShape = pressedShape(pressed)

    val combinedModifier = if (dropdownList == null) {
        modifier.combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        modifier
    }

    // Remembered per (contact, group list): this filters every group and trims each name, and
    // without the remember it re-ran on every composition of every visible row while scrolling.
    val contactGroups = remember(contact, allGroups) { contactGroupsOf(contact, allGroups) }

    val trimmedOrg = contact.org.company.trim()
    val showOrg = trimmedOrg.isNotEmpty()
    val showGroups = contactGroups.isNotEmpty()

    val content = @Composable {
        val hasDropdown = !dropdownList.isNullOrEmpty()

        key(showOrg, showGroups, contactGroups.size) {
            val itemModifier = if (embeddedInCard) {
                combinedModifier
            } else {
                val r = if (hasDropdown) 0.dp else 16.dp
                combinedModifier.clip(RoundedCornerShape(16.dp, 16.dp, r, r))
            }
            val rowContainerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                embeddedInCard -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Row(
                modifier = itemModifier
                    .fillMaxWidth()
                    .background(rowContainerColor)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContactAvatar(
                    contact,
                    decodePhoto,
                    Modifier
                        .size(50.dp)
                        .then(
                            if (sharedKey == null) Modifier
                            else Modifier.sharedContent("contact-avatar-$sharedKey")
                        ),
                    shape = avatarShape,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Same pieces, same order and same shared keys as the detail header, so every part
                    // has a counterpart to travel to. Only the type scale differs, and sharedText
                    // scales rather than reflows so that difference does not garble the text in flight.
                    val name = contact.name
                    val parts = listOfNotNull(
                        name.namePrefix.takeIf { it.isNotBlank() }?.let { "nameprefix" to it },
                        name.firstName.takeIf { it.isNotBlank() }?.let { "firstname" to it },
                        name.middleName.takeIf { it.isNotBlank() }?.let { "middlename" to it },
                        name.lastName.takeIf { it.isNotBlank() }?.let { "lastname" to it },
                        name.nameSuffix.takeIf { it.isNotBlank() }?.let { "namesuffix" to it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        parts.forEach { (slot, part) ->
                            Text(
                                text = part,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (sharedKey == null) Modifier
                                else Modifier.sharedText("contact-$slot-$sharedKey"),
                            )
                        }
                    }
                    if (contact.nickname.nickname.isNotBlank()) {
                        Text(
                            text = contact.nickname.nickname,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (sharedKey == null) Modifier
                            else Modifier.sharedText("contact-nickname-$sharedKey"),
                        )
                    }
                    if (showOrg) {
                        Text(
                            text = trimmedOrg,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (sharedKey == null) Modifier
                            else Modifier.sharedText("contact-company-$sharedKey"),
                        )
                    }
                    if (showGroups) {
                        Text(
                            text = contactGroups.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (showAccountLabels) {
                    Spacer(Modifier.width(16.dp))
                    val onDevice = stringResource(R.string.on_device)
                    Text(
                        text = accountLabels["${contact.accountType}|${contact.accountName}"]
                            ?: contact.accountName ?: onDevice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }
            }
        }
    }

    if (dropdownList != null) {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
            dropdownList.forEachIndexed { idx, it ->
                Spacer(Modifier.height(4.dp))
                ListItem(
                    content = {
                        Text(text = it)
                    },
                    modifier = Modifier.clickable {
                        dropdownListClick(idx)
                    }.clip(RoundedCornerShape(0.dp, 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp)),
                    colors = ListItemDefaults.colors(containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }))
            }
        }
    } else {
        content()
    }
}
