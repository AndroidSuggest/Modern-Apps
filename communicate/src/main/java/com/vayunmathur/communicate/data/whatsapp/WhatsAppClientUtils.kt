package com.vayunmathur.communicate.data.whatsapp

import android.util.Base64
import android.util.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolve the device list for the given (bare) user JIDs via a usync query.
 * Ref whatsmeow user.go GetUserDevices. Returns wire device JIDs ("user@server" for device 0,
 * "user:N@server" otherwise). Returns empty on failure (callers fall back to the bare JID).
 */
internal suspend fun WhatsAppClient.getUserDevices(userJids: List<String>): List<String> {
    if (userJids.isEmpty()) return emptyList()
    val bareUsers = userJids.map { it.substringBefore(":").let { u -> if (u.contains("@")) u else "$u@s.whatsapp.net" } }
    val iq = WhatsAppProtocol.buildUsyncDevicesQuery(bareUsers, generateMessageId(), generateMessageId())
    val resp = sendIqAndWait(iq, timeoutMs = 10_000) ?: run {
        WhatsAppDiag.log(TAG, "usync: no response for $bareUsers (timeout)")
        return emptyList()
    }
    val list = resp.getChildByTag("usync")?.getChildByTag("list") ?: run {
        WhatsAppDiag.log(TAG, "usync: malformed response (no list) for $bareUsers")
        return emptyList()
    }
    val devices = mutableListOf<String>()
    list.getChildren().filter { it.tag == "user" }.forEach { user ->
        val userJid = user.attrs["jid"] ?: return@forEach
        val bareUser = userJid.substringBefore("@").substringBefore(":")
        val server = userJid.substringAfter("@", "s.whatsapp.net")
        val deviceList = user.getChildByTag("devices")?.getChildByTag("device-list") ?: return@forEach
        deviceList.getChildren().filter { it.tag == "device" }.forEach { d ->
            if (d.attrs["is_hosted"] == "true") return@forEach
            val devId = d.attrs["id"]?.toIntOrNull() ?: return@forEach
            devices.add(if (devId == 0) "$bareUser@$server" else "$bareUser:$devId@$server")
        }
    }
    WhatsAppDiag.log(TAG, "usync: $bareUsers -> ${devices.size} device(s)")
    return devices
}

/**
 * Track undecryptable messages.
 * From Go handleWAUndecryptableMessage / trackUndecryptable.
 */
internal fun WhatsAppClient.trackUndecryptable(node: WhatsAppProtocol.Node) {
    val from = node.attrs["from"] ?: return
    val count = undecryptableTracker.merge(from, 1) { old, new -> old + new } ?: 1
    Log.w(TAG, "Undecryptable message from $from (count: $count)")
}

/**
 * Resolve JID: convert LID JIDs to phone number JIDs.
 * Handles DM sender LID, own message LID, broadcast, and bot cases.
 * From Go resolveJID / rerouteWAMessage — 5 distinct rerouting cases.
 */
internal fun WhatsAppClient.resolveJID(jid: String): String {
    // Case 1: Not a LID — no rerouting needed
    if (!jid.contains("@lid")) return jid

    // Case 2: Direct LID→phone mapping from cache
    lidToPhoneMap[jid]?.let { return it }

    // Case 3: Own LID matches
    val ownLid = authData?.lid
    if (ownLid != null && ownLid.isNotEmpty() && jid == ownLid) {
        return authData?.wid ?: jid
    }

    // Case 4: Bot server JIDs (Go rerouteWAMessage bot server case)
    if (jid.contains("@bot")) {
        return jid // Bot JIDs are valid as-is
    }

    // Case 5: Extract user part and check LID map without server suffix
    val userPart = jid.substringBefore("@")
    lidToPhoneMap.entries.find { it.key.startsWith("$userPart@") }?.let { return it.value }

    return jid
}

/** Look up a device contact display name for an E.164 number (null if none / no permission). */
internal fun WhatsAppClient.resolveDeviceContactName(phoneE164: String?): String? {
    if (phoneE164.isNullOrEmpty()) return null
    return try {
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneE164),
        )
        appContext.contentResolver.query(
            uri,
            arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    } catch (e: Exception) {
        null
    }
}

/** Inflate a zlib (RFC 1950) compressed buffer. WhatsApp history blobs are zlib-compressed. */
internal fun WhatsAppClient.inflateZlib(data: ByteArray): ByteArray {
    val inflater = java.util.zip.Inflater()
    inflater.setInput(data)
    val out = java.io.ByteArrayOutputStream(maxOf(64, data.size * 4))
    val buf = ByteArray(16384)
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.finished() || inflater.needsDictionary()) break
                if (inflater.needsInput()) break
            }
            out.write(buf, 0, n)
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}

