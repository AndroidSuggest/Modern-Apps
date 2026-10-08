package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.BinaryToken
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppCertProto
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import org.signal.libsignal.protocol.ecc.ECPublicKey

// -- Binary XML codec (whatsmeow binary/encoder.go + binary/decoder.go) --
// Token tables live in core (WhatsAppProtocol.BinaryToken); the stateful
// encoder/decoder are file-private here and exposed via encodeNode/decodeNode.

private const val TAG = "WhatsAppProtocolBinary"
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val ASCII_MASK = 0x7F
private const val HIGH_BIT = 0x80
private const val LIST_8_LIMIT = 256
private const val BINARY_20_BITS = 20
private const val INT24_SIZE = 3
private const val INT32_SIZE = 4
private const val NIBBLE_BITS = 4
private const val BYTE_BITS = 8
private const val SHIFT_BYTE1 = 8
private const val SHIFT_BYTE2 = 16
private const val SHIFT_BYTE3 = 24
private const val DECIMAL_LIMIT = 10
private const val HEX_LIMIT = 16
private const val PACKED_MINUS = 10
private const val PACKED_DOT = 11
private const val PACKED_END = 15

internal class BinaryEncoder {
    private val data = mutableListOf<Byte>(0)

    fun getData(): ByteArray = data.toByteArray()

    private fun pushByte(b: Byte) { data.add(b) }
    private fun pushByte(b: Int) { data.add(b.toByte()) }
    private fun pushBytes(bytes: ByteArray) { bytes.forEach { data.add(it) } }

    private fun pushInt8(value: Int) { pushByte((value and BYTE_MASK).toByte()) }
    private fun pushInt16(value: Int) {
        pushByte((value shr SHIFT_BYTE1 and BYTE_MASK).toByte())
        pushByte((value and BYTE_MASK).toByte())
    }
    private fun pushInt20(value: Int) {
        pushByte(((value shr SHIFT_BYTE2) and NIBBLE_MASK).toByte())
        pushByte(((value shr SHIFT_BYTE1) and BYTE_MASK).toByte())
        pushByte((value and BYTE_MASK).toByte())
    }
    private fun pushInt32(value: Int) {
        pushByte((value shr SHIFT_BYTE3 and BYTE_MASK).toByte())
        pushByte((value shr SHIFT_BYTE2 and BYTE_MASK).toByte())
        pushByte((value shr SHIFT_BYTE1 and BYTE_MASK).toByte())
        pushByte((value and BYTE_MASK).toByte())
    }

    private fun writeByteLength(length: Int) {
        when {
            length < LIST_8_LIMIT -> { pushByte(BinaryToken.BINARY_8); pushInt8(length) }
            length < (1 shl BINARY_20_BITS) -> { pushByte(BinaryToken.BINARY_20); pushInt20(length) }
            else -> { pushByte(BinaryToken.BINARY_32); pushInt32(length) }
        }
    }

    fun writeNode(n: Node) {
        if (n.tag == "0") {
            pushByte(BinaryToken.LIST_8)
            pushByte(BinaryToken.LIST_EMPTY)
            return
        }

        val hasContent = if (n.data != null || n.content.isNotEmpty()) 1 else 0
        val attrCount = n.attrs.count { it.value.isNotEmpty() }
        writeListStart(2 * attrCount + 1 + hasContent)
        writeString(n.tag)
        writeAttributes(n.attrs)
        if (n.data != null) {
            writeBytes(n.data)
        } else if (n.content.isNotEmpty()) {
            writeListStart(n.content.size)
            for (child in n.content) {
                writeNode(child)
            }
        }
    }

    private fun writeString(value: String) {
        val tokenIndex = BinaryToken.indexOfSingleToken(value)
        if (tokenIndex >= 0) {
            pushByte(tokenIndex)
        } else {
            val (dictIndex, tokIndex, found) = BinaryToken.indexOfDoubleByteToken(value)
            if (found) {
                pushByte(BinaryToken.DICTIONARY_0 + dictIndex)
                pushByte(tokIndex)
            } else if (validateNibble(value)) {
                writePackedBytes(value, BinaryToken.NIBBLE_8)
            } else if (validateHex(value)) {
                writePackedBytes(value, BinaryToken.HEX_8)
            } else {
                writeStringRaw(value)
            }
        }
    }

    private fun writeBytes(value: ByteArray) {
        writeByteLength(value.size)
        pushBytes(value)
    }

    private fun writeStringRaw(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeByteLength(bytes.size)
        pushBytes(bytes)
    }

