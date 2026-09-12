package com.vayunmathur.auto.protocol

import java.nio.ByteBuffer

/**
 * Turns a whole message into frames on the wire.
 *
 * The counterpart to [FrameReader]. Encryption is applied per fragment, after splitting, so
 * the length written into each header is the ciphertext length while the fragment sizes are
 * chosen against plaintext — see [Fragmenter] for why the default fragment size leaves
 * headroom for the TLS record overhead.
 *
 * @param encrypt wraps one fragment payload. The default is identity, for the plaintext
 *   frames exchanged before the handshake completes and for tests.
 */
class FrameWriter(
    private val maxFrameSize: Int = FrameHeader.DEFAULT_MAX_FRAME_SIZE,
    private val encrypt: (ByteArray) -> ByteArray = { it },
) {

    /**
     * Splits [payload] into complete frames, headers included.
     *
     * @param encrypted whether to mark and encrypt the payload. Kept separate from the
     *   presence of an [encrypt] function so the handshake frames, which travel in the clear
     *   on an otherwise-encrypted session, are explicit at the call site.
     */
    fun frame(
        channelId: Int,
        payload: ByteArray,
        isControl: Boolean = false,
        encrypted: Boolean = false,
    ): List<ByteArray> = Fragmenter
        .split(payload.size, maxFrameSize, isControl)
        .map { fragment ->
            val slice = payload.copyOfRange(fragment.offset, fragment.offset + fragment.length)
            val body = if (encrypted) encrypt(slice) else slice

            var flags = fragment.positionFlags
            if (isControl) flags = flags or FrameFlags.CONTROL
            if (encrypted) flags = flags or FrameFlags.ENCRYPTED

            val header = FrameHeader(
                channelId = channelId,
                flags = flags,
                payloadLength = body.size,
                // Only the first fragment of a fragmented message carries it.
                totalLength = if (fragment.isFirst && !fragment.isLast) payload.size else null,
            )

            ByteBuffer.allocate(header.size + body.size)
                .also { header.writeTo(it) }
                .put(body)
                .array()
        }
}
