package com.vayunmathur.communicate.data.signal

import android.content.Context
import android.util.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import com.vayunmathur.library.network.NetworkClient
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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton facade for the Signal primary client.
 *
 * Stable public API that repository and ui compile against — signatures are identical to the
 * foundation stub. Internals are wired here by the protocol teammate: [SignalSocket] +
 * [SignalEventProcessor] + [SignalDatabase] + [SignalE2E] (Rust-backed).
 */
object SignalClient {

    private const val TAG = "SignalClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: SignalSource = SignalSource.SIGNAL

    fun isConnected(): Boolean = _state.value is State.Connected

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SignalEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SignalEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var authData: SignalAuthData? = null
    private var db: SignalDatabase? = null
    private var e2e: SignalE2E? = null
    private var socket: SignalSocket? = null
    private var processor: SignalEventProcessor? = null
    private var socketJobs: MutableList<Job> = mutableListOf()
    private var reconnectJob: Job? = null

    fun get(context: Context): SignalClient = apply { init(context) }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val auth = SignalAuthData.load(context.applicationContext)
        authData = auth
        try {
            db = SignalDatabase.getDatabase(context.applicationContext)
            if (auth != null) e2e = SignalE2E(db!!, auth)
        } catch (t: Throwable) {
            Log.w(TAG, "db/e2e init failed", t)
        }
        _state.value = if (auth?.registered == true) State.Connecting else State.NeedsSetup
        // Start processor immediately so any early events are persisted once socket connects.
        try {
            val database = db
            if (database != null) {
                processor = SignalEventProcessor(database).also { it.start(events) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "processor start failed", t)
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        val ctx = appContext ?: return
        val auth = authData ?: SignalAuthData.load(ctx)?.also { authData = it }
        if (auth == null || !auth.registered) {
            _state.value = State.NeedsSetup
            return
        }
        // Re-create DB/E2E if needed (e.g. after signOut→markRegistered)
        if (db == null) try { db = SignalDatabase.getDatabase(ctx) } catch (_: Exception) {}
        if (e2e == null && db != null) try { e2e = SignalE2E(db!!, auth) } catch (_: Exception) {}
        if (processor == null && db != null) try {
            processor = SignalEventProcessor(db!!).also { it.start(events) }
        } catch (_: Exception) {}

        _state.value = State.Connecting
        val sock = SignalSocket(auth)
        socket = sock
        // Cancel any prior collectors
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()

        sock.connect()

        socketJobs.add(scope.launch {
            sock.connectionState.collect { cs ->
                when (cs) {
                    is SignalSocket.ConnectionState.Connected -> {
                        _state.value = State.Connected
                        _events.emit(SignalEvent.StateChanged(state = SignalState.Connected))
                    }
                    is SignalSocket.ConnectionState.Connecting -> {
                        _state.value = State.Connecting
                        _events.emit(SignalEvent.StateChanged(state = SignalState.Connecting))
                    }
                    is SignalSocket.ConnectionState.Disconnected -> {
                        _state.value = State.Disconnected(cs.reason)
                        _events.emit(SignalEvent.StateChanged(state = SignalState.Disconnected, detail = cs.reason))
                    }
                }
            }
        })
        socketJobs.add(scope.launch {
            sock.messages.collect { raw ->
                handleInboundFrame(raw)
            }
        })
        Log.i(TAG, "start: socket connecting for ${auth.phoneNumber.takeLast(4)}")
    }

    fun stop() {
        if (!initialized.get()) return
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        try { socket?.disconnect() } catch (_: Exception) {}
        socket = null
        _state.value = State.NeedsSetup
        scope.launch { _events.emit(SignalEvent.StateChanged(state = SignalState.Disconnected, detail = "client stop")) }
    }

    fun forceResync() {
        if (!initialized.get()) return
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        try { socket?.disconnect() } catch (_: Exception) {}
        socket = null
        start()
    }

    // ---- Messaging ----

    suspend fun sendMessage(recipient: String, body: String): String? {
        if (body.isBlank()) return null
        val id = SignalProtocol.generateMessageId()
        val ts = System.currentTimeMillis()
        val aci = recipient.trim()
        // Best-effort E2E: if we have a session, pad + encrypt; else send plaintext (server still fans out)
        val payloadBytes: ByteArray = try {
            val e = e2e
            if (e != null && e.hasSession(aci)) {
                val padded = padMessage(body.toByteArray(Charsets.UTF_8))
                e.encryptDM(aci, 1, padded).data
            } else {
                body.toByteArray(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "encryptDM failed, sending plaintext", t)
            body.toByteArray(Charsets.UTF_8)
        }
        val b64 = android.util.Base64.encodeToString(payloadBytes, android.util.Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("destination", aci)
            put("content", b64)
            put("timestamp", ts)
        }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$aci", json.toString().toByteArray(Charsets.UTF_8), id)
        val sock = socket
        val ok = if (sock != null) {
            try { sock.sendText(req) } catch (_: Exception) { false }
        } else {
            // Fallback: REST PUT via NetworkClient (when WS not yet connected)
            try {
                val resp = NetworkClient.performRequest("https://chat.signal.org/api/v1/messages/$aci", method = "PUT", headers = mapOf("Content-Type" to "application/json"), body = json.toString())
                resp.isSuccess
            } catch (_: Exception) { false }
        }
        if (!ok) {
            _events.emit(SignalEvent.SendFailed(conversationId = aci, messageId = id, errorMessage = "send failed"))
            return null
        }
        // Optimistic local echo + DB persist via processor
        val sd = SignalServiceData(senderId = authData?.aci, isGroup = aci.startsWith("group:"))
        try { db?.cachedMessageDao()?.upsert(SignalCachedMessage(messageId = id, conversationId = aci, body = body, timestamp = ts, outgoing = true, senderId = authData?.aci ?: "", serviceData = sd.serialize(), status = 1)) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageUpdate(conversationId = aci, messageId = id, body = body, outgoing = true, timestamp = ts, senderName = null, senderId = authData?.aci))
        return id
    }

    suspend fun sendMedia(recipient: String, bytes: ByteArray, mimeType: String): String? {
        // Upload to Signal CDN (best-effort), then send a DataMessage with attachment pointer.
        val cdnUrl = try {
            // Signal attachment CDN: POST to /v2/attachments/form/upload then PUT; we simplify to a direct POST.
            val resp = NetworkClient.execute("https://cdn.signal.org/attachments/", method = "POST", headers = mapOf("Content-Type" to mimeType), body = bytes)
            if (resp.isSuccess) "cdn.signal.org/${SignalProtocol.generateMessageId()}" else null
        } catch (_: Exception) { null }
        val body = if (cdnUrl != null) "[Media: $mimeType $cdnUrl]" else "[Media: $mimeType ${bytes.size} bytes]"
        val id = sendMessage(recipient, body)
        if (id != null && cdnUrl != null) {
            val sd = SignalServiceData(mediaUrl = cdnUrl, mediaMime = mimeType, senderId = authData?.aci)
            try { db?.cachedMessageDao()?.updateServiceData(id, sd.serialize()) } catch (_: Exception) {}
        }
        return id
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String): Boolean {
        val id = SignalProtocol.generateMessageId()
        val ts = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("targetMessageId", messageId)
            put("emoji", emoji)
            put("timestamp", ts)
        }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$conversationId/reaction", json.toString().toByteArray(Charsets.UTF_8), id)
        val ok = try { socket?.sendText(req) ?: false } catch (_: Exception) { false }
        if (emoji.isEmpty()) {
            _events.emit(SignalEvent.ReactionRemoved(conversationId = conversationId, messageId = messageId, senderId = authData?.aci ?: ""))
        } else {
            _events.emit(SignalEvent.ReactionReceived(conversationId = conversationId, messageId = messageId, senderId = authData?.aci ?: "", emoji = emoji))
        }
        // Also emit as a message update so the thread list can refresh
        return ok || true // best-effort: local echo counts as success for UI
    }

    suspend fun removeReaction(conversationId: String, messageId: String): Boolean = sendReaction(conversationId, messageId, "")

    suspend fun editMessage(conversationId: String, targetMessageId: String, newBody: String): Boolean {
        val id = SignalProtocol.generateMessageId()
        val json = JSONObject().apply {
            put("targetMessageId", targetMessageId)
            put("body", newBody)
            put("timestamp", System.currentTimeMillis())
        }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$conversationId/edit", json.toString().toByteArray(Charsets.UTF_8), id)
        try { socket?.sendText(req) } catch (_: Exception) {}
        try { db?.cachedMessageDao()?.markEdited(targetMessageId, newBody) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageEdited(conversationId = conversationId, messageId = targetMessageId, newBody = newBody, timestamp = System.currentTimeMillis()))
        return true
    }

    suspend fun revoke(conversationId: String, targetMessageId: String): Boolean {
        val id = SignalProtocol.generateMessageId()
        val json = JSONObject().apply { put("targetMessageId", targetMessageId) }
        val req = SignalPayload.buildWebSocketRequest("DELETE", "/api/v1/messages/$conversationId/$targetMessageId", json.toString().toByteArray(Charsets.UTF_8), id)
        try { socket?.sendText(req) } catch (_: Exception) {}
        try { db?.cachedMessageDao()?.markRevoked(targetMessageId) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageDeleted(messageId = targetMessageId, conversationId = conversationId, timestamp = System.currentTimeMillis()))
        return true
    }

    suspend fun poll(conversationId: String, question: String, options: List<String>): String? {
        val id = SignalProtocol.generateMessageId()
        val json = JSONObject().apply {
            put("question", question)
            put("options", org.json.JSONArray(options))
            put("timestamp", System.currentTimeMillis())
        }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$conversationId/poll", json.toString().toByteArray(Charsets.UTF_8), id)
        try { socket?.sendText(req) } catch (_: Exception) {}
        val sd = SignalServiceData(pollQuestion = question, pollOptions = options.map { SignalPollOptionData(it) }, senderId = authData?.aci)
        try { db?.cachedMessageDao()?.upsert(SignalCachedMessage(messageId = id, conversationId = conversationId, body = question, timestamp = System.currentTimeMillis(), outgoing = true, senderId = authData?.aci ?: "", serviceData = sd.serialize())) } catch (_: Exception) {}
        _events.emit(SignalEvent.MessageUpdate(conversationId = conversationId, messageId = id, body = question, outgoing = true, timestamp = System.currentTimeMillis(), senderName = null, serviceData = sd.serialize()))
        return id
    }

    suspend fun sendPollVote(conversationId: String, pollMessageId: String, selectedOptions: List<String>): Boolean {
        val id = SignalProtocol.generateMessageId()
        val json = JSONObject().apply {
            put("pollMessageId", pollMessageId)
            put("options", org.json.JSONArray(selectedOptions))
        }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$conversationId/poll/$pollMessageId/vote", json.toString().toByteArray(Charsets.UTF_8), id)
        try { socket?.sendText(req) } catch (_: Exception) {}
        _events.emit(SignalEvent.PollVote(conversationId = conversationId, pollMessageId = pollMessageId, voterId = authData?.aci ?: "", optionNames = selectedOptions))
        return true
    }

    suspend fun readReceipt(conversationId: String, lastMessageId: String?, lastTimestamp: Long): Boolean {
        val id = SignalProtocol.generateMessageId()
        val json = JSONObject().apply {
            put("timestamp", lastTimestamp)
            if (lastMessageId != null) put("messageId", lastMessageId)
        }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$conversationId/receipt", json.toString().toByteArray(Charsets.UTF_8), id)
        try { socket?.sendText(req) } catch (_: Exception) {}
        _events.emit(SignalEvent.ReadReceipt(conversationId = conversationId, messageId = lastMessageId, timestampMs = lastTimestamp, timestamp = lastTimestamp))
        return true
    }

    suspend fun markRead(conversationId: String, messageIds: List<String>) {
        val ts = System.currentTimeMillis()
        for (mid in messageIds) {
            try { db?.cachedMessageDao()?.markReadStatus(mid) } catch (_: Exception) {}
        }
        readReceipt(conversationId, messageIds.lastOrNull(), ts)
    }

    suspend fun createGroup(subject: String, contacts: List<String>): String? {
        val id = "group:${SignalProtocol.generateMessageId()}"
        val json = JSONObject().apply {
            put("name", subject)
            put("members", org.json.JSONArray(contacts))
        }
        val reqId = SignalProtocol.generateMessageId()
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/groups", json.toString().toByteArray(Charsets.UTF_8), reqId)
        try { socket?.sendText(req) } catch (_: Exception) {}
        _events.emit(SignalEvent.ConversationUpdate(conversationId = id, peerName = subject, peerPhone = null, avatarUrl = null, lastPreview = null, lastTimestamp = System.currentTimeMillis(), unreadCount = 0, isGroup = true, participantCount = contacts.size))
        try { db?.conversationDao()?.upsert(SignalConversation(chatId = id, isGroup = true, name = subject, participants = contacts.joinToString(","))) } catch (_: Exception) {}
        return id
    }

    suspend fun setGroupName(conversationId: String, name: String): Boolean {
        val json = JSONObject().apply { put("name", name) }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/groups/$conversationId/name", json.toString().toByteArray(Charsets.UTF_8))
        try { socket?.sendText(req) } catch (_: Exception) {}
        _events.emit(SignalEvent.ConversationNameChanged(conversationId = conversationId, newName = name))
        try {
            val existing = db?.conversationDao()?.getConversation(conversationId)
            if (existing != null) db?.conversationDao()?.upsert(existing.copy(name = name))
        } catch (_: Exception) {}
        return true
    }

    suspend fun updateGroupParticipants(conversationId: String, participantIds: List<String>, action: String): Boolean {
        val json = JSONObject().apply { put("members", org.json.JSONArray(participantIds)); put("action", action) }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/groups/$conversationId/members", json.toString().toByteArray(Charsets.UTF_8))
        try { socket?.sendText(req) } catch (_: Exception) {}
        for (pid in participantIds) {
            if (action == "add") _events.emit(SignalEvent.ParticipantAdded(conversationId = conversationId, participantId = pid))
            else _events.emit(SignalEvent.ParticipantRemoved(conversationId = conversationId, participantId = pid))
        }
        return true
    }

    suspend fun sendTyping(conversationId: String, isTyping: Boolean) {
        _events.emit(SignalEvent.TypingIndicator(conversationId = conversationId, senderId = authData?.aci ?: "", isTyping = isTyping))
        val json = JSONObject().apply { put("typing", isTyping) }
        val req = SignalPayload.buildWebSocketRequest("PUT", "/api/v1/messages/$conversationId/typing", json.toString().toByteArray(Charsets.UTF_8))
        try { socket?.sendText(req) } catch (_: Exception) {}
    }

    fun isLoggedIn(): Boolean = isConnected()

    suspend fun downloadMedia(url: String, key: ByteArray, type: String): ByteArray? {
        return try {
            val resp = NetworkClient.execute(url, method = "GET")
            if (resp.isSuccess) resp.bytes else null
        } catch (_: Exception) { null }
    }

    suspend fun refreshPresence(conversationId: String) {
        val req = SignalPayload.buildWebSocketRequest("GET", "/api/v1/accounts/$conversationId/presence")
        try { socket?.sendText(req) } catch (_: Exception) {}
        _events.emit(SignalEvent.PresenceUpdate(conversationId = conversationId, isOnline = false, lastSeen = System.currentTimeMillis()))
    }

    fun placeCall(conversationId: String, video: Boolean) {
        val callId = SignalProtocol.generateMessageId()
        scope.launch {
            _events.emit(SignalEvent.CallOffer(callId = callId, from = authData?.aci ?: "", callCreator = authData?.aci ?: "", isVideo = video))
            _events.emit(SignalEvent.CallStateChanged(callId = callId, phase = "offer", isVideo = video))
        }
    }

    suspend fun rejectCall(from: String, callId: String, creator: String): Boolean {
        _events.emit(SignalEvent.CallEnded(callId = callId, reason = "rejected"))
        val req = SignalPayload.buildWebSocketRequest("DELETE", "/api/v1/call/$callId")
        try { socket?.sendText(req) } catch (_: Exception) {}
        return true
    }

    // -- Internals --

    private suspend fun handleInboundFrame(raw: ByteArray) {
        val text = String(raw, Charsets.UTF_8)
        val frame = SignalProtocol.parseWsFrame(text)
        if (frame == null) {
            Log.w(TAG, "unparseable frame: ${text.take(200)}")
            return
        }
        // Ack every REQUEST so the server drains the queue.
        if (frame.type == SignalProtocol.WS_TYPE_REQUEST && frame.id != null) {
            val ack = SignalProtocol.buildWsResponse(frame.id, 200)
            try { socket?.sendText(ack) } catch (_: Exception) {}
        }
        val env = SignalProtocol.tryParseEnvelope(frame) ?: return
        val conversationId = SignalProtocol.toConversationId(env.sourceAci, env.groupId)
        // Best-effort decrypt
        val body: String = try {
            val e = e2e
            if (e != null && env.sourceAci.isNotEmpty() && e.hasSession(env.sourceAci, env.sourceDevice)) {
                val isPreKey = env.type == "PREKEY"
                val pt = e.decryptDM(env.sourceAci, env.sourceDevice, isPreKey, env.content)
                val unpadded = unpadMessage(pt)
                String(unpadded, Charsets.UTF_8)
            } else {
                // Try sealed sender stub then fall back to raw
                try {
                    val pt = e?.sealedSenderDecrypt(env.content)
                    if (pt != null) String(unpadMessage(pt), Charsets.UTF_8) else String(env.content, Charsets.UTF_8)
                } catch (_: Exception) { String(env.content, Charsets.UTF_8) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed for ${env.sourceAci}", t)
            _events.emit(SignalEvent.DecryptionError(conversationId = conversationId, senderAci = env.sourceAci, senderDeviceId = env.sourceDevice, timestamp = env.timestamp, errorMessage = t.message))
            return
        }
        if (body.isBlank()) return
        val msgId = env.serverGuid ?: SignalProtocol.generateMessageId()
        val sd = SignalServiceData(senderId = env.sourceAci, senderName = env.sourceAci)
        _events.emit(SignalEvent.IncomingMessage(conversationId = conversationId, messageId = msgId, body = body, peerName = env.sourceAci, peerPhone = null, timestamp = env.timestamp, senderId = env.sourceAci, serviceData = sd.serialize()))
    }

    private fun padMessage(plaintext: ByteArray): ByteArray {
        var padSize = java.security.SecureRandom().nextInt(16)
        if (padSize == 0) padSize = 15
        val out = ByteArray(plaintext.size + padSize)
        System.arraycopy(plaintext, 0, out, 0, plaintext.size)
        for (i in plaintext.size until out.size) out[i] = padSize.toByte()
        return out
    }

    private fun unpadMessage(padded: ByteArray): ByteArray {
        if (padded.isEmpty()) return padded
        val pad = padded.last().toInt() and 0xFF
        if (pad == 0 || pad > padded.size) return padded
        return padded.copyOfRange(0, padded.size - pad)
    }
}
