package com.vayunmathur.communicate.data

/**
 * Which line a conversation, message, or call belongs to. The physical SIM is the
 * default so every existing construction site (system-provider reads) stays valid;
 * Google Voice rows are tagged explicitly when merged in by the repository.
 */
enum class CommunicateLine {
    Sim,
    GoogleVoice,
}

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
    val line: CommunicateLine = CommunicateLine.Sim,
)

/**
 * A media item attached to a message. SIM MMS parts arrive as local `content://`
 * URIs; Google Voice media arrive as remote https URLs. [contentUri] holds whichever
 * form applies so the UI can load it uniformly.
 */
data class CommunicateAttachment(
    val contentUri: String,
    val mimeType: String,
)

data class SmsThread(
    val threadId: Long,
    val address: String,
    val displayName: String?,
    val snippet: String,
    val timestampMillis: Long,
    val unreadCount: Int,
    val line: CommunicateLine = CommunicateLine.Sim,
    /**
     * Google Voice thread id (e.g. `t.<...>`). Null for SIM threads, which are keyed by
     * the Long [threadId] from the system provider. GV rows synthesize a stable Long
     * [threadId] (hash of [remoteId]) so list keys and navigation still work.
     */
    val remoteId: String? = null,
)

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val line: CommunicateLine = CommunicateLine.Sim,
    val remoteId: String? = null,
    val attachments: List<CommunicateAttachment> = emptyList(),
)