/**
 * Download and decrypt media from a WhatsApp media message.
 * Integrates WhatsAppProtocol.decryptMedia() for incoming media.
 * From Go whatsmeow.Download.
 */
suspend fun WhatsAppClient.downloadMedia(
    url: String,
    mediaKey: ByteArray,
    mediaType: String,
): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val response = NetworkClient.execute(
            url,
            "GET",
            mapOf(
                "Origin" to "https://web.whatsapp.com",
                "Referer" to "https://web.whatsapp.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            ),
            connectTimeoutMs = MEDIA_CONNECT_TIMEOUT_MS,
            readTimeoutMs = MEDIA_READ_TIMEOUT_MS,
        )
        if (!response.isSuccess) {
            Log.e(TAG, "Media download failed: HTTP ${response.status}")
            return@withContext null
        }
        val encrypted = response.bytes
        if (encrypted.isEmpty()) return@withContext null
        WhatsAppProtocol.decryptMedia(encrypted, mediaKey, mediaType)
    } catch (e: Exception) {
        Log.e(TAG, "Media download/decrypt failed", e)
        null
    }
}

fun WhatsAppClient.isLoggedIn(): Boolean {
    return authData != null && _state.value is State.Connected
}

/**
 * Download + decrypt an incoming media message and cache it locally, returning a
 * [com.vayunmathur.communicate.data.whatsapp.MessageAttachment] pointing at the decrypted file (file://
 * URL) so the UI renders it inline. Returns null for non-media messages or on
 * download/decrypt failure (the "[Image]"/"[Video]" placeholder body then remains).
 * Ref whatsmeow Download.
 */
internal suspend fun WhatsAppClient.buildIncomingMediaAttachment(
    e2e: WhatsAppE2EProto.Message,
    msgId: String,
): com.vayunmathur.communicate.data.whatsapp.MessageAttachment? {
    val url: String; val directPath: String; val mediaKey: ByteArray; val encSha: ByteArray
    val mime: String?; val keyInfo: String; val type: String
    val width: Int; val height: Int; val fileName: String?; val ext: String
    when {
        e2e.hasImageMessage() -> e2e.imageMessage.let {
            url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
            encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "image/jpeg" }
            keyInfo = WhatsAppProtocol.MEDIA_KEY_IMAGE; type = "image"
            width = it.width; height = it.height; fileName = null; ext = "jpg"
        }
        e2e.hasStickerMessage() -> e2e.stickerMessage.let {
            url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
            encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "image/webp" }
            keyInfo = WhatsAppProtocol.MEDIA_KEY_STICKER; type = "sticker"
            width = it.width; height = it.height; fileName = null; ext = "webp"
        }
        e2e.hasVideoMessage() -> e2e.videoMessage.let {
            url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
            encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "video/mp4" }
            keyInfo = WhatsAppProtocol.MEDIA_KEY_VIDEO; type = "video"
            width = it.width; height = it.height; fileName = null; ext = "mp4"
        }
        e2e.hasAudioMessage() -> e2e.audioMessage.let {
            url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
            encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "audio/ogg" }
            keyInfo = WhatsAppProtocol.MEDIA_KEY_AUDIO; type = "audio"
            width = 0; height = 0; fileName = null; ext = "ogg"
        }
        e2e.hasDocumentMessage() -> e2e.documentMessage.let {
            url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
            encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { null }
            keyInfo = WhatsAppProtocol.MEDIA_KEY_DOCUMENT; type = "file"
            width = 0; height = 0
            fileName = it.fileName.ifEmpty { it.title.ifEmpty { null } }
            ext = it.fileName.substringAfterLast('.', "").ifEmpty { "bin" }
        }
        else -> return null
    }
    if (mediaKey.isEmpty()) return null
    val downloadUrl = url.ifEmpty {
        if (directPath.isEmpty()) return null
        val host = mediaConn()?.first ?: return null
        val mmsType = when (type) {
            "sticker", "image" -> "image"
            "video" -> "video"
            "audio" -> "audio"
            else -> "document"
        }
        val hash = Base64.encodeToString(encSha, Base64.URL_SAFE or Base64.NO_WRAP)
        "https://$host$directPath&hash=$hash&mms-type=$mmsType&__wa-mms="
    }
    val bytes = downloadMedia(downloadUrl, mediaKey, keyInfo) ?: return null
    return try {
        val dir = java.io.File(appContext.cacheDir, "whatsapp_media")
        dir.mkdirs()
        val safeName = msgId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = java.io.File(dir, "$safeName.$ext")
        if (!(file.exists() && file.length() > 0)) file.writeBytes(bytes)
        com.vayunmathur.communicate.data.whatsapp.MessageAttachment(
            url = "file://${file.absolutePath}",
            mimeType = mime,
            attachmentType = type,
            fileName = fileName,
            width = width,
            height = height,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to cache incoming media for $msgId", e)
        null
    }
}

