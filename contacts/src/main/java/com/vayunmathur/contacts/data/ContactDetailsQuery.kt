package com.vayunmathur.contacts.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Profile
import com.vayunmathur.library.log.Log
import androidx.core.database.getBlobOrNull
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

/**
 * Provider Data-table query for Contact details, kept in its own file so
 * Contact.kt stays under the function cap (see TooManyFunctions).
 */

fun getDetails(context: Context, id: Long, isProfile: Boolean = false): ContactDetails {
    return getDetailsInternal(context, id, isProfile)[id] ?: ContactDetails.empty()
}

/** Parses a "--MM-DD" provider date (no year) via the 1604 sentinel year. Null when unparseable. */
private fun parseNoYearDate(date: String): LocalDate? {
    if (!date.startsWith("--")) return null
    return runCatching { LocalDate.parse(NO_YEAR_SENTINEL_PREFIX + date.substring(2)) }.getOrNull()
}

private const val NO_YEAR_SENTINEL_PREFIX = "1604"

/** Falls back to the full-size photo stream when the DATA15 thumbnail blob is null. */
private fun loadPhotoFallback(
    contentResolver: android.content.ContentResolver,
    cursor: android.database.Cursor,
    contactIdIdx: Int,
): ByteArray? {
    val contactId = cursor.getLong(contactIdIdx)
    val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
    return ContactsContract.Contacts.openContactPhotoInputStream(
        contentResolver,
        contactUri,
        true,
    )?.use { it.readBytes() }
}

/** Loads the full-size photo bytes for [contactId], or null if none / on error. */
fun loadFullSizePhoto(context: Context, contactId: Long): ByteArray? = runCatching {
    val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
    ContactsContract.Contacts.openContactPhotoInputStream(
        context.contentResolver,
        contactUri,
        true
    )?.use { it.readBytes() }
}.getOrNull()

/** Column indexes for one Data-table row, resolved once per query. */
private class DetailsColumnIndexes(cursor: android.database.Cursor) {
    val rawIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
    val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
    val d1Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
    val d2Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
    val d3Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
    val d4Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
    val d5Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA5)
    val d6Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA6)
    val d7Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA7)
    val d8Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA8)
    val d9Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA9)
    val d10Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA10)
    val contactIdIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
    val d15Idx = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA15)
}

/** Accumulates one detail list per raw-contact id while the Data cursor is scanned. */
private class DetailsAccumulator {
    val phoneNumbersMap = mutableMapOf<Long, MutableList<PhoneNumber>>()
    val emailsMap = mutableMapOf<Long, MutableList<Email>>()
    val addressesMap = mutableMapOf<Long, MutableList<Address>>()
    val datesMap = mutableMapOf<Long, MutableList<Event>>()
    val photosMap = mutableMapOf<Long, MutableList<Photo>>()
    val namesMap = mutableMapOf<Long, MutableList<Name>>()
    val orgsMap = mutableMapOf<Long, MutableList<Organization>>()
    val notesMap = mutableMapOf<Long, MutableList<Note>>()
    val nicknamesMap = mutableMapOf<Long, MutableList<Nickname>>()
    val groupsMap = mutableMapOf<Long, MutableList<GroupMembership>>()
    val rawContactIds = mutableSetOf<Long>()

    fun toDetails(): Map<Long, ContactDetails> =
        rawContactIds.associateWith { rawId ->
            ContactDetails(
                phoneNumbersMap[rawId]?.distinct().orEmpty(),
                emailsMap[rawId]?.distinct().orEmpty(),
                addressesMap[rawId]?.distinct().orEmpty(),
                datesMap[rawId]?.distinct().orEmpty(),
                photosMap[rawId]?.distinct().orEmpty(),
                namesMap[rawId]?.distinct().orEmpty(),
                orgsMap[rawId]?.distinct().orEmpty(),
                notesMap[rawId]?.distinct().orEmpty(),
                nicknamesMap[rawId]?.distinct().orEmpty(),
                groupsMap[rawId]?.distinct().orEmpty()
            )
        }
}

