package com.vayunmathur.contacts.util

import android.app.Application
import com.vayunmathur.library.log.Log
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType

/**
 * SIM-backed save path and SIM account labels, kept out of ContactViewModel
 * so the class stays under the function cap (see TooManyFunctions).
 * Called as viewModel.*.
 */
internal suspend fun ContactViewModel.persistSimContact(
    contact: com.vayunmathur.contacts.data.Contact
): Boolean {
    val subId = contact.accountName?.toIntOrNull()
    val name = simPersistName(contact)
    val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
    val email = contact.details.emails.firstOrNull()?.address?.trim()?.takeIf { it.isNotEmpty() }
    if (name.isBlank() && number.isBlank()) {
        Log.status("ContactViewModel", "SIM save skipped: name and number empty")
        return false
    }
    if (contact.id < 0) {
        updateExistingSimContact(contact, name, number, email, subId)?.let { return it }
    }
    val ok = SimContactsDataSource.insertSimContact(getApplication(), name, number, email, subId)
    if (!ok) Log.error("ContactViewModel", "Failed to insert SIM contact")
    syncFromSystem()
    return ok
}

internal fun ContactViewModel.simPersistName(
    contact: com.vayunmathur.contacts.data.Contact
): String =
    contact.name.value.trim().ifEmpty {
        contact.nickname.nickname.trim().ifEmpty {
            contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
        }
    }

/**
 * Updates the existing SIM row in place (or relocates it). Returns null when there is
 * no existing row and the caller should fall through to insert.
 */
internal suspend fun ContactViewModel.updateExistingSimContact(
    contact: com.vayunmathur.contacts.data.Contact,
    name: String,
    number: String,
    email: String?,
    subId: Int?,
): Boolean? {
    val app = getApplication<Application>()
    val oldSc = SimContactsDataSource.listSimContacts(app)
        .firstOrNull { SimContactsDataSource.syntheticIdFor(it) == contact.id }
        ?: SimContactsDataSource.findBackingSimContact(app, contact)
        ?: return null
    // Skip if no actual change
    val isUnchanged = oldSc.name == name &&
        oldSc.number == number &&
        oldSc.emails == email &&
        oldSc.subscriptionId == subId
    if (isUnchanged) return true
    // Edit in place where possible. Moving a contact to a different SIM is not an
    // in-place edit, so that still falls through to delete-then-insert.
    val canEditInPlace = subId == null || subId == oldSc.subscriptionId
    if (canEditInPlace) {
        val updated = SimContactsDataSource.updateSimContact(app, oldSc, name, number, email, subId)
        if (updated) {
            syncFromSystem()
            return true
        }
    }
    SimContactsDataSource.deleteSimContact(app, oldSc)
    return null
}

internal suspend fun ContactViewModel.persistProviderContact(
    contact: com.vayunmathur.contacts.data.Contact
): Boolean {
    val contactId = contact.id
    val details = contact.details
    val oldDetails = contacts.value.find { it.id == contactId }?.details
        ?: allContactsState.value.find { it.id == contactId }?.details
        ?: com.vayunmathur.contacts.data.ContactDetails.empty()
    return contact.save(getApplication(), details, oldDetails)
}

fun ContactViewModel.simDisplayLabel(account: ContactAccount): String? =
    simAccountLabelsState.value["${account.type}|${account.name}"]

fun ContactViewModel.simDisplayLabelFor(type: String?, name: String?): String? =
    simAccountLabelsState.value["${type ?: ""}|${name ?: ""}"]

internal fun ContactViewModel.simSlotLabelsFor(
    infos: List<SimContactsDataSource.SimSubscriptionInfo>
): Map<String, String> {
    val app = getApplication<Application>()
    return infos.associate { info ->
        "$SIM_ACCOUNT_TYPE|${SimContactsDataSource.accountNameFor(info)}" to
            app.getString(R.string.sim_slot_label, SimContactsDataSource.slotNumberFor(info))
    }
}
