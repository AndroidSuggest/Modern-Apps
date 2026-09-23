package com.vayunmathur.contacts.util

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.hasYear
import kotlinx.datetime.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vayunmathur.contacts.data.Event as ContactEvent

object CalendarSyncHelper {
    private const val ACCOUNT_NAME = "Contacts"
    private const val ACCOUNT_TYPE = "com.vayunmathur.contacts"
    private const val CALENDAR_NAME = "Contacts"
    
    private val syncMutex = Mutex()

    /** Appends the sync-adapter query params used by every calendar/event write here. */
    private fun Uri.asSyncAdapter(accountName: String, accountType: String): Uri =
        buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()

    private fun ensureAccountExists(context: Context) {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
            accountManager.addAccountExplicitly(account, null, null)
        }
    }

    fun getOrCreateCalendarId(context: Context): Long {
        ensureAccountExists(context)
        
        // Cleanup old local calendar if it exists to prevent duplicates from migration
        try {
            val oldUri = CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL)
            context.contentResolver.delete(oldUri, null, null)
        } catch (_: Exception) {}

        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val selectionArgs = arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE)
        
        val calendarIds = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    calendarIds.add(cursor.getLong(0))
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarSyncHelper", "Error querying calendar", e)
        }

        val syncAdapterUri = CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)

        if (calendarIds.isNotEmpty()) {
            calendarIds.drop(1).forEach { extraId ->
                try {
                    context.contentResolver.delete(syncAdapterUri, "${CalendarContract.Calendars._ID} = ?", arrayOf(extraId.toString()))
                } catch (_: Exception) {}
            }
            return calendarIds[0]
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, context.getString(R.string.contacts_calendar_name))
            put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF4285F4.toInt())
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.currentSystemDefault().id)
        }

        return try {
            val newUri = context.contentResolver.insert(syncAdapterUri, values)
            newUri?.lastPathSegment?.toLong() ?: -1L
        } catch (e: Exception) {
            Log.e("CalendarSyncHelper", "Error creating calendar", e)
            -1L
        }
    }

    suspend fun syncContact(context: Context, contact: Contact) {
        syncMutex.withLock {
            val calendarId = getOrCreateCalendarId(context)
            if (calendarId == -1L) return

            val ops = ArrayList<ContentProviderOperation>()
            
            val eventUri = CalendarContract.Events.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)
                
            ops.add(ContentProviderOperation.newDelete(eventUri)
                .withSelection("${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.SYNC_DATA1} = ?", 
                    arrayOf(calendarId.toString(), contact.id.toString()))
                .build())

            contact.details.dates.forEach { dateEvent ->
                if (dateEvent.type == CDKEvent.TYPE_BIRTHDAY || dateEvent.type == CDKEvent.TYPE_ANNIVERSARY) {
                    ops.addAll(buildAddEventOperations(context, calendarId, contact, dateEvent, eventUri))
                }
            }
            
            try {
                if (ops.isNotEmpty()) {
                    context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
                }
            } catch (e: Exception) {
                Log.e("CalendarSyncHelper", "Error applying batch sync for contact", e)
            }
        }
    }

    private fun buildAddEventOperations(
        context: Context,
        calendarId: Long,
        contact: Contact,
        dateEvent: ContactEvent,
        eventUri: android.net.Uri
    ): List<ContentProviderOperation> {
        val originalDate = dateEvent.startDate
        val deviceTimeZone = TimeZone.currentSystemDefault()

        val eventTypeStr = if (dateEvent.type == CDKEvent.TYPE_BIRTHDAY) {
            context.getString(R.string.birthday)
        } else {
            context.getString(R.string.anniversary)
        }

        // Title deliberately omits age: a single FREQ=YEARLY instance is reused every
        // year, so any embedded age would go stale after the first occurrence.
        val title = "${contact.name.value}: $eventTypeStr"

        val hasYear = originalDate.hasYear
        // RRULE makes the DTSTART year irrelevant (only month/day recur), so for
        // no-year dates (year 1604 sentinel) normalize to a leap year (2000). This
        // ensures Feb-29 no-year birthdays still recur instead of collapsing to an
        // invalid date. For dates with a real year, keep the original year so the
        // first instance lands correctly; Feb 29 then fires on leap years only.
        // NOTE: a yearly RRULE starting Feb 29 fires only on leap years on most
        // providers — accepted as correct rather than shifting to Feb 28, which
        // would show the birthday on the wrong day in leap years.
        val anchorYear = if (hasYear) originalDate.year else 2000
        val anchorDate = try {
            LocalDate(anchorYear, originalDate.month, originalDate.day)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        // All-day events must be anchored at local midnight in the device timezone.
        // Using 00:00 UTC shifts the displayed day for negative-offset zones (US).
        val startMillis =
            anchorDate.atTime(0, 0).toInstant(deviceTimeZone).toEpochMilliseconds()

        return listOf(
            ContentProviderOperation.newInsert(eventUri)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, title)
                .withValue(CalendarContract.Events.DTSTART, startMillis)
                .withValue(CalendarContract.Events.DTEND, startMillis + 24 * 60 * 60 * 1000)
                .withValue(CalendarContract.Events.ALL_DAY, 1)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, deviceTimeZone.id)
                .withValue(CalendarContract.Events.RRULE, "FREQ=YEARLY")
                .withValue(CalendarContract.Events.SYNC_DATA1, contact.id.toString())
                .build()
        )
    }
    
    suspend fun syncAll(context: Context) {
        syncMutex.withLock {
            val calendarId = getOrCreateCalendarId(context)
            if (calendarId == -1L) return
            
            val eventUri = CalendarContract.Events.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)

            try {
                context.contentResolver.delete(eventUri, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()))
            } catch (e: Exception) {
                Log.e("CalendarSyncHelper", "Error clearing calendar", e)
            }
            
            val contacts = Contact.getAllContacts(context)
            for (contact in contacts) {
                val ops = ArrayList<ContentProviderOperation>()
                contact.details.dates.forEach { dateEvent ->
                    if (dateEvent.type == CDKEvent.TYPE_BIRTHDAY || dateEvent.type == CDKEvent.TYPE_ANNIVERSARY) {
                        ops.addAll(buildAddEventOperations(context, calendarId, contact, dateEvent, eventUri))
                    }
                }
                if (ops.isNotEmpty()) {
                    try {
                        context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
                    } catch (e: Exception) {
                        Log.e("CalendarSyncHelper", "Error in syncAll batch", e)
                    }
                }
            }
        }
    }
    
    suspend fun removeCalendar(context: Context) {
        syncMutex.withLock {
            val uri = CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.e("CalendarSyncHelper", "Error removing calendar", e)
            }
            
            try {
                val oldUri = CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL)
                context.contentResolver.delete(oldUri, null, null)
            } catch (_: Exception) {}
        }
    }
}
