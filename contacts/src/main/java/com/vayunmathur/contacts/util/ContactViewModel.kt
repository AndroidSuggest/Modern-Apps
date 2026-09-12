@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlinx.coroutines.FlowPreview::class)

package com.vayunmathur.contacts.util
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Log
import androidx.collection.LruCache
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
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
import com.vayunmathur.contacts.data.ContactGroup
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
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContact
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.contacts.util.ContactSorting.sortedByNameLocale
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

data class ContactAccount(val name: String, val type: String)

data class ContactGroupMembership(val contactId: Long, val groupId: Long)

/**
 * Implements [ContactsActions] so a binder can hand itself straight to a stateless screen;
 * the navigating members keep their no-op defaults and are overridden per screen, since the
 * ViewModel has no back stack.
 */
/** Query-token separator. Hoisted so a keystroke does not recompile the pattern. */
private val WHITESPACE = Regex("\\s+")

class ContactViewModel(application: Application) : AndroidViewModel(application), ContactsActions {

    internal val dataStore = DataStoreUtils.getInstance(application)

    // Provider-backed in-memory contact list (no local DB). Populated by
    // syncFromSystem() from the system Contacts provider + SIM ADN and refreshed via the
    // ContentObserver registered in init.
    internal val _allContacts = MutableStateFlow<List<Contact>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // The contact list starts empty and fills in asynchronously, so screens need to tell
    // "nothing loaded yet" apart from "there really are no contacts".
    private val _hasLoadedContacts = MutableStateFlow(false)
    val hasLoadedContacts: StateFlow<Boolean> = _hasLoadedContacts.asStateFlow()

    val hiddenAccounts: StateFlow<Set<String>> = dataStore.stringSetFlow("hidden_accounts")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun accountKey(type: String?, name: String?): String = "${type ?: ""}|${name ?: ""}"

    /**
     * Each contact paired with the lowercased text [filterBySearch] matches against.
     *
     * Built once per change to the address book rather than once per keystroke. The haystack
     * concatenates every name, nickname, phone number, email, note and company a contact has and
     * then lowercases the lot; doing that for the whole book between two frames of typing was the
     * expensive half of search, and none of it depends on what was typed.
     */
    private val searchIndex: StateFlow<List<Pair<com.vayunmathur.contacts.data.Contact, String>>> =
        _allContacts
            .map { all -> all.map { it to searchHaystack(it) } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contacts: StateFlow<List<com.vayunmathur.contacts.data.Contact>> = combine(
        searchIndex,
        _searchQuery,
        hiddenAccounts
    ) { indexed, query, hidden ->
        val visible = indexed.filter { (c, _) ->
            val key = accountKey(c.accountType, c.accountName)
            // Support legacy hidden entries that stored only accountName
            key !in hidden && c.accountName !in hidden
        }
        val tokens = query.trim().lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) visible.map { it.first }
        else visible.filter { (_, haystack) -> tokens.all { haystack.contains(it) } }
            .map { it.first }
    }
        // viewModelScope is Main.immediate, so without this the whole address book was filtered on
        // the UI thread between one frame of typing and the next.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Virtual SIM account display labels: key is "type|name" -> "SIM N — Carrier"
    internal val _simAccountLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val simAccountLabels: StateFlow<Map<String, String>> = _simAccountLabels.asStateFlow()

    fun simDisplayLabel(account: ContactAccount): String? = _simAccountLabels.value["${account.type}|${account.name}"]
    fun simDisplayLabelFor(type: String?, name: String?): String? = _simAccountLabels.value["${type ?: ""}|${name ?: ""}"]

    // Short form of the above ("SIM N", no carrier) for the storage badge on a contact row, which
    // has room for a name but not a name plus a carrier.
    internal val _simSlotLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val simSlotLabels: StateFlow<Map<String, String>> = _simSlotLabels.asStateFlow()

    internal fun simSlotLabelsFor(infos: List<SimContactsDataSource.SimSubscriptionInfo>): Map<String, String> {
        val app = getApplication<Application>()
        return infos.associate { info ->
            "$SIM_ACCOUNT_TYPE|${SimContactsDataSource.accountNameFor(info)}" to
                app.getString(R.string.sim_slot_label, SimContactsDataSource.slotNumberFor(info))
        }
    }

