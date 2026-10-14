package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.domain.format.ICalendar
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for calendar events. Identity is UID plus RECURRENCE-ID
 * ([RemoteEvent.recurrenceId]): each overridden instance merges independently
 * while the master keeps a null recurrence id. The relay envelope is the
 * [RemoteEvent] JSON.
 */
object CalendarKind : EntityKind<RemoteEvent>() {
    override val kind = "event"

    /** Calendar soft-deletes at the platform layer (DELETED flags); the default holds. */
    override val localDeletesAreAuthoritative = true

    private val json = Json { ignoreUnknownKeys = true }

    /** UID + RECURRENCE-ID so one instance never collides with its master. */
    override fun syncId(e: RemoteEvent): String =
        if (e.recurrenceId.isNullOrBlank()) e.uid else "${e.uid}\u0000${e.recurrenceId}"

    override fun updatedAt(e: RemoteEvent) = e.updatedAt
    override fun isDeleted(e: RemoteEvent) = e.deleted

    override fun contentHash(e: RemoteEvent): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemoteEvent): String =
        "event\u0000${e.calendarId}\u0000${e.uid}\u0000${e.recurrenceId.orEmpty()}"

    override fun rebind(e: RemoteEvent, syncId: String): RemoteEvent {
        val sep = syncId.indexOf('\u0000')
        return if (sep < 0) {
            e.copy(uid = syncId)
        } else {
            e.copy(uid = syncId.take(sep), recurrenceId = syncId.substring(sep + 1).ifBlank { null })
        }
    }

    override fun withDeleted(e: RemoteEvent, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemoteEvent, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    /**
     * Canonical iCalendar without identity or transport metadata, so a fresh
     * sync id or a server re-tag never looks like an edit. RECURRENCE-ID is
     * kept: it distinguishes an overridden instance from its master.
     */
    fun canonical(e: RemoteEvent): String =
        ICalendar.serialize(
            e.copy(
                uid = "",
                etag = null,
                href = null,
                calendarId = "",
                updatedAt = 0L,
            ),
        )

    fun toJson(e: RemoteEvent): String = json.encodeToString(e)

    fun fromJson(raw: String): RemoteEvent? = runCatching {
        json.decodeFromString<RemoteEvent>(raw)
    }.getOrNull()
}