/**
 * Logout from WhatsApp server and clear local data.
 * From whatsmeow LogoutRemote
 */
fun WhatsAppClient.logoutRemote() {
    scope.launch {
        val ws = webSocket
        val ownJid = authData?.wid ?: ""
        if (ws != null && ownJid.isNotEmpty()) {
            val logoutNode = WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf(
                    "to" to "s.whatsapp.net",
                    "type" to "set",
                    "xmlns" to "md",
                    "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                ),
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "remove-companion-device",
                        attrs = mapOf("jid" to ownJid, "reason" to "user_initiated")
                    )
                )
            )
            try {
                ws.send(WhatsAppProtocol.encodeNode(logoutNode))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send logout", e)
            }
        }
        stop()
    }
}

internal fun WhatsAppClient.extractJid(conversationId: String): String? {
    // Conversation ID arrives double-prefixed: the bridge prepends the source idPrefix ("wa")
    // to our already-"wa:"-prefixed id, giving "wa:wa:{jid}". Strip all leading "wa:".
    var s = conversationId
    while (s.startsWith("wa:")) s = s.removePrefix("wa:")
    return s.ifEmpty { null }
}

/** Strip the DB source prefix ("wa:") from a stored message id to recover the raw
 *  WhatsApp message id that the wire protocol (receipts, reaction keys) expects. */
internal fun WhatsAppClient.extractMessageId(messageId: String): String {
    var s = messageId
    while (s.startsWith("wa:")) s = s.removePrefix("wa:")
    return s
}

/** True when [jid] refers to the local user's own account (phone wid or LID), comparing the
 *  bare user part so device/agent suffixes and PN/LID addressing don't matter. */
internal fun WhatsAppClient.isOwnJid(jid: String?): Boolean {
    if (jid.isNullOrEmpty()) return false
    fun user(j: String?) = j?.substringBefore("@")?.substringBefore(":")?.substringBefore(".")
    val u = user(jid) ?: return false
    if (u.isEmpty()) return false
    return u == user(authData?.wid) || u == user(authData?.lid)
}

internal fun WhatsAppClient.generateMessageId(): String {
    return WhatsAppProtocol.generateMessageId(authData?.wid)
}

internal fun WhatsAppClient.generateRef(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal fun WhatsAppClient.generateClientId(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal fun WhatsAppClient.generateToken(): String {
    val bytes = ByteArray(24)
    random.nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal suspend fun WhatsAppClient.resolveName(jid: String): String {
    // Bot accounts (Meta AI) have no phone number — give them a friendly name.
    if (jid.contains("@bot")) return "Meta AI"
    return nameCache.getOrPut(jid) {
        // Display the phone number, stripping any device/agent/group suffixes
        // ("1234:1@…", "1234.5@…", "1234-1620@g.us").
        val phone = jid.substringBefore("@")
            .substringBefore(":")
            .substringBefore(".")
            .substringBefore("-")
        "+$phone"
    }
}

/**
 * Replace the raw "@<number>" mention tokens WhatsApp embeds in message text with the
 * mentioned contact's display name (or "You" for the local user, "Meta AI" for the bot),
 * using [WhatsAppMessage.mentionedJids] to know who each token refers to.
 */
internal suspend fun WhatsAppClient.resolveMentionsInBody(body: String, mentionedJids: List<String>): String {
    if (mentionedJids.isEmpty() || !body.contains("@")) return body
    var result = body
    for (jid in mentionedJids) {
        val userPart = jid.substringBefore("@").substringBefore(":").substringBefore(".")
        if (userPart.isEmpty()) continue
        val resolved = resolveJID(jid)
        val name = when {
            resolved == authData?.wid || resolved == authData?.lid -> "You"
            else -> resolveName(resolved)
        }
        result = result.replace("@$userPart", "@$name")
    }
    return result
}

fun WhatsAppClient.getContactSuggestions(query: String): List<ContactSuggestion> {
    // Return cached contacts matching query
    return nameCache.entries
        .filter { it.value.contains(query, ignoreCase = true) }
        .map { ContactSuggestion(it.value, null, null, MessageSource.WHATSAPP) }
        .take(10)
}
