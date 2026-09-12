package com.vayunmathur.contacts.ui

import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.runtime.Composable
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.library.ui.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, selectedUris: Set<Uri>, onClick: (Uri) -> Unit) {
    if (mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        val uri = Uri.withAppendedPath(ContactsContract.RawContacts.CONTENT_URI, contact.id.toString())
        ContactItem(
            contact = contact,
            isSelected = uri in selectedUris,
            showAccountLabels = true,
            onClick = { onClick(uri) }
        )
    } else {
        val details = contact.details
        val (relevantList, baseURI) = when(mimeType) {
            CDKEmail.CONTENT_ITEM_TYPE -> details.emails to CDKEmail.CONTENT_URI
            CDKPhone.CONTENT_ITEM_TYPE -> details.phoneNumbers to CDKPhone.CONTENT_URI
            CDKStructuredPostal.CONTENT_ITEM_TYPE -> details.addresses to CDKStructuredPostal.CONTENT_URI
            else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
        val itemUris = relevantList.map { Uri.withAppendedPath(baseURI, it.id.toString()) }
        ContactItem(
            contact = contact,
            isSelected = itemUris.any { it in selectedUris },
            showAccountLabels = true,
            onClick = {  },
            dropdownList = relevantList.map { it.value },
            dropdownListClick = { index -> onClick(itemUris[index]) }
        )
    }
}
