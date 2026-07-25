package com.vayunmathur.pdf.util

import java.io.ByteArrayOutputStream

/**
 * Produces a cryptographically signed PDF (detached PKCS#7 / adbe.pkcs7.detached)
 * from a signature-ready PDF built by [PdfNative.prepareSignature]. The CMS
 * signature (fresh self-signed RSA-2048 cert, SHA-256 with RSA) is generated in
 * the native Rust core ([PdfNative.signCms]) — no Bouncy Castle. The signed PDF
 * validates its own byte-range digest; trust must be established out of band
 * (self-signed).
 */
object PdfSigner {
    /** Size (bytes) of the /Contents placeholder; must fit the CMS signature. */
    const val CONTENTS_BYTES = 8192

    /** Patch [prepared] (from prepareSignature) with a real detached signature. */
    fun sign(prepared: ByteArray, name: String): ByteArray? {
        val buf = prepared.copyOf()

        val brKey = indexOf(buf, "/ByteRange".toByteArray(), 0)
        if (brKey < 0) return null
        val brOpen = indexOfByte(buf, '['.code.toByte(), brKey)
        val brClose = indexOfByte(buf, ']'.code.toByte(), brOpen)
        if (brOpen < 0 || brClose < 0) return null

        val cKey = indexOf(buf, "/Contents".toByteArray(), brKey)
        if (cKey < 0) return null
        val cOpen = indexOfByte(buf, '<'.code.toByte(), cKey)
        val cClose = indexOfByte(buf, '>'.code.toByte(), cOpen)
        if (cOpen < 0 || cClose < 0) return null

        // Signature covers everything except the Contents value (incl. < and >).
        val a = cOpen
        val b = cClose + 1
        val c = buf.size - b

        // Patch /ByteRange in place (fixed-width slot, so offsets don't shift).
        val slot = brClose - (brOpen + 1)
        val br = "0 $a $b $c"
        if (br.length > slot) return null
        val padded = br.padEnd(slot, ' ').toByteArray(Charsets.US_ASCII)
        System.arraycopy(padded, 0, buf, brOpen + 1, slot)

        // Concatenate the two signed byte ranges.
        val content = ByteArrayOutputStream(a + c).apply {
            write(buf, 0, a)
            write(buf, b, c)
        }.toByteArray()

        // Detached CMS built natively (RustCrypto), replacing Bouncy Castle.
        val cms = PdfNative.signCms(content, name.ifBlank { "PDF Signer" }) ?: return null

        val hex = toHex(cms)
        val hexSlot = cClose - (cOpen + 1)
        if (hex.length > hexSlot) return null // signature too large for placeholder
        val hexPadded = hex.padEnd(hexSlot, '0').toByteArray(Charsets.US_ASCII)
        System.arraycopy(hexPadded, 0, buf, cOpen + 1, hexSlot)
        return buf
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (x in bytes) {
            val v = x.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        val last = haystack.size - needle.size
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun indexOfByte(haystack: ByteArray, b: Byte, from: Int): Int {
        if (from < 0) return -1
        var i = from
        while (i < haystack.size) {
            if (haystack[i] == b) return i
            i++
        }
        return -1
    }
}
