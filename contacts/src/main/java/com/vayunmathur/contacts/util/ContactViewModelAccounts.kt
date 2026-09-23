package com.vayunmathur.contacts.util

import android.app.Application
import android.content.ContentProviderOperation
import android.provider.ContactsContract
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WHERE clause (+ selection args) matching a RawContacts account. An empty
 * type/name is treated as NULL-or-empty in the provider, so device-local
 * accounts (whose ACCOUNT_TYPE/ACCOUNT_NAME may be stored as NULL) are matched.
 */
internal fun ContactViewModel.accountSelection(type: String, name: String): Pair<String, Array<String>> {
    val args = ArrayList<String>()
    val typeClause = if (type.isEmpty()) {
        "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} = '')"
    } else {
        args.add(type)
        "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
    }
    val nameClause = if (name.isEmpty()) {
        "(${ContactsContract.RawContacts.ACCOUNT_NAME} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_NAME} = '')"
    } else {
        args.add(name)
        "${ContactsContract.RawContacts.ACCOUNT_NAME} = ?"
    }
    return "$typeClause AND $nameClause" to args.toTypedArray()
}

/** Percent-encode account key components: `%` first, then `|` and `,`. */
private fun encodeAccountComponent(raw: String): String =
    raw.replace("%", "%25").replace("|", "%7C").replace(",", "%2C")

private fun decodeAccountComponent(encoded: String): String =
    encoded.replace("%7C", "|", ignoreCase = true)
        .replace("%2C", ",", ignoreCase = true)
        .replace("%25", "%")

private fun encodeExtraAccountEntry(name: String, type: String): String =
    "${encodeAccountComponent(name)}|${encodeAccountComponent(type)}"

private fun encodeHiddenAccountKey(type: String, name: String): String =
    "${encodeAccountComponent(type)}|${encodeAccountComponent(name)}"

/** Decoded account if [entry] is a clean encoded "name|type" entry (exactly one separator and round-trips). */
private fun decodeExtraAccountEntryClean(entry: String): ContactAccount? {
    if (entry.count { it == '|' } != 1) return null
    val rawName = entry.substringBefore("|")
    val rawType = entry.substringAfter("|")
    if (rawName.isEmpty()) return null
    val name = decodeAccountComponent(rawName)
    val type = decodeAccountComponent(rawType)
    if (encodeExtraAccountEntry(name, type) != entry) return null
    return ContactAccount(name, type)
}

/** Decoded (type, name) if [entry] is a clean encoded "type|name" hidden key. */
private fun decodeHiddenKeyClean(entry: String): Pair<String, String>? {
    if (entry.count { it == '|' } != 1) return null
    val rawType = entry.substringBefore("|")
    val rawName = entry.substringAfter("|")
    if (rawType.isEmpty() && rawName.isEmpty()) return null
    val type = decodeAccountComponent(rawType)
    val name = decodeAccountComponent(rawName)
    if (encodeHiddenAccountKey(type, name) != entry) return null
    return type to name
}