private val DETAILS_PROJECTION = arrayOf(
    ContactsContract.Data.RAW_CONTACT_ID,
    ContactsContract.Data._ID,
    ContactsContract.Data.MIMETYPE,
    ContactsContract.Data.CONTACT_ID,
    ContactsContract.Data.DATA1,
    ContactsContract.Data.DATA2,
    ContactsContract.Data.DATA3,
    ContactsContract.Data.DATA4,
    ContactsContract.Data.DATA5,
    ContactsContract.Data.DATA6,
    ContactsContract.Data.DATA7,
    ContactsContract.Data.DATA8,
    ContactsContract.Data.DATA9,
    ContactsContract.Data.DATA10,
    ContactsContract.Data.DATA15
)

fun getDetailsInternal(
    context: Context,
    id: Long? = null,
    isProfile: Boolean = false,
): Map<Long, ContactDetails> {
    val contentResolver = context.contentResolver
    val accumulator = DetailsAccumulator()
    try {
        queryDetailsCursor(contentResolver, id, isProfile)?.use { cursor ->
            val cols = DetailsColumnIndexes(cursor)
            while (cursor.moveToNext()) {
                accumulateDetailsRow(contentResolver, cursor, cols, accumulator)
            }
        }
    } catch (e: android.database.SQLException) {
        Log.error("Contact", "Error querying contact details", e)
    } catch (e: IllegalArgumentException) {
        Log.error("Contact", "Error querying contact details", e)
    } catch (e: SecurityException) {
        Log.error("Contact", "Error querying contact details", e)
    }
    return accumulator.toDetails()
}

private fun queryDetailsCursor(
    contentResolver: android.content.ContentResolver,
    id: Long?,
    isProfile: Boolean,
): android.database.Cursor? {
    val uri = if (isProfile) {
        Uri.withAppendedPath(
            Profile.CONTENT_URI,
            ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
        )
    } else {
        ContactsContract.Data.CONTENT_URI
    }
    return contentResolver.query(
        uri,
        DETAILS_PROJECTION,
        id?.let { "${ContactsContract.Data.RAW_CONTACT_ID} = ?" },
        id?.let { arrayOf(it.toString()) },
        null,
    )
}

private fun accumulateDetailsRow(
    contentResolver: android.content.ContentResolver,
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    accumulator: DetailsAccumulator,
) {
    val rawId = cursor.getLong(cols.rawIdIdx)
    accumulator.rawContactIds.add(rawId)
    val dataId = cursor.getLong(cols.idIdx)
    when (cursor.getString(cols.mimeIdx)) {
        CDKPhone.CONTENT_ITEM_TYPE -> accumulator.addPhone(cursor, cols, rawId, dataId)
        CDKEmail.CONTENT_ITEM_TYPE -> accumulator.addEmail(cursor, cols, rawId, dataId)
        CDKStructuredPostal.CONTENT_ITEM_TYPE -> accumulator.addAddress(cursor, cols, rawId, dataId)
        CDKEvent.CONTENT_ITEM_TYPE -> accumulator.addEvent(cursor, cols, rawId, dataId)
        CDKPhoto.CONTENT_ITEM_TYPE ->
            accumulator.addPhoto(contentResolver, cursor, cols, rawId, dataId)
        CDKSName.CONTENT_ITEM_TYPE -> accumulator.addName(cursor, cols, rawId, dataId)
        CDKOrg.CONTENT_ITEM_TYPE -> accumulator.addOrg(cursor, cols, rawId, dataId)
        CDKNote.CONTENT_ITEM_TYPE -> accumulator.addNote(cursor, cols, rawId, dataId)
        CDKNickname.CONTENT_ITEM_TYPE -> accumulator.addNickname(cursor, cols, rawId, dataId)
        CDKGroupMembership.CONTENT_ITEM_TYPE -> accumulator.addGroup(cursor, cols, rawId, dataId)
    }
}

private fun DetailsAccumulator.addPhone(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val number = cursor.getStringOrNull(cols.d1Idx) ?: ""
    val type = cursor.getInt(cols.d2Idx)
    val label = cursor.getStringOrNull(cols.d3Idx) ?: ""
    phoneNumbersMap.getOrPut(rawId) { mutableListOf() }.add(PhoneNumber(dataId, number, type, label))
}

private fun DetailsAccumulator.addEmail(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val address = cursor.getStringOrNull(cols.d1Idx) ?: ""
    val type = cursor.getInt(cols.d2Idx)
    val label = cursor.getStringOrNull(cols.d3Idx) ?: ""
    emailsMap.getOrPut(rawId) { mutableListOf() }.add(Email(dataId, address, type, label))
}

