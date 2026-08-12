package com.vayunmathur.communicate.data.googlevoice

/**
 * Internal DTOs for the Google Voice data layer, produced by [GoogleVoiceParser] from the
 * reverse-engineered protojson responses and mapped by the repository onto the shared
 * `Communicate*` models. Keeping them separate isolates the (fragile, positional) wire
 * shapes from the rest of the app.
 */

data class GvAccount(
    /** The account's primary Google Voice number, in whatever form the wire returned. */
    val phoneNumber: String?,
)

/** Folder/tab enum for `api2thread/list` (slot 0). Values observed 1–5 in capture 2. */
enum class GvFolder(val id: Int) {
    All(1),
    Inbox(2),
    // 3/4/5 map to calls/voicemail/spam-style folders; exact mapping is unproven from the
    // wire (see voice-documentation.md "Folder Navigation Shape").
    Calls(3),
    Voicemail(4),
    Spam(5),
}

data class GvMessage(
    val id: String,
    val threadId: String,
    val phoneNumber: String,
    val text: String,
    val timestampMillis: Long,
    val outgoing: Boolean,
    val read: Boolean,
    val mediaUrls: List<String> = emptyList(),
    val hasMedia: Boolean = mediaUrls.isNotEmpty(),
)

data class GvThread(
    val id: String,
    val phoneNumber: String,
    val displayName: String?,
    val snippet: String,
    val timestampMillis: Long,
    val unreadCount: Int,
    val messages: List<GvMessage> = emptyList(),
)

enum class GvCallType { Incoming, Outgoing, Missed, Voicemail, Unknown }

data class GvCall(
    val id: String,
    val phoneNumber: String,
    val displayName: String?,
    val type: GvCallType,
    val timestampMillis: Long,
    val durationSeconds: Long,
)

/** SIP registration material from `sipregisterinfo/get`, consumed by the calling layer. */
data class GvSipRegisterInfo(
    val phoneNumber: String?,
    /** Opaque registration credential/token strings observed in the response tail. */
    val credentials: List<String>,
)
