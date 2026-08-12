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
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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

                    while (cursor.moveToNext()) {
                        val phoneNumber = cursor.getString(number).orEmpty().ifBlank { "Unknown" }
                        add(
                            CommunicateCallLogEntry(
                                id = cursor.getLong(id),
                                displayName = cursor.getString(name)?.takeIf { it.isNotBlank() },
                                phoneNumber = phoneNumber,
                                type = cursor.getInt(type).toCommunicateCallType(),
                                timestampMillis = cursor.getLong(date),
                                durationSeconds = cursor.getLong(duration),
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
        if (number.isBlank()) return
        val uri = Uri.fromParts("tel", number, null)
        if (context.hasPermission(Manifest.permission.CALL_PHONE)) {
            try {
                val telecomManager = context.getSystemService(TelecomManager::class.java)
                val extras = Bundle()
                telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)?.let {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
                }
                telecomManager.placeCall(uri, extras)
                return
            } catch (_: Exception) {
                // Fall through to ACTION_DIAL below.
            }
        }
        ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, uri))
    }

    fun openSmsComposer(context: Context, number: String? = null, body: String? = null) {
        val uri = if (number.isNullOrBlank()) "smsto:".toUri() else "smsto:$number".toUri()
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            if (!body.isNullOrBlank()) putExtra("sms_body", body)
        }
        ExternalIntents.launch(context, intent)
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