    /**
     * Encode a JID attribute value as a JID_PAIR (user@server) or AD_JID (user.agent:device)
     * token, matching WhatsApp's binary wire format. The server rejects stanzas (e.g. usync,
     * prekey fetch) whose jid attributes are written as raw strings.
     */
    private fun writeJid(jid: String) {
        val at = jid.indexOf('@')
        if (at < 0) { writeString(jid); return }
        val userPart = jid.substring(0, at)
        val server = jid.substring(at + 1)
        val colon = userPart.indexOf(':')
        val device = if (colon >= 0) userPart.substring(colon + 1).toIntOrNull() ?: 0 else 0
        val beforeColon = if (colon >= 0) userPart.substring(0, colon) else userPart
        val dot = beforeColon.indexOf('.')
        val agent = if (dot >= 0) beforeColon.substring(dot + 1).toIntOrNull() ?: 0 else 0
        val user = if (dot >= 0) beforeColon.substring(0, dot) else beforeColon
        if ((device != 0 || agent != 0) && server == "s.whatsapp.net") {
            pushByte(BinaryToken.AD_JID.toByte())
            pushByte(agent.toByte())
            pushByte(device.toByte())
            writeString(user)
        } else {
            pushByte(BinaryToken.JID_PAIR)
            if (user.isEmpty()) pushByte(BinaryToken.LIST_EMPTY) else writeString(user)
            writeString(server)
        }
    }

    private fun writeAttributes(attrs: Map<String, String>) {
        for ((key, value) in attrs) {
            if (value.isEmpty()) continue
            writeString(key)
            if (value.contains("@")) writeJid(value) else writeString(value)
        }
    }

    private fun writeListStart(size: Int) {
        when {
            size == 0 -> pushByte(BinaryToken.LIST_EMPTY)
            size < LIST_8_LIMIT -> { pushByte(BinaryToken.LIST_8); pushInt8(size) }
            else -> { pushByte(BinaryToken.LIST_16); pushInt16(size) }
        }
    }

    private fun validateNibble(value: String): Boolean {
        if (value.length > BinaryToken.PACKED_MAX) return false
        return value.all { it in '0'..'9' || it == '-' || it == '.' }
    }

    private fun validateHex(value: String): Boolean {
        if (value.length > BinaryToken.PACKED_MAX) return false
        // HEX_8 packing is uppercase-only; lowercase must fall through to raw
        // string encoding or it decodes back as uppercase (whatsmeow binary/encoder.go).
        return value.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private fun writePackedBytes(value: String, dataType: Int) {
        pushByte(dataType)
        val roundedLength = ((value.length + 1) / 2)
        val flag = if (value.length % 2 != 0) (roundedLength or 128) else roundedLength
        pushByte(flag)
        val packer = if (dataType == BinaryToken.NIBBLE_8) ::packNibble else ::packHex
        var i = 0
        while (i < value.length / 2) {
            pushByte(((packer(value[2 * i]) shl NIBBLE_BITS) or packer(value[2 * i + 1])).toByte())
            i++
        }
        if (value.length % 2 != 0) {
            pushByte(((packer(value.last()) shl NIBBLE_BITS) or packer(0.toChar())).toByte())
        }
    }

    private fun packNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        '-' -> PACKED_MINUS
        '.' -> PACKED_DOT
        0.toChar() -> PACKED_END
        else -> throw IllegalArgumentException("Invalid nibble char: $c")
    }

    private fun packHex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> DECIMAL_LIMIT + (c - 'A')
        in 'a'..'f' -> DECIMAL_LIMIT + (c - 'a')
        0.toChar() -> PACKED_END
        else -> throw IllegalArgumentException("Invalid hex char: $c")
    }
}

internal class BinaryDecoder(private val data: ByteArray) {
    private var index = 0

    private fun checkEOS(length: Int) {
        if (index + length > data.size) throw IllegalStateException("End of stream")
    }

    private fun readByte(): Int {
        checkEOS(1)
        return data[index++].toInt() and BYTE_MASK
    }

