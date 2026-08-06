@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.messages.signal

import kotlin.concurrent.atomics.*
import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.signal.auth.PreKeyManager
import com.vayunmathur.messages.signal.auth.Provisioning
import com.vayunmathur.messages.signal.contacts.ContactDiscovery
import com.vayunmathur.messages.signal.contacts.ContactManager
import com.vayunmathur.messages.signal.contacts.ProfileManager
import com.vayunmathur.messages.signal.contacts.StorageServiceManager
import com.vayunmathur.messages.signal.groups.GroupManager
import com.vayunmathur.messages.signal.groups.SenderKeyManager
import com.vayunmathur.messages.signal.media.AttachmentManager
import com.vayunmathur.messages.signal.media.BackupManager
import com.vayunmathur.messages.signal.media.BackupRestore
import com.vayunmathur.messages.signal.receiving.ContentDispatcher
import com.vayunmathur.messages.signal.receiving.DecryptedMessage
import com.vayunmathur.messages.signal.receiving.EnvelopeDecryptor
import com.vayunmathur.messages.signal.receiving.MessageContent
import com.vayunmathur.messages.signal.sending.ContentBuilders
import com.vayunmathur.messages.signal.sending.DeviceManager
import com.vayunmathur.messages.signal.sending.MessageSender
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalIdentityKeyStore
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.store.SignalRecipientEntity
import com.vayunmathur.messages.signal.store.SignalSenderKeyStore
import com.vayunmathur.messages.signal.store.SignalSessionStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.proto.WebSocketProtos
import com.vayunmathur.messages.data.buildMessagesDatabase
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SignalClient {

    private const val TAG = "SignalClient"
    private const val MESSAGE_DELETE_MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours
    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingQrScan(val qrUrl: String) : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.SIGNAL

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Application
    private var authData: SignalAuthData? = null
    private var webSocket: SignalWebSocket? = null
    private var unauthedWebSocket: SignalWebSocket? = null
    private var backfillJob: Job? = null
    private var provisioningJob: Job? = null
    private var keyCheckJob: Job? = null

    private var db: SignalDatabase? = null
    private var sessionStore: SignalSessionStore? = null
    private var identityKeyStore: SignalIdentityKeyStore? = null
    private var preKeyStore: SignalPreKeyStore? = null
    private var pniPreKeyStore: SignalPreKeyStore? = null
    private var senderKeyStore: SignalSenderKeyStore? = null
    private var messageSender: MessageSender? = null
    private var contactManager: ContactManager? = null
    private var profileManager: ProfileManager? = null
    private var groupManager: GroupManager? = null
    private var storageServiceManager: StorageServiceManager? = null
    private var contactDiscovery: ContactDiscovery? = null
    private var recipientStore: SignalRecipientStore? = null
    var syncContactsOnConnect: Boolean = false
    private var lastContactSyncTime: Long = 0L
    private var lastContactRequestTime: Long = 0L
    private val CONTACT_SYNC_THRESHOLD_MS = 3L * 24 * 60 * 60 * 1000 // 3 days (matching Go bridge)
    val encryptionLock = Mutex()

    // Idempotent receive: drop redelivered envelopes (server re-sends until ACKed)
    // so we don't reprocess/re-run the ratchet for an already-handled message.
    private val recentEnvelopeHashes: MutableMap<String, Boolean> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(512, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 4096
        })

    private val nameCache = ConcurrentHashMap<String, String>()

    fun isConnected(): Boolean {
        return webSocket?.isConnected == true && unauthedWebSocket?.isConnected == true
    }

    fun isLoggedIn(): Boolean {
        return authData?.isDeviceLoggedIn() == true
    }

    suspend fun getRemoteConfig(): String? {
        val ws = webSocket ?: return null
        return try {
            val response = ws.sendRequest("GET", "/v2/config")
            if (response.status in 200..299) response.body.toStringUtf8() else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get remote config: ${e.message}")
            null
        }
    }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext as Application
        Log.i(TAG, "init")
        scope.launch {
            val auth = SignalAuthData.load(appContext)
            if (auth != null) {
                authData = auth
                bootSession(auth)
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.load()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = SignalAuthData.load(appContext) ?: run {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            bootSession(auth)
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect — stopping Signal websockets")
        backfillJob?.cancel()
        keyCheckJob?.cancel()
        keyCheckJob = null
        webSocket?.disconnect()
        webSocket = null
        unauthedWebSocket?.disconnect()
        unauthedWebSocket = null
        messageSender = null
        contactManager = null
        contactDiscovery = null
        profileManager = null
        groupManager = null
        storageServiceManager = null
        _state.value = State.Disconnected("Disconnected")
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Signal session")
        disconnect()
        nameCache.clear()
        scope.launch { SignalAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    // QR auto-refresh: Provisioning.startProvisioning() emits an Error and completes
    // if the session times out or the websocket drops, so each completed-without-success
    // attempt simply starts a fresh session (new QR). We only surface Disconnected once
    // the retry budget is exhausted.
    fun startProvisioning() {
        _state.value = State.Connecting
        provisioningJob?.cancel()
        provisioningJob = scope.launch {
            var qrRetryCount = 0
            val maxQrRetries = 6

            while (qrRetryCount < maxQrRetries) {
                var gotSuccess = false
                var lastError: String? = null
                try {
                    // Re-enabled backup ("link'n'sync"): the QR advertises backup4,backup5
                    // so the primary uploads an ephemeral backup and hands us the
                    // ephemeralBackupKey. The 409 we previously saw was a missing
                    // capability (usernameChangeSyncMessage), now fixed — not backup.
                    // bootSession picks up the key and runs the restore pipeline.
                    Provisioning.startProvisioning(appContext, allowBackup = true).collect { event ->
                        when (event) {
                            is Provisioning.ProvisioningEvent.QrUrl -> {
                                Log.d(TAG, "QR ready (attempt ${qrRetryCount + 1}/$maxQrRetries)")
                                _state.value = State.AwaitingQrScan(event.url)
                            }
                            is Provisioning.ProvisioningEvent.Success -> {
                                gotSuccess = true
                                val data = event.deviceData
                                val auth = SignalAuthData(
                                    aci = data.aci,
                                    pni = data.pni,
                                    deviceId = data.deviceId,
                                    number = data.number,
                                    password = data.password,
                                    aciIdentityKeyPair = data.aciIdentityKeyPair,
                                    pniIdentityKeyPair = data.pniIdentityKeyPair,
                                    aciRegistrationId = data.aciRegistrationId,
                                    pniRegistrationId = data.pniRegistrationId,
                                    masterKey = data.masterKey,
                                    accountEntropyPool = data.accountEntropyPool,
                                    ephemeralBackupKey = data.ephemeralBackupKey,
                                    mediaRootBackupKey = data.mediaRootBackupKey,
                                )
                                auth.save(appContext)
                                authData = auth
                                bootSession(auth)
                            }
                            is Provisioning.ProvisioningEvent.Error -> {
                                lastError = event.message
                                Log.e(TAG, "Provisioning error: ${event.message}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(TAG, "Provisioning attempt failed", e)
                }

                if (gotSuccess) return@launch

                qrRetryCount++
                if (qrRetryCount >= maxQrRetries) {
                    _state.value = State.Disconnected("Provisioning failed: ${lastError ?: "QR not scanned in time"}")
                    return@launch
                }
                Log.d(TAG, "Provisioning attempt ended (lastError=$lastError), refreshing QR ($qrRetryCount/$maxQrRetries)")
            }
        }
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.textMessage(body, timestamp)

        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = resolveGroupInfo(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
            // Known group thread but members couldn't be resolved — fail rather
            // than misrouting to the DM path (which would target the group id as
            // an ACI and 404).
            Log.w(TAG, "sendMessage: could not resolve group $groupId")
            return false
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        return try {
            val ws = webSocket ?: return false
            val pointer = AttachmentManager.upload(ws, bytes, mime, fileName) ?: return false
            val sender = messageSender ?: return false
            val timestamp = System.currentTimeMillis()
            val dataMessage = SignalServiceProtos.DataMessage.newBuilder()
                .setTimestamp(timestamp)
                .addAttachments(pointer)
            if (!caption.isNullOrBlank()) dataMessage.setBody(caption)
            val content = SignalServiceProtos.Content.newBuilder()
                .setDataMessage(dataMessage.build())
                .build()

            val groupId = extractGroupIdFromConversation(conversationId)
            if (groupId != null) {
                val group = groupManager?.getCachedGroup(groupId)
                if (group != null) {
                    val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                    return results.any { it.success }
                }
            }

            val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
            val result = sender.sendMessage(recipientAci, content, timestamp)
            result.success
        } catch (t: Throwable) {
            Log.w(TAG, "sendMedia failed: ${t.message}")
            false
        }
    }

    // --- Shared media-send contracts (MEDIA_FEATURES_CONTRACTS §2) ---
    // Image & file flow through the existing sendMedia(); location flows through sendMessage()
    // in MessagesSessionManager (FindFamily/maps URL as text) — neither needs a new method.
    // Poll is the only Signal-specific addition required by the contract.

    /**
     * Canonical poll contract (MEDIA_FEATURES_CONTRACTS §2b). Signal DOES support polls in
     * this app (ContentBuilders.pollCreateMessage / DataMessage.PollCreate), so we wrap the
     * existing [createPoll]. Note the Go bridge marks polls CapLevelRejected, but the native
     * Signal protocol supports them and this client implements send.
     */
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        return createPoll(conversationId, question, options, allowMultiple)
    }

    // Issue #12: Group read receipt timestamps by sender ACI, send per-sender ReceiptMessage
    suspend fun markRead(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        return try {
            val messagesDb = buildMessagesDatabase(appContext)
            val messages = messagesDb.messageDao().observeForConversation(conversationId).first()
            if (messages.isEmpty()) return true

            // Send read receipt to the conversation peer for all incoming messages
            val incomingTimestamps = messages
                .filter { it.direction == com.vayunmathur.messages.data.MessageDirection.INCOMING }
                .map { it.timestamp }
            if (incomingTimestamps.isEmpty()) return true

            val peerAci = extractAci(conversationId) ?: return true
            val recipientAci = resolveRecipient(peerAci) ?: return true
            val content = ContentBuilders.readReceipt(incomingTimestamps)
            sender.sendMessage(recipientAci, content, System.currentTimeMillis())

            // Send SyncMessage.Read to sync read state to other devices
            try {
                val syncContent = ContentBuilders.syncReadMessage(peerAci, incomingTimestamps)
                sender.sendSelfSyncMessage(syncContent, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send sync read: ${t.message}")
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed: ${t.message}")
            false
        }
    }

    /**
     * Integrator read-receipt contract: propagate a READ receipt to the
     * sender(s) of incoming messages in [conversationId] up to [lastTimestamp],
     * gated on the account's read-receipts setting. Also syncs read state to our
     * own linked devices (that sync is NOT gated — it only tells our other
     * devices what we've seen). Returns true if a receipt was sent, false if the
     * setting is off, nothing is unread, or on error.
     */
    suspend fun sendReadReceipt(
        conversationId: String,
        lastMessageId: String?,
        lastTimestamp: Long,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        // Gate on the Signal read-receipts setting. Only skip when we positively
        // know it is disabled (mirrors MessageSender's final gate, which sends
        // when the account record is unknown).
        val acct = parseAccountRecord()
        if (acct != null && !acct.readReceipts) {
            Log.d(TAG, "sendReadReceipt: read receipts disabled, skipping")
            return false
        }
        return try {
            val messagesDb = buildMessagesDatabase(appContext)
            val messages = messagesDb.messageDao().observeForConversation(conversationId).first()
            val peerAci = extractAci(conversationId)
            val incoming = messages.filter {
                it.direction == com.vayunmathur.messages.data.MessageDirection.INCOMING &&
                    it.timestamp <= lastTimestamp
            }
            if (incoming.isEmpty()) return false
            // A READ receipt is addressed to the author of the messages being
            // read. For a group thread that is each distinct sender; for a DM it
            // is the peer. Fall back to the thread peer when a row has no sender.
            val bySender = incoming.groupBy { it.senderId ?: peerAci }
            var anySent = false
            for ((senderAci, senderMsgs) in bySender) {
                val aci = senderAci ?: continue
                val resolved = resolveRecipient(aci) ?: continue
                val timestamps = senderMsgs.map { it.timestamp }
                val content = ContentBuilders.readReceipt(timestamps)
                if (sender.sendMessage(resolved, content, System.currentTimeMillis()).success) {
                    anySent = true
                }
                // Sync read state to our own other devices.
                try {
                    val syncContent = ContentBuilders.syncReadMessage(aci, timestamps)
                    sender.sendSelfSyncMessage(syncContent, System.currentTimeMillis())
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to sync read state for $aci: ${t.message}")
                }
            }
            anySent
        } catch (t: Throwable) {
            Log.w(TAG, "sendReadReceipt failed: ${t.message}")
            false
        }
    }

    private fun parseAccountRecord(): com.vayunmathur.messages.signal.proto.AccountRecord? = try {
        authData?.accountRecord?.let {
            com.vayunmathur.messages.signal.proto.AccountRecord.parseFrom(it)
        }
    } catch (_: Exception) {
        null
    }

    // Fix #2: deleteThread — add SyncMessage DeleteForMe sync to Signal (supports DM and group)
    suspend fun deleteThread(conversationId: String, fromMessageRequest: Boolean = false): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        val aci = extractAci(conversationId) ?: return false

        // Build ConversationIdentifier — handle both DM and group conversations
        val groupId = extractGroupIdFromConversation(conversationId)
        val convId = if (groupId != null) {
            val groupIdBytes = Base64.decode(groupId, Base64.DEFAULT)
            SignalServiceProtos.ConversationIdentifier.newBuilder()
                .setThreadGroupId(
                    com.google.protobuf.ByteString.copyFrom(groupIdBytes)
                ).build()
        } else {
            SignalServiceProtos.ConversationIdentifier.newBuilder()
                .setThreadServiceIdBinary(
                    com.google.protobuf.ByteString.copyFrom(MessageSender.uuidToBytes(aci))
                ).build()
        }

        // Matching Go: HandleMatrixDeleteChat sends MessageRequestResponse DELETE when FromMessageRequest
        if (fromMessageRequest && groupId == null) {
            val syncContent = ContentBuilders.messageRequestResponseSync(
                threadAci = aci,
                type = SignalServiceProtos.SyncMessage.MessageRequestResponse.Type.DELETE,
            )
            try {
                sender.sendSelfSyncMessage(syncContent, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send message request delete sync: ${t.message}")
            }
        }

        // Query last 5 messages for the anchor
        val mostRecentMessages = emptyList<SignalServiceProtos.AddressableMessage>()

        val deleteContent = ContentBuilders.deleteForMeSyncMessage(
            conversationId = convId,
            mostRecentMessages = mostRecentMessages,
            isFullDelete = true,
        )

        // Send to self as sync message
        try {
            sender.sendSelfSyncMessage(deleteContent, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send DeleteForMe sync: ${t.message}")
        }

        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    /**
     * Block a conversation (MEDIA_FEATURES_CONTRACTS §4). For Signal, blocking a message
     * request is a DELETE: deleteThread(fromMessageRequest=true) sends the
     * MessageRequestResponse DELETE sync + DeleteForMe and removes the thread locally.
     */
    suspend fun blockConversation(conversationId: String): Boolean {
        return deleteThread(conversationId, fromMessageRequest = true)
    }

    // Fix #9: Message deletion with 24h time restriction and author verification
    suspend fun deleteMessage(
        messageId: String,
        conversationId: String,
        messageTimestamp: Long,
        messageAuthorAci: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        val targetTimestamp = messageId.substringAfterLast('_').toLongOrNull() ?: return false

        // Verify the message author is self (matching Go: only allow self-deletes)
        if (messageAuthorAci != null && messageAuthorAci != auth.aci) {
            Log.w(TAG, "Cannot delete other people's messages")
            return false
        }

        // Enforce 24h delete window (matching Go bridge capabilities: DeleteMaxAge = 24h)
        val age = System.currentTimeMillis() - messageTimestamp
        if (age > MESSAGE_DELETE_MAX_AGE_MS) {
            Log.w(TAG, "Cannot delete message older than 24 hours (age=${age}ms)")
            return false
        }

        val content = ContentBuilders.deleteMessage(targetTimestamp)
        val timestamp = System.currentTimeMillis()

        // Group deletes (matching Go: HandleMatrixMessageRemove sends to group)
        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Issue #11: Add messageAuthorAci parameter for correct reaction targeting
    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        add: Boolean,
        messageAuthorAci: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val targetTimestamp = messageId.substringAfterLast('_').toLongOrNull() ?: return false
        val timestamp = System.currentTimeMillis()

        // Group reactions (matching Go: HandleMatrixReaction sends to group)
        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val authorAci = messageAuthorAci ?: authData?.aci ?: return false
                val content = ContentBuilders.reactionMessage(emoji, authorAci, targetTimestamp, !add, timestamp)
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val authorAci = messageAuthorAci ?: authData?.aci ?: return false
        val content = ContentBuilders.reactionMessage(emoji, authorAci, targetTimestamp, !add, timestamp)
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #10: Group typing support (include GroupIdentifier)
    suspend fun sendTyping(conversationId: String, isTyping: Boolean = true): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val aci = extractAci(conversationId) ?: return false

        // Check if this is a group conversation
        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val groupIdBytes = Base64.decode(groupId, Base64.DEFAULT)
                val content = ContentBuilders.typingMessage(isTyping, System.currentTimeMillis(), groupIdBytes)
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, System.currentTimeMillis())
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(aci) ?: return false
        val content = ContentBuilders.typingMessage(isTyping, System.currentTimeMillis())
        val result = sender.sendMessage(recipientAci, content, System.currentTimeMillis())
        return result.success
    }

    suspend fun sendStopTyping(conversationId: String): Boolean {
        return sendTyping(conversationId, isTyping = false)
    }

    fun fetchMessages(conversationId: String, count: Int = 50) {
        // Signal doesn't support fetching history for linked devices
    }

    suspend fun sendNewThread(recipients: List<String>, body: String): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val sender = messageSender ?: return null
        val recipientAci = resolveRecipient(recipients.first()) ?: return null
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.textMessage(body, timestamp)
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return if (result.success) "${source.idPrefix}:$recipientAci" else null
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        return contactManager?.searchContacts(query) ?: emptyList()
    }

    // Issue #7: Edit messages — wraps EditMessage around a DataMessage with new body
    suspend fun sendEdit(
        conversationId: String,
        originalMessageId: String,
        newBody: String,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val targetTimestamp = originalMessageId.substringAfterLast('_').toLongOrNull() ?: return false
        val editTimestamp = System.currentTimeMillis()
        val content = ContentBuilders.editMessage(targetTimestamp, newBody, editTimestamp)

        // Group edits (matching Go: HandleMatrixEdit sends to group)
        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, editTimestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, editTimestamp)
        return result.success
    }

    // Issue #10: Accept message request by sending profileKey to the requester
    suspend fun acceptMessageRequest(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val auth = authData ?: return false
        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false

        // Send sync message
        val syncContent = ContentBuilders.messageRequestResponseSync(
            threadAci = recipientAci,
            type = SignalServiceProtos.SyncMessage.MessageRequestResponse.Type.ACCEPT,
        )
        try {
            sender.sendSelfSyncMessage(syncContent, System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send message request accept sync: ${t.message}")
        }

        // Send profileKey DataMessage to the requester (matching Go: sends ProfileKey + PNI signature)
        val profileKey = recipientStore?.getRecipient(auth.aci)?.profileKey
        val timestamp = System.currentTimeMillis()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setFlags(SignalServiceProtos.DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE)
            .setTimestamp(timestamp)
            .setRequiredProtocolVersion(0)
        if (profileKey != null) {
            dm.setProfileKey(com.google.protobuf.ByteString.copyFrom(profileKey))
        }

        // Only attach PNI signature when phone number sharing mode is EVERYBODY (matching Go bridge)
        val contentBuilder = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())

        val acctRecord = try {
            auth.accountRecord?.let { com.vayunmathur.messages.signal.proto.AccountRecord.parseFrom(it) }
        } catch (_: Exception) { null }
        val pniKeyPair = try { IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP)) } catch (_: Exception) { null }
        val aciKeyPair = try { IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP)) } catch (_: Exception) { null }
        if (acctRecord?.phoneNumberSharingMode == com.vayunmathur.messages.signal.proto.AccountRecord.PhoneNumberSharingMode.EVERYBODY
            && pniKeyPair != null && aciKeyPair != null) {
            val sig = pniKeyPair.privateKey.calculateSignature(aciKeyPair.publicKey.serialize())
            val pniBytes = MessageSender.uuidToBytes(auth.pni)
            contentBuilder.setPniSignatureMessage(
                SignalServiceProtos.PniSignatureMessage.newBuilder()
                    .setPni(com.google.protobuf.ByteString.copyFrom(pniBytes))
                    .setSignature(com.google.protobuf.ByteString.copyFrom(sig))
                    .build()
            )
        }

        val content = contentBuilder.build()

        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #4: Group metadata changes
    // Issue #8: Real group name change using GroupManager
    suspend fun setGroupName(groupId: String, newName: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.setGroupName(groupId, newName)
    }

    suspend fun setGroupAvatar(groupId: String, avatarBytes: ByteArray): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.uploadGroupAvatar(groupId, avatarBytes)
    }

    suspend fun setGroupDescription(groupId: String, newDescription: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.setGroupDescription(groupId, newDescription)
    }

    // Expose setMemberRole (Go: HandleMatrixPowerLevels → ModifyMemberRoleAction)
    suspend fun setMemberRole(groupId: String, memberAci: String, isAdmin: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        val role = if (isAdmin) GroupManager.MemberRole.ADMINISTRATOR else GroupManager.MemberRole.DEFAULT
        return gm.setMemberRole(groupId, memberAci, role)
    }

    // Accept invite (PromotePendingMemberAction)
    suspend fun acceptInvite(groupId: String, serviceId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.acceptInvite(groupId, serviceId)
    }

    // Revoke invite (DeletePendingMemberAction)
    suspend fun revokeInvite(groupId: String, serviceId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.revokeInvite(groupId, serviceId)
    }

    // Approve knock request (PromoteRequestingMemberAction)
    suspend fun approveKnock(groupId: String, memberAci: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.approveKnock(groupId, memberAci)
    }

    // Deny knock request (DeleteRequestingMemberAction)
    suspend fun denyKnock(groupId: String, memberAci: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.denyKnock(groupId, memberAci)
    }

    // Leave group (matching Go: HandleMatrixMembership Leave)
    suspend fun leaveGroup(groupId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.leaveGroup(groupId)
    }

    // Kick member (matching Go: HandleMatrixMembership Kick)
    suspend fun kickMember(groupId: String, memberAci: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.kickMember(groupId, memberAci)
    }

    // Invite member (matching Go: HandleMatrixMembership Invite)
    suspend fun inviteMember(groupId: String, serviceId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.inviteMember(groupId, serviceId)
    }

    // Ban member (matching Go: HandleMatrixMembership Ban)
    suspend fun banMember(groupId: String, serviceId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.banMember(groupId, serviceId)
    }

    // Unban member (matching Go: HandleMatrixMembership Unban)
    suspend fun unbanMember(groupId: String, serviceId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.unbanMember(groupId, serviceId)
    }

    // Set group announcements-only mode (matching Go: HandleMatrixPowerLevels → ModifyAnnouncementsOnly)
    suspend fun setAnnouncementsOnly(groupId: String, announcementsOnly: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        return gm.setAnnouncementsOnly(groupId, announcementsOnly)
    }

    // Set group attributes access (matching Go: HandleMatrixPowerLevels → ModifyAttributesAccess)
    suspend fun setAttributesAccess(groupId: String, adminOnly: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        val access = if (adminOnly) GroupManager.AccessControl.ADMINISTRATOR else GroupManager.AccessControl.MEMBER
        return gm.setAttributesAccess(groupId, access)
    }

    // Set group member access (matching Go: HandleMatrixPowerLevels → ModifyMemberAccess)
    suspend fun setMemberAccess(groupId: String, adminOnly: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        val gm = groupManager ?: return false
        val access = if (adminOnly) GroupManager.AccessControl.ADMINISTRATOR else GroupManager.AccessControl.MEMBER
        return gm.setMemberAccess(groupId, access)
    }

    // Send viewed receipt (Go: HandleMatrixViewingChat sends VIEWED receipt)
    suspend fun sendViewedReceipt(recipientAci: String, timestamps: List<Long>): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        return try {
            val resolved = resolveRecipient(recipientAci) ?: return false
            val content = ContentBuilders.viewedReceipt(timestamps)
            val result = sender.sendMessage(resolved, content, System.currentTimeMillis())
            result.success
        } catch (t: Throwable) {
            Log.w(TAG, "sendViewedReceipt failed: ${t.message}")
            false
        }
    }

    // Fix #6: Disappearing messages
    suspend fun setDisappearingTimer(conversationId: String, expirationSeconds: Int): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val aci = extractAci(conversationId) ?: return false
        val groupId = extractGroupIdFromConversation(conversationId)

        val timestamp = System.currentTimeMillis()

        // For groups, use GroupChange (matching Go: HandleMatrixDisappearingTimer uses GroupChange for groups)
        if (groupId != null) {
            val gm = groupManager ?: return false
            val success = gm.setDisappearingTimer(groupId, expirationSeconds)
            if (success) return true
            // Fallback to DataMessage if GroupChange fails
            val group = gm.getCachedGroup(groupId)
            if (group != null) {
                val content = ContentBuilders.disappearingTimerMessage(expirationSeconds, timestamp)
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
            return false
        }

        val content = ContentBuilders.disappearingTimerMessage(expirationSeconds, timestamp)
        val recipientAci = resolveRecipient(aci) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #7: Poll creation
    suspend fun createPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean = false,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val timestamp = System.currentTimeMillis()
        val content = ContentBuilders.pollCreateMessage(question, options, allowMultiple, timestamp)

        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // Fix #7: Poll voting
    suspend fun votePoll(
        conversationId: String,
        targetAuthorAci: String,
        targetTimestamp: Long,
        optionIndexes: List<Int>,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val sender = messageSender ?: return false
        val timestamp = System.currentTimeMillis()
        val authorBytes = MessageSender.uuidToBytes(targetAuthorAci)
        val content = ContentBuilders.pollVoteMessage(authorBytes, targetTimestamp, optionIndexes, timestamp)

        val groupId = extractGroupIdFromConversation(conversationId)
        if (groupId != null) {
            val group = groupManager?.getCachedGroup(groupId)
            if (group != null) {
                val results = sender.sendGroupMessage(groupId, group.memberAcis, content, timestamp)
                return results.any { it.success }
            }
        }

        val recipientAci = resolveRecipient(extractAci(conversationId) ?: return false) ?: return false
        val result = sender.sendMessage(recipientAci, content, timestamp)
        return result.success
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private suspend fun bootSession(auth: SignalAuthData) {
        _state.value = State.Connecting
        try {
            SignalHttpClient.init(appContext)
            val database = SignalDatabase.getInstance(appContext)
            db = database

            val sessStore = SignalSessionStore(database)
            val idStore = SignalIdentityKeyStore(
                database,
                auth.aciIdentityKeyPair,
                auth.aciRegistrationId,
            )
            val pkStore = SignalPreKeyStore(database, SignalPreKeyStore.SERVICE_ACI)
            val pniPkStore = SignalPreKeyStore(database, SignalPreKeyStore.SERVICE_PNI)
            val skStore = SignalSenderKeyStore(database)
            sessionStore = sessStore
            identityKeyStore = idStore
            preKeyStore = pkStore
            pniPreKeyStore = pniPkStore
            senderKeyStore = skStore

            val basicAuth = "${auth.aci}.${auth.deviceId}:${auth.password}"

            if (webSocket != null) {
                webSocket?.disconnect()
                webSocket = null
            }
            if (unauthedWebSocket != null) {
                unauthedWebSocket?.disconnect()
                unauthedWebSocket = null
            }

            val ws = SignalWebSocket(
                appContext,
                android.util.Base64.encodeToString(
                    basicAuth.toByteArray(),
                    android.util.Base64.NO_WRAP,
                ),
            )
            ws.connect("wss://chat.signal.org/v1/websocket/")
            webSocket = ws

            val unauthedWs = SignalWebSocket(appContext)
            unauthedWs.connect("wss://chat.signal.org/v1/websocket/")
            unauthedWebSocket = unauthedWs

            val devManager = DeviceManager(ws, sessStore, idStore, pkStore, pkStore, pkStore, skStore)
            val recipientStore = SignalRecipientStore(database)
            this.recipientStore = recipientStore
            val groupStore = SignalGroupStore(database)
            val aciKeyPair = try { IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP)) } catch (_: Exception) { null }
            val pniKeyPair = try { IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP)) } catch (_: Exception) { null }
            val acctRecord = try {
                auth.accountRecord?.let { com.vayunmathur.messages.signal.proto.AccountRecord.parseFrom(it) }
            } catch (_: Exception) { null }
            val sender = MessageSender(ws, sessStore, idStore, pkStore, pkStore, pkStore, skStore, auth.aci, auth.deviceId, devManager, recipientStore, unauthedWs, groupStore, auth.pni, aciKeyPair, pniKeyPair, acctRecord, encryptionLock)
            messageSender = sender
            contactManager = ContactManager(recipientStore)
            contactDiscovery = ContactDiscovery(recipientStore, ws, appContext)
            profileManager = ProfileManager(unauthedWs, recipientStore)
            groupManager = GroupManager(ws, groupStore, recipientStore, auth.aci, auth.pni, auth.password)
            groupManager?.messageSender = sender
            groupManager?.profileManager = profileManager
            storageServiceManager = StorageServiceManager(recipientStore, groupStore, ws) { accountRecord ->
                val current = authData ?: return@StorageServiceManager
                val updated = current.copy(accountRecord = accountRecord.toByteArray())
                updated.save(appContext)
                authData = updated
            }

            ws.incomingRequestHandler = { request ->
                handleIncomingRequest(request, auth, sessStore, idStore, pkStore, pniPkStore, skStore)
            }

            _state.value = State.Connected
            Log.i(TAG, "Connected to Signal")

            scope.launch {
                try {
                    ws.sendRequest(
                        "PUT",
                        "/v1/devices/capabilities",
                        Provisioning.signalCapabilities.toString().toByteArray(),
                    )
                    Log.d(TAG, "Successfully registered capabilities")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to register capabilities: ${t.message}")
                }
            }

            scope.launch {
                try {
                    val aciKeyPair = IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP))
                    val pniKeyPair = IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP))
                    PreKeyManager.generateAndUploadPreKeys(ws, pkStore, pniPkStore, aciKeyPair, pniKeyPair)
                } catch (t: Throwable) {
                    Log.w(TAG, "Initial pre-key upload failed: ${t.message}")
                }
            }

            keyCheckJob?.cancel()
            keyCheckJob = scope.launch {
                try {
                    val aciKeyPair = IdentityKeyPair(Base64.decode(auth.aciIdentityKeyPair, Base64.NO_WRAP))
                    val pniKeyPair = IdentityKeyPair(Base64.decode(auth.pniIdentityKeyPair, Base64.NO_WRAP))
                    PreKeyManager.keyCheckLoop(ws, pkStore, pniPkStore, aciKeyPair, pniKeyPair)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Key check loop terminated: ${t.message}")
                    if (t.message?.contains("422") == true) {
                        _state.value = State.Disconnected("Logged out")
                        stop()
                    }
                }
            }

            if (syncContactsOnConnect) {
                val now = System.currentTimeMillis()
                if (lastContactSyncTime == 0L || (now - lastContactSyncTime) > CONTACT_SYNC_THRESHOLD_MS) {
                    lastContactSyncTime = now
                    kickoffBackfill()
                } else {
                    Log.d(TAG, "Skipping contact sync, last sync was ${(now - lastContactSyncTime) / 1000}s ago")
                }
            }

            if (auth.masterKey == null) {
                scope.launch {
                    try {
                        sendStorageMasterKeyRequest()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Storage key sync request failed: ${t.message}")
                    }
                }
            } else {
                // We already have the storage-service master key: pull contacts/groups/account
                // records from storage service. Ref signalmeow StorageService sync.
                syncStorageIfPossible()
            }

            // Phase 2-6: one-shot "link & sync" history restore. If the primary handed us an
            // ephemeralBackupKey during linking, download + decrypt + replay the backup so
            // prior conversations/messages/media appear. Clear the key after success so it
            // only runs once. recipientStore/groupManager are already constructed above.
            if (auth.ephemeralBackupKey != null) {
                kickoffBackupRestore(auth, ws, database, recipientStore)
            }

            // Surface any groups we already know about (from a prior storage sync / restore)
            // as conversations, so group threads appear without waiting for a new message.
            syncGroupConversations()

            scope.launch {
                var debounceJob: Job? = null
                ws.connectionEvents.collect { event ->
                    when (event) {
                        is SignalWebSocket.ConnectionEvent.Connected -> {
                            debounceJob?.cancel()
                            debounceJob = null
                            _state.value = State.Connected
                        }
                        is SignalWebSocket.ConnectionEvent.Disconnected -> {
                            // Debounce disconnect events (matching Go: 7-second debounce)
                            if (debounceJob == null) {
                                debounceJob = scope.launch {
                                    delay(7_000L)
                                    _state.value = State.Disconnected(event.reason)
                                    debounceJob = null
                                }
                            }
                        }
                        is SignalWebSocket.ConnectionEvent.LoggedOut -> {
                            debounceJob?.cancel()
                            _state.value = State.Disconnected("Logged out")
                            stop()
                        }
                        is SignalWebSocket.ConnectionEvent.Error -> {
                            debounceJob?.cancel()
                            _state.value = State.Disconnected(event.reason)
                        }
                        is SignalWebSocket.ConnectionEvent.FatalError -> {
                            debounceJob?.cancel()
                            _state.value = State.Disconnected(event.reason)
                        }
                        is SignalWebSocket.ConnectionEvent.CleanShutdown -> {
                            debounceJob?.cancel()
                        }
                        is SignalWebSocket.ConnectionEvent.Connecting -> {}
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "bootSession failed", e)
            _state.value = State.Disconnected("Boot failed: ${e.message}")
        }
    }

    private fun handleIncomingRequest(
        request: WebSocketProtos.WebSocketRequestMessage,
        auth: SignalAuthData,
        sessStore: SignalSessionStore,
        idStore: SignalIdentityKeyStore,
        pkStore: SignalPreKeyStore,
        pniPkStore: SignalPreKeyStore,
        skStore: SignalSenderKeyStore,
    ) {
        if (request.verb == "PUT" && request.path == "/api/v1/queue/empty") {
            webSocket?.sendResponse(request.id, 200)
            // Trigger contact sync when queue is empty (matching Go: QueueEmpty event)
            scope.launch {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastContactRequestTime > 30_000L) {
                        lastContactRequestTime = now
                        kickoffBackfill()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to trigger contact sync on queue empty: ${t.message}")
                }
            }
            return
        }
        if (request.verb != "PUT" || request.path != "/api/v1/message") {
            webSocket?.sendResponse(request.id, 200)
            return
        }
        scope.launch {
            try {
                val envelope = SignalServiceProtos.Envelope.parseFrom(request.body)
                // Idempotency: identical envelope bytes mean a redelivery of an
                // already-processed message; ACK and drop to avoid ratchet desync
                // and duplicate events. Ref signalmeow receiving.go EventBuffer.
                val dedupKey = Base64.encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(request.body.toByteArray()),
                    Base64.NO_WRAP,
                )
                if (recentEnvelopeHashes.put(dedupKey, true) != null) {
                    Log.d(TAG, "Dropping duplicate redelivered envelope")
                    webSocket?.sendResponse(request.id, 200)
                    return@launch
                }
                // Serialize ratchet/session access: libsignal session state is not
                // safe under concurrent encrypt/decrypt for the same recipient.
                val result = encryptionLock.withLock {
                    EnvelopeDecryptor.decrypt(
                        envelope, sessStore, idStore, pkStore, pkStore, pkStore, skStore,
                        null, auth.aci, auth.deviceId,
                        selfPni = auth.pni,
                        pniPreKeyStore = pniPkStore,
                        pniSignedPreKeyStore = pniPkStore,
                        pniKyberPreKeyStore = pniPkStore,
                    )
                }

                if (result.error != null) {
                    Log.e(TAG, "Decryption failed from ${result.senderAci}:${result.senderDeviceId}", result.error)
                    _events.emit(GMEvent.DecryptionError(
                        source, result.senderAci, result.senderAci, result.senderDeviceId,
                        envelope.clientTimestamp, result.error.message
                    ))
                    webSocket?.sendResponse(request.id, 200)
                    return@launch
                }

                webSocket?.sendResponse(request.id, 200)

                if (result.content != null) {
                    // Process inbound SenderKeyDistributionMessage so subsequent
                    // SenderKey-encrypted group messages from this sender decrypt.
                    // Ref signalmeow/receiving.go + libsignal GroupSessionBuilder.
                    if (result.content.hasSenderKeyDistributionMessage()) {
                        try {
                            val skdm = org.signal.libsignal.protocol.message.SenderKeyDistributionMessage(
                                result.content.senderKeyDistributionMessage.toByteArray()
                            )
                            val senderAddress = org.signal.libsignal.protocol.SignalProtocolAddress(
                                result.senderAci, result.senderDeviceId
                            )
                            encryptionLock.withLock {
                                org.signal.libsignal.protocol.groups.GroupSessionBuilder(skStore)
                                    .process(senderAddress, skdm)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to process SKDM from ${result.senderAci}: ${e.message}")
                        }
                    }

                    val decrypted = ContentDispatcher.dispatch(
                        result.senderAci, result.senderDeviceId,
                        result.content, result.timestamp, result.serverTimestamp,
                        selfAci = auth.aci,
                    )
                    handleDecryptedMessage(decrypted)

                    // Capture inbound profile keys (carried on regular data messages) so we can
                    // fetch + populate the sender's profile. Ref signalmeow incomingDataMessage.
                    if (result.senderAci != auth.aci &&
                        result.content.hasDataMessage() &&
                        result.content.dataMessage.profileKey.size() == 32
                    ) {
                        captureProfileKey(
                            result.senderAci,
                            result.content.dataMessage.profileKey.toByteArray(),
                        )
                    }

                    if (result.senderAci != auth.aci && result.content.hasDataMessage()) {
                        messageSender?.sendDeliveryReceipt(
                            result.senderAci,
                            listOf(result.content.dataMessage.timestamp),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
                webSocket?.sendResponse(request.id, 200)
            }
        }
    }

    private suspend fun handleDecryptedMessage(msg: DecryptedMessage?) {
        if (msg == null) return
        val chatId = msg.senderAci
        val senderName = nameCache[msg.senderAci] ?: resolveDisplayName(msg.senderAci)

        when (val content = msg.content) {
            is MessageContent.TextMessage -> {
                val msgId = "${chatId}_${msg.timestamp}"
                if (content.groupId != null) {
                    emitGroupMessage(content.groupId, msgId, content.body, msg.senderAci, senderName, msg.timestamp)
                } else {
                    _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, content.body, false, msg.timestamp, senderName, senderId = msg.senderAci))
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, content.body, senderName, null, msg.timestamp))
                    _events.emit(GMEvent.ConversationUpdate(
                        source = source, conversationId = chatId,
                        peerName = senderName, peerPhone = null, avatarUrl = null,
                        lastPreview = content.body, lastTimestamp = msg.timestamp,
                        unreadCount = 1, conversationType = "Signal",
                    ))
                }
            }
            is MessageContent.Attachment -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = content.body ?: "[Attachment]"
                if (content.groupId != null) {
                    emitGroupMessage(content.groupId, msgId, body, msg.senderAci, senderName, msg.timestamp)
                } else {
                    _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName, senderId = msg.senderAci))
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
                }
            }
            is MessageContent.Reaction -> {
                // Reactions are handled by updating the message
            }
            is MessageContent.Delete -> {
                val msgId = "${chatId}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageDeleted(source, msgId))
            }
            // Fix #19: AdminDelete handling
            is MessageContent.AdminDelete -> {
                val authorAci = content.targetAuthorAci ?: msg.senderAci
                val msgId = "${authorAci}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageDeleted(source, msgId))
            }
            is MessageContent.SyncSent -> {
                val destAci = content.destinationAci ?: return
                val syncContent = content.message ?: return
                if (syncContent is MessageContent.TextMessage) {
                    val msgId = "${destAci}_${content.timestamp}"
                    _events.emit(GMEvent.MessageUpdate(source, destAci, msgId, syncContent.body, true, content.timestamp, null))
                    _events.emit(GMEvent.ConversationUpdate(
                        source = source, conversationId = destAci,
                        peerName = resolveDisplayName(destAci), peerPhone = null, avatarUrl = null,
                        lastPreview = syncContent.body, lastTimestamp = content.timestamp,
                        unreadCount = 0, conversationType = "Signal",
                    ))
                }
            }
            is MessageContent.Typing -> {}
            is MessageContent.ReadReceipt -> {}
            // Fix #16: ViewedReceipt handling
            is MessageContent.ViewedReceipt -> {
                Log.d(TAG, "Received viewed receipt for ${content.timestamps.size} messages")
            }
            is MessageContent.DeliveryReceipt -> {}
            is MessageContent.Call -> {}
            is MessageContent.Edit -> {
                val msgId = "${chatId}_${content.targetTimestamp}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, content.newBody, false, msg.timestamp, senderName))
            }
            // Fix #11: SyncRead handler — forward as read receipts
            is MessageContent.SyncRead -> {
                for ((senderAci, timestamp) in content.messages) {
                    _events.emit(GMEvent.ReadReceipt(source, conversationId = senderAci, messageId = "${senderAci}_${timestamp}"))
                }
                Log.d(TAG, "Processed ${content.messages.size} sync read messages")
            }
            is MessageContent.SyncKeys -> {
                val masterKey = content.masterKey
                    ?: content.accountEntropyPool?.let { aep ->
                        try {
                            org.signal.libsignal.messagebackup.AccountEntropyPool.deriveSvrKey(aep)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to derive master key from account entropy pool", e)
                            null
                        }
                    }
                if (masterKey != null) {
                    val auth = authData ?: return
                    val updated = auth.copy(masterKey = Base64.encodeToString(masterKey, Base64.NO_WRAP))
                    updated.save(appContext)
                    authData = updated
                    Log.i(TAG, "Received and saved master key from sync")
                    // Now that we have the storage-service key, pull down storage records.
                    syncStorageIfPossible()
                }
            }
            is MessageContent.SyncFetchLatest -> {
                Log.d(TAG, "Received fetch latest: ${content.type}")
            }
            // Fix #12: SyncDeleteForMe handler — process conversation and message deletes
            is MessageContent.SyncDeleteForMe -> {
                for (convDelete in content.conversationDeletes) {
                    if (!convDelete.isFullDelete) continue
                    val convAci = convDelete.conversationId.threadAci
                    if (convAci != null) {
                        _events.emit(GMEvent.ConversationDeleted(source, "${source.idPrefix}:$convAci"))
                        Log.d(TAG, "SyncDeleteForMe: deleted conversation for $convAci")
                    }
                    // Issue #15: Handle mostRecentMessages variant — delete individual messages
                    for (addrMsg in convDelete.mostRecentMessages) {
                        val authorAci = addrMsg.authorAci ?: continue
                        val delMsgId = "${authorAci}_${addrMsg.sentTimestamp}"
                        _events.emit(GMEvent.MessageDeleted(source, delMsgId))
                    }
                    for (addrMsg in convDelete.mostRecentNonExpiringMessages) {
                        val authorAci = addrMsg.authorAci ?: continue
                        val delMsgId = "${authorAci}_${addrMsg.sentTimestamp}"
                        _events.emit(GMEvent.MessageDeleted(source, delMsgId))
                    }
                }
                for (msgDelete in content.messageDeletes) {
                    for (addrMsg in msgDelete.messages) {
                        val authorAci = addrMsg.authorAci ?: continue
                        val delMsgId = "${authorAci}_${addrMsg.sentTimestamp}"
                        _events.emit(GMEvent.MessageDeleted(source, delMsgId))
                    }
                }
                for (attDelete in content.attachmentDeletes) {
                    val authorAci = attDelete.targetMessage.authorAci ?: continue
                    val delMsgId = "${authorAci}_${attDelete.targetMessage.sentTimestamp}"
                    Log.d(TAG, "SyncDeleteForMe: attachment delete for message $delMsgId")
                }
            }
            // Fix #13: SyncMessageRequestResponse handler (all types)
            is MessageContent.SyncMessageRequestResponse -> {
                val threadAci = content.threadAci
                when (content.type) {
                    "ACCEPT" -> {
                        if (threadAci != null) Log.d(TAG, "Message request accepted for $threadAci")
                    }
                    "DELETE", "BLOCK_AND_DELETE" -> {
                        if (threadAci != null) {
                            _events.emit(GMEvent.ConversationDeleted(source, "${source.idPrefix}:$threadAci"))
                            Log.d(TAG, "Message request ${content.type.lowercase()} for $threadAci")
                        }
                    }
                    "BLOCK" -> {
                        if (threadAci != null) Log.d(TAG, "Message request blocked for $threadAci")
                    }
                    "SPAM" -> {
                        if (threadAci != null) Log.d(TAG, "Message request marked as spam for $threadAci")
                    }
                    else -> Log.d(TAG, "Unknown message request response type: ${content.type}")
                }
            }
            // Fix #15: Contact sync blob processing
            is MessageContent.SyncContacts -> {
                try {
                    val (contacts, avatars) = ContactManager.unmarshalContactDetailsMessages(content.blob)
                    contactManager?.handleContactSync(contacts, avatars)
                    Log.d(TAG, "Processed contact sync with ${contacts.size} contacts")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process contact sync blob", e)
                }
            }
            is MessageContent.Sticker -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = content.emoji ?: "[Sticker]"
                if (content.groupId != null) {
                    emitGroupMessage(content.groupId, msgId, body, msg.senderAci, senderName, msg.timestamp)
                } else {
                    _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName, senderId = msg.senderAci))
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
                }
            }
            is MessageContent.ProfileKeyUpdate -> {
                captureProfileKey(content.senderAci, content.profileKey)
            }
            // Fix #3: GroupChange processing
            is MessageContent.GroupChange -> {
                val gId = content.groupId
                if (gId != null) {
                    Log.d(TAG, "Received group change for $gId at revision ${content.revision}")
                    groupManager?.invalidateCachedGroup(gId)
                    val masterKey = groupManager?.let { gm ->
                        val stored = db?.groupDao()?.get(gId)
                        stored?.masterKey
                    }
                    if (masterKey != null) {
                        groupManager?.fetchGroup(gId, masterKey)
                    }
                }
            }
            // Fix #17: GroupCallUpdate handling
            is MessageContent.GroupCallUpdate -> {
                val gId = content.groupId
                if (gId != null && content.eraId != null) {
                    val isNew = groupManager?.updateActiveCall(gId, content.eraId) ?: false
                    if (isNew) {
                        val msgId = "${gId}_${msg.timestamp}"
                        _events.emit(GMEvent.IncomingMessage(source, gId, msgId, "Group call started", senderName, null, msg.timestamp))
                    }
                }
                Log.d(TAG, "GroupCallUpdate: eraId=${content.eraId}, groupId=${content.groupId}")
            }
            // Fix #7: Poll messages
            is MessageContent.PollCreate -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val optionsList = content.options.joinToString(", ")
                val body = "Poll: ${content.question}\nOptions: $optionsList"
                if (content.groupId != null) {
                    emitGroupMessage(content.groupId, msgId, body, msg.senderAci, senderName, msg.timestamp)
                } else {
                    _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, body, false, msg.timestamp, senderName, senderId = msg.senderAci))
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
                }
            }
            is MessageContent.PollVote -> {
                Log.d(TAG, "Received poll vote for timestamp ${content.targetTimestamp}")
            }
            // Fix #6: Disappearing timer update
            is MessageContent.ExpirationTimerUpdate -> {
                val msgId = "${chatId}_${msg.timestamp}"
                val body = if (content.expirationSeconds > 0) {
                    "Disappearing messages set to ${content.expirationSeconds}s"
                } else {
                    "Disappearing messages turned off"
                }
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, body, senderName, null, msg.timestamp))
            }
            // Fix #18: Payment/GiftBadge/Contact placeholder text
            is MessageContent.Payment -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, "Payment message (unsupported)", senderName, null, msg.timestamp))
            }
            is MessageContent.GiftBadge -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, "Gift badge (unsupported)", senderName, null, msg.timestamp))
            }
            is MessageContent.ContactCard -> {
                val msgId = "${chatId}_${msg.timestamp}"
                _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, "Contact card (unsupported)", senderName, null, msg.timestamp))
            }
            is MessageContent.Unknown -> {
                Log.d(TAG, "Unknown content: ${content.description}")
            }
        }
    }

    private suspend fun resolveGroupInfo(groupId: String): GroupManager.SignalGroup? {
        val gm = groupManager ?: return null
        gm.getCachedGroup(groupId)?.let { return it }
        val masterKey = db?.groupDao()?.get(groupId)?.masterKey ?: return null
        return try {
            gm.getOrFetchGroup(groupId, masterKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve group $groupId: ${e.message}")
            null
        }
    }

    // Emit a group message with per-message sender identity (name + ACI) so group threads
    // render each sender, plus a group ConversationUpdate (isGroup + title + participant count).
    private suspend fun emitGroupMessage(
        groupId: String,
        msgId: String,
        body: String,
        senderAci: String,
        senderName: String?,
        timestamp: Long,
    ) {
        val resolvedSender = senderName ?: resolveDisplayName(senderAci) ?: senderAci.take(8)
        val group = resolveGroupInfo(groupId)
        val title = group?.title
        val participantCount = group?.members?.size ?: 0
        val participantNames = group?.members?.mapNotNull { m ->
            resolveDisplayName(m.aci.toString())
        }.orEmpty()
        val serviceData = if (participantNames.isNotEmpty()) {
            JSONObject().put("participantNames", org.json.JSONArray(participantNames)).toString()
        } else {
            null
        }
        _events.emit(
            GMEvent.MessageUpdate(
                source, groupId, msgId, body, false, timestamp,
                senderName = resolvedSender, senderId = senderAci,
            )
        )
        _events.emit(
            GMEvent.IncomingMessage(
                source, groupId, msgId, body,
                peerName = title, peerPhone = null, timestamp = timestamp,
                senderName = resolvedSender, senderId = senderAci,
            )
        )
        _events.emit(
            GMEvent.ConversationUpdate(
                source = source, conversationId = groupId,
                peerName = title, peerPhone = null, avatarUrl = null,
                lastPreview = body, lastTimestamp = timestamp,
                unreadCount = 1, isGroup = true, participantCount = participantCount,
                conversationType = "Signal", serviceData = serviceData,
            )
        )
    }

    private suspend fun resolveDisplayName(aci: String): String? {
        nameCache[aci]?.let { return it }
        val name = contactManager?.getDisplayName(aci)
        if (name != null) nameCache[aci] = name
        return name
    }

    private fun kickoffBackupRestore(
        auth: SignalAuthData,
        ws: SignalWebSocket,
        database: SignalDatabase,
        recipientStore: SignalRecipientStore,
    ) {
        scope.launch {
            try {
                val keyB64 = auth.ephemeralBackupKey ?: return@launch
                val ephemeralBackupKey = Base64.decode(keyB64, Base64.NO_WRAP)
                Log.i(TAG, "Starting link & sync backup restore")
                val manager = BackupManager(ws, auth.aci, database)
                val meta = manager.waitForTransfer(ephemeralBackupKey)
                if (meta == null) {
                    Log.i(TAG, "No transfer archive available; skipping restore")
                    clearEphemeralBackupKey()
                    return@launch
                }
                val ok = manager.fetchAndProcessTransfer(meta, ephemeralBackupKey)
                if (!ok) {
                    Log.w(TAG, "Transfer archive processing failed; will retry on next link")
                    return@launch
                }
                val restore = BackupRestore(
                    db = database,
                    recipientStore = recipientStore,
                    groupManager = groupManager,
                    selfAci = auth.aci,
                    source = source,
                    emit = { _events.emit(it) },
                )
                restore.restoreAll()
                // One-shot: clear the key so we don't re-run on subsequent boots.
                clearEphemeralBackupKey()
                Log.i(TAG, "Link & sync backup restore finished")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Backup restore failed", e)
            }
        }
    }

    private suspend fun clearEphemeralBackupKey() {
        val current = authData ?: return
        if (current.ephemeralBackupKey == null) return
        val updated = current.copy(ephemeralBackupKey = null)
        updated.save(appContext)
        authData = updated
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "kickoffBackfill — Signal backfill is contact-sync driven")
            _events.emit(GMEvent.ConversationUpdate(
                source = source, conversationId = "_backfill_sentinel",
                peerName = null, peerPhone = null, avatarUrl = null,
                lastPreview = null, lastTimestamp = 0, unreadCount = 0,
            ))

            val sender = messageSender ?: return@launch
            try {
                val syncRequest = SignalServiceProtos.SyncMessage.Request.newBuilder()
                    .setType(SignalServiceProtos.SyncMessage.Request.Type.CONTACTS)
                    .build()
                val syncMessage = SignalServiceProtos.SyncMessage.newBuilder()
                    .setRequest(syncRequest)
                    .build()
                val content = SignalServiceProtos.Content.newBuilder()
                    .setSyncMessage(syncMessage)
                    .build()
                val auth = authData ?: return@launch
                sender.sendMessage(auth.aci, content, System.currentTimeMillis())
                Log.d(TAG, "Sent contacts sync request to primary device")
            } catch (t: Throwable) {
                Log.w(TAG, "Contacts sync request failed: ${t.message}")
            }
        }
    }

    private fun syncStorageIfPossible() {
        val mgr = storageServiceManager ?: return
        val masterKeyB64 = authData?.masterKey ?: return
        val masterKey = try {
            Base64.decode(masterKeyB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid stored master key: ${e.message}")
            return
        }
        scope.launch {
            try {
                mgr.syncStorage(masterKey)
                // Storage sync stores group master keys but never creates conversation rows;
                // surface them so groups appear without waiting for a live group message.
                syncGroupConversations()
            } catch (e: Exception) {
                Log.w(TAG, "Storage sync failed: ${e.message}")
            }
        }
    }

    // Surface Signal group conversations proactively. Storage-service sync stores group master
    // keys but never emits a conversation, and backup restore is one-shot — so without this,
    // groups only appear when a NEW group message arrives (hence "no groups on a fresh
    // session"). Enumerate known groups and emit a group ConversationUpdate for any that don't
    // already have a row, so we never clobber an existing thread's unread/preview.
    private fun syncGroupConversations() {
        val gm = groupManager ?: return
        val database = db ?: return
        scope.launch {
            try {
                val groups = SignalGroupStore(database).getAllGroups()
                if (groups.isEmpty()) return@launch
                val appDb = buildMessagesDatabase(appContext)
                var surfaced = 0
                for (entity in groups) {
                    try {
                        val convId = "${source.idPrefix}:${entity.groupId}"
                        if (appDb.conversationDao().get(convId) != null) continue
                        val group = gm.getOrFetchGroup(entity.groupId, entity.masterKey) ?: continue
                        if (group.title.isBlank() && group.members.isEmpty()) continue
                        val participantNames = group.members.mapNotNull { resolveDisplayName(it.aci.toString()) }
                        val serviceData = if (participantNames.isNotEmpty()) {
                            JSONObject().put("participantNames", org.json.JSONArray(participantNames)).toString()
                        } else {
                            null
                        }
                        _events.emit(
                            GMEvent.ConversationUpdate(
                                source = source, conversationId = entity.groupId,
                                peerName = group.title.ifBlank { null }, peerPhone = null, avatarUrl = null,
                                lastPreview = null, lastTimestamp = 0L, unreadCount = 0,
                                isGroup = true, participantCount = group.members.size,
                                conversationType = "Signal", serviceData = serviceData,
                            )
                        )
                        surfaced++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to surface group ${entity.groupId}: ${e.message}")
                    }
                }
                Log.i(TAG, "syncGroupConversations: surfaced $surfaced group conversation(s)")
            } catch (e: Exception) {
                Log.w(TAG, "syncGroupConversations failed: ${e.message}")
            }
        }
    }

    // Persists a captured profile key for [aci] (if changed) and refreshes that contact's profile
    // (name/about/avatar) via ProfileManager. // UNVERIFIED runtime (network fetch).
    private fun captureProfileKey(aci: String, profileKey: ByteArray) {
        if (profileKey.size != 32) return
        val store = recipientStore ?: return
        val pm = profileManager ?: return
        scope.launch {
            try {
                val existing = store.getRecipient(aci)
                val changed = existing?.profileKey?.contentEquals(profileKey) != true
                if (changed) {
                    store.storeRecipient(
                        existing?.copy(profileKey = profileKey)
                            ?: SignalRecipientEntity(aci = aci, profileKey = profileKey)
                    )
                    pm.retrieveProfileByID(aci, profileKey)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture profile key for $aci: ${e.message}")
            }
        }
    }

    private suspend fun sendStorageMasterKeyRequest() {
        val sender = messageSender ?: return
        val auth = authData ?: return
        val syncRequest = SignalServiceProtos.SyncMessage.Request.newBuilder()
            .setType(SignalServiceProtos.SyncMessage.Request.Type.KEYS)
            .build()
        val syncMessage = SignalServiceProtos.SyncMessage.newBuilder()
            .setRequest(syncRequest)
            .build()
        val content = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(syncMessage)
            .build()
        sender.sendMessage(auth.aci, content, System.currentTimeMillis())
        Log.d(TAG, "Sent storage master key request to primary device")
    }

    private fun extractAci(conversationId: String): String? {
        return conversationId.substringAfter(':', conversationId).takeIf { it.isNotBlank() }
    }

    private fun extractGroupIdFromConversation(conversationId: String): String? {
        // The id after the "sig:" source prefix is either a recipient ACI (a
        // canonical UUID) for a 1:1 thread, or a base64 group identifier for a
        // group thread (see emitGroupMessage / ContentDispatcher.extractGroupId).
        // Group threads were never routed correctly because nothing ever emits a
        // "group:" prefix; classify by shape instead — anything that is not a UUID
        // is a group id. (A legacy "group:" prefix is still honored.)
        val id = conversationId.substringAfter(':', conversationId)
        if (id.isBlank()) return null
        if (id.startsWith("group:")) return id.substringAfter("group:")
        return if (UUID_REGEX.matches(id)) null else id
    }

    private suspend fun resolveRecipient(identifier: String): String? {
        if (!identifier.startsWith("+")) return identifier
        val discovery = contactDiscovery ?: return null
        return discovery.resolveE164(identifier)
    }
}
