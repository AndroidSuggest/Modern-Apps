@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.everysync.network.remote

import kotlin.uuid.Uuid
import android.content.Context
import com.vayunmathur.everysync.domain.format.ICalendar
import com.vayunmathur.everysync.domain.format.VCard
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.data.sink.CalendarSink
import com.vayunmathur.everysync.data.sink.ContactsSink

/**
 * Shared two-way CalDAV/CardDAV sync routines against [ContactsSink] /
 * [CalendarSink]. Resource hrefs are used as the local SOURCE_ID / _SYNC_ID so
 * ETag comparison is direct; server deletions are detected by set difference.
 * Local edits are pushed back via PUT/DELETE with If-Match. Used by every DAV
 * caller regardless of auth (Basic for iCloud/generic, Bearer for Google).
 */
object DavSync {

    suspend fun syncContacts(
        context: Context,
        account: String,
        client: DavClient,
        baseUrl: String,
        direction: SyncDirection,
    ) {
        val collections = client.discoverCollections(baseUrl, isCalendar = false)
        if (direction != SyncDirection.PUSH) {
            pullContacts(context, account, client, collections)
        }
        if (direction != SyncDirection.PULL && collections.isNotEmpty()) {
            pushContacts(context, account, client, collections.first().url.trimEnd('/'))
        }
    }

    private suspend fun pullContacts(
        context: Context,
        account: String,
        client: DavClient,
        collections: List<DavCollection>,
    ) {
        val local = ContactsSink.localUidToEtag(context, account)
        val allRemoteHrefs = mutableSetOf<String>()
        for (col in collections) {
            val resources = client.listResources(col.url)
            allRemoteHrefs += resources.map { it.href }
            val changed = resources.filter { local[it.href] != it.etag }
            val fetched = client.multiget(col.url, changed.map { it.href }, isCalendar = false)
            for (res in fetched) {
                val data = res.data ?: continue
                val parsed = VCard.parse(data, fallbackUid = res.href)
                val withHref = parsed.copy(uid = res.href, etag = res.etag, href = res.href)
                ContactsSink.upsert(context, account, withHref)
            }
        }
        // Server-side deletions.
        (local.keys - allRemoteHrefs).forEach { ContactsSink.delete(context, account, it) }
    }

    private suspend fun pushContacts(
        context: Context,
        account: String,
        client: DavClient,
        collectionUrl: String,
    ) {
        for (change in ContactsSink.getLocalChanges(context, account)) {
            pushContactChange(context, account, client, collectionUrl, change)
        }
    }

    private suspend fun pushContactChange(
        context: Context,
        account: String,
        client: DavClient,
        collectionUrl: String,
        change: com.vayunmathur.everysync.data.sink.LocalContactChange,
    ) {
        if (change.deleted && change.sourceId != null) {
            client.delete(change.sourceId, change.etag)
            return
        }
        val contact = change.contact ?: return
        if (change.deleted) return
        val href = change.sourceId ?: "$collectionUrl/${Uuid.random()}.vcf"
        val vcard = VCard.serialize(contact.copy(uid = contact.uid.ifBlank { href }))
        val newEtag = client.put(href, "text/vcard; charset=utf-8", vcard, change.etag)
        if (change.sourceId == null) {
            ContactsSink.setSourceId(context, account, change.rawContactId, href, newEtag)
        } else {
            ContactsSink.clearDirty(context, account, change.rawContactId)
        }
    }

    suspend fun syncCalendars(
        context: Context,
        account: String,
        client: DavClient,
        baseUrl: String,
        direction: SyncDirection,
    ) {
        val collections = client.discoverCollections(baseUrl, isCalendar = true)
        for (col in collections) {
            syncOneCalendar(context, account, client, col, direction)
        }
    }

    private suspend fun syncOneCalendar(
        context: Context,
        account: String,
        client: DavClient,
        col: DavCollection,
        direction: SyncDirection,
    ) {
        val localCalId = CalendarSink.getOrCreateCalendarId(
            context,
            account,
            col.url,
            col.displayName,
            col.color,
        )
        if (localCalId == -1L) return
        if (direction != SyncDirection.PUSH) {
            pullCalendar(context, account, client, col, localCalId)
        }
        if (direction != SyncDirection.PULL) {
            pushCalendar(context, account, client, col, localCalId)
        }
    }

    private suspend fun pullCalendar(
        context: Context,
        account: String,
        client: DavClient,
        col: DavCollection,
        localCalId: Long,
    ) {
        val local = CalendarSink.localUidToEtag(context, localCalId)
        val resources = client.listResources(col.url)
        val remoteHrefs = resources.map { it.href }.toSet()
        val changed = resources.filter { local[it.href] != it.etag }
        val fetched = client.multiget(col.url, changed.map { it.href }, isCalendar = true)
        for (res in fetched) {
            val data = res.data ?: continue
            val events = ICalendar.parse(
                data,
                calendarId = localCalId.toString(),
                fallbackUid = res.href,
            )
            for (ev in events) {
                val withHref = ev.copy(uid = res.href, etag = res.etag, href = res.href)
                CalendarSink.upsertEvent(context, account, localCalId, withHref)
            }
        }
        (local.keys - remoteHrefs).forEach {
            CalendarSink.deleteEvent(context, account, localCalId, it)
        }
    }

    private suspend fun pushCalendar(
        context: Context,
        account: String,
        client: DavClient,
        col: DavCollection,
        localCalId: Long,
    ) {
        val collectionUrl = col.url.trimEnd('/')
        for (change in CalendarSink.getLocalChanges(context, localCalId)) {
            pushCalendarChange(context, account, client, collectionUrl, localCalId, change)
        }
    }

    private suspend fun pushCalendarChange(
        context: Context,
        account: String,
        client: DavClient,
        collectionUrl: String,
        localCalId: Long,
        change: com.vayunmathur.everysync.data.sink.LocalEventChange,
    ) {
        if (change.deleted && change.syncId != null) {
            client.delete(change.syncId, change.etag)
            return
        }
        val event = change.event ?: return
        if (change.deleted) return
        val href = change.syncId ?: "$collectionUrl/${Uuid.random()}.ics"
        val ics = ICalendar.serialize(event.copy(uid = event.uid.ifBlank { href }))
        val newEtag = client.put(href, "text/calendar; charset=utf-8", ics, change.etag)
        if (change.syncId == null) {
            // Re-create under the server href so future syncs match.
            CalendarSink.deleteEvent(context, account, localCalId, event.uid)
            val withHref = event.copy(uid = href, etag = newEtag, href = href)
            CalendarSink.upsertEvent(context, account, localCalId, withHref)
        } else {
            CalendarSink.clearDirty(context, account, change.eventId)
        }
    }
}