    val groups: StateFlow<List<ContactGroup>> = callbackFlow {
        val resolver = getApplication<Application>().contentResolver
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                launch { send(fetchGroups()) }
            }
        }
        resolver.registerContentObserver(ContactsContract.Groups.CONTENT_URI, true, observer)
        send(fetchGroups())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun fetchGroups(): List<ContactGroup> {
        val resolver = getApplication<Application>().contentResolver
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE)
        val list = mutableListOf<ContactGroup>()
        resolver.query(uri, projection, "${ContactsContract.Groups.GROUP_VISIBLE} = 1 AND ${ContactsContract.Groups.DELETED} = 0", null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                list.add(ContactGroup(cursor.getLong(idIdx), cursor.getString(titleIdx) ?: "Unnamed"))
            }
        }
        return list.sortedByNameLocale { it.name }
    }

    val contactGroupMemberships: StateFlow<List<ContactGroupMembership>> = contacts.map { contactList ->
        contactList.flatMap { contact ->
            contact.details.groups.map { membership ->
                ContactGroupMembership(contactId = contact.id, groupId = membership.groupId)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    internal val _accounts = MutableStateFlow<List<ContactAccount>>(emptyList())
    val accounts: StateFlow<List<ContactAccount>> = _accounts.asStateFlow()

    internal val _lastSelectedAccount = MutableStateFlow<ContactAccount?>(null)

    // Parsed-VCF state for the import screen. null = not yet parsed (or cleared);
    // empty list = parsed and found nothing; non-empty = parsed contacts ready to import.
    private val _parsedVcfContacts = MutableStateFlow<List<Contact>?>(null)
    val parsedVcfContacts: StateFlow<List<Contact>?> = _parsedVcfContacts.asStateFlow()

    val isCalendarSyncEnabled: StateFlow<Boolean> = dataStore.booleanFlow("calendar_sync_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val showAccountLabels: StateFlow<Boolean> = dataStore.booleanFlow("show_account_labels")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Coalesces system-contact change notifications; collected with debounce so a
    // burst of provider writes triggers a single re-sync instead of one per change.
    private val syncTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Fires on any ContactsContract change (contact/raw/data/account/group). Kept as a
    // field so onCleared() can unregister it and avoid leaking the ContentResolver.
    private val contactsObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) { syncTrigger.tryEmit(Unit) }
    }

    init {
        // notifyForDescendants=true on the top-level authority so any contact/raw/data/
        // account change fires it; debounced so a sync burst causes a single reload.
        getApplication<Application>().contentResolver
            .registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactsObserver)
        viewModelScope.launch {
            // Load immediately so cold-launched screens like InsertOrEdit don't
            // show empty list while waiting for the debounce.
            syncFromSystem()
            syncTrigger.debounce(300).collectLatest { syncFromSystem() }
        }
        loadAccounts()
        loadLastSelectedAccount()
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(contactsObserver)
        super.onCleared()
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * The lowercased text a contact is matched against: names, nicknames, phone numbers, emails,
     * notes and organizations, run together.
     *
     * Built once per contact when the address book changes (see [searchIndex]), not per keystroke.
     * A query matches when every whitespace-separated token is a substring of this.
     */
    private fun searchHaystack(contact: Contact): String = buildString {
        append(contact.details.names.joinToString(" ") { it.value }); append(' ')
        append(contact.details.nicknames.joinToString(" ") { it.nickname }); append(' ')
        append(contact.details.phoneNumbers.joinToString(" ") { it.number }); append(' ')
        append(contact.details.emails.joinToString(" ") { it.address }); append(' ')
        append(contact.details.notes.joinToString(" ") { it.content }); append(' ')
        append(contact.details.orgs.joinToString(" ") { it.company })
    }.lowercase()

    fun setCalendarSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("calendar_sync_enabled", enabled)
            if (enabled) {
                withContext(Dispatchers.IO) {
                    CalendarSyncHelper.syncAll(getApplication())
                }
                CalendarWorker.schedule(getApplication())
            } else {
                withContext(Dispatchers.IO) {
                    CalendarSyncHelper.removeCalendar(getApplication())
                }
                CalendarWorker.cancel(getApplication())
            }
        }
    }

    fun setShowAccountLabels(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("show_account_labels", enabled)
        }
    }

    /** Requests a debounced re-sync from the system contacts provider. */
    fun loadContacts() {
        syncTrigger.tryEmit(Unit)
    }

    internal suspend fun syncFromSystem() = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val device = com.vayunmathur.contacts.data.Contact.getAllContacts(app)
            val sim = SimContactsDataSource.simContactsAsContacts(app)
            _allContacts.value = device + sim
            // Refresh SIM account labels for UI (type|name -> display)
            val infos = SimContactsDataSource.getSimSubscriptionInfos(app)
            val labels = infos.associate { info ->
                val acc = ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
                "${acc.type}|${acc.name}" to SimContactsDataSource.getSimAccountDisplayLabel(app, info)
            }
            _simAccountLabels.value = labels
            _simSlotLabels.value = simSlotLabelsFor(infos)
            // Also refresh the accounts list to include current SIM accounts (in case SIM inserted/removed)
            // Do it by launching loadAccounts() if needed; but we can update _accounts directly
            // to avoid double query. However loadAccounts() also merges DataStore saved accounts,
            // so we trigger it.
            // Use a direct call to avoid extra launch overhead: we are already on IO, but
            // loadAccounts() launches its own coroutine, so just trigger it.
            // To keep accounts in sync, launch a refresh:
            launch { loadAccountsInternal() }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error loading contacts", e)
        } finally {
            _hasLoadedContacts.value = true
        }
    }

    fun loadAccounts() {
        viewModelScope.launch(Dispatchers.IO) {
            loadAccountsInternal()
        }
    }

    /** Parses every [uris] off the main thread and exposes the result via [parsedVcfContacts]. */
    fun parseVcfUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            _parsedVcfContacts.value = emptyList()
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val allContacts = mutableListOf<Contact>()
            uris.forEach { uri ->
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        allContacts.addAll(VcfUtils.parseContacts(input))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ContactViewModel", "Error parsing VCF file: $uri", e)
                }
            }
            _parsedVcfContacts.value = allContacts
        }
    }

    /** Clears any parsed-VCF state held in the VM (called when the import screen dismisses). */
    fun clearParsedVcf() {
        _parsedVcfContacts.value = null
    }

    /**
     * Bulk-imports the previously parsed [contacts] into the account with [accountName] and [accountType].
     * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure).
     */
    fun importVcfContacts(
        contacts: List<Contact>,
        accountName: String,
        accountType: String,
        onDone: () -> Unit = {},
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // If target is a SIM account, route each contact via SIM data source
                    if (isSimAccountType(accountType)) {
                        val subId = accountName.toIntOrNull()
                        contacts.forEach { contact ->
                            val name = contact.name.value.trim().ifEmpty { contact.details.phoneNumbers.firstOrNull()?.number ?: "" }
                            val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
                            val email = contact.details.emails.firstOrNull()?.address?.trim()?.takeIf { it.isNotEmpty() }
                            if (name.isNotBlank() || number.isNotBlank()) {
                                SimContactsDataSource.insertSimContact(app, name, number, email, subId)
                            }
                        }
                        // Refresh unified list
                        withContext(Dispatchers.Main) { loadContacts() }
                    } else {
                        contacts.forEach { contact ->
                            val toSave = contact.copy(
                                accountName = accountName,
                                accountType = accountType
                            )
                            toSave.save(app, toSave.details, ContactDetails.empty())
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ContactViewModel", "Error importing contacts", e)
                }
            }
            loadContacts()
            onDone()
        }
    }

    fun getContact(contactId: Long): Contact? {
        return contacts.value.find { it.id == contactId } ?: _allContacts.value.find { it.id == contactId }
    }

    override fun deleteContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isSimAccountType(contact.accountType)) {
                val sc = SimContactsDataSource.findBackingSimContact(getApplication(), contact)
                    ?: SimContact(-1, contact.name.value, contact.details.phoneNumbers.firstOrNull()?.number ?: "", contact.details.emails.firstOrNull()?.address, contact.accountName?.toIntOrNull())
                val toDelete = if (sc.subscriptionId == null) sc.copy(subscriptionId = contact.accountName?.toIntOrNull()) else sc
                SimContactsDataSource.deleteSimContact(getApplication(), toDelete)
                syncFromSystem()
            } else {
                com.vayunmathur.contacts.data.Contact.delete(getApplication(), contact)
                if (isCalendarSyncEnabled.value) {
                    CalendarSyncHelper.syncContact(getApplication(), contact.copy(details = contact.details.copy(dates = emptyList())))
                }
            }
            // The system-contacts ContentObserver picks up device deletes and re-syncs; SIM path already synced.
        }
    }

    // Groups Management
    override fun addGroup(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Groups.TITLE, name)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            }
            resolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        }
    }

    override fun deleteGroup(groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId), null, null)
        }
    }

    fun addContactsToGroup(contactIds: List<Long>, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // Filter out SIM contacts (they don't support groups)
            val deviceIds = contactIds.filter { id ->
                val c = _allContacts.value.find { it.id == id }
                c == null || !isSimAccountType(c.accountType)
            }
            if (deviceIds.isEmpty()) return@launch
            val resolver = getApplication<Application>().contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            deviceIds.forEach { contactId ->
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    .build())
            }
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error adding contacts to group", e)
            }
        }
    }

    fun removeContactsFromGroup(contactIds: List<Long>, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            contactIds.forEach { contactId ->
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?", 
                        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()))
                    .build())
            }
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error removing contacts from group", e)
            }
        }
    }

    fun getContactsForGroup(groupId: Long): Flow<List<Contact>> {
        return combine(contacts, contactGroupMemberships) { contacts, memberships ->
            val contactIds = memberships.filter { it.groupId == groupId }.map { it.contactId }
            contacts.filter { it.id in contactIds }
        }
    }

    fun getContactFlow(contactId: Long): Flow<Contact?> {
        return contacts.map { contacts -> contacts.find { it.id == contactId } ?: _allContacts.value.find { it.id == contactId } }
    }

    override fun saveContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) { persistContact(contact) }
    }

    /** Writes [contact] to the SIM or the contacts provider. Returns false if the write failed. */
    internal suspend fun persistContact(contact: com.vayunmathur.contacts.data.Contact): Boolean {
        if (isSimAccountType(contact.accountType)) {
            val subId = contact.accountName?.toIntOrNull()
            val name = contact.name.value.trim().ifEmpty { contact.nickname.nickname.trim().ifEmpty { contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: "" } }
            val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
            val email = contact.details.emails.firstOrNull()?.address?.trim()?.takeIf { it.isNotEmpty() }
            if (name.isBlank() && number.isBlank()) {
                Log.w("ContactViewModel", "SIM save skipped: name and number empty")
                return false
            }
            val isExisting = contact.id < 0
            var oldSc: SimContact? = null
            if (isExisting) {
                oldSc = SimContactsDataSource.listSimContacts(getApplication()).firstOrNull { SimContactsDataSource.syntheticIdFor(it) == contact.id }
                if (oldSc == null) oldSc = SimContactsDataSource.findBackingSimContact(getApplication(), contact)
                if (oldSc != null) {
                    // Skip if no actual change
                    if (oldSc.name == name && oldSc.number == number && oldSc.emails == email && oldSc.subscriptionId == subId) {
                        return true
                    }
                    // Edit in place where possible. Moving a contact to a different SIM is not an
                    // in-place edit, so that still falls through to delete-then-insert.
                    if (subId == null || subId == oldSc.subscriptionId) {
                        if (SimContactsDataSource.updateSimContact(getApplication(), oldSc, name, number, email, subId)) {
                            syncFromSystem()
                            return true
                        }
                    }
                    SimContactsDataSource.deleteSimContact(getApplication(), oldSc)
                }
            }
            val ok = SimContactsDataSource.insertSimContact(getApplication(), name, number, email, subId)
            if (!ok) Log.e("ContactViewModel", "Failed to insert SIM contact")
            syncFromSystem()
            return ok
        }
        val contactId = contact.id
        val details = contact.details
        val oldDetails = contacts.value.find { it.id == contactId }?.details
            ?: _allContacts.value.find { it.id == contactId }?.details
            ?: com.vayunmathur.contacts.data.ContactDetails.empty()
        return contact.save(getApplication(), details, oldDetails)
    }

    // ---------------------------------------------------------------------
    // Base64 photo decode cache.
    // ---------------------------------------------------------------------

    private val photoCache = LruCache<String, Bitmap>(32)

    /**
     * Returns the decoded [Bitmap] for the Base64-encoded contact photo, or
     * `null` if decoding fails. Decodes at most once per unique input string
     * across the entire app lifetime (subject to LRU eviction).
     */
    @Synchronized
    override fun decodePhoto(base64: String): Bitmap? {
        photoCache.get(base64)?.let { return it }
        return try {
            val bytes = Base64.decode(base64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                photoCache.put(base64, it)
            }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error decoding contact photo", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // EditContactPage form draft state.
    // ---------------------------------------------------------------------

    data class ContactDraft(
        val namePrefix: String = "",
        val firstName: String = "",
        val middleName: String = "",
        val lastName: String = "",
        val nameSuffix: String = "",
        val company: String = "",
        val noteContent: String = "",
        val nickname: String = "",
        val photo: Photo? = null,
        val birthday: LocalDate? = null,
        val accountName: String = "",
        val accountType: String = "",
        val phoneNumbers: List<PhoneNumber> = emptyList(),
        val emails: List<Email> = emptyList(),
        val dates: List<Event> = emptyList(),
        val addresses: List<Address> = emptyList(),
        val groupMemberships: List<GroupMembership> = emptyList(),
    )

    internal val _editDraft = MutableStateFlow<ContactDraft?>(null)
    val editDraft: StateFlow<ContactDraft?> = _editDraft.asStateFlow()

    /** Original contact loaded into the current draft, if any. */
    internal var editingOriginal: Contact? = null
    /** Tracks which contactId the draft was initialized for. `null` = new contact. */
    internal var editingContactId: Long? = null
    /** True once a draft has been initialized at all (distinguishes "new contact" from "uninitialized"). */
    internal var editingInitialized: Boolean = false

    internal fun normalizePhoneForCompare(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }.trim()
    }
}
