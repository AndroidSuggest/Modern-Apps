package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.ims.SipMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.applyRcsDeliveryReport
import com.vayunmathur.communicate.data.cacheInboundRcs
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsImdn
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.buildImdnBody
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RcsPendingGroups
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.rcs.extractImdnMessageId
import com.vayunmathur.communicate.data.rcs.parseChunkHeader
import com.vayunmathur.communicate.data.rcs.parseEditBody
import com.vayunmathur.communicate.data.rcs.parseGeopushBody
import com.vayunmathur.communicate.data.rcs.parseImdnBody
import com.vayunmathur.communicate.data.rcs.parseIsComposingBody
import com.vayunmathur.communicate.data.rcs.parseRevokeBody
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground owner for the RCS single-registration line: keeps the SIP
 * delegate + UCE listener alive, writes inbound messages to Room, and posts
 * new-message notifications while Communicate is backgrounded. Mirrors
 * [SignalSyncService] (FGS type remoteMessaging).
 */
class RcsSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** MSRP connections per conversation, owned by this service's lifecycle. */
    internal val msrpConnections = java.util.concurrent.ConcurrentHashMap<String, RcsMsrp.MsrpConnection>()

    /** Close MSRP connections whose sessions went away. */
    internal fun closeDeadMsrpConnections() {
        msrpConnections.keys.filter { RcsSessionManager.sessionFor(it) == null }.forEach { id ->
            msrpConnections.remove(id)?.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: Android requires startForeground() within ~5s of any
        // startForegroundService() call, INCLUDING the ACTION_STOP path (MainActivity calls stop()
        // at launch when not registered, on a service that was never started).
        ensureChannels()
        startForegroundCompat(buildSyncNotification())

        // Dev-only feature: if ever started in a release build, immediately stand down.
        if (!RcsFeature.enabled) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            RcsSipTransport.ensureRegistered(this@RcsSyncService)
            RcsSipTransport.onInboundMessage = { message ->
                serviceScope.launch { handleInbound(message) }
            }
            // Watch for teardown: when the transport goes unavailable, reflect it
            // in the sync notification and stop if the gate flips off.
            launch {
                RcsSipTransport.state.collect { state ->
                    if (!RcsFeature.enabled) shutdown()
                    updateSyncNotification(state)
                }
            }
            // Own MSRP connection lifecycle: when a session gains a usable
            // remote path, connect out and feed inbound chunks to the inbox.
            launch {
                RcsSessionManager.sessions.collect { sessions ->
                    for ((conversationId, session) in sessions) {
                        if (session.msrpRemotePath == null || msrpConnections.containsKey(conversationId)) {
                            continue
                        }
                        val conn = RcsMsrp.connect(session, this@RcsSyncService) { contentType, body ->
                            serviceScope.launch { handleMsrpChunk(conversationId, session, contentType, body) }
                        }
                        if (conn != null) {
                            msrpConnections[conversationId] = conn
                        }
                    }
                    // Drop connections whose sessions went away.
                    val live = sessions.keys
                    msrpConnections.keys.filter { it !in live }.forEach { id ->
                        msrpConnections.remove(id)?.close()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RcsSipTransport.onInboundMessage = null
        msrpConnections.values.forEach { runCatching { it.close() } }
        msrpConnections.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout for type=$fgsType; leaving foreground to avoid crash")
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private suspend fun handleInbound(message: SipMessage) {
        val startLine = message.getStartLine()
        val method = startLine.substringBefore(" ").trim().uppercase()
        // Dialog-forming and dialog methods route before MESSAGE handling.
        when (method) {
            "INVITE" -> {
                handleInboundInvite(message)
                return
            }
            "BYE" -> {
                handleInboundBye(message)
                return
            }
            "REFER" -> {
                handleInboundRefer(message)
                return
            }
            "OPTIONS" -> {
                handleInboundOptions(message)
                return
            }
        }
        val envelope = parseEnvelope(message) ?: return
        // E2EE control + data payloads route before plaintext handling.
        handleE2EEInbound(envelope)?.let { e2ee ->
            when (e2ee.kind) {
                InboundKind.Text -> {
                    CommunicateRepository.cacheInboundRcs(
                        context = this,
                        conversationId = e2ee.conversationId,
                        body = e2ee.body,
                        senderId = e2ee.senderId,
                        messageId = e2ee.messageId,
                        ftUrl = e2ee.ftUrl,
                        ftMime = e2ee.ftMime,
                    )
                    showIncomingNotification(e2ee)
                }
                InboundKind.Receipt, InboundKind.Typing -> Unit
            }
            return
        }
        val parsed = parseInbound(envelope) ?: return
        when (parsed.kind) {
            InboundKind.Text -> {
                CommunicateRepository.cacheInboundRcs(
                    context = this,
                    conversationId = parsed.conversationId,
                    body = parsed.body,
                    senderId = parsed.senderId,
                    messageId = parsed.messageId,
                    ftUrl = parsed.ftUrl,
                    ftMime = parsed.ftMime,
                )
                // Hosted focus: relay the message to the other members
                // (ciphertext passes through untouched when E2EE is on).
                relayFocusMessage(parsed, envelope.raw)
                // IMDN positive-delivery: the message reached us, so tell the sender.
                sendImdnReport(parsed)
                showIncomingNotification(parsed)
            }
            InboundKind.Receipt, InboundKind.Typing -> {
                // Routed to RcsImdn / RcsSessionManager.typing by parseInbound already.
                Unit
            }
        }
    }

    /** Inbound BYE: tear down the session + MSRP connection. */
    private suspend fun handleInboundBye(message: SipMessage) {
        if (!RcsFeature.enabled) return
        val callId = message.getCallIdParameter() ?: return
        RcsSessionManager.onSipRequest("BYE", callId, "", null, "")
        closeDeadMsrpConnections()
        Log.i(TAG, "BYE processed callId=$callId")
    }

    /**
     * Inbound MSRP chunk: CPIM text becomes an inbox row (+ notification);
     * IMDN/typing ride their trackers. Reuses the SIP parse helpers by
     * synthesizing the CPIM text path.
     */
    private suspend fun handleMsrpChunk(
        conversationId: String,
        session: RcsSession,
        contentType: String,
        body: ByteArray,
    ) {
        if (!RcsFeature.enabled) return
        val text = body.toString(Charsets.UTF_8)
        if (contentType.contains("imdn", ignoreCase = true)) {
            RcsImdn.onReportReceived(text)
            return
        }
        if (contentType.contains("im-composing", ignoreCase = true)) {
            RcsSessionManager.onSipRequest("MESSAGE", session.callId, conversationId, contentType, text)
            return
        }
        val display = if (contentType.contains("cpim", ignoreCase = true)) {
            extractTextBody(body) ?: text
        } else {
            text
        }
        if (display.isBlank()) return
        val ft = RcsFileTransferHttp.parseFtBody(display)
        CommunicateRepository.cacheInboundRcs(
            context = this,
            conversationId = conversationId,
            body = ft?.let { display.substringAfter("\r\n").ifBlank { "[file]" } } ?: display,
            senderId = conversationId,
            messageId = "in-msrp-${display.hashCode()}-${System.currentTimeMillis()}",
            ftUrl = ft?.url,
            ftMime = ft?.mime,
        )
        showIncomingNotification(
            InboundRcs(
                conversationId = conversationId,
                body = display,
                senderId = conversationId,
                messageId = "in-msrp-${display.hashCode()}",
            ),
        )
    }

    /**
     * Focus relay: when [parsed] arrived on a conversation we host the focus
     * for, re-send the raw body to every other focus member. The relayed bytes
     * are the original wire body (CPIM + payload), so MLS ciphertext stays
     * opaque to us — E2EE terminates per-member, not at the focus.
     */
    private suspend fun relayFocusMessage(parsed: InboundRcs, rawBody: String) {
        if (!RcsFeature.enabled) return
        val focusUri = RcsSessionManager.hostedFocusFor(parsed.conversationId) ?: return
        val members = RcsSessionManager.focusMembers(focusUri) ?: return
        val others = members.filter { it != parsed.senderId && it.isNotBlank() }
        if (others.isEmpty() || rawBody.isBlank()) return
        for (peer in others) {
            runCatching {
                val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                    fromUri = "sip:me@rcs",
                    toUri = "sip:$peer@rcs",
                    callId = "${java.util.UUID.randomUUID()}@rcs-relay",
                    body = rawBody,
                )
                RcsSipTransport.sendSipMessage(startLine, headers, content)
            }
        }
        Log.i(TAG, "Relayed focus message to ${others.size} members")
    }

    private suspend fun sendImdnReport(parsed: InboundRcs) {
        val originalId = parsed.imdnMessageId ?: return
        runCatching {
            val report = buildImdnBody(originalId, ImdnDisposition.Delivered)
            val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:${parsed.conversationId}@rcs",
                callId = "${java.util.UUID.randomUUID()}@rcs-imdn",
                body = report,
            )
            RcsSipTransport.sendSipMessage(startLine, headers, content)
        }
    }

    private enum class InboundKind { Text, Receipt, Typing }

    /** Raw envelope: repaired headers + sender + content type + body + ids. */
    private data class Envelope(
        val from: String,
        val contentType: String?,
        val raw: String,
        val callId: String,
        val viaBranch: String,
    )

    private fun parseEnvelope(message: SipMessage): Envelope? {
        return runCatching {
            // Modem quirk (TestRcsApp RegistrationControllerImpl.repairHeaderSection):
            // some modems emit "ia:" instead of "Via:".
            var headers = message.getHeaderSection()
            if (headers.startsWith("ia:")) {
                headers = "V$headers"
                Log.w(TAG, "Repaired malformed Via header")
            }
            val from = headerValue(headers, "From:")?.substringAfter("<")?.substringBefore(">")
                ?.substringAfter("sip:")?.substringBefore("@")
                ?.takeIf { it.isNotBlank() } ?: return null
            val contentType = headerValue(headers, "Content-Type:")
            val raw = message.getContent().toString(Charsets.UTF_8)
            if (raw.isBlank()) return null
            Envelope(
                from = from,
                contentType = contentType,
                raw = raw,
                callId = message.getCallIdParameter() ?: raw.hashCode().toString(),
                viaBranch = message.getViaBranchParameter(),
            )
        }.getOrNull()
    }

    private fun parseInbound(envelope: Envelope): InboundRcs? {
        return runCatching {
            val from = envelope.from
            val contentType = envelope.contentType
            val raw = envelope.raw
            // IMDN reports update delivery ticks AND route to the tracker.
            if (contentType?.contains("imdn", ignoreCase = true) == true ||
                parseImdnBody(raw) != null
            ) {
                parseImdnBody(raw)?.let { (id, disposition) ->
                    serviceScope.launch {
                        CommunicateRepository.applyRcsDeliveryReport(this@RcsSyncService, id, disposition)
                    }
                }
                RcsImdn.onReportReceived(raw)
                return InboundRcs(
                    conversationId = from,
                    body = "",
                    senderId = from,
                    messageId = "in-${envelope.callId}",
                    kind = InboundKind.Receipt,
                )
            }
            if (contentType?.contains("im-composing", ignoreCase = true) == true ||
                parseIsComposingBody(raw) != null
            ) {
                RcsSessionManager.onSipRequest("MESSAGE", envelope.callId, from, contentType, raw)
                return InboundRcs(
                    conversationId = from,
                    body = "",
                    senderId = from,
                    messageId = "in-${envelope.callId}",
                    kind = InboundKind.Typing,
                )
            }
            val body = extractTextBody(envelope.raw.toByteArray(Charsets.UTF_8)) ?: return null
            if (body.isBlank()) return null
            // Revoke: blank the referenced row, no inbox row.
            parseRevokeBody(body)?.let { revokedId ->
                serviceScope.launch {
                    runCatching {
                        val db = RcsDatabase.getDatabase(this@RcsSyncService)
                        db.cachedMessageDao().get(revokedId)?.let {
                            db.cachedMessageDao().upsert(it.copy(body = ""))
                        }
                    }
                }
                return InboundRcs(
                    conversationId = from,
                    body = "",
                    senderId = from,
                    messageId = "in-${envelope.callId}",
                    kind = InboundKind.Receipt,
                )
            }
            // Edit: swap the referenced row body, no new inbox row.
            parseEditBody(body)?.let { (originalId, newText) ->
                serviceScope.launch {
                    runCatching {
                        val db = RcsDatabase.getDatabase(this@RcsSyncService)
                        db.cachedMessageDao().get(originalId)?.let {
                            db.cachedMessageDao().upsert(it.copy(body = newText))
                        }
                    }
                }
                return InboundRcs(
                    conversationId = from,
                    body = "",
                    senderId = from,
                    messageId = "in-${envelope.callId}",
                    kind = InboundKind.Receipt,
                )
            }
            // Geolocation Push → location row.
            parseGeopushBody(body)?.let { (lat, lon, label) ->
                return InboundRcs(
                    conversationId = from,
                    body = label ?: "📍 $lat, $lon",
                    senderId = from,
                    messageId = "in-${envelope.callId}-${envelope.viaBranch}",
                    kind = InboundKind.Text,
                    ftUrl = "geo:$lat,$lon",
                    ftMime = "application/vnd.gsma.rcs.geopush+xml",
                    imdnMessageId = extractImdnMessageId(raw),
                )
            }
            // Large Message chunks: buffer per Message-ID; emit only when complete.
            parseChunkHeader(body)?.let { (chunkId, part, total) ->
                val complete = bufferLargeChunk(from, chunkId, part, total, body)
                if (complete == null) {
                    return InboundRcs(
                        conversationId = from,
                        body = "",
                        senderId = from,
                        messageId = "in-${envelope.callId}",
                        kind = InboundKind.Receipt,
                    )
                }
                return InboundRcs(
                    conversationId = from,
                    body = complete,
                    senderId = from,
                    messageId = "in-$chunkId",
                    kind = InboundKind.Text,
                    imdnMessageId = extractImdnMessageId(raw),
                )
            }
            // FT-over-HTTP descriptors expose URL + mime for the renderer.
            val ft = RcsFileTransferHttp.parseFtBody(body)
            InboundRcs(
                conversationId = from,
                body = ft?.let { body.substringAfter("\r\n").ifBlank { "[file]" } } ?: body,
                senderId = from,
                messageId = "in-${envelope.callId}-${envelope.viaBranch}",
                kind = InboundKind.Text,
                ftUrl = ft?.url,
                ftMime = ft?.mime,
                imdnMessageId = extractImdnMessageId(raw),
            )
        }.getOrNull()
    }

    /**
     * Route E2EE payloads: key-package publications (cache for group creation),
     * Welcome envelopes (join + persist), MLS commits/application (decrypt;
     * application text becomes an inbox row). Returns an [InboundRcs] when the
     * payload produces a visible row, null when fully absorbed (key packages,
     * commits, typing-like control).
     */
    private suspend fun handleE2EEInbound(envelope: Envelope): InboundRcs? {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return null
        val from = envelope.from
        val contentType = envelope.contentType
        val raw = envelope.raw
        val body = extractTextBody(raw.toByteArray(Charsets.UTF_8)) ?: raw
        // Key-request → auto-publish our key package (one round trip setup).
        if (contentType?.contains(RcsE2E.CT_KEY_REQUEST, ignoreCase = true) == true ||
            body.contains(RcsE2E.CT_KEY_REQUEST, ignoreCase = true)
        ) {
            RcsE2E.localE164(this)?.let { local ->
                RcsKeyDirectory.publishTo(this, local, from)
            }
            return InboundRcs(from, "", from, "in-kreq-${System.currentTimeMillis()}", InboundKind.Typing)
        }
        // Key package publication → stash, satisfy pending groups, and create
        // the 1:1 group if none exists yet.
        if (contentType?.contains(RcsE2E.CT_KEY_PACKAGE, ignoreCase = true) == true ||
            body.contains(RcsE2E.CT_KEY_PACKAGE, ignoreCase = true)
        ) {
            RcsKeyDirectory.parsePublished(body)?.let { kp ->
                RcsPeerKeys.store(this, from, kp)
                // Pending encrypted groups: create each ready one now.
                for ((pendingId, packages) in RcsPendingGroups.readyFor(from)) {
                    RcsE2E.localE164(this)?.let { local ->
                        if (RcsE2E.setupEncryptedGroup(this, local, pendingId, packages)) {
                            RcsPendingGroups.remove(pendingId)
                        }
                    }
                }
                // Auto-setup: first key package for a 1:1 conversation with no
                // group creates it and sends Welcome. Closed-loop peers opt in
                // by running this code; no group forms with non-participants.
                val conversationId = from
                if (RcsE2E.groupIdFor(this, conversationId) == null &&
                    !RcsPendingGroups.isPending(conversationId)
                ) {
                    RcsE2E.localE164(this)?.let { local ->
                        RcsE2E.setupGroupWithPeer(this, local, conversationId, from, kp)
                    }
                }
            }
            return InboundRcs(from, "", from, "in-kp-${System.currentTimeMillis()}", InboundKind.Typing)
        }
        // Welcome envelope → join the group, then announce with a notice row.
        if (contentType?.contains(RcsE2E.CT_WELCOME, ignoreCase = true) == true ||
            body.contains(RcsE2E.CT_WELCOME, ignoreCase = true) == true
        ) {
            val welcomeBytes = decodePayloadBody(body) ?: return null
            val conversationId = from
            val groupId = RcsE2E.joinEncryptedGroup(this, conversationId, welcomeBytes)
            if (groupId != null) {
                // Rotate: publish a fresh key package so future adds work.
                RcsE2E.localE164(this)?.let { local ->
                    RcsE2E.freshKeyPackage(this, local)
                }
                return InboundRcs(
                    conversationId, "🔒 Encrypted chat started", from,
                    "in-welcome-${System.currentTimeMillis()}",
                )
            }
            return null
        }
        // MLS data (commit or application) → decrypt against the conversation group.
        val isMls = contentType?.contains(RcsE2E.CT_MLS, ignoreCase = true) == true ||
            contentType?.contains(RcsE2E.CT_COMMIT, ignoreCase = true) == true ||
            RcsE2E.isMlsPayload(body)
        if (!isMls) return null
        val payload = decodePayloadBody(body) ?: return null
        val (plaintext, isApp) = RcsE2E.decryptFor(this, from, payload) ?: return null
        if (!isApp) return null
        val text = plaintext.toString(Charsets.UTF_8)
        if (text.isBlank()) return null
        val callId = text.hashCode().toString()
        return InboundRcs(
            conversationId = from,
            body = text,
            senderId = from,
            messageId = "in-mls-$callId-${System.currentTimeMillis()}",
            ftUrl = null,
            ftMime = null,
            imdnMessageId = extractImdnMessageId(text),
        )
    }

    /** Large-message reassembly buffers: conversation → messageId → part → text. */
    private val largeChunks = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, MutableMap<Int, String>>>()

    /**
     * Buffer one Large Message chunk; return the joined text once all [total]
     * parts for ([conversationId], [chunkId]) arrived, else null.
     */
    private fun bufferLargeChunk(
        conversationId: String,
        chunkId: String,
        part: Int,
        total: Int,
        body: String,
    ): String? {
        if (total <= 0 || part <= 0 || part > total) return null
        val text = body.substringAfter("\r\n\r\n", "").ifBlank {
            body.substringAfter("\n\n", "")
        }.trim()
        val convo = largeChunks.getOrPut(conversationId) { java.util.concurrent.ConcurrentHashMap() }
        val parts = convo.getOrPut(chunkId) { java.util.concurrent.ConcurrentHashMap() }
        parts[part] = text
        if (parts.size != total) return null
        val complete = (1..total).map { parts[it] ?: return null }.joinToString("")
        convo.remove(chunkId)
        if (convo.isEmpty()) largeChunks.remove(conversationId)
        return complete.ifBlank { null }
    }

    /** Base64-decode a CPIM-wrapped binary payload body. */
    private fun decodePayloadBody(body: String): ByteArray? {
        val b64 = if (body.contains("\r\n\r\n")) {
            body.substringAfter("\r\n\r\n", "").trim()
        } else if (body.contains("\n\n")) {
            body.substringAfter("\n\n", "").trim()
        } else {
            body.trim()
        }.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }.getOrNull()
    }
    /**
     * Extract display text from a message body. Pager-mode RCS arrives as
     * `message/cpim` (TestRcsApp CpimUtils); plain `text/plain` passes through.
     */
    private fun extractTextBody(content: ByteArray): String? {
        val raw = content.toString(Charsets.UTF_8)
        if (raw.isBlank()) return null
        if (raw.contains("message/cpim", ignoreCase = true)) {
            // CPIM: headers, blank line, payload. The payload after the last
            // double-CRLF is the text (cpim content with Content-Type text/plain).
            val sections = raw.split("\r\n\r\n")
            if (sections.size >= 2) {
                return sections.last().trim().takeIf { it.isNotEmpty() }
            }
            return null
        }
        return raw
    }

    private data class InboundRcs(
        val conversationId: String,
        val body: String,
        val senderId: String,
        val messageId: String,
        val kind: InboundKind = InboundKind.Text,
        val ftUrl: String? = null,
        val ftMime: String? = null,
        val imdnMessageId: String? = null,
    )

    private fun showIncomingNotification(parsed: InboundRcs) {
        val target = ConversationTarget(
            line = CommunicateLine.Rcs,
            address = parsed.conversationId,
            remoteId = parsed.conversationId,
            isGroup = false,
            personName = parsed.senderId,
        )
        ConversationSpace.notifyIncoming(
            context = this,
            target = target,
            channelId = INCOMING_CHANNEL_ID,
            body = parsed.body.ifBlank { getString(R.string.new_message) },
            timestamp = System.currentTimeMillis(),
            smallIcon = R.mipmap.ic_launcher,
        )
    }

    private fun ensureChannels() {
        ensureNotificationChannel(
            id = SYNC_CHANNEL_ID,
            name = "RCS sync",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Keeps the RCS connection alive",
        ) {
            setSound(null, null)
            enableVibration(false)
        }
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = "RCS messages",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Incoming RCS messages",
        ) {
            setAllowBubbles(true)
        }
    }

    private fun buildSyncNotification(state: RcsRegistrationState? = null): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RcsSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = when (state) {
            is RcsRegistrationState.Available -> getString(R.string.rcs_available)
            is RcsRegistrationState.Unavailable -> getString(R.string.rcs_not_available)
            else -> getString(R.string.rcs_provisioning)
        }
        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("RCS")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateSyncNotification(state: RcsRegistrationState) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(SYNC_NOTIFICATION_ID, buildSyncNotification(state))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        RcsSipTransport.onInboundMessage = null
        msrpConnections.values.forEach { runCatching { it.close() } }
        msrpConnections.clear()
        runCatching { RcsSipTransport.tearDown(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal const val TAG = "RcsSync"
        private const val SYNC_NOTIFICATION_ID = 4723
        private const val SYNC_CHANNEL_ID = "rcs_sync"
        internal const val INCOMING_CHANNEL_ID = "rcs_messages_incoming"
        private const val ACTION_STOP = "com.vayunmathur.communicate.rcs.STOP_SYNC"
        const val EXTRA_OPEN_RCS_THREAD = "open_rcs_thread"

        fun start(context: Context) {
            // Dev-only feature: never start the RCS sync service in the release variant.
            if (!RcsFeature.enabled) return
            ContextCompat.startForegroundService(context, Intent(context, RcsSyncService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RcsSyncService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
