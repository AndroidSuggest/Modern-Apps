@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package com.vayunmathur.messages.gmessages

import kotlin.uuid.Uuid
import kotlin.concurrent.atomics.*
import android.content.Context
import android.util.Base64
import android.util.Log
import authentication.Authentication
import client.Client.ListConversationsRequest
import client.Client.ListConversationsResponse
import client.Client.ListContactsRequest
import client.Client.ListContactsResponse
import client.Client.ListTopContactsRequest
import client.Client.ListTopContactsResponse
import client.Client.GetOrCreateConversationRequest
import client.Client.GetOrCreateConversationResponse
import client.Client.ListMessagesRequest
import client.Client.ListMessagesResponse
import client.Client.MessagePayload
import client.Client.MessageReadRequest
import client.Client.SendMessageRequest
import client.Client.SendMessageResponse
import client.Client.SendReactionRequest
import client.Client.SendReactionResponse
import client.Client.TypingUpdateRequest
import client.Client.UpdateConversationRequest
import client.Client.UpdateConversationResponse
import client.Client.DeleteConversationData
import client.Client.ConversationActionStatus
import client.Client.DeleteMessageRequest
import client.Client.DeleteMessageResponse
import com.google.protobuf.ByteString
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.util.ContactResolver
import conversations.Conversations.Conversation
import conversations.Conversations.Message
import conversations.Conversations.MessageInfo
import conversations.Conversations.MessageContent
import conversations.Conversations.ReactionData
import conversations.Conversations.EmojiType
import events.Events.UpdateEvents
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import client.Client.ReplyPayload
import conversations.Conversations.MessageStatusType
import conversations.Conversations.ConversationSendMode
import conversations.Conversations.ConversationStatus
import conversations.Conversations.ConversationType
import events.Events.AlertType
import events.Events.TypingTypes
import rpc.Rpc.ActionType
import rpc.Rpc.MessageType
import settings.SettingsOuterClass

/**
 * Process-global owner of the Google-Messages-for-Web protocol session.
 *
 * Lifecycle:
 *  1. [init] is called once by the foreground service. We load any
 *     persisted [AuthData] off disk.
 *  2. [start] is idempotent: if persisted auth exists, opens the
 *     long-poll and emits Connected. Otherwise sits Idle.
 *  3. [startPair] hits Pairing/RegisterPhoneRelay, stores the initial
 *     tachyon token, opens the long-poll so we can receive the Paired
 *     event, then returns the QR URL for the UI to render.
 *  4. The long-poll dispatches a Paired event → we persist the new
 *     device info + tachyon token, transition to Connected, and trigger
 *     the conversation backfill.
 *  5. [stop] cancels everything and clears the persisted auth.
 *
 * For v1 we LIST_CONVERSATIONS on connect (for backfill / inbox
 * population). Outbound messaging uses [sendMessage] (text) and
 * [sendMedia] (media upload + caption) via the session handler.
 */
object GMessagesClient {

    private const val TAG = "GMessagesClient"
    
    /** Refresh token if it expires within this buffer (1 hour). Matches Go implementation. */
    private const val REFRESH_TACHYON_BUFFER_MS = 60 * 60 * 1000L

    /** Default TTL when the server returns 0 (24 hours in microseconds). Matches Go `updateTachyonAuthToken`. */
    private const val DEFAULT_TTL_US = 24L * 60 * 60 * 1_000_000

    sealed interface State {
        data object Idle : State
        data class Pairing(val qrUrl: String) : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    val source: MessageSource = MessageSource.MESSAGES_WEB

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private val rpc = RpcClient()
    @Volatile private var auth: AuthData = AuthData.generateInitial()
    private val authMutex = Mutex()
    private val sessionHandler = SessionHandler(rpc) { auth }
    private val media = Media { auth }
    private val longPoll = LongPoll(
        rpc = rpc,
        authProvider = { auth },
        sessionHandler = sessionHandler,
        onEvent = ::handleLongPollEvent,
        refreshToken = ::refreshAuthToken,
    )
    private var backfillJob: Job? = null

    /** Per-conversation "my SIM" participant ID surfaced by
     *  LIST_CONVERSATIONS.defaultOutgoingID. Used to populate the
     *  participantID on outgoing SendMessageRequest payloads. */
    private val outgoingIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var conversationsFetchedOnce = false

    // --- Bridge state tracking (port of Go connector's GMClient fields) ---
    @Volatile private var browserInactiveType: String = ""
    @Volatile private var ready = false
    @Volatile private var phoneResponding = true
    @Volatile private var noDataReceivedRecently = false
    @Volatile private var longPollingError: String? = null
    @Volatile private var lastDataReceived = System.currentTimeMillis()
    @Volatile private var batteryLow = false
    @Volatile private var mobileData = false
    @Volatile private var switchedToGoogleLogin = false
    @Volatile private var sessionId = ""
    @Volatile private var aggressiveReconnect = true
    private var cachedContacts: List<com.vayunmathur.messages.util.ContactSuggestion>? = null
    private var contactsFetchedAt = 0L
    private val contactsFetchLock = Mutex()
    private val pendingOutgoing = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var defaultSimPayload: SettingsOuterClass.SIMPayload? = null
    private val conversationTypes = java.util.concurrent.ConcurrentHashMap<String, ConversationType>()
    private val conversationSendModes = java.util.concurrent.ConcurrentHashMap<String, ConversationSendMode>()
    private val conversationForceRcs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var qrRetryCount = 0
    private const val MAX_QR_RETRIES = 6

    // Issue 2: message edit detection — track content hashes per message ID
    private val messageContentHashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Issue 5: reaction sync per-user — track (messageId, userId) → emoji to prevent duplicate events
    private val reactionState = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, String>()

    // --- Message deduplication ring buffer
    private data class UpdateDedupItem(val id: String, val hash: ByteArray)
    private val recentUpdates = arrayOfNulls<UpdateDedupItem>(8)
    private var recentUpdatesPtr = 0
    private val dedupLock = Any()

    // --- Per-message metadata for status state machine (port of Go MessageMetadata) ---
    private class MessageMeta {
        var isOutgoing = false
        var statusNum: Int = 0
        var textHash = ""
        var globalPartCount = 0
        var mssSent = false
        var mssFailSent = false
        var mssDeliverySent = false
        var readReceiptSent = false
        var globalStatusText = ""
        var groupReadBy = mutableListOf<String>()
    }
    private val messageMeta = java.util.concurrent.ConcurrentHashMap<String, MessageMeta>()

    // Syncing flags for MESSAGE_DELETED suppression (port of Go syncingMobileDatabase/syncingConversations)
    @Volatile private var syncingMobileDatabase = false
    @Volatile private var syncingConversations = false

