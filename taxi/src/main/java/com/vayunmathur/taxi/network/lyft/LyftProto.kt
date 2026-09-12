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
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> {
                    val (v, np) = readVarint(buf, p); p = np
                    varints.getOrPut(field) { mutableListOf() }.add(v)
                }
                1 -> {
                    var v = 0L
                    for (i in 0 until 8) v = v or ((buf[p + i].toLong() and 0xff) shl (8 * i))
                    p += 8
                    fixed64.getOrPut(field) { mutableListOf() }.add(v)
                }
                2 -> {
                    val (len, np) = readVarint(buf, p); p = np
                    val s = p; val e = p + len.toInt()
                    ranges.getOrPut(field) { mutableListOf() }.add(intArrayOf(s, e))
                    p = e
                }
                5 -> p += 4
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
        fun readVarint(buf: ByteArray, from: Int): Pair<Long, Int> {
            var p = from
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[p++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return result to p
        }
    }
}
