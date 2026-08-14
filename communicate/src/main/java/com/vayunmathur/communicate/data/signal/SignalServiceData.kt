package com.vayunmathur.communicate.data.signal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rich-feature payload serialized into `SmsMessage.serviceData` (and the cached-message row) so the
 * Signal line can carry reactions, edits, revokes, quoted replies and group metadata through the
 * SIM/GV-shaped models without changing those paths.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData] for Signal.
 */
@Serializable
data class SignalServiceData(
    val senderName: String? = null,
    val senderId: String? = null,
    val reactions: List<SignalReaction> = emptyList(),
    val isEdited: Boolean = false,
    val isRevoked: Boolean = false,
    // Poll
    val pollQuestion: String? = null,
    val pollOptions: List<SignalPollOptionData> = emptyList(),
    // Quoted reply
    val quotedMessageId: String? = null,
    val quotedBody: String? = null,
    val quotedSender: String? = null,
    // Group
    val isGroup: Boolean = false,
    val groupParticipantCount: Int = 0,
    // Media
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
) {
    fun serialize(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parse(json: String?): SignalServiceData? =
            if (json.isNullOrBlank()) null else runCatching {
                JSON.decodeFromString<SignalServiceData>(json)
            }.getOrNull()
    }
}

@Serializable
data class SignalPollOptionData(
    val name: String,
    val voteCount: Int = 0,
    val voters: List<String> = emptyList(),
)

@Serializable
data class SignalReaction(val emoji: String, val count: Int)