    private fun readInt8(): Int = readByte()
    private fun readInt16(): Int {
        checkEOS(2)
        val v = ((data[index].toInt() and BYTE_MASK) shl 8) or (data[index + 1].toInt() and BYTE_MASK)
        index += 2
        return v
    }
    private fun readInt20(): Int {
        checkEOS(INT24_SIZE)
        val v = ((data[index].toInt() and NIBBLE_MASK) shl 16) or
                ((data[index + 1].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
                (data[index + 2].toInt() and BYTE_MASK)
        index += INT24_SIZE
        return v
    }
    private fun readInt32(): Int {
        checkEOS(INT32_SIZE)
        val v = ((data[index].toInt() and BYTE_MASK) shl SHIFT_BYTE3) or
                ((data[index + 1].toInt() and BYTE_MASK) shl SHIFT_BYTE2) or
                ((data[index + 2].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
                (data[index + INT24_SIZE].toInt() and BYTE_MASK)
        index += INT32_SIZE
        return v
    }

    private fun readRaw(length: Int): ByteArray {
        checkEOS(length)
        val result = data.copyOfRange(index, index + length)
        index += length
        return result
    }

    private fun readPacked8(tag: Int): String {
        val startByte = readByte()
        val sb = StringBuilder()
        for (i in 0 until (startByte and ASCII_MASK)) {
            val currByte = readByte()
            sb.append(unpackByte(tag, (currByte shr NIBBLE_BITS) and NIBBLE_MASK))
            sb.append(unpackByte(tag, currByte and NIBBLE_MASK))
        }
        var result = sb.toString()
        if ((startByte shr (BYTE_BITS - 1)) != 0) result = result.dropLast(1)
        return result
    }

    private fun unpackByte(tag: Int, value: Int): Char = when (tag) {
        BinaryToken.NIBBLE_8 -> unpackNibble(value)
        BinaryToken.HEX_8 -> unpackHex(value)
        else -> throw IllegalArgumentException("Unknown packed tag: $tag")
    }
    private fun unpackNibble(value: Int): Char = when {
        value < DECIMAL_LIMIT -> ('0' + value)
        value == PACKED_MINUS -> '-'
        value == PACKED_DOT -> '.'
        value == PACKED_END -> 0.toChar()
        else -> throw IllegalArgumentException("Invalid nibble: $value")
    }
    private fun unpackHex(value: Int): Char = when {
        value < DECIMAL_LIMIT -> ('0' + value)
        value < HEX_LIMIT -> ('A' + value - DECIMAL_LIMIT)
        else -> throw IllegalArgumentException("Invalid hex: $value")
    }

    private fun readListSize(tag: Int): Int = when (tag) {
        BinaryToken.LIST_EMPTY.toInt() and BYTE_MASK -> 0
        BinaryToken.LIST_8.toInt() and BYTE_MASK -> readInt8()
        BinaryToken.LIST_16.toInt() and BYTE_MASK -> readInt16()
        else -> throw IllegalArgumentException("Unknown list tag: $tag")
    }

    private fun read(asString: Boolean): Any? {
        val tag = readByte()
        return when (tag) {
            BinaryToken.LIST_EMPTY.toInt() and BYTE_MASK -> null
            BinaryToken.LIST_8.toInt() and BYTE_MASK,
            BinaryToken.LIST_16.toInt() and BYTE_MASK -> readList(tag)
            BinaryToken.BINARY_8.toInt() and BYTE_MASK -> readSized(readInt8(), asString)
            BinaryToken.BINARY_20.toInt() and BYTE_MASK -> readSized(readInt20(), asString)
            BinaryToken.BINARY_32.toInt() and BYTE_MASK -> readSized(readInt32(), asString)
            in BinaryToken.DICTIONARY_0..BinaryToken.DICTIONARY_3 -> {
                val idx = readInt8()
                BinaryToken.getDoubleToken(tag - BinaryToken.DICTIONARY_0, idx)
            }
            BinaryToken.AD_JID -> readAdJid()
            BinaryToken.FB_JID -> readFbJid()
            BinaryToken.INTEROP_JID -> readInteropJid()
            BinaryToken.JID_PAIR.toInt() and BYTE_MASK -> readJidPair()
            BinaryToken.NIBBLE_8, BinaryToken.HEX_8 -> readPacked8(tag)
            else -> readSingleToken(tag)
        }
    }

    /** Read a sized binary blob (or UTF-8 string when [asString]). */
    private fun readSized(size: Int, asString: Boolean): Any =
        if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)

    /** AD JID: agent + device + user. */
    private fun readAdJid(): String {
        val agent = readByte()
        val device = readByte()
        val user = read(true) as? String ?: ""
        return "$user.${agent}:${device}@s.whatsapp.net"
    }

    /** FB JID: user + device + server. */
    private fun readFbJid(): String {
        val user = read(true) as? String ?: ""
        val device = readInt16()
        val server = read(true) as? String ?: "msgr"
        return "$user:$device@$server"
    }

    /** Interop JID: user + device + integrator + server. */
    private fun readInteropJid(): String {
        val user = read(true) as? String ?: ""
        val device = readInt16()
        val integrator = readInt16()
        val server = read(true) as? String ?: ""
        return "$user:$device:$integrator@$server"
    }

    /** JID pair: user + server. */
    private fun readJidPair(): String {
        val user = read(true) as? String
        val server = read(true) as? String ?: throw IllegalStateException("JID missing server")
        return if (user != null) "$user@$server" else "@$server"
    }

    /** Single-byte dictionary token. */
    private fun readSingleToken(tag: Int): String {
        if (tag in 1 until BinaryToken.singleByteTokens.size) {
            return BinaryToken.singleByteTokens[tag]
        }
        throw IllegalArgumentException("Invalid token $tag at position $index")
    }

    private fun readList(tag: Int): List<Node> {
        val size = readListSize(tag)
        return (0 until size).map { readNode() }
    }

    fun readNode(): Node {
        val listTag = readInt8()
        val listSize = readListSize(listTag)
        val tag = read(true) as? String ?: throw IllegalStateException("Node tag is not a string")
        if (listSize == 0 || tag.isEmpty()) throw IllegalStateException("Invalid node")

        val attrCount = (listSize - 1) shr 1
        val attrs = mutableMapOf<String, String>()
        for (i in 0 until attrCount) {
            val key = read(true) as? String ?: continue
            val value = read(true)
            attrs[key] = value?.toString() ?: ""
        }

        val content = mutableListOf<Node>()
        var nodeData: ByteArray? = null
        if (listSize % 2 == 0) {
            val contentData = read(false)
            when (contentData) {
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    content.addAll(contentData as List<Node>)
                }
                is ByteArray -> nodeData = contentData
                is String -> nodeData = contentData.toByteArray(Charsets.UTF_8)
            }
        }

        return Node(tag, attrs, content, nodeData)
    }
}

fun WhatsAppProtocol.encodeNode(node: Node): ByteArray {
    val encoder = BinaryEncoder()
    encoder.writeNode(node)
    return encoder.getData()
}

fun WhatsAppProtocol.decodeNode(data: ByteArray): Node {
    val decoder = BinaryDecoder(unpack(data))
    return decoder.readNode()
}

// WhatsApp Noise certificate root public key + issuer serial (whatsmeow/handshake.go).
private val WA_CERT_PUB_KEY = byteArrayOf(
    0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71, 0x66.toByte(), 0xaa.toByte(),
    0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37, 0xc4.toByte(), 0xa2.toByte(),
    0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c, 0xe1.toByte(), 0xf7.toByte(),
    0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b,
)
private const val WA_CERT_ISSUER_SERIAL = 0
private const val CERT_SIGNATURE_SIZE = 64
private const val IDENTITY_KEY_SIZE = 32

/** True when all cert chain parts are present and well-formed. */
private fun hasValidCertParts(
    interRaw: ByteArray,
    leafRaw: ByteArray,
    interSig: ByteArray,
    leafSig: ByteArray,
): Boolean = interRaw.isNotEmpty() &&
    leafRaw.isNotEmpty() &&
    interSig.size == CERT_SIGNATURE_SIZE &&
    leafSig.size == CERT_SIGNATURE_SIZE

/**
 * Verify the server's Noise certificate chain (intermediate signed by the WA root,
 * leaf signed by the intermediate, leaf key == server static key, validity window).
 * Port of whatsmeow/handshake.go verifyServerCert. Returns true if trusted.
 */
fun WhatsAppProtocol.verifyServerCert(certDecrypted: ByteArray, staticDecrypted: ByteArray): Boolean {
    return try {
        val chain = WhatsAppCertProto.CertChain.parseFrom(certDecrypted)
        val interRaw = chain.intermediate.details.toByteArray()
        val interSig = chain.intermediate.signature.toByteArray()
        val leafRaw = chain.leaf.details.toByteArray()
        val leafSig = chain.leaf.signature.toByteArray()
        if (!hasValidCertParts(interRaw, leafRaw, interSig, leafSig)) {
            Log.error(TAG, "cert: missing/invalid parts")
            return false
        }
        if (!ECPublicKey.fromPublicKeyBytes(WA_CERT_PUB_KEY).verifySignature(interRaw, interSig)) {
            Log.error(TAG, "cert: intermediate signature invalid")
            return false
        }
        val inter = WhatsAppCertProto.CertChain.NoiseCertificate.Details.parseFrom(interRaw)
        if (inter.issuerSerial != WA_CERT_ISSUER_SERIAL || inter.key.size() != IDENTITY_KEY_SIZE) {
            Log.error(TAG, "cert: bad intermediate issuer/key")
            return false
        }
        verifyLeafCert(inter, leafRaw, leafSig, staticDecrypted)
    } catch (expected: Exception) {
        Log.error(TAG, "cert verification error", expected)
        false
    }
}

/** Verify the leaf certificate against the intermediate. */
private fun verifyLeafCert(
    inter: WhatsAppCertProto.CertChain.NoiseCertificate.Details,
    leafRaw: ByteArray,
    leafSig: ByteArray,
    staticDecrypted: ByteArray,
): Boolean {
    if (!ECPublicKey.fromPublicKeyBytes(inter.key.toByteArray()).verifySignature(leafRaw, leafSig)) {
        Log.error(TAG, "cert: leaf signature invalid")
        return false
    }
    val leaf = WhatsAppCertProto.CertChain.NoiseCertificate.Details.parseFrom(leafRaw)
    if (leaf.issuerSerial != inter.serial) {
        Log.error(TAG, "cert: leaf issuer serial mismatch")
        return false
    }
    if (!leaf.key.toByteArray().contentEquals(staticDecrypted)) {
        Log.error(TAG, "cert: leaf key != server static key")
        return false
    }
    val now = System.currentTimeMillis() / 1000
    for (d in listOf(inter, leaf)) {
        if (d.notBefore != 0L && now < d.notBefore) { Log.error(TAG, "cert: not yet valid"); return false }
        if (d.notAfter != 0L && now > d.notAfter) { Log.error(TAG, "cert: expired"); return false }
    }
    return true
}

/**
 * Strips the leading compression flag byte from a decrypted frame and inflates
 * the remainder with zlib when the flag's 0x02 bit is set.
 * Ref: whatsmeow/binary/unpack.go Unpack().
 */
private fun WhatsAppProtocol.unpack(data: ByteArray): ByteArray {
    if (data.isEmpty()) throw IllegalStateException("empty frame, no flag byte")
    val flag = data[0].toInt() and BYTE_MASK
    val payload = data.copyOfRange(1, data.size)
    return if (flag and 2 > 0) {
        inflatePayload(payload)
    } else {
        payload
    }
}

/** Zlib-inflate a flagged frame payload. */
private fun inflatePayload(payload: ByteArray): ByteArray {
    val inflater = java.util.zip.Inflater()
    inflater.setInput(payload)
    val out = java.io.ByteArrayOutputStream(payload.size * 2)
    val buf = ByteArray(8192)
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.finished() || inflater.needsDictionary()) break
                if (inflater.needsInput()) throw IllegalStateException("zlib needs more input")
            }
            out.write(buf, 0, n)
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}

