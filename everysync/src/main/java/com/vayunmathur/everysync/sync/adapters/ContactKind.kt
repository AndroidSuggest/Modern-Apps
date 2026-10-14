package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.domain.format.VCard
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for contacts. The relay envelope is the
 * [RemoteContact] JSON; the canonical content form for hashing is the vCard
 * text with identity and transport metadata stripped, so a server re-tag or
 * a fresh sync id never looks like an edit.
 */
object ContactKind : EntityKind<RemoteContact>() {
    override val kind = "contact"

    /** Contacts soft-delete at the platform layer (DELETED flags); the default holds. */
    override val localDeletesAreAuthoritative = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun syncId(e: RemoteContact) = e.uid
    override fun updatedAt(e: RemoteContact) = e.updatedAt
    override fun isDeleted(e: RemoteContact) = e.deleted

    override fun contentHash(e: RemoteContact): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemoteContact): String =
        "contact\u0000${e.displayName}\u0000${e.phones.firstOrNull()?.value.orEmpty()}" +
            "\u0000${e.emails.firstOrNull()?.value.orEmpty()}"

    override fun rebind(e: RemoteContact, syncId: String) = e.copy(uid = syncId)
    override fun withDeleted(e: RemoteContact, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemoteContact, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    /** Canonical vCard without identity or transport metadata. */
    fun canonical(e: RemoteContact): String =
        VCard.serialize(e.copy(uid = "", etag = null, href = null, updatedAt = 0L))

    fun toJson(e: RemoteContact): String = json.encodeToString(e)

    fun fromJson(raw: String): RemoteContact? = runCatching {
        json.decodeFromString<RemoteContact>(raw)
    }.getOrNull()

    fun parseVCard(text: String, fallbackUid: String, updatedAt: Long = 0L): RemoteContact =
        VCard.parse(text, fallbackUid).copy(updatedAt = updatedAt)
}
