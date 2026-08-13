package com.vayunmathur.communicate.data.whatsapp.call

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node

/**
 * Phase 10a — WhatsApp call **signaling** (spike). Builds/parses the `<call>` XMPP stanzas that ride
 * the same Noise socket as messaging (offer/accept/reject/terminate), with per-device E2E `<enc>`
 * children carrying the call key. The media/VoIP engine itself is native-only and out of scope for
 * this pass (see plan Phase 10b / Option C).
 *
 * ⚠️ The exact child layout (`audio`/`net`/`capability`/`encopt` markers, attribute names) is
 * reconstructed from the reverse-engineering notes and `whatsmeow`; it MUST be validated live — the
 * go/no-go is whether the server accepts the offer and the target rings.
 *
 * Reference: whatsapp-documentation.md "Voice Calls And Video Calls" (SignalingXmppCallback
 * `sendCallStanza`, OutgoingSignalingHandler per-destination `enc` children).
 */
object WhatsAppCallSignaling {

    /** A parsed inbound call stanza. */
    data class InboundCall(
        val callId: String,
        val from: String,
        val creator: String,
        val isVideo: Boolean,
        val kind: Kind,
    ) {
        enum class Kind { OFFER, ACCEPT, REJECT, TERMINATE, UNKNOWN }
    }

    /**
     * Build an outbound call offer.
     * @param encs per-device encrypted call-key children (dev jid, enc type, ciphertext).
     */
    fun buildOffer(
        to: String,
        callId: String,
        callCreator: String,
        video: Boolean,
        encs: List<WhatsAppProtocol.ParticipantEnc>,
        stanzaId: String,
    ): Node {
        val mediaMarker = if (video) Node(tag = "video") else Node(tag = "audio", attrs = mapOf("enc" to "opus", "rate" to "16000"))
        val destinations = encs.map { enc ->
            Node(
                tag = "to",
                attrs = mapOf("jid" to enc.deviceJid),
                content = listOf(
                    Node(tag = "enc", attrs = mapOf("v" to "2", "type" to enc.encType), data = enc.ciphertext),
                ),
            )
        }
        val offer = Node(
            tag = "offer",
            attrs = mapOf("call-id" to callId, "call-creator" to callCreator),
            content = buildList {
                add(mediaMarker)
                add(Node(tag = "net", attrs = mapOf("medium" to "3")))
                add(Node(tag = "encopt", attrs = mapOf("keygen" to "2")))
                addAll(destinations)
            },
        )
        return Node(
            tag = "call",
            attrs = mapOf("to" to to, "id" to stanzaId),
            content = listOf(offer),
        )
    }

    /** Ack/accept an inbound call. */
    fun buildAccept(to: String, callId: String, callCreator: String, stanzaId: String): Node =
        Node(
            tag = "call",
            attrs = mapOf("to" to to, "id" to stanzaId),
            content = listOf(
                Node(tag = "accept", attrs = mapOf("call-id" to callId, "call-creator" to callCreator)),
            ),
        )

    fun buildReject(to: String, callId: String, callCreator: String, stanzaId: String): Node =
        Node(
            tag = "call",
            attrs = mapOf("to" to to, "id" to stanzaId),
            content = listOf(
                Node(tag = "reject", attrs = mapOf("call-id" to callId, "call-creator" to callCreator)),
            ),
        )

    fun buildTerminate(to: String, callId: String, callCreator: String, reason: String, stanzaId: String): Node =
        Node(
            tag = "call",
            attrs = mapOf("to" to to, "id" to stanzaId),
            content = listOf(
                Node(
                    tag = "terminate",
                    attrs = mapOf("call-id" to callId, "call-creator" to callCreator, "reason" to reason),
                ),
            ),
        )

    /** Parse an inbound `<call>` stanza into a typed [InboundCall]. */
    fun parse(node: Node): InboundCall? {
        if (node.tag != "call") return null
        val from = node.attrs["from"] ?: node.attrs["participant"] ?: return null
        val child = node.getChildren().firstOrNull() ?: return null
        val kind = when (child.tag) {
            "offer" -> InboundCall.Kind.OFFER
            "accept" -> InboundCall.Kind.ACCEPT
            "reject" -> InboundCall.Kind.REJECT
            "terminate" -> InboundCall.Kind.TERMINATE
            else -> InboundCall.Kind.UNKNOWN
        }
        val callId = child.attrs["call-id"] ?: node.attrs["id"] ?: ""
        val creator = child.attrs["call-creator"] ?: from
        val isVideo = child.getChildren().any { it.tag == "video" }
        return InboundCall(callId, from, creator, isVideo, kind)
    }
}