internal suspend fun ContactViewModel.loadAccountsInternal() {
    val app = getApplication<Application>()
    val uri = ContactsContract.RawContacts.CONTENT_URI
    val projection = arrayOf(
        ContactsContract.RawContacts.ACCOUNT_NAME,
        ContactsContract.RawContacts.ACCOUNT_TYPE
    )
    val accountSet = mutableSetOf<ContactAccount>()
    try {
        // Tombstoned raw contacts keep their account columns, so without this filter an
        // account whose every contact has been deleted stays in the account list forever.
        val cursor = app.contentResolver.query(
            uri,
            projection,
            "${ContactsContract.RawContacts.DELETED} = 0",
            null,
            null,
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: ""
                val type = it.getString(1) ?: ""
                accountSet.add(ContactAccount(name, type))
            }
        }
    } catch (e: Exception) {
        Log.e("ContactViewModel", "Error querying raw contacts for accounts", e)
    }
    val legacyAccounts = dataStore.getString("extra_accounts").orEmpty()
    if (legacyAccounts.isNotBlank()) {
        legacyAccounts.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { legacyEntry ->
            val clean = decodeExtraAccountEntryClean(legacyEntry)
            if (clean != null) {
                dataStore.addStringToSetIfAbsent("extra_accounts_set", legacyEntry)
            } else if (legacyEntry.contains("|")) {
                val nameOld = legacyEntry.substringBeforeLast("|")
                val typeOld = legacyEntry.substringAfterLast("|")
                if (nameOld.isNotEmpty()) {
                    dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(nameOld, typeOld))
                }
            } else {
                dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(legacyEntry, LOCAL_ACCOUNT_TYPE))
            }
        }
        dataStore.setString("extra_accounts", "")
    }
    val savedSet = dataStore.stringSetFlow("extra_accounts_set").first()
    val savedAccounts = mutableListOf<ContactAccount>()
    for (entry in savedSet) {
        val clean = decodeExtraAccountEntryClean(entry)
        if (clean != null) {
            savedAccounts.add(clean)
            continue
        }
        if (entry.contains("|")) {
            val nameOld = entry.substringBeforeLast("|")
            val typeOld = entry.substringAfterLast("|")
            if (nameOld.isEmpty()) continue
            savedAccounts.add(ContactAccount(nameOld, typeOld))
            dataStore.removeStringFromSet("extra_accounts_set", entry)
            dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(nameOld, typeOld))
        } else if (entry.isNotEmpty()) {
            val decodedName = decodeAccountComponent(entry)
            val account = ContactAccount(decodedName.ifEmpty { entry }, LOCAL_ACCOUNT_TYPE)
            if (account.name.isNotEmpty()) {
                savedAccounts.add(account)
                dataStore.removeStringFromSet("extra_accounts_set", entry)
                dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(account.name, account.type))
            }
        }
    }
    // Migrate hidden_accounts keys containing raw separators to encoded form.
    try {
        val hiddenSet = dataStore.getStringSetAwait("hidden_accounts")
        for (hiddenEntry in hiddenSet) {
            if (!hiddenEntry.contains("|")) continue
            if (decodeHiddenKeyClean(hiddenEntry) != null) continue
            val typeOld = hiddenEntry.substringBefore("|")
            val nameOld = hiddenEntry.substringAfter("|")
            if (typeOld.isEmpty() && nameOld.isEmpty()) continue
            dataStore.removeStringFromSet("hidden_accounts", hiddenEntry)
            dataStore.addStringToSet("hidden_accounts", encodeHiddenAccountKey(typeOld, nameOld))
        }
    } catch (_: Exception) {
    }
    // Virtual SIM accounts
    val simInfos = SimContactsDataSource.getSimSubscriptionInfos(app)
    val simAccounts = simInfos.map { info ->
        ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
    }
    val simLabels = simInfos.associate { info ->
        val acc = ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
        "${acc.type}|${acc.name}" to SimContactsDataSource.getSimAccountDisplayLabel(app, info)
    }
    _simAccountLabels.value = simLabels
    _simSlotLabels.value = simSlotLabelsFor(simInfos)

    // Sort: SIM accounts by display label, others by name
    val all = (accountSet + savedAccounts + simAccounts).toList()
    val collator = ContactSorting.collator()
    _accounts.value = all.sortedWith(compareBy(collator) { acc ->
        if (acc.type == SIM_ACCOUNT_TYPE) _simAccountLabels.value["${acc.type}|${acc.name}"] ?: acc.name
        else acc.name
    })
}

fun ContactViewModel.loadAccounts() {
    viewModelScope.launch(Dispatchers.IO) {
        loadAccountsInternal()
    }
}

fun ContactViewModel.loadLastSelectedAccount() {
    val name = dataStore.getString("last_account_name")
    val type = dataStore.getString("last_account_type")
    _lastSelectedAccount.value = ContactAccount(name.orEmpty(), type.orEmpty())
}

fun ContactViewModel.setLastSelectedAccount(name: String, type: String) {
    viewModelScope.launch {
        dataStore.setString("last_account_name", name)
        dataStore.setString("last_account_type", type)
        _lastSelectedAccount.value = ContactAccount(name, type)
    }
}

fun ContactViewModel.setAccountVisibility(account: ContactAccount, visible: Boolean) {
    val key = encodeHiddenAccountKey(account.type, account.name)
    val legacyRawKey = "${account.type}|${account.name}"
    if (visible) {
        dataStore.removeStringFromSet("hidden_accounts", key)
        if (legacyRawKey != key) dataStore.removeStringFromSet("hidden_accounts", legacyRawKey)
        // Also clear legacy entry if it exists (migration)
        dataStore.removeStringFromSet("hidden_accounts", account.name)
    } else {
        dataStore.addStringToSet("hidden_accounts", key)
        if (legacyRawKey != key) dataStore.removeStringFromSet("hidden_accounts", legacyRawKey)
    }
}

// Backward-compatible overload used by old call sites that only knew name
fun ContactViewModel.setAccountVisibility(accountName: String, visible: Boolean) {
    // Try to resolve type from current accounts list
    val matched = _accounts.value.firstOrNull { it.name == accountName }
    if (matched != null) {
        setAccountVisibility(matched, visible)
    } else {
        // Fallback: treat as legacy name-only key
        if (visible) dataStore.removeStringFromSet("hidden_accounts", accountName)
        else dataStore.addStringToSet("hidden_accounts", accountName)
    }
}

