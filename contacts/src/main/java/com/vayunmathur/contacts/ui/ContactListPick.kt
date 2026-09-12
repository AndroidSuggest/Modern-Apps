package com.vayunmathur.contacts.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExtendedFloatingActionButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.contacts.util.ContactSorting.groupKey
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListPick(
    mimeType: String?,
    contacts: List<Contact>,
    allowMultiple: Boolean = false,
    selectedUris: List<Uri> = emptyList(),
    onConfirm: () -> Unit = {},
    onClick: (Uri) -> Unit,
) {
    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }

    val groupedContacts = remember(otherContacts) {
        otherContacts
            .groupBy { groupKey(it.name.value) }
            .toSortedMap()
    }

    val selectedSet = selectedUris.toSet()

    LazyListScaffold(
        topBar = { TopAppBar({ Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (allowMultiple) {
                ExtendedFloatingActionButton(onClick = onConfirm) {
                    val label = stringResource(UiR.string.done) +
                        if (selectedUris.isNotEmpty()) " (${selectedUris.size})" else ""
                    Text(label)
                }
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (favorites.isNotEmpty()) {
            item(key = "pick-favorites-header") { FavoritesHeader() }
            item(key = "pick-favorites-card") {
                GroupedContactSection(count = favorites.size) { idx ->
                    ContactItemPick(favorites[idx], mimeType, selectedSet, onClick)
                }
            }
        }

        groupedContacts.forEach { (letter, contactsInGroup) ->
            item(key = "pick-letter-header-$letter") { LetterHeader(letter) }
            item(key = "pick-letter-card-$letter") {
                GroupedContactSection(count = contactsInGroup.size) { idx ->
                    ContactItemPick(contactsInGroup[idx], mimeType, selectedSet, onClick)
                }
            }
        }
    }
}
