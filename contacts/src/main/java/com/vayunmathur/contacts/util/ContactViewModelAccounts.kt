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
        legacyAccounts.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            dataStore.addStringToSetIfAbsent("extra_accounts_set", it)
        }
        dataStore.setString("extra_accounts", "")
    }
    val savedAccounts = dataStore.stringSetFlow("extra_accounts_set").first().mapNotNull { entry ->
        val parts = entry.split("|")
        parts.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { name ->
            ContactAccount(name, parts.getOrElse(1) { LOCAL_ACCOUNT_TYPE })
        }
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
    val key = "${account.type}|${account.name}"
    if (visible) {
        dataStore.removeStringFromSet("hidden_accounts", key)
        // Also clear legacy entry if it exists (migration)
        dataStore.removeStringFromSet("hidden_accounts", account.name)
    } else {
        dataStore.addStringToSet("hidden_accounts", key)
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
        if (dataStore.addStringToSetIfAbsent("extra_accounts_set", "$name|$type")) {
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
        val collides = _accounts.value.any { it.type == account.type && it.name == trimmed }
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
            dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
            dataStore.addStringToSetIfAbsent("extra_accounts_set", "$trimmed|${account.type}")
            // Migrate the hidden-accounts visibility key ("type|name").
            val hidden = dataStore.getStringSetAwait("hidden_accounts")
            val oldHiddenKey = "${account.type}|${account.name}"
            if (oldHiddenKey in hidden) {
                dataStore.removeStringFromSet("hidden_accounts", oldHiddenKey)
                dataStore.addStringToSet("hidden_accounts", "${account.type}|$trimmed")
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
            dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
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
