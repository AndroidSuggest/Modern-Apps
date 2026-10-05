package com.vayunmathur.communicate.data.rcs.e2e

import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Group-query helpers for [RcsE2E] (split for file length).
 * Extension functions on [RcsE2E]; behavior identical, call sites unchanged.
 */

internal suspend fun RcsE2E.groupIdFor(context: android.content.Context, conversationId: String): ByteArray? =
    withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        val group = RcsDatabase.getDatabase(context).mlsGroupDao().getByConversation(conversationId)
            ?: return@withContext null
        if (group.groupIdHex.startsWith("pending:")) return@withContext null
        group.groupIdHex.chunked(RcsE2E.HEX_PAIR).map { it.toInt(RcsE2E.HEX_RADIX).toByte() }.toByteArray()
    }

/**
 * True when [body] (CPIM text or raw) carries an MLS payload: magic
 * prefixes after base64 decode, or the MLS content types.
 */
internal fun RcsE2E.isMlsPayload(body: String): Boolean {
    if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
    if (body.contains(RcsE2E.CT_MLS, ignoreCase = true) ||
        body.contains(RcsE2E.CT_COMMIT, ignoreCase = true) ||
        body.contains(RcsE2E.CT_WELCOME, ignoreCase = true)
    ) {
        return true
    }
    val b64 = if (body.contains("\r\n\r\n")) {
        body.substringAfter("\r\n\r\n", "").trim()
    } else {
        body.trim()
    }.takeIf { it.isNotEmpty() } ?: return false
    val bytes = runCatching {
        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }.getOrNull() ?: return false
    if (bytes.size < RcsE2E.MIN_FRAME_BYTES) return false
    return runCatching { RustMlsCrypto.payloadKind(bytes) in RcsE2E.PAYLOAD_KINDS }.getOrDefault(false)
}

/** Our own E.164 (default SMS subscription's number when readable). */
internal fun RcsE2E.localE164(context: android.content.Context): String? {
    if (!RcsFeature.enabled) return null
    return runCatching {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
            ?: return null
        tm.line1Number?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

internal fun RcsE2E.parseMembers(csv: String): Map<String, Int> {
    if (csv.isBlank()) return emptyMap()
    return csv.split(",").mapNotNull { entry ->
        val (member, idx) = entry.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val index = idx.trim().toIntOrNull() ?: return@mapNotNull null
        member.trim().takeIf { it.isNotEmpty() }?.to(index)
    }.toMap()
}

/** Drop [droppedIdx] leaves from a member map, re-encoding the CSV. */
internal fun RcsE2E.pruneMembers(csv: String, droppedIdx: Set<Int>): String =
    parseMembers(csv).filterValues { it !in droppedIdx }
        .entries.joinToString(",") { "${it.key}=${it.value}" }

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun RcsE2E.commitLockFor(conversationId: String): kotlinx.coroutines.sync.Mutex =
    commitLocks.getOrPut(conversationId) { kotlinx.coroutines.sync.Mutex() }

internal fun e2eThreadFor(e164: String): String = "e2e:$e164"
