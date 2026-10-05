package com.vayunmathur.taxi.network.lyft

/**
 * Minimal read-only protobuf message view over a byte range. Supports exactly the wire types the
 * offers response uses: varint (0), 64-bit (1), length-delimited (2). Accessors return the last
 * value for a field number (protobuf "last one wins"), which is all this parser needs.
 */
internal class ProtoMessage(private val buf: ByteArray, start: Int, private val end: Int) {
    // fieldNumber -> list of entries. For wire 0/1 the Long is the value; for wire 2 it is the
    // [start, end) byte range packed as start (in `ranges`).
    private val varints = HashMap<Int, MutableList<Long>>()
    private val fixed64 = HashMap<Int, MutableList<Long>>()
    private val ranges = HashMap<Int, MutableList<IntArray>>()

    init {
        var p = start
        loop@ while (p < end) {
            val (tag, afterTag) = readVarint(buf, p)
            p = afterTag
            val field = (tag ushr TAG_FIELD_SHIFT).toInt()
            when ((tag and WIRE_TYPE_MASK).toInt()) {
                WIRE_VARINT -> {
                    val (v, np) = readVarint(buf, p); p = np
                    varints.getOrPut(field) { mutableListOf() }.add(v)
                }
                WIRE_FIXED64 -> {
                    var v = 0L
                    for (i in 0 until FIXED64_BYTES) {
                        v = v or ((buf[p + i].toLong() and BYTE_MASK) shl (BITS_PER_BYTE * i))
                    }
                    p += FIXED64_BYTES
                    fixed64.getOrPut(field) { mutableListOf() }.add(v)
                }
                WIRE_LENGTH_DELIMITED -> {
                    val (len, np) = readVarint(buf, p); p = np
                    val s = p; val e = p + len.toInt()
                    ranges.getOrPut(field) { mutableListOf() }.add(intArrayOf(s, e))
                    p = e
                }
                WIRE_FIXED32 -> p += FIXED32_BYTES
                else -> break@loop // unknown/invalid wire type: stop rather than misread
            }
        }
    }

    fun varint(field: Int): Long? = varints[field]?.lastOrNull()

    fun double(field: Int): Double? = fixed64[field]?.lastOrNull()?.let { Double.fromBits(it) }

    fun string(field: Int): String? = ranges[field]?.lastOrNull()?.let {
        String(buf, it[0], it[1] - it[0], Charsets.UTF_8)
    }

    fun message(field: Int): ProtoMessage? = ranges[field]?.lastOrNull()?.let {
        ProtoMessage(buf, it[0], it[1])
    }

    fun messages(field: Int): List<ProtoMessage> =
        ranges[field]?.map { ProtoMessage(buf, it[0], it[1]) } ?: emptyList()

    /** A google.protobuf.Int64Value/UInt64Value wrapper: a sub-message with field 1 = the value. */
    fun wrappedLong(field: Int): Long? = message(field)?.varint(1)

    /** A google.protobuf.StringValue wrapper: a sub-message with field 1 = the string. */
    fun wrappedString(field: Int): String? = message(field)?.string(1)

    /** A google.protobuf.BoolValue wrapper: a sub-message with field 1 = the bool (varint). */
    fun wrappedBool(field: Int): Boolean? = message(field)?.varint(1)?.let { it != 0L }

    /** A google.protobuf.DoubleValue wrapper: a sub-message with field 1 = the double. */
    fun wrappedDouble(field: Int): Double? = message(field)?.double(1)

    private companion object {
        // Protobuf wire types and tag layout.
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5
        private const val TAG_FIELD_SHIFT = 3
        private const val WIRE_TYPE_MASK = 7L
        private const val FIXED64_BYTES = 8
        private const val FIXED32_BYTES = 4
        private const val BYTE_MASK = 0xffL
        private const val BITS_PER_BYTE = 8
        // Varint decoding.
        private const val VARINT_PAYLOAD_BITS = 7
        private const val VARINT_PAYLOAD_MASK = 0x7f
        private const val VARINT_CONTINUATION = 0x80

        fun readVarint(buf: ByteArray, from: Int): Pair<Long, Int> {
            var p = from
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[p++].toInt() and BYTE_MASK.toInt()
                result = result or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
                if (b < VARINT_CONTINUATION) break
                shift += VARINT_PAYLOAD_BITS
            }
            return result to p
        }
    }
}
