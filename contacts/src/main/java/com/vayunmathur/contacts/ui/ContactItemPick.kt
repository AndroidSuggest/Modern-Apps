package com.vayunmathur.contacts.ui

import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.runtime.Composable
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.library.ui.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, selectedUris: Set<Uri>, onClick: (Uri) -> Unit) {
    // SIM-backed synthetic contacts have negative ids and accountType == SIM_ACCOUNT_TYPE.
    // They have no provider rows, so any URI built from their ids would be bogus:
    // render the row but never emit a URI for it.
    val isSimBacked = isSimAccountType(contact.accountType)
    if (mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.id)
        ContactItem(
            contact = contact,
            isSelected = uri in selectedUris,
            showAccountLabels = true,
            onClick = {
                if (isSimBacked) return@ContactItem
                onClick(uri)
            }
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
            dropdownListClick = { index ->
                // SIM-backed rows carry Data id 0 (no provider row); never emit those URIs.
                if (!isSimBacked && relevantList[index].id != 0L) {
                    onClick(itemUris[index])
                }
            }
        )
    }
}