private fun DetailsAccumulator.addAddress(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    var formatted = cursor.getStringOrNull(cols.d1Idx)
    val type = cursor.getInt(cols.d2Idx)
    val label = cursor.getStringOrNull(cols.d3Idx) ?: ""
    if (formatted.isNullOrBlank()) {
        formatted = joinAddressParts(cursor, cols)
    }
    addressesMap.getOrPut(rawId) { mutableListOf() }.add(Address(dataId, formatted, type, label))
}

private fun joinAddressParts(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
): String {
    val street = cursor.getStringOrNull(cols.d4Idx)
    val city = cursor.getStringOrNull(cols.d7Idx)
    val region = cursor.getStringOrNull(cols.d8Idx)
    val code = cursor.getStringOrNull(cols.d9Idx)
    val country = cursor.getStringOrNull(cols.d10Idx)
    return listOfNotNull(street, city, region, code, country)
        .filter { it.isNotBlank() }
        .joinToString(", ")
}

private fun DetailsAccumulator.addEvent(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val date = cursor.getStringOrNull(cols.d1Idx) ?: ""
    val type = cursor.getInt(cols.d2Idx)
    val label = cursor.getStringOrNull(cols.d3Idx) ?: ""
    val localDate = parseProviderDate(date) ?: return
    datesMap.getOrPut(rawId) { mutableListOf() }.add(Event(dataId, localDate, type, label))
}

/** Tries ISO, then the numeric provider format, then the "--MM-DD" no-year form. */
private fun parseProviderDate(date: String): LocalDate? =
    runCatching { LocalDate.parse(date, LocalDate.Formats.ISO) }.getOrNull()
        ?: runCatching {
            LocalDate.parse(date, LocalDate.Format { year(); monthNumber(); day() })
        }.getOrNull()
        ?: parseNoYearDate(date)

private fun DetailsAccumulator.addPhoto(
    contentResolver: android.content.ContentResolver,
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    // Prefer the DATA15 thumbnail blob so whole-book sync doesn't open
    // a full-size stream per row; only fall back when the blob is null.
    val photoBytes = cursor.getBlobOrNull(cols.d15Idx)
        ?: loadPhotoFallback(contentResolver, cursor, cols.contactIdIdx)
    if (photoBytes == null) return
    try {
        photosMap.getOrPut(rawId) { mutableListOf() }.add(Photo(dataId, Base64.encode(photoBytes)))
    } catch (e: IllegalArgumentException) {
        Log.error("Contact", "Error reading contact photo", e)
    }
}

private fun DetailsAccumulator.addName(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val prefix = cursor.getStringOrNull(cols.d4Idx) ?: ""
    val given = cursor.getStringOrNull(cols.d2Idx) ?: ""
    val middle = cursor.getStringOrNull(cols.d5Idx) ?: ""
    val family = cursor.getStringOrNull(cols.d3Idx) ?: ""
    val suffix = cursor.getStringOrNull(cols.d6Idx) ?: ""
    namesMap.getOrPut(rawId) { mutableListOf() }.add(Name(dataId, prefix, given, middle, family, suffix))
}

private fun DetailsAccumulator.addOrg(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val company = cursor.getStringOrNull(cols.d1Idx) ?: ""
    orgsMap.getOrPut(rawId) { mutableListOf() }.add(Organization(dataId, company))
}

private fun DetailsAccumulator.addNote(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val noteContent = cursor.getStringOrNull(cols.d1Idx) ?: ""
    notesMap.getOrPut(rawId) { mutableListOf() }.add(Note(dataId, noteContent))
}

private fun DetailsAccumulator.addNickname(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val nickname = cursor.getStringOrNull(cols.d1Idx) ?: ""
    val type = cursor.getInt(cols.d2Idx)
    nicknamesMap.getOrPut(rawId) { mutableListOf() }.add(Nickname(dataId, nickname, type))
}

private fun DetailsAccumulator.addGroup(
    cursor: android.database.Cursor,
    cols: DetailsColumnIndexes,
    rawId: Long,
    dataId: Long,
) {
    val groupId = cursor.getLong(cols.d1Idx)
    groupsMap.getOrPut(rawId) { mutableListOf() }.add(GroupMembership(dataId, groupId))
}
