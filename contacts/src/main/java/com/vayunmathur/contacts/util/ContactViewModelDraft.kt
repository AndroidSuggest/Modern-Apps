@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.vayunmathur.contacts.util

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

fun ContactViewModel.initEditDraft(
    contactId: Long?,
    prefill: ContactPrefill? = null,
) {
    if (editingInitialized && editingContactId == contactId && _editDraft.value != null) return
    val contact = contactId?.let {
        getContact(it) ?: Contact.getContact(getApplication(), it) ?: _allContacts.value.find { c -> c.id == it }
    }
    val details = contact?.details
    editingOriginal = contact
    editingContactId = contactId
    editingInitialized = true
    _editDraft.value = ContactViewModel.ContactDraft(
        namePrefix = contact?.name?.namePrefix ?: "",
        firstName = contact?.name?.firstName ?: prefill?.name ?: "",
        middleName = contact?.name?.middleName ?: "",
        lastName = contact?.name?.lastName ?: "",
        nameSuffix = contact?.name?.nameSuffix ?: "",
        company = contact?.org?.company ?: prefill?.company ?: "",
        noteContent = contact?.note?.content ?: prefill?.notes ?: "",
        nickname = contact?.nickname?.nickname ?: prefill?.nickname ?: "",
        photo = contact?.photo,
        birthday = contact?.birthday?.startDate,
        accountName = contact?.accountName ?: _lastSelectedAccount.value?.name ?: "",
        accountType = contact?.accountType ?: _lastSelectedAccount.value?.type ?: "",
        // Almost every contact has a phone number, so the editor opens with a row ready
        // rather than making the user press "add phone" first. Blank rows are dropped on save.
        phoneNumbers = mergePhones(details?.phoneNumbers ?: emptyList(), prefill?.phones ?: emptyList())
            .ifEmpty { listOf(ContactDetail.default<PhoneNumber>()) },
        emails = mergeEmails(details?.emails ?: emptyList(), prefill?.emails ?: emptyList()),
        dates = details?.dates ?: emptyList(),
        addresses = mergeAddresses(details?.addresses ?: emptyList(), prefill?.postals ?: emptyList()),
        groupMemberships = details?.groups ?: emptyList(),
    )
}

/** Appends prefilled phones that aren't already present (compared by normalized number). */
internal fun ContactViewModel.mergePhones(existing: List<PhoneNumber>, prefill: List<PrefillValue>): List<PhoneNumber> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    for (p in prefill) {
        val norm = normalizePhoneForCompare(p.value)
        if (norm.isEmpty()) continue
        if (result.any { normalizePhoneForCompare(it.number) == norm }) continue
        result += PhoneNumber(0, p.value, p.type ?: CDKPhone.TYPE_MOBILE, p.label ?: "")
    }
    return result
}

/** Appends prefilled emails that aren't already present (case-insensitive address match). */
internal fun ContactViewModel.mergeEmails(existing: List<Email>, prefill: List<PrefillValue>): List<Email> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    for (e in prefill) {
        val v = e.value.trim()
        if (v.isEmpty()) continue
        if (result.any { it.address.trim().equals(v, ignoreCase = true) }) continue
        result += Email(0, e.value, e.type ?: CDKEmail.TYPE_HOME, e.label ?: "")
    }
    return result
}

/** Appends prefilled postal addresses that aren't already present (case-insensitive match). */
internal fun ContactViewModel.mergeAddresses(existing: List<Address>, prefill: List<PrefillValue>): List<Address> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    for (a in prefill) {
        val v = a.value.trim()
        if (v.isEmpty()) continue
        if (result.any { it.formattedAddress.trim().equals(v, ignoreCase = true) }) continue
        result += Address(0, a.value, a.type ?: CDKStructuredPostal.TYPE_HOME, a.label ?: "")
    }
    return result
}

/**
 * Used by INSERT_OR_EDIT / SHOW_OR_CREATE_CONTACT flow when the user picks an existing
 * contact from the dialer. Directly appends [phone] (if not already present) and saves,
 * without opening the full editor.
 */
fun ContactViewModel.addPhoneNumberToContact(
    contactId: Long,
    phone: String,
    type: Int = CDKPhone.TYPE_MOBILE,
    onComplete: () -> Unit = {}
) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val existing = Contact.getContact(app, contactId) ?: _allContacts.value.find { it.id == contactId } ?: contacts.value.find { it.id == contactId }
            if (existing == null) {
                withContext(Dispatchers.Main) { onComplete() }
                return@launch
            }
            if (isSimAccountType(existing.accountType)) {
                val subId = existing.accountName?.toIntOrNull()
                val normalizedNew = normalizePhoneForCompare(phone)
                val alreadyHas = existing.details.phoneNumbers.any { normalizePhoneForCompare(it.number) == normalizedNew }
                if (alreadyHas) {
                    withContext(Dispatchers.Main) { onComplete() }
                    return@launch
                }
                val name = existing.name.value
                if (existing.details.phoneNumbers.isEmpty()) {
                    val oldSc = SimContactsDataSource.findBackingSimContact(app, existing)
                    if (oldSc != null) SimContactsDataSource.deleteSimContact(app, oldSc)
                    SimContactsDataSource.insertSimContact(app, name.ifEmpty { phone }, phone, null, subId)
                } else {
                    // SIM can hold only one number; store the new number as an additional SIM entry
                    SimContactsDataSource.insertSimContact(app, name.ifEmpty { phone }, phone, null, subId)
                }
                syncFromSystem()
            } else {
                val normalizedNew = normalizePhoneForCompare(phone)
                val alreadyHas = existing.details.phoneNumbers.any { normalizePhoneForCompare(it.number) == normalizedNew }
                if (alreadyHas) {
                    withContext(Dispatchers.Main) { onComplete() }
                    return@launch
                }
                val newDetails = existing.details.copy(
                    phoneNumbers = existing.details.phoneNumbers + PhoneNumber(0, phone, type)
                )
                existing.save(app, newDetails, existing.details)
            }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Failed to add phone to contact $contactId", e)
        }
        withContext(Dispatchers.Main) { onComplete() }
    }
}

