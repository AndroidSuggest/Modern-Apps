package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-shape assertions for the messaging-hardening send builders (Phase A 1a-1c). These are the
 * proto builders that were previously emitting plaintext `<enc>` nodes; they now return E2E
 * [WhatsAppE2EProto.Message] protos so the caller routes them through the Signal fan-out. Pure
 * (protobuf-java only), so no Android runtime is required.
 */
class WhatsAppSendProtoTest {

    @Test
    fun editProto_setsMessageEditProtocolMessage() {
        val msg = WhatsAppProtocol.buildEditProto("123@s.whatsapp.net", "TARGET1", "new text")
        assertTrue(msg.hasProtocolMessage())
        val pm = msg.protocolMessage
        assertEquals(WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT, pm.type)
        assertEquals("TARGET1", pm.key.id)
        assertTrue(pm.key.fromMe)
        assertEquals("123@s.whatsapp.net", pm.key.remoteJid)
        assertEquals("new text", pm.editedMessage.conversation)
        assertTrue(pm.timestampMs > 0)
    }

    @Test
    fun revokeProto_ownMessage_fromMeNoParticipant() {
        val msg = WhatsAppProtocol.buildRevokeProto(
            chatJid = "123@g.us",
            senderJid = "me@s.whatsapp.net",
            targetMessageId = "T1",
            ownJid = "me@s.whatsapp.net",
        )
        val pm = msg.protocolMessage
        assertEquals(WhatsAppE2EProto.ProtocolMessage.Type.REVOKE, pm.type)
        assertTrue(pm.key.fromMe)
        assertFalse(pm.key.hasParticipant())
        assertTrue(WhatsAppProtocol.isRevokeFromMe("me@s.whatsapp.net", "me@s.whatsapp.net"))
        assertTrue(WhatsAppProtocol.isRevokeFromMe("", "me@s.whatsapp.net"))
    }

    @Test
    fun revokeProto_groupOtherSender_setsParticipant() {
        val msg = WhatsAppProtocol.buildRevokeProto(
            chatJid = "123@g.us",
            senderJid = "other@s.whatsapp.net",
            targetMessageId = "T2",
            ownJid = "me@s.whatsapp.net",
        )
        val pm = msg.protocolMessage
        assertFalse(pm.key.fromMe)
        assertEquals("other@s.whatsapp.net", pm.key.participant)
        assertFalse(WhatsAppProtocol.isRevokeFromMe("other@s.whatsapp.net", "me@s.whatsapp.net"))
    }

    @Test
    fun contactProto_setsDisplayNameAndVcard() {
        val msg = WhatsAppProtocol.buildContactProto("Alice", "BEGIN:VCARD\nEND:VCARD")
        assertTrue(msg.hasContactMessage())
        assertEquals("Alice", msg.contactMessage.displayName)
        assertEquals("BEGIN:VCARD\nEND:VCARD", msg.contactMessage.vcard)
    }

    @Test
    fun disappearingTimerProto_setsEphemeralSetting() {
        val msg = WhatsAppProtocol.buildDisappearingTimerProto(604800L)
        val pm = msg.protocolMessage
        assertEquals(WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING, pm.type)
        assertEquals(604800, pm.ephemeralExpiration)
    }

    @Test
    fun textProto_plain_isConversation() {
        val msg = WhatsAppProtocol.buildTextProto("hello")
        assertTrue(msg.hasConversation())
        assertEquals("hello", msg.conversation)
        assertFalse(msg.hasExtendedTextMessage())
    }

    @Test
    fun textProto_withMentions_usesExtendedTextWithContextInfo() {
        val msg = WhatsAppProtocol.buildTextProto(
            "hi @alice",
            mentionedJids = listOf("111@s.whatsapp.net"),
        )
        assertTrue(msg.hasExtendedTextMessage())
        val ext = msg.extendedTextMessage
        assertEquals("hi @alice", ext.text)
        assertEquals(listOf("111@s.whatsapp.net"), ext.contextInfo.mentionedJidList)
    }

    @Test
    fun textProto_withQuotedReply_setsStanzaIdAndParticipant() {
        val quoted = WhatsAppProtocol.QuotedContext(
            stanzaId = "QID",
            participant = "222@s.whatsapp.net",
            quotedMessage = WhatsAppProtocol.buildConversationMessage("original"),
        )
        val msg = WhatsAppProtocol.buildTextProto("reply", quoted = quoted)
        val ctx = msg.extendedTextMessage.contextInfo
        assertEquals("QID", ctx.stanzaId)
        assertEquals("222@s.whatsapp.net", ctx.participant)
        assertEquals("original", ctx.quotedMessage.conversation)
    }

    @Test
    fun textProto_withLinkPreview_setsPreviewFields() {
        val preview = WhatsAppProtocol.LinkPreview(
            matchedText = "https://example.com",
            canonicalUrl = "https://example.com/",
            title = "Example",
            description = "An example",
        )
        val msg = WhatsAppProtocol.buildTextProto("see https://example.com", linkPreview = preview)
        val ext = msg.extendedTextMessage
        assertEquals("https://example.com", ext.matchedText)
        assertEquals("https://example.com/", ext.canonicalUrl)
        assertEquals("Example", ext.title)
        assertEquals("An example", ext.description)
    }

    @Test
    fun buildContextInfo_returnsNullWhenEmpty() {
        assertNull(WhatsAppProtocol.buildContextInfo())
    }

    @Test
    fun unwrapViewOnce_v2_returnsInnerAndTypesAsImage() {
        val image = WhatsAppE2EProto.Message.newBuilder()
            .setImageMessage(
                WhatsAppE2EProto.ImageMessage.newBuilder().setMimetype("image/jpeg"),
            )
            .build()
        val wrapped = WhatsAppE2EProto.Message.newBuilder()
            .setViewOnceMessageV2(
                WhatsAppE2EProto.FutureProofMessage.newBuilder().setMessage(image),
            )
            .build()

        val inner = WhatsAppProtocol.unwrapViewOnce(wrapped)
        assertTrue(inner != null && inner.hasImageMessage())
        assertEquals("image image/jpeg", WhatsAppProtocol.getMessageType(wrapped))
    }

    @Test
    fun unwrapViewOnce_nonWrapper_returnsNull() {
        val plain = WhatsAppProtocol.buildConversationMessage("hi")
        assertNull(WhatsAppProtocol.unwrapViewOnce(plain))
    }
}
