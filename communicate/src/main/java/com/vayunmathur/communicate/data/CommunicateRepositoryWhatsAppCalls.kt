package com.vayunmathur.communicate.data

import android.content.Context
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature
import com.vayunmathur.communicate.data.whatsapp.createGroup
import com.vayunmathur.communicate.data.whatsapp.placeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WhatsApp call controls for [CommunicateRepository] (split for file length).
 * Behavior identical, call sites unchanged.
 */

fun CommunicateRepository.whatsAppPlaceCall(conversationId: String, video: Boolean = false) {
    if (!WhatsAppFeature.enabled) return
    WhatsAppClient.placeCall(conversationId, video)
}

fun CommunicateRepository.whatsAppAnswerCall() {
    if (!WhatsAppFeature.enabled) return
    com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.answer()
}

fun CommunicateRepository.whatsAppRejectCall() {
    if (!WhatsAppFeature.enabled) return
    com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.reject()
}

fun CommunicateRepository.whatsAppHangupCall() {
    if (!WhatsAppFeature.enabled) return
    com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.hangup()
}

fun CommunicateRepository.whatsAppSetCallMuted(muted: Boolean) =
    com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.setMuted(muted)

fun CommunicateRepository.whatsAppSetCallSpeaker(on: Boolean) =
    com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.setSpeaker(on)

/**
 * Create a WhatsApp group with [subject] and the given [contacts] (phone numbers / addresses).
 * Each contact is normalized to a full WhatsApp user JID before the create IQ is sent. Returns
 * the new group's `@g.us` JID on success so the caller can open the thread, or null on failure.
 */
suspend fun CommunicateRepository.createWhatsAppGroup(
    context: Context,
    subject: String,
    contacts: List<String>,
): String? = withContext(Dispatchers.IO) {
    val jids = contacts
        .map { toWhatsAppJid(context, it) }
        .filter { it.endsWith("@s.whatsapp.net") }
        .distinct()
    if (jids.isEmpty()) return@withContext null
    WhatsAppClient.createGroup(subject, jids)
}
