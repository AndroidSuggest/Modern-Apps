package com.vayunmathur.communicate.data.rcs.e2e

import android.content.Context
import android.telephony.SubscriptionManager
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsMlsGroup
import com.vayunmathur.communicate.data.rcs.RcsMlsIdentity
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Closed-loop MLS E2EE orchestration for the RCS line (our-app-to-our-app).
 *
 * Flow:
 * - Each local E.164 gets an Ed25519 identity (generated once, Room-held).
 * - 1:1 E2EE: creator builds a group, adds the peer's key package (fetched
 *   over RCS via [RcsKeyDirectory], which exchanges key packages as opaque
 *   RCS payloads), sends Welcome + commit; joiner persists the group.
 * - Group E2EE: same, with N key packages.
 * - Sends encrypt to the group; receives decrypt by group id. Commits merge
 *   automatically inside the crate.
 *
 * Key packages are exchanged over the RCS transport itself (no side channel):
 * an unencrypted `message/cpim` with content-type `application/x-rcs-keypackage`
 * carries the TLS bytes; Welcomes ride `application/x-rcs-welcome`.
 */
object RcsE2E {
    /** Content-Type marking a key-package publication message. */
    const val CT_KEY_PACKAGE = "application/x-rcs-keypackage"

    /** Content-Type marking a Welcome envelope message. */
    const val CT_WELCOME = "application/x-rcs-welcome"

    /** Content-Type marking an MLS commit message. */
    const val CT_COMMIT = "application/x-rcs-commit"

    /** Content-Type marking an MLS application (encrypted) message. */
    const val CT_MLS = "application/x-rcs-mls"