// -- Frame helpers --

/**
 * Build a framed message with optional header and 3-byte big-endian length prefix.
 * From whatsmeow/socket/framesocket.go SendFrame()
 */
fun WhatsAppProtocol.buildFramedMessage(data: ByteArray, header: ByteArray?): ByteArray {
    val headerLength = header?.size ?: 0
    val dataLength = data.size
    if (dataLength >= FRAME_MAX_SIZE) {
        throw IllegalArgumentException("Frame too large: $dataLength bytes (max $FRAME_MAX_SIZE)")
    }
    val frame = ByteArray(headerLength + FRAME_LENGTH_SIZE + dataLength)

    var offset = 0
    if (header != null) {
        System.arraycopy(header, 0, frame, offset, headerLength)
        offset += headerLength
    }
    frame[offset] = (dataLength shr SHIFT_BYTE2).toByte()
    frame[offset + 1] = (dataLength shr SHIFT_BYTE1).toByte()
    frame[offset + 2] = dataLength.toByte()
    offset += FRAME_LENGTH_SIZE
    System.arraycopy(data, 0, frame, offset, dataLength)
    return frame
}

/**
 * Extract frame payload from raw data (strip 3-byte length prefix).
 * From whatsmeow/socket/framesocket.go processData()
 */
fun WhatsAppProtocol.extractFrame(data: ByteArray): ByteArray {
    if (data.size < FRAME_LENGTH_SIZE) return data
    val length = ((data[0].toInt() and BYTE_MASK) shl 16) or
            ((data[1].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
            (data[2].toInt() and BYTE_MASK)
    if (data.size < FRAME_LENGTH_SIZE + length) return data
    return data.copyOfRange(FRAME_LENGTH_SIZE, FRAME_LENGTH_SIZE + length)
}