/** Applies [transform] to the current draft, if any. */
fun ContactViewModel.updateEditDraft(transform: (ContactViewModel.ContactDraft) -> ContactViewModel.ContactDraft) {
    val current = _editDraft.value ?: return
    _editDraft.value = transform(current)
}

/** Clears the in-progress draft and forgets which contact was being edited. */
fun ContactViewModel.clearEditDraft() {
    _editDraft.value = null
    editingOriginal = null
    editingContactId = null
    editingInitialized = false
}

/**
 * Decodes the picked image URI off the main thread, scales it to 500x500,
 * Base64-encodes it, and updates [editDraft]'s photo.
 */
fun ContactViewModel.setEditDraftPhotoFromBitmap(bitmap: Bitmap) {
    viewModelScope.launch(Dispatchers.IO) {
        val scaled = if (bitmap.width != 1024 || bitmap.height != 1024) {
            bitmap.scale(1024, 1024)
        } else bitmap
        val baos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val encoded = Base64.encode(baos.toByteArray())
        updateEditDraft { draft ->
            val newPhoto = draft.photo?.withValue(encoded)
                ?: com.vayunmathur.contacts.data.Photo(0, encoded)
            draft.copy(photo = newPhoto)
        }
    }
}

/**
 * Persists the current draft via the unified save path.
 * SIM vs device routing is handled by [saveContact] based on draft.accountType.
 */
fun ContactViewModel.saveEditDraft(onResult: ((Boolean, String?) -> Unit)? = null) {
    val draft = _editDraft.value ?: return
    // For SIM accounts, validate SIM limits: at least name or phone needed, and SIM can't store extra fields
    if (isSimAccountType(draft.accountType)) {
        val nameVal = listOfNotNull(draft.namePrefix.ifEmpty { null }, draft.firstName.ifEmpty { null }, draft.middleName.ifEmpty { null }, draft.lastName.ifEmpty { null }, draft.nameSuffix.ifEmpty { null }).joinToString(" ").trim()
        val phoneVal = draft.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
        if (nameVal.isEmpty() && phoneVal.isEmpty()) {
            onResult?.invoke(false, getApplication<Application>().getString(R.string.sim_name_or_phone_required))
            return
        }
    }
    val original = editingOriginal
    val phoneNumbers = draft.phoneNumbers.filter { it.number.isNotBlank() }
    val birthdayId = original?.birthday?.id ?: 0L
    val datesWithoutBirthday = draft.dates.filter { it.type != CDKEvent.TYPE_BIRTHDAY }.toMutableList()
    draft.birthday?.let { bday ->
        datesWithoutBirthday += Event(birthdayId, bday, CDKEvent.TYPE_BIRTHDAY)
    }
    val details = ContactDetails(
        phoneNumbers = phoneNumbers,
        emails = draft.emails,
        addresses = draft.addresses,
        dates = datesWithoutBirthday,
        photos = listOfNotNull(draft.photo),
        names = listOf(
            Name(
                original?.name?.id ?: 0,
                draft.namePrefix,
                draft.firstName,
                draft.middleName,
                draft.lastName,
                draft.nameSuffix
            )
        ),
        orgs = listOf(Organization(original?.org?.id ?: 0, draft.company)),
        notes = listOf(Note(original?.note?.id ?: 0, draft.noteContent)),
        nicknames = listOf(
            Nickname(
                original?.nickname?.id ?: 0,
                draft.nickname,
                CDKNickname.TYPE_DEFAULT
            )
        ),
        groups = draft.groupMemberships
    )
    // For existing SIM contacts, keep synthetic id so saveContact can locate old row.
    // The provider requires an account to name both fields or neither, so a draft pointing at
    // a half-renamed account saves to device-local rather than being rejected.
    val accountType = draft.accountType.ifEmpty { null }
    val accountName = if (accountType == null) null else draft.accountName.ifEmpty { null }
    val newContact = original?.copy(
        accountType = accountType,
        accountName = accountName,
        details = details
    ) ?: Contact(
        id = 0,
        accountType = accountType,
        accountName = accountName,
        isFavorite = false,
        details = details
    )
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { persistContact(newContact) }
        if (ok) clearEditDraft()
        onResult?.invoke(ok, null)
    }
}
