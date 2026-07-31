package org.schabi.newpipe.extractor.services.youtube.sabr

import java.security.SecureRandom

internal class SabrColdStartPoToken private constructor() {

    companion object {
        private const val MAX_IDENTIFIER_BYTES = 118
        private val RANDOM = SecureRandom()

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun generate(identifier: String, clientState: Int): ByteArray {
            val identifierBytes = identifier.toByteArray(Charsets.UTF_8)
            if (identifierBytes.size > MAX_IDENTIFIER_BYTES) {
                throw SabrProtocolException("PO token identifier is too long")
            }

            val timestamp = (System.currentTimeMillis() / 1000L).toInt()
            val key = byteArrayOf(RANDOM.nextInt(256).toByte(), RANDOM.nextInt(256).toByte())
            val header = byteArrayOf(
                key[0],
                key[1],
                0,
                clientState.toByte(),
                ((timestamp shr 24) and 0xff).toByte(),
                ((timestamp shr 16) and 0xff).toByte(),
                ((timestamp shr 8) and 0xff).toByte(),
                (timestamp and 0xff).toByte()
            )

            val packet = ByteArray(2 + header.size + identifierBytes.size)
            packet[0] = 34
            packet[1] = (header.size + identifierBytes.size).toByte()
            System.arraycopy(header, 0, packet, 2, header.size)
            System.arraycopy(identifierBytes, 0, packet, 2 + header.size, identifierBytes.size)

            for (i in key.size until packet.size - 2) {
                packet[2 + i] = (packet[2 + i].toInt() xor packet[2 + (i % key.size)].toInt()).toByte()
            }
            return packet
        }
    }
}
