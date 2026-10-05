package com.vayunmathur.contacts.util

import android.app.Application
import android.util.Log
import com.vayunmathur.contacts.data.ContactAccount
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import kotlinx.coroutines.flow.first

/**
 * One-time DataStore migrations for account keys, kept out of
 * ContactViewModelAccounts so each file stays under the function cap
 * (see TooManyFunctions). Called as viewModel.*.
 */

private suspend fun ContactViewModel.migrateLegacyAccounts() {
    val legacyAccounts = dataStore.getString("extra_accounts").orEmpty()
    if (legacyAccounts.isBlank()) return
    legacyAccounts.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { legacyEntry -> migrateLegacyAccountEntry(legacyEntry) }
    dataStore.setString("extra_accounts", "")
}

private suspend fun ContactViewModel.migrateLegacyAccountEntry(legacyEntry: String) {
    val clean = decodeExtraAccountEntryClean(legacyEntry)
    if (clean != null) {
        dataStore.addStringToSetIfAbsent("extra_accounts_set", legacyEntry)
        return
    }
    if (!legacyEntry.contains("|")) {
        val encoded = encodeExtraAccountEntry(legacyEntry, LOCAL_ACCOUNT_TYPE)
        dataStore.addStringToSetIfAbsent("extra_accounts_set", encoded)
        return
    }
    val nameOld = legacyEntry.substringBeforeLast("|")
    val typeOld = legacyEntry.substringAfterLast("|")
    if (nameOld.isNotEmpty()) {
        val encoded = encodeExtraAccountEntry(nameOld, typeOld)
        dataStore.addStringToSetIfAbsent("extra_accounts_set", encoded)
    }
}

private suspend fun ContactViewModel.loadSavedAccounts(): List<ContactAccount> {
    val savedSet = dataStore.stringSetFlow("extra_accounts_set").first()
    val savedAccounts = mutableListOf<ContactAccount>()
    for (entry in savedSet) {
        migrateSavedAccountEntry(entry, savedAccounts)
    }
    return savedAccounts
}

/** Normalizes one saved-accounts entry; single exit so the loop has one jump. */
private suspend fun ContactViewModel.migrateSavedAccountEntry(
    entry: String,
    savedAccounts: MutableList<ContactAccount>,
) {
    val account = decodeSavedAccountEntry(entry)
    if (account == null) return
    savedAccounts.add(account)
    if (isCleanSavedEntry(entry)) return
    dataStore.removeStringFromSet("extra_accounts_set", entry)
    val encoded = encodeExtraAccountEntry(account.name, account.type)
    dataStore.addStringToSetIfAbsent("extra_accounts_set", encoded)
}

private fun decodeSavedAccountEntry(entry: String): ContactAccount? {
    decodeExtraAccountEntryClean(entry)?.let { return it }
    if (entry.contains("|")) {
        val nameOld = entry.substringBeforeLast("|")
        val typeOld = entry.substringAfterLast("|")
        if (nameOld.isEmpty()) return null
        return ContactAccount(nameOld, typeOld)
    }
    if (entry.isEmpty()) return null
    val decodedName = decodeAccountComponent(entry)
    val account = ContactAccount(decodedName.ifEmpty { entry }, LOCAL_ACCOUNT_TYPE)
    return account.takeIf { it.name.isNotEmpty() }
}

private fun isCleanSavedEntry(entry: String): Boolean =
    decodeExtraAccountEntryClean(entry) != null

// Migrate hidden_accounts keys containing raw separators to encoded form.
// Broad catch is deliberate: DataStore reads fail with OEM-specific
// RuntimeExceptions and a best-effort migration must not break account load.
@Suppress("TooGenericExceptionCaught")
private suspend fun ContactViewModel.migrateHiddenAccountKeys() {
    try {
        val hiddenSet = dataStore.getStringSetAwait("hidden_accounts")
        for (hiddenEntry in hiddenSet) {
            migrateHiddenAccountKey(hiddenEntry)
        }
    } catch (e: Exception) {
        Log.w("ContactViewModel", "Error migrating hidden accounts", e)
    }
}

/** Migrates one hidden-accounts key; single exit so the loop has one jump. */
private suspend fun ContactViewModel.migrateHiddenAccountKey(hiddenEntry: String) {
    if (shouldKeepHiddenKey(hiddenEntry)) return
    val typeOld = hiddenEntry.substringBefore("|")
    val nameOld = hiddenEntry.substringAfter("|")
    if (typeOld.isEmpty() && nameOld.isEmpty()) return
    dataStore.removeStringFromSet("hidden_accounts", hiddenEntry)
    dataStore.addStringToSet("hidden_accounts", encodeHiddenAccountKey(typeOld, nameOld))
}

private fun shouldKeepHiddenKey(hiddenEntry: String): Boolean {
    if (!hiddenEntry.contains("|")) return true
    return decodeHiddenKeyClean(hiddenEntry) != null
}

