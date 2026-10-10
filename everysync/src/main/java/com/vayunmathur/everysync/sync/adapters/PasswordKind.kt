package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for passwords and passkeys. Both ride the same
 * `passwords:<vaultId>` channel as native [RemotePassword] JSON (no KDBX
 * mapping); [RemotePassword.isPasskey] distinguishes the two halves.
 */
object PasswordKind : EntityKind<RemotePassword>() {
    override val kind = "password"

    private val json = Json { ignoreUnknownKeys = true }

    override fun syncId(e: RemotePassword) = e.uid
    override fun updatedAt(e: RemotePassword) = e.updatedAt
    override fun isDeleted(e: RemotePassword) = e.deleted

    override fun contentHash(e: RemotePassword): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemotePassword): String =
        if (e.isPasskey) {
            "passkey\u0000${e.rpId}\u0000${e.credentialId}"
        } else {
            "password\u0000${e.name}\u0000${e.username}"
        }

    override fun rebind(e: RemotePassword, syncId: String) = e.copy(uid = syncId)
    override fun withDeleted(e: RemotePassword, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemotePassword, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    /**
     * Canonical JSON with identity and timestamps stripped, so the hash is
     * comparable across sides and stable across sync cycles.
     */
    fun canonical(e: RemotePassword): String = json.encodeToString(
        e.copy(uid = "", updatedAt = 0L, deleted = false),
    )

    fun toJson(e: RemotePassword): String = json.encodeToString(e)

    fun fromJson(raw: String): RemotePassword? = runCatching {
        json.decodeFromString<RemotePassword>(raw)
    }.getOrNull()

    /** Native password record. */
    fun password(
        uid: String,
        name: String,
        username: String = "",
        email: String = "",
        password: String = "",
        note: String = "",
        totpSecret: String? = null,
        websites: List<String> = emptyList(),
        updatedAt: Long = System.currentTimeMillis(),
    ) = RemotePassword(
        uid = uid,
        isPasskey = false,
        name = name,
        username = username,
        email = email,
        password = password,
        note = note,
        totpSecret = totpSecret,
        websites = websites,
        updatedAt = updatedAt,
    )

    /** Native passkey record. */
    fun passkey(
        uid: String,
        rpId: String,
        credentialId: String,
        rpName: String = "",
        userId: String = "",
        userName: String = "",
        userDisplayName: String = "",
        privateKeyB64: String = "",
        creationTime: Long = System.currentTimeMillis(),
        lastUsedTime: Long = System.currentTimeMillis(),
        signCount: Int = 0,
        linkedPasswordSyncId: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
    ) = RemotePassword(
        uid = uid,
        isPasskey = true,
        rpId = rpId,
        rpName = rpName,
        credentialId = credentialId,
        userId = userId,
        userName = userName,
        userDisplayName = userDisplayName,
        privateKeyB64 = privateKeyB64,
        creationTime = creationTime,
        lastUsedTime = lastUsedTime,
        signCount = signCount,
        linkedPasswordSyncId = linkedPasswordSyncId,
        updatedAt = updatedAt,
    )
}
