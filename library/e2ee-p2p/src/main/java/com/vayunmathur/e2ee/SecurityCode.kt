package com.vayunmathur.e2ee

import java.security.MessageDigest

/**
 * Derives a human-comparable "security code" (safety number) from the two participants' public
 * keys. Both devices compute the **same** code because the two key fingerprints are combined in a
 * canonical (sorted) order before hashing. If the codes match on both devices, the two peers hold
 * each other's genuine keys — i.e. no one (not the server) substituted a key to intercept the
 * end-to-end-encrypted channel.
 *
 * Inputs are each side's public key material exactly as it is held and exchanged — [Pqc.securityCode]
 * passes the raw public bundles, with no re-encoding. It is the canonical *ordering* of the two
 * fingerprints, not any canonical encoding, that makes both devices arrive at the same code.
 */
object SecurityCode {

    /** Iterated hashing slows brute-force search for a colliding short code. */
    private const val ITERATIONS = 4000

    /** @return a 6-group, 30-digit code like "12345 67890 ...", identical on both devices. */
    fun compute(myKeyDer: ByteArray, theirKeyDer: ByteArray): String {
        val a = sha256(myKeyDer)
        val b = sha256(theirKeyDer)
        // Canonical order so both sides (which hold the two keys in opposite roles) agree.
        var h = if (compareLex(a, b) <= 0) a + b else b + a
        repeat(ITERATIONS) { h = sha256(h) }
        return format(h)
    }

    private fun sha256(x: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(x)

    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and BYTE_MASK) - (b[i].toInt() and BYTE_MASK)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private const val BYTE_MASK = 0xFF

    private fun format(h: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        var group = 0
        while (group < GROUP_COUNT && i + BYTES_PER_GROUP <= h.size) {
            var value = 0L
            for (j in 0 until BYTES_PER_GROUP) {
                value = (value shl BYTE_BITS) or (h[i + j].toInt() and BYTE_MASK).toLong()
            }
            if (group > 0) sb.append(' ')
            sb.append((value % GROUP_MODULUS).toString().padStart(GROUP_DIGITS, '0'))
            i += BYTES_PER_GROUP
            group++
        }
        return sb.toString()
    }

    private const val GROUP_COUNT = 6
    private const val BYTES_PER_GROUP = 5
    private const val BYTE_BITS = 8
    private const val GROUP_MODULUS = 100000L
    private const val GROUP_DIGITS = 5
}
