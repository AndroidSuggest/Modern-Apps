package com.vayunmathur.emergency.platform

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.vayunmathur.emergency.domain.formatPostalFallback

private const val TAG = "OwnerIdentityReader"

private typealias CDKPostal = ContactsContract.CommonDataKinds.StructuredPostal

/** The owner's picked identity: display name plus any postal addresses. */
data class OwnerIdentity(
    val name: String,
    val addresses: List<String> = emptyList(),
)

/**
 * Reads the owner's identity from a whole-contact pick result.
 *
 * Our contacts app answers a contact pick with a per-raw-contact URI
 * (`RawContacts.CONTENT_URI/<rawId>`, see `ContactItemPick`); aggregate contact
 * and lookup URIs are resolved defensively to the same shape. The name comes
 * from `DISPLAY_NAME_PRIMARY` and addresses from the contact's `StructuredPostal`
 * rows (`FORMATTED_ADDRESS`, falling back to the joined components exactly like
 * the contacts app's `getDetailsInternal`). Needs `READ_CONTACTS`; without it
 * every read returns null and the UI must prompt.
 */
class OwnerIdentityReader(private val context: Context) {

    /** Reads the identity, or null when the URI does not resolve or access is denied. */
    fun read(contactUri: Uri): OwnerIdentity? {
        val rawId = rawContactId(contactUri) ?: return null
        val name = readDisplayName(rawId) ?: return null
        if (name.isBlank()) return null
        return OwnerIdentity(name, readAddresses(rawId))
    }

    private fun rawContactId(contactUri: Uri): Long? {
        val segments = contactUri.pathSegments
        // Raw-contact URI from our picker: content://.../raw_contacts/<rawId>.
        if (segments.size >= 2 && segments[0] == ContactsContract.RawContacts.CONTENT_URI.lastPathSegment) {
            return contactUri.lastPathSegment?.toLongOrNull()
        }
        // Aggregate contact URI: content://.../contacts/<id> or .../contacts/lookup/<key>/<id>.
        val contactId = aggregateContactId(contactUri) ?: return null
        return firstRawContactId(contactId)
    }

    private fun aggregateContactId(contactUri: Uri): Long? {
        if (contactUri.path?.contains("/lookup/") == true) {
            return runCatching {
                ContactsContract.Contacts.lookupContact(context.contentResolver, contactUri)
                    ?.let { ContentUris.parseId(it) }
            }.getOrNull()
        }
        return contactUri.lastPathSegment?.toLongOrNull()
    }

    private fun firstRawContactId(contactId: Long): Long? {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(contactId.toString()),
                "${ContactsContract.RawContacts.STARRED} DESC, ${ContactsContract.RawContacts._ID} ASC",
            )
        }.getOrElse {
            Log.w(TAG, "unable to resolve raw contact for $contactId", it)
            return null
        } ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getLong(0)
        }
    }

    private fun readDisplayName(rawId: Long): String? {
        val uri = ContentUris.withAppendedId(
            ContactsContract.RawContacts.CONTENT_URI, rawId,
        )
        val cursor = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
                null, null, null,
            )
        }.getOrElse {
            Log.w(TAG, "unable to read display name for raw contact $rawId", it)
            return null
        } ?: return null
        cursor.use {
            if (!it.moveToNext()) return null
            return it.getString(0).orEmpty()
        }
    }

    private fun readAddresses(rawId: Long): List<String> {
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    CDKPostal.FORMATTED_ADDRESS,
                    CDKPostal.STREET,
                    CDKPostal.CITY,
                    CDKPostal.REGION,
                    CDKPostal.POSTCODE,
                    CDKPostal.COUNTRY,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawId.toString(), CDKPostal.CONTENT_ITEM_TYPE),
                null,
            )
        }.getOrElse {
            Log.w(TAG, "unable to read addresses for raw contact $rawId", it)
            return emptyList()
        } ?: return emptyList()
        cursor.use {
            val addresses = mutableListOf<String>()
            while (it.moveToNext()) {
                val formatted = it.getString(0)
                val address = if (!formatted.isNullOrBlank()) {
                    formatted
                } else {
                    formatPostalFallback(
                        street = it.getString(1),
                        city = it.getString(2),
                        region = it.getString(3),
                        postcode = it.getString(4),
                        country = it.getString(5),
                    )
                }
                if (address.isNotBlank()) addresses += address
            }
            return addresses.distinct()
        }
    }
}
