package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.util.ContactListUiState
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.library.util.NavBackStack

/** Binds [ContactViewModel] to the stateless [ContactListScreen]. */
@Composable
fun ContactList(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadContacts()
        viewModel.loadAccounts()
    }

    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val showAccountLabels by viewModel.showAccountLabels.collectAsStateWithLifecycle()
    val simSlotLabels by viewModel.simSlotLabels.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hasLoadedContacts by viewModel.hasLoadedContacts.collectAsStateWithLifecycle()

    val last = backStack.last()

    ContactListScreen(
        state = ContactListUiState(
            contacts = contacts,
            groups = groups,
            searchQuery = searchQuery,
            showAccountLabels = showAccountLabels,
            accountLabels = simSlotLabels,
            openContactId = when (last) {
                is Route.ContactDetail -> last.contactId
                is Route.EditContact -> last.contactId
                else -> null
            },
            showAddButton = last !is Route.EditContact,
            isLoading = !hasLoadedContacts,
        ),
        actions = object : ContactsActions by viewModel {
            override fun openContact(contact: Contact) = onContactClick(contact)

            override fun addContact() = onAddContactClick()

            override fun addToGroup(contactIds: List<Long>) {
                backStack.add(Route.AddToGroupDialog(contactIds))
            }

            override fun shareContacts(contacts: List<Contact>, filename: String) {
                shareContactsAsVcf(scope, context, contacts, filename, resources.getString(R.string.share_contact))
            }
        },
    )
}
