package com.vayunmathur.contacts.util

import android.graphics.Bitmap
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactGroup

/**
 * The UI contract between [ContactViewModel] and the three screens the store listing is
 * shot from (contact list, contact details, groups).
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — which is what the listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements [ContactsActions].
 *
 * There is one actions interface rather than one per screen because the screens genuinely
 * share callbacks (the bottom nav, opening a contact, decoding a photo); splitting it would
 * mean the same defaulted member declared in several interfaces, which the ViewModel would
 * then have to disambiguate by hand.
 */

/** Bottom-nav destination. */
enum class ContactsTab { Contacts, Groups, Settings }

/** A group together with its members, so a row can show its count without its own flow. */
data class GroupWithContacts(val group: ContactGroup, val contacts: List<Contact>)

/** Everything the contact list draws. */
data class ContactListUiState(
    val contacts: List<Contact> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    val searchQuery: String = "",
    val showAccountLabels: Boolean = true,
    /** Contact open in the detail pane, highlighted in the list on a wide screen. */
    val openContactId: Long? = null,
    val showAddButton: Boolean = true,
)

/** Everything the contact details page draws. */
data class ContactDetailsUiState(
    val contact: Contact,
    val groups: List<ContactGroup> = emptyList(),
    /** Messaging apps that have a row for this contact, resolved from the package manager. */
    val platforms: ContactPlatforms = ContactPlatforms(),
    val isGoogleMeetInstalled: Boolean = false,
)

/** Everything the groups page draws. */
data class GroupsUiState(
    val groups: List<GroupWithContacts> = emptyList(),
)

/**
 * Contact screen callbacks. Every method has a no-op default so a preview can render a
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 * The navigating and `Context`-dependent ones are supplied by each screen's binder; the
 * rest are implemented by [ContactViewModel] directly.
 */
interface ContactsActions {
    fun setSearchQuery(query: String) {}
    fun deleteContact(contact: Contact) {}
    fun saveContact(contact: Contact) {}
    fun addGroup(name: String) {}
    fun deleteGroup(groupId: Long) {}

    /** Decodes a Base64 contact photo, through the ViewModel's cache. */
    fun decodePhoto(base64: String): Bitmap? = null

    fun openContact(contact: Contact) {}
    fun addContact() {}
    fun editContact(contactId: Long) {}

    /** Asks for confirmation before deleting — the plain [deleteContact] does not. */
    fun confirmDeleteContact(contact: Contact) {}
    fun closeContact() {}
    fun addToGroup(contactIds: List<Long>) {}

    /** Exports [contacts] to a VCF named [filename] and opens a share chooser. */
    fun shareContacts(contacts: List<Contact>, filename: String) {}
    fun selectTab(tab: ContactsTab) {}

    companion object {
        val Noop: ContactsActions = object : ContactsActions {}
    }
}