    /** Content-Type marking a key-package request (peer auto-answers). */
    const val CT_KEY_REQUEST = "application/x-rcs-keyrequest"

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Our identity bytes for [e164], generating + persisting on first use.
     * Null when the gate is off or native is unavailable.
     */
    suspend fun identityFor(context: Context, e164: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || e164.isBlank()) return@withContext null
            val db = RcsDatabase.getDatabase(context)
            db.mlsIdentityDao().get(e164)?.identity ?: runCatching {
                val out = RustMlsCrypto.generateIdentity(e164.toByteArray(Charsets.UTF_8))
                    ?: return@runCatching null
                val identity = out.getOrNull(1) ?: return@runCatching null
                db.mlsIdentityDao().upsert(
                    RcsMlsIdentity(e164 = e164, identity = identity, updatedAt = System.currentTimeMillis()),
                )
                identity
            }.getOrNull()
        }

    /**
     * Fresh key package TLS for [e164] (publish to peers). Persists the
     * evolved storage snapshot. Null on any failure.
     */
    suspend fun freshKeyPackage(context: Context, e164: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
            val identity = identityFor(context, e164) ?: return@withContext null
            val db = RcsDatabase.getDatabase(context)
            val group = db.mlsGroupDao().getByConversation(e2eThreadFor(e164))
            val storage = group?.storage ?: ByteArray(0)
            runCatching {
                val out = RustMlsCrypto.buildKeyPackage(storage, identity) ?: return@runCatching null
                val storageOut = out.getOrNull(0) ?: ByteArray(0)
                val kp = out.getOrNull(1) ?: return@runCatching null
                if (group != null) {
                    db.mlsGroupDao().upsert(group.copy(storage = storageOut, updatedAt = System.currentTimeMillis()))
                } else {
                    // No group yet: stash the snapshot under the 1:1 pseudo-group row.
                    db.mlsGroupDao().upsert(
                        RcsMlsGroup(
                            groupIdHex = "pending:$e164",
                            conversationId = e2eThreadFor(e164),
                            storage = storageOut,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                kp
            }.getOrNull()
        }

    /**
     * Create an E2EE group for [conversationId] with our local identity, then
     * add [peerKeyPackages]. Returns the commit + welcome payloads to send
     * (commit → group thread, welcome → each joiner 1:1). Persists the group.
     */
    suspend fun createEncryptedGroup(
        context: Context,
        e164: String,
        conversationId: String,
        peerKeyPackages: List<ByteArray>,
    ): Pair<ByteArray, ByteArray>? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable || peerKeyPackages.isEmpty()) return@withContext null
        val identity = identityFor(context, e164) ?: return@withContext null
        val db = RcsDatabase.getDatabase(context)
        runCatching {
            var storage = db.mlsGroupDao().getByConversation(conversationId)?.storage ?: ByteArray(0)
            val created = RustMlsCrypto.createGroup(storage, identity) ?: return@runCatching null
            storage = created.getOrNull(0) ?: storage
            val groupId = created.getOrNull(1) ?: return@runCatching null
            val added = RustMlsCrypto.addMembers(storage, identity, groupId, peerKeyPackages.toTypedArray())
                ?: return@runCatching null
            val storageOut = added.getOrNull(0) ?: storage
            val commit = added.getOrNull(1) ?: return@runCatching null
            val welcome = added.getOrNull(2) ?: return@runCatching null
            db.mlsGroupDao().upsert(
                RcsMlsGroup(
                    groupIdHex = groupId.hex(),
                    conversationId = conversationId,
                    storage = storageOut,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            commit to welcome
        }.getOrNull()
    }

    /**
     * Join from a Welcome envelope. Persists the group under [conversationId].
     * Returns the group id, or null.
     */
    suspend fun joinEncryptedGroup(
        context: Context,
        conversationId: String,
        welcome: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
        val db = RcsDatabase.getDatabase(context)
        val storage = db.mlsGroupDao().getByConversation(conversationId)?.storage ?: ByteArray(0)
        runCatching {
            val out = RustMlsCrypto.joinGroup(storage, welcome) ?: return@runCatching null
            val storageOut = out.getOrNull(0) ?: storage
            val groupId = out.getOrNull(1) ?: return@runCatching null
            db.mlsGroupDao().upsert(
                RcsMlsGroup(
                    groupIdHex = groupId.hex(),
                    conversationId = conversationId,
                    storage = storageOut,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            groupId
        }.getOrNull()
    }

    /**
     * Encrypt [plaintext] for [conversationId]'s group. Returns the framed
     * payload to send, or null.
     */
    suspend fun encryptTo(
        context: Context,
        e164: String,
        conversationId: String,
        plaintext: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
        val identity = identityFor(context, e164) ?: return@withContext null
        val db = RcsDatabase.getDatabase(context)
        val group = db.mlsGroupDao().getByConversation(conversationId) ?: return@withContext null
        if (group.groupIdHex.startsWith("pending:")) return@withContext null
        runCatching {
            val groupId = group.groupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val out = RustMlsCrypto.encrypt(group.storage, identity, groupId, plaintext)
                ?: return@runCatching null
            val storageOut = out.getOrNull(0) ?: group.storage
            val payload = out.getOrNull(1) ?: return@runCatching null
            db.mlsGroupDao().upsert(group.copy(storage = storageOut, updatedAt = System.currentTimeMillis()))
            payload
        }.getOrNull()
    }

    /**
     * Decrypt [payload] for [conversationId]'s group. Returns
     * `(plaintext, isApplication)` — commits merge silently with empty
     * plaintext. Null on failure (caller keeps the opaque row / drops).
     */
    suspend fun decryptFor(
        context: Context,
        conversationId: String,
        payload: ByteArray,
    ): Pair<ByteArray, Boolean>? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return@withContext null
        val db = RcsDatabase.getDatabase(context)
        val group = db.mlsGroupDao().getByConversation(conversationId) ?: return@withContext null
        if (group.groupIdHex.startsWith("pending:")) return@withContext null
        runCatching {
            val groupId = group.groupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val out = RustMlsCrypto.decrypt(group.storage, groupId, payload)
                ?: return@runCatching null
            val storageOut = out.getOrNull(0) ?: group.storage
            val isApp = (out.getOrNull(1)?.firstOrNull() ?: 0) == 1.toByte()
            val plaintext = out.getOrNull(2) ?: ByteArray(0)
            db.mlsGroupDao().upsert(group.copy(storage = storageOut, updatedAt = System.currentTimeMillis()))
            plaintext to isApp
        }.getOrNull()
    }

    /**
     * Request the peer's key package (they auto-publish on receipt) and
     * publish ours. Call when the user taps "Start encrypted chat".
     */
    suspend fun requestEncryptedChat(context: Context, localE164: String, peerE164: String): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        // Ensure our identity exists first.
        identityFor(context, localE164) ?: return false
        // Publish ours alongside the request so one round trip suffices.
        RcsKeyDirectory.publishTo(context, localE164, peerE164)
        return sendKeyRequest(context, peerE164)
    }

    private suspend fun sendKeyRequest(context: Context, peerE164: String): Boolean {
        if (!RcsSipTransport.canSend()) return false
        val body = "Content-Type: $CT_KEY_REQUEST\r\n\r\nrequest"
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$peerE164@rcs",
            callId = "${UUID.randomUUID()}@rcs-kreq",
            body = body,
        )
        return runCatching { RcsSipTransport.sendSipMessage(startLine, headers, content) }
            .getOrDefault(false)
    }

    /**
     * Create the E2EE group once the peer's key package is known and send the
     * Welcome + commit. Called when a key package arrives for a conversation
     * with no group yet, or explicitly from the setup UI. Returns true when
     * the Welcome + commit were accepted by the transport.
     */
    suspend fun setupGroupWithPeer(
        context: Context,
        localE164: String,
        conversationId: String,
        peerE164: String,
        peerKeyPackage: ByteArray,
    ): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        val (commit, welcome) = createEncryptedGroup(context, localE164, conversationId, listOf(peerKeyPackage))
            ?: return false
        // Commit → the (new) group thread; Welcome → the peer 1:1.
        val commitOk = sendMlsEnvelope(context, conversationId, commit, CT_COMMIT)
        val welcomeOk = sendMlsEnvelope(context, peerE164, welcome, CT_WELCOME)
        // Rotate our key package now that the previous one may be consumed.
        freshKeyPackage(context, localE164)
        return commitOk && welcomeOk
    }

    private suspend fun sendMlsEnvelope(
        context: Context,
        recipient: String,
        framed: ByteArray,
        contentType: String,
    ): Boolean {
        if (!RcsSipTransport.canSend()) return false
        val b64 = android.util.Base64.encodeToString(framed, android.util.Base64.NO_WRAP)
        val body = "Content-Type: $contentType\r\n\r\n$b64"
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$recipient@rcs",
            callId = "${UUID.randomUUID()}@rcs-env",
            body = body,
        )
        val typedHeaders = headers.replace("Content-Type: message/cpim", "Content-Type: $contentType")
        return runCatching { RcsSipTransport.sendSipMessage(startLine, typedHeaders, content) }
            .getOrDefault(false)
    }

    /** Group id for [conversationId], or null when no E2EE group exists. */
    suspend fun groupIdFor(context: Context, conversationId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext null
            val group = RcsDatabase.getDatabase(context).mlsGroupDao().getByConversation(conversationId)
                ?: return@withContext null
            if (group.groupIdHex.startsWith("pending:")) return@withContext null
            group.groupIdHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

    /**
     * True when [body] (CPIM text or raw) carries an MLS payload: magic
     * prefixes after base64 decode, or the MLS content types.
     */
    fun isMlsPayload(body: String): Boolean {
        if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return false
        if (body.contains(CT_MLS, ignoreCase = true) ||
            body.contains(CT_COMMIT, ignoreCase = true) ||
            body.contains(CT_WELCOME, ignoreCase = true)
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
        if (bytes.size < 8) return false
        return runCatching { RustMlsCrypto.payloadKind(bytes) in 0..1 }.getOrDefault(false)
    }

    /** Our own E.164 (default SMS subscription's number when readable). */
    fun localE164(context: Context): String? {
        if (!RcsFeature.enabled) return null
        return runCatching {
            val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
                ?: return null
            tm.line1Number?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun e2eThreadFor(e164: String): String = "e2e:$e164"
}
