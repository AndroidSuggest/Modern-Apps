package com.vayunmathur.communicate.data

data class CommunicateContact(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val label: String,
)

enum class CommunicateCallType {
    Incoming,
    Outgoing,
    Missed,
    Rejected,
    Blocked,
    Voicemail,
    Unknown,
}

data class CommunicateCallLogEntry(
    val id: Long,
    val displayName: String?,
    val phoneNumber: String,
    val type: CommunicateCallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
)

data class SmsThread(
    val threadId: Long,
    val address: String,
    val displayName: String?,
    val snippet: String,
    val timestampMillis: Long,
    val unreadCount: Int,
)

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val read: Boolean,
)
