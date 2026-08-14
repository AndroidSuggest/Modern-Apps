package com.vayunmathur.contacts.data

import kotlinx.serialization.Serializable

/** A single prefilled contact value (phone/email/postal) with optional type + custom label. */
@Serializable
data class PrefillValue(
    val value: String,
    val type: Int? = null,
    val label: String? = null,
)

/**
 * Contact fields extracted from an ACTION_INSERT / ACTION_INSERT_OR_EDIT / ACTION_EDIT intent.
 *
 * Covers both the [android.provider.ContactsContract.Intents.Insert] scalar extras and the
 * [android.provider.ContactsContract.Intents.Insert.DATA] `ArrayList<ContentValues>` mechanism,
 * so any field a caller (dialer, share sheet, assistant, etc.) attaches is carried through
 * navigation and applied to the edit draft.
 */
@Serializable
data class ContactPrefill(
    val name: String? = null,
    val company: String? = null,
    val notes: String? = null,
    val nickname: String? = null,
    val phones: List<PrefillValue> = emptyList(),
    val emails: List<PrefillValue> = emptyList(),
    val postals: List<PrefillValue> = emptyList(),
) {
    val primaryPhone: String? get() = phones.firstOrNull()?.value
    val primaryEmail: String? get() = emails.firstOrNull()?.value
}