fun ContactViewModel.createAccount(name: String, type: String = LOCAL_ACCOUNT_TYPE, onComplete: (() -> Unit)? = null) {
    viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        val collides = _accounts.value.any { it.type == type && it.name.equals(trimmed, ignoreCase = true) }
        if (collides) {
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
            return@launch
        }
        if (dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(trimmed, type))) {
            loadAccounts()
        }
        withContext(Dispatchers.Main) {
            onComplete?.invoke()
        }
    }
}

/**
 * Renames a local (on-device) account. Guards that [account] is a local account and that
 * [newName] is non-blank and does not collide with an existing account of the same type.
 * Re-points every RawContact stored under the old name to [newName] and migrates the
 * DataStore references (saved-accounts set, hidden-accounts key, last-selected account).
 * [onResult] is invoked on the main thread with success and an optional error message key.
 */
fun ContactViewModel.renameLocalAccount(
    account: ContactAccount,
    newName: String,
    onResult: ((Boolean, String?) -> Unit)? = null,
) {
    viewModelScope.launch {
        val trimmed = newName.trim()
        if (!isLocalAccountType(account.type)) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
            return@launch
        }
        if (isDefaultLocalAccount(account.name, account.type)) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
            return@launch
        }
        if (trimmed.isEmpty()) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "blank") }
            return@launch
        }
        if (trimmed == account.name) {
            withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
            return@launch
        }
        val collides = _accounts.value.any { it.type == account.type && it.name.equals(trimmed, ignoreCase = true) && it.name != account.name }
        if (collides) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "collision") }
            return@launch
        }
        withContext(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val ops = ArrayList<ContentProviderOperation>()
                val (sel, args) = accountSelection(account.type, account.name)
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                        .withSelection(sel, args)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, trimmed)
                        .build()
                )
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error renaming local account", e)
            }
            // Migrate the saved-accounts label so an empty account (no contacts) is renamed too.
            dataStore.removeStringFromSet("extra_accounts_set", encodeExtraAccountEntry(account.name, account.type))
            dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
            dataStore.addStringToSetIfAbsent("extra_accounts_set", encodeExtraAccountEntry(trimmed, account.type))
            // Migrate the hidden-accounts visibility key ("type|name").
            val hidden = dataStore.getStringSetAwait("hidden_accounts")
            val oldHiddenKey = encodeHiddenAccountKey(account.type, account.name)
            val legacyRawHiddenKey = "${account.type}|${account.name}"
            if (oldHiddenKey in hidden || legacyRawHiddenKey in hidden) {
                dataStore.removeStringFromSet("hidden_accounts", oldHiddenKey)
                if (legacyRawHiddenKey != oldHiddenKey) dataStore.removeStringFromSet("hidden_accounts", legacyRawHiddenKey)
                dataStore.addStringToSet("hidden_accounts", encodeHiddenAccountKey(account.type, trimmed))
            }
            // Migrate last-selected (the implicit default save location).
            if (dataStore.getString("last_account_type") == account.type &&
                dataStore.getString("last_account_name") == account.name
            ) {
                dataStore.setString("last_account_name", trimmed)
                _lastSelectedAccount.value = ContactAccount(trimmed, account.type)
            }
        }
        loadAccounts()
        loadContacts()
        withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
    }
}

/**
 * Deletes a local (on-device) account and all of its contacts. Guards that [account] is a
 * local account. Hard-deletes every RawContact for the account (via the
 * CALLER_IS_SYNCADAPTER URI param, since the local account has no sync adapter) and clears
 * the DataStore references. [onResult] is invoked on the main thread.
 */
fun ContactViewModel.deleteLocalAccount(
    account: ContactAccount,
    onResult: ((Boolean, String?) -> Unit)? = null,
) {
    viewModelScope.launch {
        if (!isLocalAccountType(account.type)) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
            return@launch
        }
        if (isDefaultLocalAccount(account.name, account.type)) {
            withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
            return@launch
        }
        withContext(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .build()
                val (sel, args) = accountSelection(account.type, account.name)
                resolver.delete(uri, sel, args)
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error deleting local account", e)
            }
            dataStore.removeStringFromSet("extra_accounts_set", encodeExtraAccountEntry(account.name, account.type))
            dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
            dataStore.removeStringFromSet("hidden_accounts", encodeHiddenAccountKey(account.type, account.name))
            dataStore.removeStringFromSet("hidden_accounts", "${account.type}|${account.name}")
            // Legacy name-only hidden key.
            dataStore.removeStringFromSet("hidden_accounts", account.name)
            // If this account was the default save location, reset to on-device.
            if (dataStore.getString("last_account_type") == account.type &&
                dataStore.getString("last_account_name") == account.name
            ) {
                dataStore.setString("last_account_name", "")
                dataStore.setString("last_account_type", "")
                _lastSelectedAccount.value = ContactAccount("", "")
            }
        }
        loadAccounts()
        loadContacts()
        withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
    }
}
