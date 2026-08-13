package com.vayunmathur.communicate.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceWebSender
import com.vayunmathur.communicate.data.googlevoice.GvCall
import com.vayunmathur.communicate.data.googlevoice.GvCallType
import com.vayunmathur.communicate.data.googlevoice.GvMessage
import com.vayunmathur.communicate.data.googlevoice.GvThread
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.ui.ExternalIntents

object CommunicateRepository {
    fun loadContacts(context: Context): List<CommunicateContact> {
        if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                    val name = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val number = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val type = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val label = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                    val seenNumbers = mutableSetOf<String>()

                    while (cursor.moveToNext()) {
                        val rawNumber = cursor.getString(number).orEmpty().trim()
                        if (rawNumber.isEmpty()) continue
                        val normalized = rawNumber.filter { it.isDigit() || it == '+' }
                        if (!seenNumbers.add(normalized.ifEmpty { rawNumber })) continue
                        add(
                            CommunicateContact(
                                id = cursor.getLong(id),
                                name = cursor.getString(name).orEmpty().ifBlank { rawNumber },
                                phoneNumber = rawNumber,
                                label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                    context.resources,
                                    cursor.getInt(type),
                                    cursor.getString(label),
                                ).toString(),
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadCallLogs(context: Context): List<CommunicateCallLogEntry> {
        if (!context.hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val account = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                    val activeSubs = SimManager.activeSims(context).map { it.subscriptionId }.toSet()

                    while (cursor.moveToNext()) {
                        val phoneNumber = cursor.getString(number).orEmpty().ifBlank { "Unknown" }
                        // PHONE_ACCOUNT_ID is the SIM's subscription id on most devices; keep it
                        // only when it maps to an active SIM so we can label the row by SIM.
                        val subId = account.takeIf { it >= 0 }
                            ?.let { cursor.getString(it) }
                            ?.toIntOrNull()
                            ?.takeIf { it in activeSubs }
                        add(
                            CommunicateCallLogEntry(
                                id = cursor.getLong(id),
                                displayName = cursor.getString(name)?.takeIf { it.isNotBlank() },
                                phoneNumber = phoneNumber,
                                type = cursor.getInt(type).toCommunicateCallType(),
                                timestampMillis = cursor.getLong(date),
                                durationSeconds = cursor.getLong(duration),
                                subscriptionId = subId,
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun loadSmsThreads(context: Context): List<SmsThread> {
        if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()
        return loadSmsMessages(context, threadId = null)
            .groupBy { it.threadId }
            .values
            .mapNotNull { messages ->
                val newest = messages.maxByOrNull { it.timestampMillis } ?: return@mapNotNull null
                val address = newest.address
                SmsThread(
                    threadId = newest.threadId,
                    address = address,
                    displayName = findContactName(context, address),
                    snippet = newest.body,
                    timestampMillis = newest.timestampMillis,
                    unreadCount = messages.count { !it.outgoing && !it.read },
                    subscriptionId = newest.subscriptionId,
                )
            }
            .sortedByDescending { it.timestampMillis }
    }

    fun loadSmsMessages(context: Context, threadId: Long?): List<SmsMessage> {
        if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        val selection = threadId?.let { "${Telephony.Sms.THREAD_ID} = ?" }
        val args = threadId?.let { arrayOf(it.toString()) }
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                args,
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                buildList {
                    val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val thread = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                    val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                    val sub = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                    while (cursor.moveToNext()) {
                        add(
                            SmsMessage(
                                id = cursor.getLong(id),
                                threadId = cursor.getLong(thread),
                                address = cursor.getString(address).orEmpty(),
                                body = cursor.getString(body).orEmpty(),
                                timestampMillis = cursor.getLong(date),
                                outgoing = cursor.getInt(type) == Telephony.Sms.MESSAGE_TYPE_SENT ||
                                    cursor.getInt(type) == Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                                read = cursor.getInt(read) != 0,
                                subscriptionId = sub.takeIf { it >= 0 }?.let { cursor.getInt(it) }?.takeIf { it >= 0 },
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun findContactName(context: Context, number: String): String? {
        if (!context.hasPermission(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    fun placeCall(context: Context, number: String) {
        placeCall(context, choice = null, number = number)
    }

    /**
     * Place a call from a chosen line. Google Voice routes through the self-managed account; a SIM
     * choice places via that SIM's [PhoneAccountHandle]; null uses the default outgoing account.
     */
    fun placeCall(context: Context, choice: LineChoice?, number: String) {
        if (number.isBlank()) return
        if (choice is LineChoice.GoogleVoice) {
            com.vayunmathur.communicate.telephony.GoogleVoiceTelecom.placeOutgoing(context, number)
            return
        }
        val uri = Uri.fromParts("tel", number, null)
        if (context.hasPermission(Manifest.permission.CALL_PHONE)) {
            try {
                val telecomManager = context.getSystemService(TelecomManager::class.java)
                val extras = Bundle()
                val handle = (choice as? LineChoice.Sim)?.let { phoneAccountHandleForSub(context, it.subscriptionId) }
                    ?: telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
                handle?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                telecomManager.placeCall(uri, extras)
                return
            } catch (_: Exception) {
                // Fall through to ACTION_DIAL below.
            }
        }
        ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, uri))
    }

    /** Map a SIM subscription id to its Telecom [PhoneAccountHandle] (handle id is the sub id). */
    private fun phoneAccountHandleForSub(context: Context, subscriptionId: Int): android.telecom.PhoneAccountHandle? {
        if (subscriptionId < 0) return null
        if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
        val tm = context.getSystemService(TelecomManager::class.java) ?: return null
        return runCatching {
            tm.callCapablePhoneAccounts.firstOrNull { it.id == subscriptionId.toString() }
        }.getOrNull()
    }

    fun openSmsComposer(context: Context, number: String? = null, body: String? = null) {
        val uri = if (number.isNullOrBlank()) "smsto:".toUri() else "smsto:$number".toUri()
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (!body.isNullOrBlank()) putExtra("sms_body", body)
        }
        ExternalIntents.launch(context, intent)
    }

    /** Resolve (or create) the SIM thread id for an address, so a new SIM conversation shows history. */
    fun getOrCreateSmsThreadId(context: Context, address: String): Long? = runCatching {
        Telephony.Threads.getOrCreateThreadId(context, address)
    }.getOrNull()

    // ------------------------------------------------------------------
    // Google Voice merge (second line)
    //
    // These suspend variants return SIM data (tagged CommunicateLine.Sim) merged with Google
    // Voice data (tagged CommunicateLine.GoogleVoice) when a GV session is present. All GV
    // network work is done here off the main thread; callers already invoke us from
    // Dispatchers.IO in produceState. GV failures are swallowed so the SIM inbox still loads.
    // ------------------------------------------------------------------

    /** Merged thread list: SIM threads + Google Voice threads, newest first. */
    suspend fun loadSmsThreadsMerged(context: Context): List<SmsThread> {
        val sim = loadSmsThreads(context)
        val gv = loadGoogleVoiceThreads(context)
        val wa = loadWhatsAppThreads(context)
        return (sim + gv + wa).sortedByDescending { it.timestampMillis }
    }

    /** Route by line: SIM threads read the provider; GV threads hit `api2thread/get`. */
    suspend fun loadSmsMessagesMerged(context: Context, thread: SmsThread): List<SmsMessage> =
        when (thread.line) {
            CommunicateLine.Sim -> loadSmsMessages(context, thread.threadId)
            CommunicateLine.GoogleVoice -> {
                val remoteId = thread.remoteId ?: return emptyList()
                runCatching {
                    GoogleVoiceClient.get(context).getThread(remoteId)
                        .map { it.toSmsMessage(thread.threadId, context) }
                }.getOrDefault(emptyList())
            }
            CommunicateLine.WhatsApp -> {
                // New conversations have no remoteId yet — derive the JID from the address so the
                // outgoing echo (cached under the same normalized JID) shows immediately.
                val jid = thread.remoteId?.takeIf { it.isNotBlank() } ?: toWhatsAppJid(context, thread.address)
                loadWhatsAppMessages(context, jid)
            }
        }

    /** Merged call history: device call log + Google Voice calls, newest first. */
    suspend fun loadCallLogsMerged(context: Context): List<CommunicateCallLogEntry> {
        val device = loadCallLogs(context)
        val gv = loadGoogleVoiceCalls(context)
        return (device + gv).sortedByDescending { it.timestampMillis }
    }

    /**
     * Dispatch an outgoing message from a chosen line. A SIM choice sends via that SIM's
     * [android.telephony.SmsManager] and records it in the provider; Google Voice mints a token in
     * an offscreen WebView and posts `api2thread/sendsms`. Returns true on success.
     */
    suspend fun sendMessage(
        context: Context,
        choice: LineChoice,
        address: String,
        body: String,
        threadRemoteId: String? = null,
        attachments: List<CommunicateAttachment> = emptyList(),
    ): Boolean = when (choice) {
        is LineChoice.Sim -> if (attachments.isEmpty()) {
            sendSimSms(context, choice.subscriptionId, address, body)
        } else {
            false
        }
        LineChoice.GoogleVoice -> runCatching {
            // The bot-defense token is minted invisibly in an offscreen WebView; the app then
            // builds and sends the sendsms API call itself using that token. For MMS, let the
            // real web composer upload media and build the media-bearing body, then replay it.
            val activity = context as? android.app.Activity ?: return@runCatching false
            val sendBody = if (attachments.isEmpty()) {
                val token = GoogleVoiceWebSender.mintToken(activity, address, body) ?: return@runCatching false
                com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
                    .buildSendSmsBody(address, body, threadRemoteId, botToken = token)
            } else {
                GoogleVoiceWebSender.mintPreparedBody(activity, address, body, attachments) ?: return@runCatching false
            }
            GoogleVoiceClient.get(context).sendPreparedSms(sendBody)
            true
        }.getOrDefault(false)
        LineChoice.WhatsApp -> withContext(Dispatchers.IO) {
            runCatching {
                // For WhatsApp the conversation is addressed by JID: use the thread's remoteId when
                // replying to an existing chat, else derive a 1:1 JID from the phone number.
                val jid = threadRemoteId ?: toWhatsAppJid(context, address)
                val ok = if (attachments.isEmpty()) {
                    WhatsAppClient.sendMessage(jid, body)
                } else {
                    // Media send goes through the dedicated path; here just send any caption text.
                    if (body.isNotBlank()) WhatsAppClient.sendMessage(jid, body) else true
                }
                // Echo the outgoing message into the local cache so it shows in our own thread
                // (a primary-only line gets no server echo of its own sends).
                if (ok && body.isNotBlank()) cacheOutgoingWhatsApp(context, jid, body)
                ok
            }.getOrDefault(false)
        }
    }

    /** Send an SMS from a specific SIM subscription and store it in the Sent box. */
    private fun sendSimSms(context: Context, subscriptionId: Int, address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) return false
        if (!context.hasPermission(Manifest.permission.SEND_SMS)) {
            openSmsComposer(context, address, body)
            return true
        }
        return runCatching {
            val base = context.getSystemService(android.telephony.SmsManager::class.java)
            val sms = if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
            val parts = sms.divideMessage(body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                sms.sendTextMessage(address, null, body, null, null)
            }
            // Record in the provider Sent box so it shows in the thread (we're the default SMS app).
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
                context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            }
            true
        }.getOrDefault(false)
    }

    /** Toggle a Google Voice thread attribute (read/archive/spam) via batchupdateattributes. */
    suspend fun updateGoogleVoiceThread(
        context: Context,
        remoteId: String,
        action: com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction,
    ): Boolean = runCatching {
        GoogleVoiceClient.get(context).updateThreadAttributes(remoteId, action)
        true
    }.getOrDefault(false)

    private suspend fun loadGoogleVoiceThreads(context: Context): List<SmsThread> {
        val session = GoogleVoiceSession.get(context)
        if (!session.hasUsableCredentials()) return emptyList()
        return runCatching {
            GoogleVoiceClient.get(context).listThreads().map { it.toSmsThread(context) }
        }.getOrDefault(emptyList())
    }

    private suspend fun loadGoogleVoiceCalls(context: Context): List<CommunicateCallLogEntry> {
        val session = GoogleVoiceSession.get(context)
        if (!session.hasUsableCredentials()) return emptyList()
        return runCatching {
            GoogleVoiceClient.get(context).listCalls().map { it.toCallLogEntry(context) }
        }.getOrDefault(emptyList())
    }

    /** Stable positive Long key for a GV remote id, kept clear of provider thread ids. */
    fun stableThreadId(remoteId: String): Long = (remoteId.hashCode().toLong() and 0xFFFFFFFFL) or 0x1_0000_0000L

    // ------------------------------------------------------------------
    // WhatsApp primary line: threads/messages read from the local Room cache
    // (populated by WhatsAppEventProcessor), rich actions delegate to WhatsAppClient.
    // ------------------------------------------------------------------

    /** Virtual (network-backed) lines that don't map to a physical SIM subscription. */
    val isVirtualLine = setOf(CommunicateLine.GoogleVoice, CommunicateLine.WhatsApp)

    private suspend fun loadWhatsAppThreads(context: Context): List<SmsThread> {
        if (!WhatsAppLineSession.get(context).isSignedIn()) return emptyList()
        return runCatching {
            val db = WhatsAppDatabase.getDatabase(context)
            db.cachedMessageDao().getLatestPerConversation().map { m ->
                val sd = WhatsAppServiceData.parse(m.serviceData)
                val jid = m.conversationJid
                val isGroup = sd?.isGroup ?: jid.endsWith("@g.us")
                val unread = db.conversationDao().getConversation(jid)?.unreadCount ?: 0
                SmsThread(
                    threadId = stableThreadId(jid),
                    address = jidToDisplayAddress(jid),
                    displayName = whatsAppDisplayName(context, jid, sd),
                    snippet = if (m.isRevoked) "This message was deleted" else m.body,
                    timestampMillis = m.timestamp,
                    unreadCount = unread,
                    line = CommunicateLine.WhatsApp,
                    remoteId = jid,
                    isGroup = isGroup,
                    avatarUrl = null,
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun loadWhatsAppMessages(context: Context, jid: String): List<SmsMessage> =
        runCatching {
            val db = WhatsAppDatabase.getDatabase(context)
            db.cachedMessageDao().getForConversation(jid).map { m ->
                SmsMessage(
                    id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                    threadId = stableThreadId(jid),
                    address = jidToDisplayAddress(jid),
                    body = if (m.isRevoked) "" else m.body,
                    timestampMillis = m.timestamp,
                    outgoing = m.outgoing,
                    read = true,
                    line = CommunicateLine.WhatsApp,
                    remoteId = m.messageId,
                    serviceData = m.serviceData,
                )
            }
        }.getOrDefault(emptyList())

    // -- WhatsApp rich actions (delegate to the client) --

    suspend fun sendWhatsAppReaction(
        jid: String,
        messageId: String,
        emoji: String,
        targetFromMe: Boolean,
        targetSenderJid: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        WhatsAppClient.sendReaction(jid, messageId, emoji, targetFromMe, targetSenderJid)
    }

    suspend fun editWhatsAppMessage(jid: String, messageId: String, newBody: String): Boolean =
        withContext(Dispatchers.IO) { WhatsAppClient.sendEdit(jid, messageId, newBody) }

    suspend fun revokeWhatsAppMessage(jid: String, messageId: String, senderJid: String = ""): Boolean =
        withContext(Dispatchers.IO) { WhatsAppClient.sendRevoke(jid, messageId, senderJid) }

    suspend fun sendWhatsAppPollVote(
        jid: String,
        pollMessageId: String,
        pollCreatorJid: String,
        pollFromMe: Boolean,
        selectedOptionNames: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        WhatsAppClient.sendPollVote(jid, pollMessageId, pollCreatorJid, pollFromMe, selectedOptionNames)
    }

    suspend fun createWhatsAppPoll(
        jid: String,
        question: String,
        options: List<String>,
        selectableCount: Int = 0,
    ): String? = withContext(Dispatchers.IO) {
        WhatsAppClient.sendPollCreation(jid, question, options, selectableCount)
    }

    suspend fun sendWhatsAppMedia(jid: String, bytes: ByteArray, mimeType: String, fileName: String?): Boolean =
        withContext(Dispatchers.IO) { WhatsAppClient.sendMedia(jid, bytes, mimeType, fileName) }

    suspend fun sendWhatsAppReadReceipt(
        jid: String,
        lastMessageId: String?,
        lastTimestamp: Long,
        senderJid: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        WhatsAppClient.sendReadReceipt(jid, lastMessageId, lastTimestamp, senderJid)
    }

    /**
     * Mark a WhatsApp conversation read: clear the local unread badge and send a `read` receipt for
     * the latest inbound message (blue ticks on the sender's side). [alreadyReadId] is the last
     * message we already receipted, so the foreground poll doesn't re-send. Returns the id now
     * marked read (or [alreadyReadId] when there is nothing newer).
     */
    suspend fun markWhatsAppRead(
        context: Context,
        remoteId: String?,
        address: String,
        alreadyReadId: String?,
    ): String? = withContext(Dispatchers.IO) {
        val jid = remoteId?.takeIf { it.isNotBlank() } ?: toWhatsAppJid(context, address)
        val db = WhatsAppDatabase.getDatabase(context)
        runCatching {
            db.conversationDao().getConversation(jid)?.let {
                if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
            }
        }
        val lastInbound = runCatching {
            db.cachedMessageDao().getForConversation(jid).lastOrNull { !it.outgoing }
        }.getOrNull() ?: return@withContext alreadyReadId
        if (lastInbound.messageId == alreadyReadId) return@withContext alreadyReadId
        // For groups the receipt needs the participant; 1:1 goes to the chat JID itself.
        val sender = if (jid.endsWith("@g.us")) lastInbound.senderJid.takeIf { it.isNotBlank() } else null
        runCatching {
            WhatsAppClient.sendReadReceipt(jid, lastInbound.messageId, lastInbound.timestamp / 1000, sender)
        }
        lastInbound.messageId
    }

    /** numeric local part of a JID (strips :device and .agent suffixes). */
    private fun jidLocalPart(jid: String): String =
        jid.substringBefore("@").substringBefore(":").substringBefore(".")

    private fun jidToDisplayAddress(jid: String): String {
        if (jid.endsWith("@g.us")) return jid
        val phone = jidLocalPart(jid)
        return if (phone.isNotEmpty() && phone.all { it.isDigit() }) "+$phone" else phone
    }

    private fun whatsAppDisplayName(context: Context, jid: String, sd: WhatsAppServiceData?): String? {
        if (jid.endsWith("@g.us")) return sd?.senderName // group display name not cached in v1
        val phone = jidLocalPart(jid)
        return findContactName(context, "+$phone") ?: sd?.senderName
    }

    /**
     * Build a 1:1 WhatsApp JID from a phone number / address. WhatsApp JIDs require the FULL
     * international number (country code, no '+'), so a nationally-typed number like "2134774209"
     * must be normalized to "12134774209" — otherwise usync returns 0 devices and the message goes
     * nowhere. Uses libphonenumber with the SIM/locale region to infer the country code.
     */
    private fun toWhatsAppJid(context: Context, address: String): String {
        if (address.contains("@")) return address
        val region = runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
            (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
        val e164 = runCatching {
            val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
            val parsed = util.parse(address, region)
            util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164)
        }.getOrNull()
        val normalized = (e164 ?: address).filter { it.isDigit() }
        return "$normalized@s.whatsapp.net"
    }

    /** Insert an outgoing WhatsApp message into the local cache so it shows in our own thread. */
    private suspend fun cacheOutgoingWhatsApp(context: Context, jid: String, body: String) {
        runCatching {
            val db = WhatsAppDatabase.getDatabase(context)
            val now = System.currentTimeMillis()
            db.cachedMessageDao().upsert(
                WhatsAppCachedMessage(
                    messageId = "local-${java.util.UUID.randomUUID()}",
                    conversationJid = jid,
                    body = body,
                    timestamp = now,
                    outgoing = true,
                ),
            )
            val existing = db.conversationDao().getConversation(jid)
            db.conversationDao().upsert(
                (existing ?: WhatsAppConversation(chatJid = jid)).copy(lastMessageTimestamp = now),
            )
        }
    }

    private fun GvThread.toSmsThread(context: Context): SmsThread = SmsThread(
        threadId = stableThreadId(id),
        address = phoneNumber,
        displayName = displayName ?: findContactName(context, phoneNumber),
        snippet = snippet.ifBlank { if (messages.any { it.hasMedia }) context.getString(R.string.gv_media_message) else "" },
        timestampMillis = timestampMillis,
        unreadCount = unreadCount,
        line = CommunicateLine.GoogleVoice,
        remoteId = id,
    )

    private fun GvMessage.toSmsMessage(threadId: Long, context: Context): SmsMessage = SmsMessage(
        id = ("$threadId#$id").hashCode().toLong(),
        threadId = threadId,
        address = phoneNumber,
        body = text.ifBlank { if (hasMedia) context.getString(R.string.gv_media_message) else "" },
        timestampMillis = timestampMillis,
        outgoing = outgoing,
        read = read,
        line = CommunicateLine.GoogleVoice,
        remoteId = id,
        attachments = mediaUrls.map { CommunicateAttachment(it, "image/*") },
    )

    private fun GvCall.toCallLogEntry(context: Context): CommunicateCallLogEntry = CommunicateCallLogEntry(
        id = stableThreadId(id),
        displayName = displayName ?: findContactName(context, phoneNumber),
        phoneNumber = phoneNumber,
        type = type.toCommunicateCallType(),
        timestampMillis = timestampMillis,
        durationSeconds = durationSeconds,
        line = CommunicateLine.GoogleVoice,
    )

    private fun GvCallType.toCommunicateCallType(): CommunicateCallType = when (this) {
        GvCallType.Incoming -> CommunicateCallType.Incoming
        GvCallType.Outgoing -> CommunicateCallType.Outgoing
        GvCallType.Missed -> CommunicateCallType.Missed
        GvCallType.Voicemail -> CommunicateCallType.Voicemail
        GvCallType.Unknown -> CommunicateCallType.Unknown
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Int.toCommunicateCallType(): CommunicateCallType = when (this) {
    CallLog.Calls.INCOMING_TYPE -> CommunicateCallType.Incoming
    CallLog.Calls.OUTGOING_TYPE -> CommunicateCallType.Outgoing
    CallLog.Calls.MISSED_TYPE -> CommunicateCallType.Missed
    CallLog.Calls.REJECTED_TYPE -> CommunicateCallType.Rejected
    CallLog.Calls.BLOCKED_TYPE -> CommunicateCallType.Blocked
    CallLog.Calls.VOICEMAIL_TYPE -> CommunicateCallType.Voicemail
    else -> CommunicateCallType.Unknown
}
