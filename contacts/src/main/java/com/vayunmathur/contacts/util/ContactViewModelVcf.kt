package com.vayunmathur.contacts.util

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VCF import operations, kept out of ContactViewModel so the class stays
 * under the function cap (see TooManyFunctions). Called as viewModel.*.
 */
/** Parses every [uris] off the main thread and exposes the result via [ContactViewModel.parsedVcfContacts]. */
fun ContactViewModel.parseVcfUris(uris: List<android.net.Uri>) {
    if (uris.isEmpty()) {
        parsedVcfContactsState.value = emptyList()
        return
    }
    val app = getApplication<Application>()
    viewModelScope.launch(Dispatchers.IO) {
        val allContacts = mutableListOf<Contact>()
        uris.forEach { uri ->
            parseSingleVcfUri(app, uri, allContacts)
        }
        parsedVcfContactsState.value = allContacts
    }
}

private fun ContactViewModel.parseSingleVcfUri(
    app: Application,
    uri: android.net.Uri,
    allContacts: MutableList<Contact>,
) {
    try {
        app.contentResolver.openInputStream(uri)?.use { input ->
            allContacts.addAll(VcfUtils.parseContacts(input))
        }
    } catch (e: java.io.FileNotFoundException) {
        Log.error("ContactViewModel", "Error parsing VCF file: $uri", e)
    } catch (e: SecurityException) {
        Log.error("ContactViewModel", "Error parsing VCF file: $uri", e)
    } catch (e: java.io.IOException) {
        Log.error("ContactViewModel", "Error parsing VCF file: $uri", e)
    }
}

/** Clears any parsed-VCF state held in the VM (called when the import screen dismisses). */
fun ContactViewModel.clearParsedVcf() {
    parsedVcfContactsState.value = null
}

/**
 * Bulk-imports the previously parsed [contacts] into the account with [accountName] and [accountType].
 * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure).
 */
fun ContactViewModel.importVcfContacts(
    contacts: List<Contact>,
    accountName: String,
    accountType: String,
    onDone: () -> Unit = {},
) {
    val app = getApplication<Application>()
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            // Broad catch is deliberate: bulk import crosses the contacts
            // provider, SIM ADN and VCF parsing, and must report failure
            // via onDone rather than crashing the import screen.
            @Suppress("TooGenericExceptionCaught")
            try {
                if (isSimAccountType(accountType)) {
                    importVcfToSim(app, contacts, accountName)
                    // Refresh unified list
                    withContext(Dispatchers.Main) { loadContacts() }
                } else {
                    importVcfToProvider(app, contacts, accountName, accountType)
                }
            } catch (e: Exception) {
                Log.error("ContactViewModel", "Error importing contacts", e)
            }
        }
        loadContacts()
        onDone()
    }
}

private suspend fun ContactViewModel.importVcfToSim(
    app: Application,
    contacts: List<Contact>,
    accountName: String,
) {
    val subId = accountName.toIntOrNull()
    contacts.forEach { contact ->
        val name = contact.name.value.trim().ifEmpty {
            contact.details.phoneNumbers.firstOrNull()?.number ?: ""
        }
        val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
        val email = contact.details.emails.firstOrNull()?.address?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (name.isNotBlank() || number.isNotBlank()) {
            SimContactsDataSource.insertSimContact(app, name, number, email, subId)
        }
    }
}

private fun ContactViewModel.importVcfToProvider(
    app: Application,
    contacts: List<Contact>,
    accountName: String,
    accountType: String,
) {
    contacts.forEach { contact ->
        val toSave = contact.copy(
            accountName = accountName,
            accountType = accountType
        )
        toSave.save(app, toSave.details, ContactDetails.empty())
    }
}
