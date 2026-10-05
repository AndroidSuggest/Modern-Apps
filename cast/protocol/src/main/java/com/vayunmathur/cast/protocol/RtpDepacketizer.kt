package com.vayunmathur.cast.protocol

/**
 * One parsed RTP packet: the header fields, and this packet's slice of the frame.
 *
 * [frameId8] and [referenceFrameId8] are the **truncated** ids straight off the wire. Expanding
 * them needs a reference point the packet itself does not carry, so that is [FrameAssembler]'s job
 * rather than this one's - a depacketizer that guessed would be the same class of bug as reading
 * the frame counter as a byte.
 */
data class RtpPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    /** Set on the last packet of a frame. Redundant with [packetId] == [maxPacketId], and checked. */
    val marker: Boolean,
    val rtpTimestamp: Long,
    val ssrc: Long,
    val isKeyFrame: Boolean,
    val frameId8: Int,
    val packetId: Int,
    val maxPacketId: Int,
    val referenceFrameId8: Int,
    val payload: ByteArray,
) {
    val packetCount: Int get() = maxPacketId + 1

    override fun equals(other: Any?): Boolean =
        other is RtpPacket &&
            payloadType == other.payloadType &&
            sequenceNumber == other.sequenceNumber &&
            marker == other.marker &&
            rtpTimestamp == other.rtpTimestamp &&
            ssrc == other.ssrc &&
            isKeyFrame == other.isKeyFrame &&
            frameId8 == other.frameId8 &&
            packetId == other.packetId &&
            maxPacketId == other.maxPacketId &&
            referenceFrameId8 == other.referenceFrameId8 &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = payloadType
        result = 31 * result + sequenceNumber
        result = 31 * result + marker.hashCode()
        result = 31 * result + rtpTimestamp.hashCode()
        result = 31 * result + ssrc.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + frameId8
        result = 31 * result + packetId
        result = 31 * result + maxPacketId
        result = 31 * result + referenceFrameId8
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * The exact inverse of [RtpPacketizer]: bytes off the socket back into header fields and a payload
 * slice.
 *
 * Every rejection below is a shape [RtpPacketizer] cannot produce, which is the whole reason this
 * can afford to be strict where a Cast receiver could not. A malformed datagram is dropped by
 * returning null rather than throwing: UDP is open to anything on the LAN, and a stray packet must
 * cost one branch rather than an exception per datagram.
 */
object RtpDepacketizer {

    private const val BYTE_MASK = 0xff
    private const val PAYLOAD_TYPE_MASK = 0b0111_1111
    private const val EXTENSION_COUNT_MASK = 0b0011_1111

    /** Version 2, no padding, no extension, no CSRCs — matches the packetizer's first byte. */
    private const val REQUIRED_FIRST_BYTE = 0b1000_0000

    private const val OFFSET_BYTE_0 = 0
    private const val OFFSET_BYTE_1 = 1
    private const val OFFSET_SEQUENCE = 2
    private const val OFFSET_TIMESTAMP = 4
    private const val OFFSET_SSRC = 8
    private const val OFFSET_CAST_BYTE = 12
    private const val OFFSET_FRAME_ID = 13
    private const val OFFSET_PACKET_ID = 14
    private const val OFFSET_MAX_PACKET_ID = 16
    private const val OFFSET_REFERENCE_FRAME_ID = 18

    fun parse(packet: ByteArray): RtpPacket? {
        if (packet.size < BASE_HEADER_SIZE) return null
        val header = parseHeader(packet) ?: return null
        if (header.packetId > header.maxPacketId) return null
        // The marker bit and the packet ids have to agree; disagreeing means corruption, and
        // trusting one over the other is how a frame ends up assembled from the wrong pieces.
        if (header.marker != (header.packetId == header.maxPacketId)) return null

        return RtpPacket(
            payloadType = header.payloadType,
            sequenceNumber = header.sequenceNumber,
            marker = header.marker,
            rtpTimestamp = header.rtpTimestamp,
            ssrc = header.ssrc,
            isKeyFrame = header.isKeyFrame,
            frameId8 = header.frameId8,
            packetId = header.packetId,
            maxPacketId = header.maxPacketId,
            referenceFrameId8 = header.referenceFrameId8,
            payload = packet.copyOfRange(BASE_HEADER_SIZE, packet.size),
        )
    }

    private data class ParsedHeader(
        val payloadType: Int,
        val sequenceNumber: Int,
        val marker: Boolean,
        val rtpTimestamp: Long,
        val ssrc: Long,
        val isKeyFrame: Boolean,
        val frameId8: Int,
        val packetId: Int,
        val maxPacketId: Int,
        val referenceFrameId8: Int,
    )

    private fun parseHeader(packet: ByteArray): ParsedHeader? {
        val byte0 = packet[OFFSET_BYTE_0].toInt() and BYTE_MASK
        // Version 2, no padding, no extension, no CSRCs - `kRtpRequiredFirstByte`. Padding and
        // header extensions would change where the payload starts, and we never write either.
        if (byte0 != REQUIRED_FIRST_BYTE) return null

        val byte1 = packet[OFFSET_BYTE_1].toInt() and BYTE_MASK
        val castByte = packet[OFFSET_CAST_BYTE].toInt() and BYTE_MASK
        // The reference frame id is what makes the header 19 bytes rather than 18. Our packetizer
        // always writes it, so a packet without it did not come from us.
        // Extension count. We never emit the adaptive-latency extension, so anything here would
        // shift the payload by an amount this parser has no rule for.
        if (castByte and CAST_HAS_REFERENCE_FRAME_ID_BIT == 0 ||
            castByte and EXTENSION_COUNT_MASK != 0
        ) {
            return null
        }
        return ParsedHeader(
            payloadType = byte1 and PAYLOAD_TYPE_MASK,
            sequenceNumber = packet.getShort(OFFSET_SEQUENCE),
            marker = byte1 and RTP_MARKER_BIT != 0,
            rtpTimestamp = packet.getInt(OFFSET_TIMESTAMP),
            ssrc = packet.getInt(OFFSET_SSRC),
            isKeyFrame = castByte and CAST_KEY_FRAME_BIT != 0,
            frameId8 = packet[OFFSET_FRAME_ID].toInt() and BYTE_MASK,
            packetId = packet.getShort(OFFSET_PACKET_ID),
            maxPacketId = packet.getShort(OFFSET_MAX_PACKET_ID),
            referenceFrameId8 = packet[OFFSET_REFERENCE_FRAME_ID].toInt() and BYTE_MASK,
        )
    }
}