    // Chat info cache for group read receipts and phone number resolution
    private val chatInfoCache = java.util.concurrent.ConcurrentHashMap<String, conversations.Conversations.Conversation>()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            AuthData.load(appContext)?.let { auth = it }
            if (auth.isPaired()) {
                Log.i(TAG, "found persisted pair, resuming long-poll")
                longPoll.start(scope)
                sessionHandler.startAckInterval(scope)
                _state.value = State.Connected
                postConnect()
            }
        }
    }

    fun start() {
        if (!initialized.load()) return
        if (auth.isPaired() && _state.value !is State.Connected) {
            longPoll.start(scope)
            sessionHandler.startAckInterval(scope)
            _state.value = State.Connected
            scope.launch { postConnect() }
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing pair")
        // Remote unpair: notify the server we're intentionally disconnecting (1.8)
        val currentAuth = auth
        if (currentAuth.isPaired()) {
            scope.launch {
                try {
                    val token = currentAuth.tachyonToken() ?: return@launch
                    val browser = currentAuth.browser() ?: return@launch
                    val req = authentication.Authentication.RevokeRelayPairingRequest.newBuilder()
                        .setAuthMessage(
                            authentication.Authentication.AuthMessage.newBuilder()
                                .setRequestID(Uuid.random().toString())
                                .setTachyonAuthToken(ByteString.copyFrom(token))
                                .setConfigVersion(PairFlow.ConfigVersion)
                        )
                        .setBrowser(browser)
                        .build()
                    rpc.postProtobuf(
                        url = Endpoints.RevokeRelayPairingUrl,
                        body = req,
                        responseTemplate = authentication.Authentication.RevokeRelayPairingResponse.getDefaultInstance(),
                    )
                    Log.i(TAG, "remote unpair sent")
                } catch (e: Exception) {
                    Log.w(TAG, "remote unpair failed: ${e.message}")
                }
            }
        }
        backfillJob?.cancel()
        longPoll.stop()
        sessionHandler.cancelAll()
        val freshAuth = AuthData.generateInitial()
        auth = freshAuth
        scope.launch { freshAuth.save(appContext) }
        outgoingIds.clear()
        conversationsFetchedOnce = false
        browserInactiveType = ""
        ready = false
        phoneResponding = true
        noDataReceivedRecently = false
        longPollingError = null
        batteryLow = false
        mobileData = false
        switchedToGoogleLogin = false
        sessionId = ""
        cachedContacts = null
        contactsFetchedAt = 0L
        pendingOutgoing.clear()
        defaultSimPayload = null
        conversationTypes.clear()
        conversationSendModes.clear()
        conversationForceRcs.clear()
        messageContentHashes.clear()
        reactionState.clear()
        messageMeta.clear()
        chatInfoCache.clear()
        syncingMobileDatabase = false
        syncingConversations = false
        didHackySetActive = false
        qrRetryCount = 0
        rpc.close()
        media.close()
        _state.value = State.Idle
    }

    suspend fun startPair(): String {
        qrRetryCount = 0
        return startPairAttempt()
    }

    private suspend fun startPairAttempt(): String {
        try {
            val qrUrl = authMutex.withLock {
                if (qrRetryCount == 0) {
                    auth = AuthData.generateInitial()
                    val result = PairFlow.registerAndBuildQrUrl(rpc, auth)
                    val ttlUs = result.tachyonTtlUs.let { if (it == 0L) DEFAULT_TTL_US else it }
                    auth = auth.copy(
                        tachyonAuthTokenB64 = android.util.Base64.encodeToString(result.tachyonToken, android.util.Base64.NO_WRAP),
                        tachyonTtlUs = ttlUs,
                        tachyonExpiryMs = System.currentTimeMillis() + (ttlUs / 1000),
                    )
                    result.qrUrl
                } else {
                    val result = PairFlow.refreshPhoneRelay(rpc, auth)
                    // RefreshPhoneRelay does not issue a new tachyon token; keep the
                    // existing one and only surface the regenerated QR URL.
                    result.qrUrl
                }
            }
            if (qrRetryCount == 0) {
                longPoll.start(scope)
            }
            _state.value = State.Pairing(qrUrl)
            qrRetryCount++
            return qrUrl
        } catch (t: Throwable) {
            Log.e(TAG, "startPair failed (attempt $qrRetryCount)", t)
            _state.value = State.Disconnected("Pair failed: ${t.message}")
            throw t
        }
    }

    /** Refresh the QR code (up to MAX_QR_RETRIES). Returns the new QR URL. */
    suspend fun refreshQr(): String? {
        if (qrRetryCount >= MAX_QR_RETRIES) {
            Log.w(TAG, "QR refresh exhausted ($qrRetryCount/$MAX_QR_RETRIES)")
            return null
        }
        return try {
            startPairAttempt()
        } catch (t: Throwable) {
            Log.e(TAG, "QR refresh failed", t)
            null
        }
    }

    /**
     * Send a text message via SEND_MESSAGE.
     *
     * Mirrors `ConvertMatrixMessage` + `Client.SendMessage` in
     * `pkg/connector/handlematrix.go` / `pkg/libgm/methods.go`:
     * builds a [SendMessageRequest] with a [MessageContent] info part,
     * a fresh `tmp_…` transaction ID, and the per-conversation
     * `participantID` we captured from LIST_CONVERSATIONS.
     *
     * Returns `true` iff the relay reports `SUCCESS`.
     */
    suspend fun sendMessage(
        conversationId: String,
        body: String,
        replyToMessageId: String? = null,
        isEmote: Boolean = false,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val text = if (isEmote) "/me $body" else body
        val info = MessageInfo.newBuilder()
            .setMessageContent(MessageContent.newBuilder().setContent(text))
            .build()
        return sendWithInfos(webId, listOf(info), replyToMessageId = replyToMessageId)
    }

    /**
     * Send an image (or any media) via SEND_MESSAGE. Uploads the bytes
     * first via [Media.upload], then attaches the resulting
     * [MediaContent] to a SendMessageRequest. If [caption] is non-blank,
     * a separate MessageContent info part is appended so the recipient
     * sees image + caption like Google Messages does.
     */
    suspend fun sendMedia(
        conversationId: String,
        data: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val mediaContent = try {
            media.upload(data, fileName, mime)
        } catch (t: Throwable) {
            Log.w(TAG, "media upload failed: ${t.message}")
            return false
        }
        val infos = mutableListOf(
            MessageInfo.newBuilder().setMediaContent(mediaContent).build()
        )
        if (!caption.isNullOrBlank()) {
            infos += MessageInfo.newBuilder()
                .setMessageContent(MessageContent.newBuilder().setContent(caption))
                .build()
        }
        return sendWithInfos(webId, infos, replyToMessageId = null)
    }

    /**
     * Polls are not supported by Google Messages (no poll concept in libgm's
     * SEND_MESSAGE proto) — contract §2b lists this platform as a no-op. Always
     * returns false; the capability layer hides the poll entry for this platform.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        Log.i(TAG, "sendPoll unsupported on Google Messages")
        return false
    }

    /** Common SEND_MESSAGE plumbing — builds the envelope and awaits a response. */
    private suspend fun sendWithInfos(
        webId: String,
        infos: List<MessageInfo>,
        replyToMessageId: String? = null,
    ): Boolean {
        val tmpId = "tmp_${kotlin.random.Random.nextLong(1_000_000_000_000L).toString().padStart(12, '0')}"
        val participantId = outgoingIds[webId].orEmpty()
        val payload = MessagePayload.newBuilder()
            .setTmpID(tmpId)
            .setConversationID(webId)
            .setParticipantID(participantId)
            .setTmpID2(tmpId)
            .addAllMessageInfo(infos)
            .build()
        val reqBuilder = SendMessageRequest.newBuilder()
            .setConversationID(webId)
            .setMessagePayload(payload)
            .setTmpID(tmpId)
        // SIM payload (item 4)
        defaultSimPayload?.let { reqBuilder.setSIMPayload(it) }
        // ForceRCS flag (item 20)
        val convType = conversationTypes[webId]
        val sendMode = conversationSendModes[webId]
        val forceRcs = conversationForceRcs[webId] == true
        if (convType == ConversationType.RCS &&
            sendMode == ConversationSendMode.SEND_MODE_AUTO && forceRcs) {
            reqBuilder.setForceRCS(true)
        }
        // Reply support (item 5)
        if (replyToMessageId != null) {
            val replyWebId = replyToMessageId.substringAfter(':', replyToMessageId)
            reqBuilder.setReply(ReplyPayload.newBuilder().setMessageID(replyWebId))
        }
        // Track for remote echo (item 23)
        pendingOutgoing[tmpId] = webId
        val req = reqBuilder.build()
        val resp = sessionHandler.sendAndWait(ActionType.SEND_MESSAGE, req)
            ?: return false.also {
                Log.w(TAG, "SEND_MESSAGE timed out")
                pendingOutgoing.remove(tmpId)
            }
        val data = resp.decryptedData ?: return false.also { pendingOutgoing.remove(tmpId) }
        val parsed = runCatching { SendMessageResponse.parseFrom(data) }.getOrNull()
            ?: return false.also { pendingOutgoing.remove(tmpId) }
        Log.i(TAG, "SEND_MESSAGE status=${parsed.status}")
        if (parsed.status != SendMessageResponse.Status.SUCCESS) {
            pendingOutgoing.remove(tmpId)
            val errorMsg = getSendFailureMessage(parsed)
            if (errorMsg != null) {
                _events.emit(GMEvent.SendFailed(
                    source = source,
                    conversationId = webId,
                    tmpId = tmpId,
                    errorMessage = errorMsg,
                ))
            }
        }
        return parsed.status == SendMessageResponse.Status.SUCCESS
    }

    /**
     * Mark messages in [conversationId] as read up to [messageId].
     * Mirrors `Client.MarkRead`.
     */
    suspend fun markRead(conversationId: String, messageId: String?): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val msgWebId = messageId?.substringAfter(':', messageId).orEmpty()
        val req = MessageReadRequest.newBuilder()
            .setConversationID(webId)
            .setMessageID(msgWebId)
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.MESSAGE_READ, req)
        return resp != null
    }

    /**
     * Integrator read-receipt contract: send a read receipt for [lastMessageId].
     * Read receipts only exist on RCS — for SMS threads this is a no-op that
     * returns false (the phone honors its own per-account RCS read-receipt
     * setting; there is no separate web-protocol toggle). Returns true when a
     * receipt was sent.
     */
    suspend fun sendReadReceipt(
        conversationId: String,
        lastMessageId: String?,
        lastTimestamp: Long,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        if (conversationTypes[webId] != ConversationType.RCS) {
            // SMS threads have no read receipts.
            return false
        }
        return markRead(conversationId, lastMessageId)
    }

    /**
     * Delete [conversationId] on the phone via UPDATE_CONVERSATION
     * with [ConversationActionStatus.DELETE]. The phone is part of the
     * proto because the relay routes the delete to the correct SIM /
     * thread on the device.
     */
    suspend fun deleteConversation(conversationId: String, phone: String?): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        // Phone number resolution fallback (1.5): if phone is unknown, try to
        // resolve it from cached chat info or by fetching the conversation.
        var resolvedPhone = phone
        if (resolvedPhone.isNullOrBlank()) {
            val cached = chatInfoCache[webId]
            if (cached != null) {
                resolvedPhone = cached.participantsList
                    ?.firstOrNull { it.isVisible && !it.isMe && it.id.number.isNotBlank() }
                    ?.id?.number
            }
            if (resolvedPhone.isNullOrBlank()) {
                val convResp = sessionHandler.sendAndWait(ActionType.GET_UPDATES, null, timeoutMs = 10_000)
                Log.d(TAG, "deleteConversation: phone fallback attempted via relay")
            }
        }
        val data = DeleteConversationData.newBuilder()
            .setConversationID(webId)
            .apply { if (!resolvedPhone.isNullOrBlank()) setPhone(resolvedPhone) }
            .build()
        val req = UpdateConversationRequest.newBuilder()
            .setAction(ConversationActionStatus.DELETE)
            .setConversationID(webId)
            .setDeleteData(data)
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.UPDATE_CONVERSATION, req)
            ?: return false
        val body = resp.decryptedData ?: return false
        val parsed = runCatching { UpdateConversationResponse.parseFrom(body) }.getOrNull()
        return parsed?.success == true
    }

    /**
     * Add / remove / switch a reaction on [messageId].
     * Mirrors `Client.SendReaction` + `gmproto.MakeReactionData`: send the unicode
     * emoji together with its inferred [EmojiType] (CUSTOM for anything not in the
     * built-in set), matching Google's web client.
     */
    suspend fun sendReaction(
        messageId: String,
        emoji: String,
        action: SendReactionRequest.Action,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val msgWebId = messageId.substringAfter(':', messageId)
        val qualifiedEmoji = fullyQualifyEmoji(emoji)
        val reqBuilder = SendReactionRequest.newBuilder()
            .setMessageID(msgWebId)
            .setAction(action)
            .setReactionData(
                ReactionData.newBuilder()
                    .setUnicode(qualifiedEmoji)
                    .setType(unicodeToEmojiType(qualifiedEmoji))
            )
        // Go omits SIMPayload for REMOVE action (1.2)
        if (action != SendReactionRequest.Action.REMOVE) {
            defaultSimPayload?.let { reqBuilder.setSIMPayload(it) }
        }
        val req = reqBuilder.build()
        val resp = sessionHandler.sendAndWait(ActionType.SEND_REACTION, req) ?: return false
        val body = resp.decryptedData ?: return false
        val parsed = runCatching { SendReactionResponse.parseFrom(body) }.getOrNull()
        return parsed?.success == true
    }

    /**
     * Notify the peer that the user is currently typing.
     * Fire-and-forget (no waiter).
     */
    suspend fun sendTyping(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = conversationId.substringAfter(':', conversationId)
        val reqBuilder = TypingUpdateRequest.newBuilder()
            .setData(
                TypingUpdateRequest.Data.newBuilder()
                    .setConversationID(webId)
                    .setTyping(true)
            )
        defaultSimPayload?.let { reqBuilder.setSIMPayload(it) }
        return sessionHandler.sendNoWait(ActionType.TYPING_UPDATES, reqBuilder.build())
    }

    /** Stop-typing: Go skips this entirely (returns nil), so we do the same (1.4). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendStopTyping(conversationId: String): Boolean = true

    /**
     * Pushes a settings update to the relay (currently the push-notifications enabled flag).
     * Fire-and-forget SETTINGS_UPDATE action, mirroring libgm Client.UpdateSettings. // UNVERIFIED runtime
     */
    suspend fun updateSettings(pushEnabled: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        val req = client.Client.SettingsUpdateRequest.newBuilder()
            .setPushSettings(
                client.Client.SettingsUpdateRequest.PushSettings.newBuilder()
                    .setEnabled(pushEnabled)
            )
            .build()
        return sessionHandler.sendNoWait(ActionType.SETTINGS_UPDATE, req)
    }

    /**
     * Delete a single message via DELETE_MESSAGE. The relay echoes the
     * delete through the long-poll's MESSAGE_DELETED event when
     * complete — local Room rows are cleared via that event path so
     * the UI updates once for both local-initiated and remote-initiated
     * deletes.
     */
    suspend fun deleteMessage(messageId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val webId = messageId.substringAfter(':', messageId)
        val req = DeleteMessageRequest.newBuilder().setMessageID(webId).build()
        val resp = sessionHandler.sendAndWait(ActionType.DELETE_MESSAGE, req) ?: return false
        val body = resp.decryptedData ?: return false
        val parsed = runCatching { DeleteMessageResponse.parseFrom(body) }.getOrNull()
        return parsed?.success == true
    }

    /**
     * Create (or return an existing) conversation for [numbers].
     *
     * Mirrors `Client.GetOrCreateConversation` (`pkg/libgm/methods.go:52`).
     * Single phone = 1:1 thread. Multiple phones = SMS group, or RCS
     * group when [createRcsGroup] + [rcsGroupName] is supplied.
     *
     * Returns the source-prefixed conversation id of the (possibly
     * newly-created) thread, or null on failure. The relay emits a
     * GET_UPDATES with the new conversation immediately after; we also
     * emit a synthetic [GMEvent.ConversationUpdate] so the inbox sees
     * the row before the long-poll catches up.
     */
    suspend fun getOrCreateConversation(
        numbers: List<String>,
        rcsGroupName: String? = null,
        createRcsGroup: Boolean = false,
    ): String? {
        if (numbers.isEmpty() || _state.value !is State.Connected) return null
        val req = GetOrCreateConversationRequest.newBuilder()
            .addAllNumbers(numbers.map {
                conversations.Conversations.ContactNumber.newBuilder()
                    .setMysteriousInt(2)  // 2 = "from contact list", per libgm
                    .setNumber(it)
                    .setNumber2(it)
                    .build()
            })
            .apply {
                if (createRcsGroup) {
                    setCreateRCSGroup(true)
                    rcsGroupName?.let { setRCSGroupName(it) }
                }
            }
            .build()
        val resp = sessionHandler.sendAndWait(ActionType.GET_OR_CREATE_CONVERSATION, req)
            ?: return null
        val parsed = runCatching {
            GetOrCreateConversationResponse.parseFrom(resp.decryptedData ?: return null)
        }.getOrNull() ?: return null

        if (parsed.status == GetOrCreateConversationResponse.Status.CREATE_RCS) {
            // Server says "retry with createRcsGroup = true". Port of
            // Go startchat.go: reqData.CreateRCSGroup = ptr.Ptr(true)
            Log.i(TAG, "getOrCreate: CREATE_RCS — retrying with createRcsGroup=true")
            return getOrCreateConversation(numbers, rcsGroupName = rcsGroupName ?: "", createRcsGroup = true)
        }
        if (!parsed.hasConversation()) {
            Log.w(TAG, "getOrCreate: status=${parsed.status} but no conversation")
            return null
        }
        val conv = parsed.conversation
        // Eagerly emit a synthetic ConversationUpdate so the inbox row
        // appears before the long-poll's GET_UPDATES delivers the same
        // info. emitConversation is idempotent via Room upsert so the
        // duplicate from the long-poll is harmless.
        emitConversation(conv)
        return "${source.idPrefix}:${conv.conversationID}"
    }

    /**
     * Server-side contact list, used to populate the recipient picker.
     * Mirrors `Client.ListContacts` (`pkg/libgm/methods.go:34`).
     * The bridge passes hardcoded magic ints (1, 350, 50) that we
     * preserve verbatim — the relay rejects requests without them.
     */
    suspend fun listContacts(): List<com.vayunmathur.messages.util.ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        contactsFetchLock.withLock {
            val cached = cachedContacts
            if (cached != null && System.currentTimeMillis() - contactsFetchedAt < 5 * 60 * 1000L) {
                return cached
            }
            val req = ListContactsRequest.newBuilder()
                .setI1(1)
                .setI2(350)
                .setI3(50)
                .build()
            val resp = sessionHandler.sendAndWait(ActionType.LIST_CONTACTS, req) ?: return cached ?: emptyList()
            val parsed = runCatching {
                ListContactsResponse.parseFrom(resp.decryptedData ?: return cached ?: emptyList())
            }.getOrNull() ?: return cached ?: emptyList()
            val result = (0 until parsed.contactsCount).mapNotNull { i ->
                parsed.getContacts(i).toSuggestion()
            }
            cachedContacts = result
            contactsFetchedAt = System.currentTimeMillis()
            return result
        }
    }

    /** Server-side "frequent contacts" — used as the picker's initial
     *  suggestion list before the user types anything. */
    suspend fun listTopContacts(count: Int = 8): List<com.vayunmathur.messages.util.ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        val req = ListTopContactsRequest.newBuilder().setCount(count).build()
        val resp = sessionHandler.sendAndWait(ActionType.LIST_TOP_CONTACTS, req) ?: return emptyList()
        val parsed = runCatching {
            ListTopContactsResponse.parseFrom(resp.decryptedData ?: return emptyList())
        }.getOrNull() ?: return emptyList()
        return (0 until parsed.contactsCount).mapNotNull { i ->
            parsed.getContacts(i).toSuggestion()
        }
    }

    private fun conversations.Conversations.Contact.toSuggestion(): com.vayunmathur.messages.util.ContactSuggestion? {
        val phone = number?.number?.takeIf { it.isNotBlank() }
            ?: number?.number2?.takeIf { it.isNotBlank() }
            ?: return null
        val displayName = name.takeIf { it.isNotBlank() } ?: phone
        return com.vayunmathur.messages.util.ContactSuggestion(
            displayName = displayName,
            phoneE164 = phone,
            avatarUrl = null,
            source = source,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @Deprecated("kept for binary compat — call sendMessage(conversationId, body) directly", level = DeprecationLevel.HIDDEN)
    suspend fun sendMessageLegacy(conversationId: String, body: String): Boolean =
        sendMessage(conversationId, body)

    /**
     * Pump for inbound real-time updates pushed by the relay.
     *
     * Each GET_UPDATES message wraps an [UpdateEvents] oneof. We handle
     * the three high-signal kinds:
     *  - [UpdateEvents.MessageEvent]: new/edited messages → emit
     *    MessageUpdate (+ IncomingMessage for non-outgoing).
     *  - [UpdateEvents.ConversationEvent]: conversation metadata bumps
     *    → re-emit the row via [emitConversation].
     *  - [UpdateEvents.TypingEvent]: kept here for completeness; no UI
     *    yet, so we just log.
     *
     * Anything else is logged at debug and ignored. The bridge itself
     * has handlers for stickers / settings / participant events; those
     * are out of scope for v1.
     */
    private suspend fun handleGetUpdates(data: ByteArray, isOld: Boolean = false) {
        val updates = runCatching { UpdateEvents.parseFrom(data) }.getOrNull() ?: run {
            Log.w(TAG, "GET_UPDATES: failed to parse UpdateEvents")
            return
        }
        when {
            updates.hasMessageEvent() -> {
                val msgs = updates.messageEvent.dataList
                Log.i(TAG, "GET_UPDATES: ${msgs.size} message event(s)")
                noDataReceivedRecently = false
                lastDataReceived = System.currentTimeMillis()
                msgs.forEach { msg ->
                    if (deduplicateUpdate(msg.messageID, data)) return
                    emitMessage(msg)
                }
            }
            updates.hasConversationEvent() -> {
                val convs = updates.conversationEvent.dataList
                Log.i(TAG, "GET_UPDATES: ${convs.size} conversation event(s)")
                noDataReceivedRecently = false
                lastDataReceived = System.currentTimeMillis()
                convs.forEach { conv ->
                    if (deduplicateUpdate(conv.conversationID, data)) return
                    if (isOld) {
                        Log.d(TAG, "ignoring old conversation event conv=${conv.conversationID}")
                        return@forEach
                    }
                    emitConversation(conv)
                }
            }
            updates.hasTypingEvent() -> {
                if (isOld) return
                val typingData = updates.typingEvent.data
                val convId = typingData.conversationID
                val isTyping = typingData.type == TypingTypes.STARTED_TYPING
                val senderId = typingData.user?.number.orEmpty()
                Log.d(TAG, "GET_UPDATES typing conv=$convId typing=$isTyping sender=$senderId")
                _events.emit(
                    GMEvent.TypingIndicator(
                        source = source,
                        conversationId = convId,
                        senderId = senderId,
                        isTyping = isTyping,
                    )
                )
            }
            updates.hasUserAlertEvent() -> {
                if (isOld) return
                handleUserAlert(updates.userAlertEvent.alertType)
            }
            updates.hasSettingsEvent() -> {
                if (!noDataReceivedRecently) lastDataReceived = System.currentTimeMillis()
                handleSettings(data)
            }
            updates.hasBrowserPresenceCheckEvent() -> {
                Log.d(TAG, "GET_UPDATES: browser presence check, sending ack")
                scope.launch { ackBrowserPresence() }
            }
            updates.hasAccountChange() -> {
                handleAccountChange(data)
            }
            else -> Log.d(TAG, "GET_UPDATES: unhandled event kind")
        }
    }

    private suspend fun ackBrowserPresence() {
        sessionHandler.sendMessageNoResponse(SendMessageParams(
            action = ActionType.ACK_BROWSER_PRESENCE,
        ))
    }

    /** Port of Go's handleUserAlert (handlegmessages.go:256-337). */
    private fun handleUserAlert(alertType: AlertType) {
        Log.d(TAG, "handleUserAlert: $alertType")
        var becameInactive = false
        if (!noDataReceivedRecently) {
            lastDataReceived = System.currentTimeMillis()
        }
        when (alertType) {
            AlertType.BROWSER_INACTIVE,
            AlertType.BROWSER_INACTIVE_FROM_TIMEOUT,
            AlertType.BROWSER_INACTIVE_FROM_INACTIVITY -> {
                browserInactiveType = alertType.name
                becameInactive = true
                Log.i(TAG, "Browser became inactive: $alertType")
            }
            AlertType.BROWSER_ACTIVE -> {
                val wasInactive = browserInactiveType.isNotEmpty() || !ready
                browserInactiveType = ""
                ready = true
                val newSessionId = sessionHandler.currentSessionId()
                val sessionIdChanged = sessionId.isNotEmpty() && sessionId != newSessionId
                if (sessionIdChanged || wasInactive || noDataReceivedRecently) {
                    Log.i(TAG, "BROWSER_ACTIVE: resyncing (sessionChanged=$sessionIdChanged wasInactive=$wasInactive noData=$noDataReceivedRecently)")
                    sessionId = newSessionId
                    val minimal = !sessionIdChanged && !wasInactive
                    scope.launch { kickoffBackfill(minimal = minimal) }
                } else {
                    sessionId = newSessionId
                }
                noDataReceivedRecently = false
                lastDataReceived = System.currentTimeMillis()
            }
            AlertType.MOBILE_DATA_CONNECTION -> {
                mobileData = true
                Log.d(TAG, "Phone connected to mobile data")
            }
            AlertType.MOBILE_WIFI_CONNECTION -> {
                mobileData = false
                Log.d(TAG, "Phone connected to WiFi")
            }
            AlertType.MOBILE_BATTERY_LOW -> {
                batteryLow = true
                Log.d(TAG, "Phone battery low")
            }
            AlertType.MOBILE_BATTERY_RESTORED -> {
                batteryLow = false
                Log.d(TAG, "Phone battery restored")
            }
            AlertType.MOBILE_DATABASE_SYNC_STARTED,
            AlertType.MOBILE_DATABASE_PARTIAL_SYNC_STARTED -> {
                syncingMobileDatabase = true
                Log.d(TAG, "Mobile database sync started")
            }
            AlertType.MOBILE_DATABASE_SYNC_COMPLETE,
            AlertType.MOBILE_DATABASE_PARTIAL_SYNC_COMPLETED -> {
                syncingMobileDatabase = false
                Log.d(TAG, "Mobile database sync complete, triggering minimal backfill")
                scope.launch { kickoffBackfill(minimal = true) }
            }
            AlertType.MOBILE_DATABASE_SYNCING -> {
                Log.d(TAG, "Mobile database syncing")
            }
            AlertType.RCS_CONNECTION -> {
                Log.d(TAG, "RCS connection established")
            }
            else -> {
                Log.d(TAG, "Unhandled alert type: $alertType")
                return
            }
        }
        // Port of Go's aggressive reconnect on browser inactive
        if (becameInactive && aggressiveReconnect) {
            aggressiveSetActive()
        }
    }

    /** Port of Go's handleSettings (handlegmessages.go:361-395). */
    private fun handleSettings(data: ByteArray) {
        try {
            val updates = UpdateEvents.parseFrom(data)
            if (updates.hasSettingsEvent()) {
                val settingsProto = updates.settingsEvent
                if (settingsProto.getSIMCardsCount() > 0) {
                    val firstSim = settingsProto.getSIMCards(0)
                    if (firstSim.hasSIMData()) {
                        defaultSimPayload = firstSim.getSIMData().getSIMPayload()
                        Log.d(TAG, "handleSettings: stored SIM payload (${settingsProto.getSIMCardsCount()} SIMs)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "handleSettings: could not parse settings: ${e.message}")
        }
    }

    /** Port of Go's handleAccountChange (handlegmessages.go:236-254). */
    private fun handleAccountChange(data: ByteArray) {
        try {
            val updates = UpdateEvents.parseFrom(data)
            if (updates.hasAccountChange()) {
                val change = updates.accountChange
                switchedToGoogleLogin = change.enabled
                Log.i(TAG, "handleAccountChange: account=${change.account} switchedToGoogle=$switchedToGoogleLogin")
                if (!switchedToGoogleLogin) {
                    ready = true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "handleAccountChange: ${e.message}")
        }
    }

    /** Port of Go's aggressiveSetActive (handlegmessages.go:339-359).
     * Called when the browser becomes inactive — retries SetActiveSession. */
    private fun aggressiveSetActive() {
        if (!aggressiveReconnect) return
        scope.launch {
            val sleepTimes = longArrayOf(5_000, 10_000, 30_000)
            for (sleepMs in sleepTimes) {
                if (browserInactiveType.isEmpty()) {
                    Log.i(TAG, "aggressiveSetActive: session became active on its own")
                    return@launch
                }
                Log.i(TAG, "aggressiveSetActive: sleeping ${sleepMs}ms")
                kotlinx.coroutines.delay(sleepMs)
                if (browserInactiveType.isEmpty()) {
                    Log.i(TAG, "aggressiveSetActive: session became active on its own")
                    return@launch
                }
                try {
                    sessionHandler.setActiveSession()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "aggressiveSetActive failed: ${e.message}")
                }
            }
        }
    }

    /** Port of Go's hackyResetActive (2.3). Waits 7s, retries SetActive,
     *  and if still not ready, attempts full reconnect. */
    @Volatile private var didHackySetActive = false
    private fun hackyResetActive() {
        if (didHackySetActive) return
        didHackySetActive = true
        noDataReceivedRecently = false
        lastDataReceived = 0L
        scope.launch {
            kotlinx.coroutines.delay(7_000)
            if (!ready && phoneResponding) {
                Log.w(TAG, "hackyResetActive: client not ready, retrying setActiveSession")
                try {
                    sessionHandler.setActiveSession()
                } catch (e: Exception) {
                    Log.w(TAG, "hackyResetActive: setActiveSession failed: ${e.message}")
                }
                kotlinx.coroutines.delay(7_000)
                if (!ready && phoneResponding) {
                    Log.w(TAG, "hackyResetActive: still not ready, reconnecting long-poll")
                    longPoll.stop()
                    longPoll.start(scope)
                }
            }
        }
    }

    /** Variation selector: ensure emoji is fully qualified with VS16 (issue 4). */
    private fun fullyQualifyEmoji(emoji: String): String {
        if (emoji.isEmpty() || emoji.contains('\uFE0F')) return emoji
        // Only append VS16 to single-codepoint emoji that aren't already qualified
        val codePointCount = emoji.codePointCount(0, emoji.length)
        if (codePointCount == 1) return "$emoji\uFE0F"
        return emoji
    }

    /** Port of libgm gmproto.UnicodeToEmojiType (emojitype.go). VS16-insensitive. */
    private fun unicodeToEmojiType(emoji: String): EmojiType = when (emoji.replace("\uFE0F", "")) {
        "\uD83D\uDC4D" -> EmojiType.LIKE          // 👍
        "\uD83D\uDE0D" -> EmojiType.LOVE          // 😍
        "\uD83D\uDE02" -> EmojiType.LAUGH         // 😂
        "\uD83D\uDE2E" -> EmojiType.SURPRISED     // 😮
        "\uD83D\uDE25" -> EmojiType.SAD           // 😥
        "\uD83D\uDE20" -> EmojiType.ANGRY         // 😠
        "\uD83D\uDC4E" -> EmojiType.DISLIKE       // 👎
        "\uD83E\uDD14" -> EmojiType.QUESTIONING   // 🤔
        "\uD83D\uDE22" -> EmojiType.CRYING_FACE   // 😢
        "\uD83D\uDE21" -> EmojiType.POUTING_FACE  // 😡
        "\u2764" -> EmojiType.RED_HEART           // ❤
        else -> EmojiType.CUSTOM
    }

    /** SHA-256 based deduplication ring buffer. Port of Go's deduplicateUpdate. */
    private fun deduplicateUpdate(id: String, data: ByteArray): Boolean {
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
        synchronized(dedupLock) {
            for (i in recentUpdatesPtr + recentUpdates.size - 1 downTo recentUpdatesPtr) {
                val item = recentUpdates[i % recentUpdates.size] ?: continue
                if (item.id == id) {
                    if (item.hash.contentEquals(hash)) {
                        Log.d(TAG, "dedup: ignoring duplicate update id=$id")
                        return true
                    }
                    break
                }
            }
            recentUpdates[recentUpdatesPtr] = UpdateDedupItem(id, hash)
            recentUpdatesPtr = (recentUpdatesPtr + 1) % recentUpdates.size
        }
        return false
    }

    /**
     * Load the recent messages for [conversationId] via LIST_MESSAGES.
     * The response flows back through the long-poll into
     * [handleDataMessage] which calls [emitMessage] for each row.
     *
     * Idempotent on the wire — Room upsert deduplicates by message id.
     * Strip the source prefix because the relay only knows Google's
     * thread id.
     */
    fun fetchMessages(conversationId: String, count: Int = 100, cursor: client.Client.Cursor? = null) {
        if (_state.value !is State.Connected) return
        scope.launch {
            val webId = conversationId.substringAfter(':', conversationId)
            Log.i(TAG, "fetchMessages convId=$webId count=$count cursor=${cursor != null}")
            val reqBuilder = ListMessagesRequest.newBuilder()
                .setConversationID(webId)
                .setCount(count.toLong())
            cursor?.let { reqBuilder.setCursor(it) }
            val req = reqBuilder.build()
            val resp = sessionHandler.sendAndWait(ActionType.LIST_MESSAGES, req)
            if (resp == null) Log.w(TAG, "fetchMessages: no response")
        }
    }

    /**
     * Post-connect sequence matching Go's `postConnect`:
     * send pending acks, wait for long-poll to settle, set active session, then backfill.
     */
    private suspend fun postConnect() {
        kotlinx.coroutines.delay(2_000)
        // If old messages haven't drained yet, wait up to 3 more seconds
        // (port of Go's skipCount check in postConnect)
        if (longPoll.skipCount > 0) {
            Log.w(TAG, "skipCount is non-zero (${longPoll.skipCount}) in postConnect, waiting longer")
            repeat(3) {
                if (longPoll.skipCount <= 0) return@repeat
                kotlinx.coroutines.delay(1_000)
            }
            if (longPoll.skipCount > 0) {
                Log.w(TAG, "skipCount is still non-zero (${longPoll.skipCount})")
            }
            // Port of Go's HackySetActiveMayFail event: fired concurrently
            // BEFORE acks/setActive when skipCount was non-zero.
            hackyResetActive()
        }
        // Send acks before set active session (matches Go's postConnect)
        sessionHandler.sendAckRequest()
        kotlinx.coroutines.delay(1_000)
        sessionHandler.setActiveSession()
        kotlinx.coroutines.delay(1_000)
        // Check IsBugleDefault (matches Go's postConnect)
        val bugleResp = sessionHandler.sendAndWait(ActionType.IS_BUGLE_DEFAULT, null, timeoutMs = 10_000)
        if (bugleResp?.decryptedData != null) {
            val parsed = runCatching { client.Client.IsBugleDefaultResponse.parseFrom(bugleResp.decryptedData) }.getOrNull()
            Log.d(TAG, "IsBugleDefault: success=${parsed?.success}")
        }
        kickoffBackfill()
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        scope.launch {
            // Try to refresh token first, then do backfill
            refreshAuthToken()
            kickoffBackfill()
        }
    }

    /** Refresh the tachyon auth token if it's about to expire.
     *  Port of `refreshAuthToken` in `pkg/libgm/client.go`. */
    suspend fun refreshAuthToken() {
        val currentAuth = auth
        val browser = currentAuth.browser() ?: return

        val now = System.currentTimeMillis()
        val timeUntilExpiry = currentAuth.tachyonExpiryMs - now
        if (timeUntilExpiry > REFRESH_TACHYON_BUFFER_MS) {
            Log.d(TAG, "Token refresh not needed, expires in ${timeUntilExpiry / 1000}s")
            return
        }

        Log.i(TAG, "Refreshing auth token (expires in ${timeUntilExpiry / 1000}s)")

        try {
            val requestId = Uuid.random().toString()
            val timestamp = System.currentTimeMillis() * 1000

            // sign() uses SHA256withECDSA which hashes internally —
            // pass the raw bytes, NOT a pre-computed SHA-256 digest.
            val signData = "$requestId:$timestamp".toByteArray(Charsets.UTF_8)
            val signature = currentAuth.refreshKey.sign(signData)

            val tachyonToken = currentAuth.tachyonToken() ?: return
            val authMessage = Authentication.AuthMessage.newBuilder()
                .setRequestID(requestId)
                .setTachyonAuthToken(ByteString.copyFrom(tachyonToken))
                .setNetwork(currentAuth.authNetwork())
                .setConfigVersion(PairFlow.ConfigVersion)
                .build()

            val refreshRequest = Authentication.RegisterRefreshRequest.newBuilder()
                .setMessageAuth(authMessage)
                .setCurrBrowserDevice(browser)
                .setUnixTimestamp(timestamp)
                .setSignature(ByteString.copyFrom(signature))
                .setParameters(
                    Authentication.RegisterRefreshRequest.Parameters.newBuilder()
                        .setEmptyArr(util.Util.EmptyArr.getDefaultInstance())
                        .build()
                )
                .setMessageType(2)
                .build()

            val response = rpc.postPbLiteDecoded(
                url = Endpoints.RegisterRefreshUrl,
                body = refreshRequest,
                responseTemplate = Authentication.RegisterRefreshResponse.getDefaultInstance()
            )
            val tokenData = response.tokenData
            val newToken = tokenData.tachyonAuthToken
            if (newToken.isEmpty) {
                Log.w(TAG, "Token refresh failed: no token in response")
                return
            }

            var newTtlUs = tokenData.ttl
            if (newTtlUs == 0L) {
                newTtlUs = DEFAULT_TTL_US
            }
            authMutex.withLock {
                auth = currentAuth.copy(
                    tachyonAuthTokenB64 = Base64.encodeToString(newToken.toByteArray(), Base64.NO_WRAP),
                    tachyonTtlUs = newTtlUs,
                    tachyonExpiryMs = System.currentTimeMillis() + (newTtlUs / 1000)
                )
                auth.save(appContext)
            }
            Log.i(TAG, "Auth token refreshed successfully, new expiry in ${newTtlUs / 1000 / 1000}s")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh auth token", e)
        }
    }

    // ----------------------------------------------------------------
    // Long-poll event handling
    // ----------------------------------------------------------------

    private suspend fun handleLongPollEvent(evt: LongPollEvent) {
        when (evt) {
            is LongPollEvent.Paired -> {
                // Hand off to our own scope: handlePaired calls longPoll.stop()
                // which would otherwise cancel the very coroutine we're in
                // (the long-poll's reader) mid-execution.
                scope.launch { handlePaired(evt) }
            }
            LongPollEvent.Revoked -> {
                Log.w(TAG, "pair revoked by phone")
                _state.value = State.Disconnected("Pair revoked")
            }
            is LongPollEvent.Data -> handleDataMessage(evt.msg)
            is LongPollEvent.FatalError -> {
                Log.e(TAG, "long-poll fatal error: ${evt.reason}")
                _state.value = State.Disconnected(evt.reason)
            }
            LongPollEvent.NoDataReceived -> {
                noDataReceivedRecently = true
            }
            is LongPollEvent.TemporaryError -> {
                longPollingError = evt.error
                Log.w(TAG, "long-poll temporary error: ${evt.error}")
            }
            LongPollEvent.Recovered -> {
                longPollingError = null
                Log.i(TAG, "long-poll recovered")
            }
        }
    }

    private suspend fun handlePaired(p: LongPollEvent.Paired) {
        Log.i(TAG, "received Paired event — switching to Connected (ttlUs=${p.tachyonTtlUs})")
        val ttlUs = p.tachyonTtlUs.let { if (it == 0L) DEFAULT_TTL_US else it }
        authMutex.withLock {
            auth = auth.copy(
                mobileDeviceB64 = p.mobileDeviceB64,
                browserDeviceB64 = p.browserDeviceB64,
                tachyonAuthTokenB64 = p.tachyonTokenB64,
                tachyonTtlUs = ttlUs,
                tachyonExpiryMs = System.currentTimeMillis() + (ttlUs / 1000),
            )
            auth.save(appContext)
        }
        _state.value = State.Connected

        // CRITICAL: the long-poll that received this Paired event is
        // still authenticated with the INITIAL (pre-pair) tachyon token.
        // The relay routes responses keyed by the long-poll's auth
        // token; subsequent SendMessage calls use the new PERMANENT
        // token after pair, so their responses get routed to a
        // long-poll that doesn't exist. We must close + reopen the
        // long-poll to start listening with the permanent token.
        //
        // Sleep 2 s first to let the phone persist the pair data — if
        // we reconnect too quickly the phone may not recognize the
        // session and silently unpair us (same trick libgm uses in
        // `pair.go` completePairing).
        Log.i(TAG, "sleeping 2s before reconnecting long-poll with permanent token")
        kotlinx.coroutines.delay(2_000)
        longPoll.stop()
        longPoll.start(scope)

        // The init()/start() paths start the ack ticker before postConnect(); the
        // in-session first-pair path must do the same or acks never fire after QR pair.
        sessionHandler.startAckInterval(scope)

        postConnect()
    }

    private suspend fun handleDataMessage(msg: IncomingRpc) {
        // Detect GAIA logout signal — port of Go's hackyLoggedOutBytes check.
        // When the relay sends GET_UPDATES with no encrypted payload and
        // unencrypted bytes [0x72, 0x00], it means the Google account was
        // logged out on the phone side.
        if (msg.action == ActionType.GET_UPDATES && msg.decryptedData == null) {
            val unenc = msg.unencryptedData
            if (unenc != null && unenc.size == 2 &&
                unenc[0] == 0x72.toByte() && unenc[1] == 0x00.toByte()
            ) {
                Log.w(TAG, "detected GAIA logout signal (hackyLoggedOutBytes)")
                _state.value = State.Disconnected("Logged out from Google account")
                return
            }
        }
        val data = msg.decryptedData ?: return
        when (msg.action) {
            ActionType.LIST_CONVERSATIONS -> {
                val resp = runCatching { ListConversationsResponse.parseFrom(data) }.getOrNull() ?: return
                Log.i(TAG, "backfill: ${resp.conversationsCount} conversations")
                for (i in 0 until resp.conversationsCount) emitConversation(resp.getConversations(i))
            }
            ActionType.LIST_MESSAGES -> {
                val resp = runCatching { ListMessagesResponse.parseFrom(data) }.getOrNull() ?: return
                Log.i(TAG, "thread fill: ${resp.messagesCount} messages")
                val rows = (0 until resp.messagesCount).map { idx ->
                    buildMessageRow(resp.getMessages(idx))
                }
                com.vayunmathur.messages.util.MessagesSessionManager.bulkUpsertMessages(rows)
            }
            ActionType.GET_UPDATES -> handleGetUpdates(data, msg.isOld)
            else -> Log.d(TAG, "unhandled data action ${msg.action}")
        }
    }

    private suspend fun emitConversation(c: conversations.Conversations.Conversation) {
        // Cache chat info for group read receipts and phone resolution
        chatInfoCache[c.conversationID] = c

        // Spam/blocked/deleted conversation handling: emit delete event instead
        when (c.status) {
            ConversationStatus.SPAM_FOLDER,
            ConversationStatus.BLOCKED_FOLDER,
            ConversationStatus.DELETED -> {
                Log.d(TAG, "emitConversation: ${c.status} conv=${c.conversationID}, emitting delete")
                _events.emit(GMEvent.ConversationDeleted(source = source, conversationId = c.conversationID))
                return
            }
            else -> { /* active/archived — continue normally */ }
        }

        c.defaultOutgoingID.takeIf { it.isNotBlank() }?.let { outgoingIds[c.conversationID] = it }

        // Track conversation type/sendMode/forceRCS for outbound message routing
        conversationTypes[c.conversationID] = c.type
        conversationSendModes[c.conversationID] = c.sendMode

        val otherParticipants = (0 until c.participantsCount)
            .map { c.getParticipants(it) }
            .filter { !it.isMe }

        val isGroup = c.isGroupChat || otherParticipants.size > 1
        val peerPhone = otherParticipants.firstOrNull { it.id.number.isNotBlank() }?.id?.number

        val contact = if (!isGroup) {
            peerPhone?.let { ContactResolver.lookup(appContext, it) }
        } else null

        val displayName = when {
            isGroup -> groupLabel(c, otherParticipants)
            else -> contact?.displayName ?: peerPhone ?: c.name.takeIf { it.isNotBlank() }
        }

        val type = when (c.type) {
            ConversationType.SMS -> "SMS"
            ConversationType.RCS -> "RCS"
            else -> null
        }

        _events.emit(
            GMEvent.ConversationUpdate(
                source = source,
                conversationId = c.conversationID,
                peerName = displayName,
                peerPhone = if (isGroup) null else peerPhone,
                avatarUrl = contact?.photoUri,
                lastPreview = if (c.hasLatestMessage()) c.latestMessage.displayContent.takeIf { it.isNotBlank() } else null,
                lastTimestamp = toMillis(c.lastMessageTimestamp),
                unreadCount = if (c.unread) 1 else 0,
                isGroup = isGroup,
                participantCount = otherParticipants.size,
                conversationType = type,
                outgoingId = c.defaultOutgoingID.takeIf { it.isNotBlank() },
            )
        )
    }

    /** Build a "Alice, Bob & 2 others" label for a group. Uses the
     *  device's contact name for each participant when available, else
     *  the participant's fullName from the relay, else their number. */
    private fun groupLabel(
        c: conversations.Conversations.Conversation,
        others: List<conversations.Conversations.Participant>,
    ): String {
        // Prefer the explicit thread name (RCS groups often have one).
        val explicit = c.name.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val names = others.map { p ->
            val phone = p.id.number.takeIf { it.isNotBlank() }
            val deviceName = phone?.let { ContactResolver.lookup(appContext, it)?.displayName }
            deviceName
                ?: p.firstName.takeIf { it.isNotBlank() }
                ?: p.fullName.takeIf { it.isNotBlank() }
                ?: phone
                ?: "Unknown"
        }
        return when {
            names.isEmpty() -> "Group"
            names.size <= 2 -> names.joinToString(", ")
            else -> names.take(2).joinToString(", ") + " & ${names.size - 2} others"
        }
    }

    /** Pure transformation of one proto Message into a Room row.
     *  Used by the bulk LIST_MESSAGES path. */
    private fun buildMessageRow(m: conversations.Conversations.Message): com.vayunmathur.messages.data.Message {
        val body = (0 until m.messageInfoCount)
            .mapNotNull { idx ->
                val info = m.getMessageInfo(idx)
                when {
                    info.hasMessageContent() -> info.messageContent.content
                    info.hasMediaContent() -> mediaLabel(info.mediaContent)
                    else -> null
                }
            }
            .joinToString("\n")
        val outgoing = m.hasSenderParticipant() && m.senderParticipant.isMe
        val sourcePrefix = source.idPrefix
        return com.vayunmathur.messages.data.Message(
            id = "$sourcePrefix:${m.messageID}",
            conversationId = "$sourcePrefix:${m.conversationID}",
            body = body,
            direction = if (outgoing) com.vayunmathur.messages.data.MessageDirection.OUTGOING
                else com.vayunmathur.messages.data.MessageDirection.INCOMING,
            state = if (outgoing) com.vayunmathur.messages.data.MessageState.SENT
                else com.vayunmathur.messages.data.MessageState.DELIVERED,
            timestamp = toMillis(m.timestamp),
            senderName = if (m.hasSenderParticipant()) {
                m.senderParticipant.fullName.takeIf { it.isNotBlank() }
                    ?: m.senderParticipant.firstName.takeIf { it.isNotBlank() }
            } else null,
            reactionsJson = extractReactionsJson(m),
        )
    }

    /**
     * The relay returns timestamps in **microseconds** since epoch (see
     * `time.UnixMicro(conv.GetLastMessageTimestamp())` in the Go bridge
     * `pkg/connector/chatsync.go`). Everywhere else in this app (Room,
     * notifications, [java.util.Date], Voice's emission path) expects
     * **milliseconds**. Convert at the boundary so we never mix units.
     */
    private fun toMillis(usec: Long): Long = usec / 1000

    private fun mediaLabel(mc: conversations.Conversations.MediaContent): String {
        val type = mc.mimeType.substringBefore('/').lowercase()
        val name = mc.mediaName.takeIf { it.isNotBlank() }
        return when {
            name != null -> "\uD83D\uDCCE $name"
            type == "image" -> "\uD83D\uDDBC\uFE0F [image]"
            type == "video" -> "\uD83C\uDFA5 [video]"
            type == "audio" -> "\uD83C\uDFA7 [audio]"
            else -> "\uD83D\uDCCE [attachment]"
        }
    }

    /** Roll up the per-emoji reaction entries on a Message into the
     *  [count: Int] aggregate we store. */
    private fun extractReactionsJson(m: conversations.Conversations.Message): String? {
        if (m.reactionsCount == 0) return null
        val reactions = (0 until m.reactionsCount).mapNotNull { idx ->
            val entry = m.getReactions(idx)
            val emoji = entry.data.unicode.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.vayunmathur.messages.data.Reaction(
                emoji = emoji,
                count = entry.participantIDsCount.coerceAtLeast(1),
            )
        }
        if (reactions.isEmpty()) return null
        return kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                com.vayunmathur.messages.data.Reaction.serializer()
            ),
            reactions,
        )
    }

    private suspend fun emitMessage(m: conversations.Conversations.Message) {
        val outgoing = m.hasSenderParticipant() && m.senderParticipant.isMe
        val statusType = m.messageStatus.status
        val statusNum = statusType.number

        // Remote echo handling (1.6): store metadata for outgoing messages
        if (outgoing && m.tmpID.isNotBlank()) {
            pendingOutgoing.remove(m.tmpID)
            val meta = messageMeta.getOrPut(m.messageID) { MessageMeta() }
            meta.isOutgoing = true
            meta.textHash = computeContentHash(m)
            meta.globalPartCount = m.messageInfoCount
            for (i in 0 until m.messageInfoCount) {
                val info = m.getMessageInfo(i)
                if (info.hasMediaContent()) {
                    meta.statusNum = statusNum
                }
            }
        }

        // MESSAGE_DELETED: suppress during sync (2.8), then emit delete event
        if (statusType == MessageStatusType.MESSAGE_DELETED) {
            if (syncingMobileDatabase || syncingConversations) {
                Log.d(TAG, "suppressing MESSAGE_DELETED during sync id=${m.messageID}")
                return
            }
            Log.d(TAG, "emitMessage: MESSAGE_DELETED id=${m.messageID}")
            messageContentHashes.remove(m.messageID)
            messageMeta.remove(m.messageID)
            _events.emit(
                GMEvent.MessageDeleted(
                    source = source,
                    messageId = m.messageID,
                    conversationId = m.conversationID,
                    timestamp = toMillis(m.timestamp),
                )
            )
            return
        }

        // Tombstone filtering (3.7): skip protocol switch notices, group creation, etc.
        val isDm = chatInfoCache[m.conversationID]?.let { !it.isGroupChat } ?: true
        if (shouldIgnoreStatus(statusNum, isDm)) {
            handleTombstoneForceRcs(m, statusNum)
            return
        }

        // Download status ranking (3.1): prevent status downgrades
        val existingMeta = messageMeta[m.messageID]
        if (existingMeta != null && downloadStatusRank(statusNum) < downloadStatusRank(existingMeta.statusNum)) {
            Log.d(TAG, "ignoring status downgrade for ${m.messageID}")
            return
        }

        // Chat ID change detection (3.3): detect if message moved between conversations
        if (existingMeta != null && existingMeta.statusNum != 0) {
            // Existing message — we're handling an update, not a new message
        }

        // Get or create per-message metadata
        val meta = messageMeta.getOrPut(m.messageID) { MessageMeta() }
        meta.isOutgoing = outgoing
        meta.statusNum = statusNum
        meta.globalPartCount = m.messageInfoCount

        // Skip auto-downloading statuses — message data is incomplete (Go returns ErrIgnoringRemoteEvent)
        if (statusType == MessageStatusType.INCOMING_AUTO_DOWNLOADING ||
            statusType == MessageStatusType.INCOMING_RETRYING_AUTO_DOWNLOAD) {
            Log.d(TAG, "ignoring auto-downloading status for ${m.messageID}")
            return
        }

        val statusStr = when (statusType) {
            MessageStatusType.OUTGOING_DELIVERED -> "delivered"
            MessageStatusType.OUTGOING_DISPLAYED -> "read"
            MessageStatusType.OUTGOING_COMPLETE -> "sent"
            MessageStatusType.INCOMING_DOWNLOAD_FAILED -> "download_failed"
            MessageStatusType.INCOMING_MANUAL_DOWNLOADING,
            MessageStatusType.INCOMING_RETRYING_MANUAL_DOWNLOAD -> "downloading"
            MessageStatusType.INCOMING_DOWNLOAD_FAILED_SIM_HAS_NO_DATA -> "download_failed_no_data"
            MessageStatusType.INCOMING_DOWNLOAD_CANCELED -> "download_canceled"
            else -> null
        }

        // MSS events (3.5): send success confirmation
        if (outgoing && !meta.mssSent && isSuccessfullySentStatus(statusType)) {
            meta.mssSent = true
        }

        // MSS failure event
        if (!meta.mssFailSent && !meta.mssSent) {
            val failMsg = getFailMessage(statusNum)
            if (failMsg.isNotEmpty()) {
                meta.mssFailSent = true
                _events.emit(GMEvent.SendFailed(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = m.messageID,
                    errorMessage = failMsg,
                ))
            }
        }

        // MSS delivery receipt (3.5)
        if (!meta.mssDeliverySent &&
            (statusType == MessageStatusType.OUTGOING_DELIVERED ||
             statusType == MessageStatusType.OUTGOING_DISPLAYED)) {
            meta.mssDeliverySent = true
            _events.emit(
                GMEvent.ReadReceipt(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = m.messageID,
                    timestamp = toMillis(m.timestamp),
                    isDelivery = true,
                )
            )
        }

        // DM read receipt
        if (!meta.readReceiptSent && statusType == MessageStatusType.OUTGOING_DISPLAYED) {
            meta.readReceiptSent = true
            _events.emit(
                GMEvent.ReadReceipt(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = m.messageID,
                    timestamp = toMillis(m.timestamp),
                )
            )
        }

        // Group read receipts from status text (3.6)
        if (statusType == MessageStatusType.OUTGOING_DISPLAYED) {
            val statusText = runCatching { m.messageStatus.statusText }.getOrNull().orEmpty()
            if (statusText.startsWith("Read by ") && statusText != meta.globalStatusText) {
                handleGroupReadReceipts(m, meta, statusText)
            }
        }

        // Build message body + attempt media download
        var mediaBytes: ByteArray? = null
        var mediaMime: String? = null
        var mediaName: String? = null
        val body = (0 until m.messageInfoCount)
            .mapNotNull { idx ->
                val info = m.getMessageInfo(idx)
                when {
                    info.hasMessageContent() -> info.messageContent.content
                    info.hasMediaContent() -> {
                        val mc = info.mediaContent
                        // Prefer the full-size attachment; fall back to the thumbnail when the full
                        // media is unavailable or its download fails. Ref libgm DownloadMedia +
                        // thumbnail handling (MediaContent.thumbnailMediaID/thumbnailDecryptionKey).
                        val full = if (mc.mediaID.isNotBlank() && mc.decryptionKey.size() > 0) {
                            try {
                                media.download(mc.mediaID, mc.decryptionKey.toByteArray())
                            } catch (e: Exception) {
                                Log.w(TAG, "media download failed: ${e.message}")
                                null
                            }
                        } else null
                        val downloaded = full ?: run {
                            if (mc.thumbnailMediaID.isNotBlank() && mc.thumbnailDecryptionKey.size() > 0) {
                                try {
                                    media.download(mc.thumbnailMediaID, mc.thumbnailDecryptionKey.toByteArray())
                                } catch (e: Exception) {
                                    Log.w(TAG, "thumbnail download failed: ${e.message}")
                                    null
                                }
                            } else null
                        }
                        if (downloaded != null) {
                            mediaBytes = downloaded
                            mediaMime = mc.mimeType
                            mediaName = mc.mediaName.takeIf { it.isNotBlank() }
                            mc.mediaName.takeIf { it.isNotBlank() } ?: "[media]"
                        } else {
                            mediaLabel(mc)
                        }
                    }
                    else -> null
                }
            }
            .joinToString("\n")
            .ifBlank { "" }

        // Edit detection
        val contentHash = computeContentHash(m)
        val previousHash = messageContentHashes.put(m.messageID, contentHash)
        if (previousHash != null && previousHash != contentHash) {
            Log.d(TAG, "emitMessage: edit detected id=${m.messageID}")
            _events.emit(
                GMEvent.MessageEdited(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = m.messageID,
                    newBody = body,
                    timestamp = toMillis(m.timestamp),
                )
            )
        }

        // Reaction sync per-user
        syncReactions(m)

        _events.emit(
            GMEvent.MessageUpdate(
                source = source,
                conversationId = m.conversationID,
                messageId = m.messageID,
                body = body,
                outgoing = outgoing,
                timestamp = toMillis(m.timestamp),
                senderName = if (m.hasSenderParticipant()) {
                    m.senderParticipant.fullName.takeIf { it.isNotBlank() }
                        ?: m.senderParticipant.firstName.takeIf { it.isNotBlank() }
                } else null,
                reactionsJson = extractReactionsJson(m),
                mediaData = mediaBytes,
                mediaMime = mediaMime,
                mediaName = mediaName,
                statusType = statusStr,
            )
        )
        if (!outgoing && body.isNotEmpty()) {
            _events.emit(
                GMEvent.IncomingMessage(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = m.messageID,
                    body = body,
                    peerName = if (m.hasSenderParticipant()) {
                        m.senderParticipant.fullName.takeIf { it.isNotBlank() }
                    } else null,
                    peerPhone = if (m.hasSenderParticipant()) {
                        m.senderParticipant.id.number.takeIf { it.isNotBlank() }
                    } else null,
                    timestamp = toMillis(m.timestamp),
                )
            )
        }
    }

    /** Compute a hash of text + media IDs for edit detection. */
    private fun computeContentHash(m: conversations.Conversations.Message): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until m.messageInfoCount) {
            val info = m.getMessageInfo(i)
            if (info.hasMessageContent()) {
                digest.update(info.messageContent.content.toByteArray(Charsets.UTF_8))
            }
            if (info.hasMediaContent()) {
                val mediaId = info.mediaContent.mediaID.ifBlank { info.mediaContent.thumbnailMediaID }
                digest.update(mediaId.toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Emit per-user reaction add/remove events, deduplicating via [reactionState]. */
    private suspend fun syncReactions(m: conversations.Conversations.Message) {
        val currentReactions = mutableMapOf<Pair<String, String>, String>()
        for (i in 0 until m.reactionsCount) {
            val entry = m.getReactions(i)
            val emoji = entry.data.unicode.takeIf { it.isNotBlank() } ?: continue
            for (j in 0 until entry.participantIDsCount) {
                val userId = entry.getParticipantIDs(j)
                currentReactions[Pair(m.messageID, userId)] = emoji
            }
        }
        // Detect new or changed reactions
        for ((key, emoji) in currentReactions) {
            val prev = reactionState.put(key, emoji)
            if (prev == null || prev != emoji) {
                _events.emit(
                    GMEvent.ReactionReceived(
                        source = source,
                        conversationId = m.conversationID,
                        messageId = key.first,
                        senderId = key.second,
                        emoji = emoji,
                    )
                )
            }
        }
        // Detect removed reactions
        val removedKeys = reactionState.keys.filter {
            it.first == m.messageID && it !in currentReactions
        }
        for (key in removedKeys) {
            reactionState.remove(key)
            _events.emit(
                GMEvent.ReactionRemoved(
                    source = source,
                    conversationId = m.conversationID,
                    messageId = key.first,
                    senderId = key.second,
                )
            )
        }
    }

    private fun kickoffBackfill(minimal: Boolean = false) {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            syncingConversations = true
            try {
                // Minimal sync optimization: if no recent messages, skip full sync
                if (minimal && !noDataReceivedRecently) {
                    Log.d(TAG, "minimal backfill: no recent data, skipping full sync")
                    syncingConversations = false
                    return@launch
                }
                val msgType = if (!conversationsFetchedOnce) {
                    conversationsFetchedOnce = true
                    MessageType.BUGLE_ANNOTATION
                } else {
                    MessageType.BUGLE_MESSAGE
                }
                Log.i(TAG, "kicking off LIST_CONVERSATIONS (messageType=$msgType minimal=$minimal)")
                val req = ListConversationsRequest.newBuilder()
                    .setCount(50)
                    .build()
                val resp = sessionHandler.sendAndWait(
                    ActionType.LIST_CONVERSATIONS,
                    req,
                    messageType = msgType,
                )
                if (resp == null) {
                    Log.w(TAG, "backfill: no response (timeout?)")
                } else {
                    Log.i(TAG, "backfill response received (decryptedBytes=${resp.decryptedData?.size ?: 0})")
                }
            } finally {
                syncingConversations = false
            }
        }
    }

    // ----------------------------------------------------------------
    // Helper functions ported from Go connector
    // ----------------------------------------------------------------

    /** Tombstone status filtering (3.7). Port of Go's shouldIgnoreStatus.
     *  Tombstones are in the 200-299 status range. */
    private fun shouldIgnoreStatus(statusNum: Int, isDm: Boolean): Boolean {
        if (statusNum < 200 || statusNum >= 300) return false
        val status = MessageStatusType.forNumber(statusNum) ?: return false
        // DM-only tombstones (protocol switches between SMS/RCS/E2EE)
        val dmOnlyTombstones = setOf(
            MessageStatusType.TOMBSTONE_PROTOCOL_SWITCH_TO_TEXT,
            MessageStatusType.TOMBSTONE_PROTOCOL_SWITCH_TO_RCS,
            MessageStatusType.TOMBSTONE_PROTOCOL_SWITCH_TO_ENCRYPTED_RCS,
            MessageStatusType.TOMBSTONE_PROTOCOL_SWITCH_TO_ENCRYPTED_RCS_INFO,
            MessageStatusType.TOMBSTONE_ONE_ON_ONE_SMS_CREATED,
            MessageStatusType.TOMBSTONE_ONE_ON_ONE_RCS_CREATED,
            MessageStatusType.TOMBSTONE_ENCRYPTED_ONE_ON_ONE_RCS_CREATED,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS,
        )
        // Always-ignore tombstones (group creation, identity changes, etc.)
        val alwaysIgnore = setOf(
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_ENCRYPTED_GROUP_CREATED,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_GROUP_PROTOCOL_SWITCH_E2EE_TO_RCS,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_GROUP_PROTOCOL_SWITCH_RCS_TO_E2EE,
            MessageStatusType.TOMBSTONE_RCS_GROUP_CREATED,
            MessageStatusType.TOMBSTONE_MMS_GROUP_CREATED,
            MessageStatusType.TOMBSTONE_SMS_BROADCAST_CREATED,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PARTICIPANT_THEME_CHANGE,
            MessageStatusType.TOMBSTONE_SHOW_LINK_PREVIEWS,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_ACTIVE_SELF_IDENTITY_CHANGED,
        )
        if (status in alwaysIgnore) return true
        if (status in dmOnlyTombstones) return isDm
        return false
    }

    /** Handle ForceRCS toggle from tombstone messages.
     *  Port of Go's ConvertMessage tombstone handling. */
    private fun handleTombstoneForceRcs(m: conversations.Conversations.Message, statusNum: Int) {
        val status = MessageStatusType.forNumber(statusNum) ?: return
        when (status) {
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_RCS_TO_E2EE,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_TEXT_TO_E2EE -> {
                if (conversationForceRcs[m.conversationID] != true) {
                    conversationForceRcs[m.conversationID] = true
                    Log.d(TAG, "tombstone: ForceRCS=true for ${m.conversationID}")
                }
            }
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_RCS,
            MessageStatusType.MESSAGE_STATUS_TOMBSTONE_PROTOCOL_SWITCH_E2EE_TO_TEXT -> {
                if (conversationForceRcs[m.conversationID] == true) {
                    conversationForceRcs[m.conversationID] = false
                    Log.d(TAG, "tombstone: ForceRCS=false for ${m.conversationID}")
                }
            }
            else -> { /* not a ForceRCS-related tombstone */ }
        }
    }

    /** Download status ranking (3.1). Port of Go's downloadStatusRank.
     *  Higher rank = more progressed. Prevents downgrades. */
    private fun downloadStatusRank(statusNum: Int): Int {
        val status = MessageStatusType.forNumber(statusNum) ?: return 100
        return when (status) {
            MessageStatusType.INCOMING_AUTO_DOWNLOADING -> 0
            MessageStatusType.INCOMING_MANUAL_DOWNLOADING,
            MessageStatusType.INCOMING_RETRYING_AUTO_DOWNLOAD,
            MessageStatusType.INCOMING_RETRYING_MANUAL_DOWNLOAD,
            MessageStatusType.INCOMING_DOWNLOAD_FAILED,
            MessageStatusType.INCOMING_DOWNLOAD_FAILED_TOO_LARGE,
            MessageStatusType.INCOMING_DOWNLOAD_FAILED_SIM_HAS_NO_DATA,
            MessageStatusType.INCOMING_DOWNLOAD_CANCELED,
            MessageStatusType.INCOMING_YET_TO_MANUAL_DOWNLOAD -> 1
            else -> 100
        }
    }

    /** Check if a status indicates successful send. Port of Go's isSuccessfullySentStatus. */
    private fun isSuccessfullySentStatus(status: MessageStatusType): Boolean = when (status) {
        MessageStatusType.OUTGOING_DELIVERED,
        MessageStatusType.OUTGOING_COMPLETE,
        MessageStatusType.OUTGOING_DISPLAYED -> true
        else -> false
    }

    /** Failure message for outgoing message statuses. Port of Go's getFailMessage. */
    private fun getFailMessage(statusNum: Int): String {
        val status = MessageStatusType.forNumber(statusNum) ?: return ""
        return when (status) {
            MessageStatusType.OUTGOING_FAILED_TOO_LARGE -> "too large"
            MessageStatusType.OUTGOING_FAILED_RECIPIENT_LOST_RCS -> "recipient lost RCS support"
            MessageStatusType.OUTGOING_FAILED_RECIPIENT_LOST_ENCRYPTION -> "recipient lost encryption support"
            MessageStatusType.OUTGOING_FAILED_RECIPIENT_DID_NOT_DECRYPT,
            MessageStatusType.OUTGOING_FAILED_RECIPIENT_DID_NOT_DECRYPT_NO_MORE_RETRY -> "recipient failed to decrypt message"
            MessageStatusType.OUTGOING_FAILED_GENERIC -> "generic carrier error, check google messages and try again"
            MessageStatusType.OUTGOING_FAILED_NO_RETRY_NO_FALLBACK -> "no fallback error"
            MessageStatusType.OUTGOING_FAILED_EMERGENCY_NUMBER -> "emergency number error"
            MessageStatusType.OUTGOING_CANCELED -> "canceled"
            else -> ""
        }
    }

    /** Detailed error message from SendMessageResponse. Port of Go's responseStatusError. */
    private fun getSendFailureMessage(resp: SendMessageResponse): String? {
        if (resp.status == SendMessageResponse.Status.SUCCESS) return null
        return when (resp.status.number) {
            0 -> {
                if (resp.hasGoogleAccountSwitch() &&
                    resp.googleAccountSwitch.account.contains('@'))
                    "Switch back to QR pairing or log in with Google account to send messages"
                else "Unrecognized response status 0"
            }
            2 -> "Unknown permanent error"                      // FAILURE_2
            3 -> "Unknown temporary error"                      // FAILURE_3
            4 -> "Google Messages is not your default SMS app"  // FAILURE_4
            else -> "Unrecognized response status ${resp.status.number}"
        }
    }

    /** Parse group read receipts from "Read by Alice, Bob" status text (3.6). */
    private suspend fun handleGroupReadReceipts(
        m: conversations.Conversations.Message,
        meta: MessageMeta,
        statusText: String,
    ) {
        meta.globalStatusText = statusText
        val cached = chatInfoCache[m.conversationID] ?: return
        // Build name → participantID map from cached conversation
        val nameToIds = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until cached.participantsCount) {
            val p = cached.getParticipants(i)
            if (p.isMe) continue
            val name = p.firstName.takeIf { it.isNotBlank() }
                ?: p.formattedNumber.takeIf { it.isNotBlank() }
                ?: continue
            val pid = p.id.participantID.takeIf { it.isNotBlank() } ?: continue
            nameToIds.getOrPut(name) { mutableListOf() }.add(pid)
        }
        val readByStr = statusText.removePrefix("Read by ")
        val newReadBy = mutableListOf<String>()
        for (name in readByStr.split(", ").map { it.trim() }) {
            val ids = nameToIds[name] ?: continue
            if (ids.isNotEmpty()) {
                newReadBy.add(ids.removeAt(0))
            }
        }
        // Emit read receipts for newly-added readers
        val oldReadBy = meta.groupReadBy.toSet()
        for (participantId in newReadBy) {
            if (participantId !in oldReadBy) {
                _events.emit(
                    GMEvent.ReadReceipt(
                        source = source,
                        conversationId = m.conversationID,
                        messageId = m.messageID,
                        senderId = participantId,
                        timestamp = toMillis(m.timestamp),
                    )
                )
            }
        }
        meta.groupReadBy = newReadBy
    }
}
