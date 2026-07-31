package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections

class SabrRequestCancellationPolicy private constructor(
    private val field1: Int,
    private val field3: Int,
    items: List<Item>
) {
    private val items: List<Item> = Collections.unmodifiableList(ArrayList(items))

    internal companion object {
        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrRequestCancellationPolicy {
            var field1 = 0
            var field3 = 0
            val items = mutableListOf<Item>()
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 1 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    field1 = field.getVarint().toInt()
                } else if (field.getNumber() == 2 && field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
                    items.add(Item.decode(field.getBytes()))
                } else if (field.getNumber() == 3 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    field3 = field.getVarint().toInt()
                }
            }
            return SabrRequestCancellationPolicy(field1, field3, items)
        }
    }

    fun getField1(): Int = field1
    fun getField3(): Int = field3
    fun getItems(): List<Item> = items

    fun summarize(): String {
        val builder = StringBuilder()
        builder.append("field1=").append(field1)
            .append(", items=").append(items.size).append('[')
        val sampleSize = minOf(4, items.size)
        for (i in 0 until sampleSize) {
            if (i > 0) builder.append(',')
            builder.append(items[i].summarize())
        }
        if (items.size > sampleSize) builder.append(",...")
        builder.append(']').append(", field3=").append(field3)
        return builder.toString()
    }

    class Item private constructor(
        private val field1: Int,
        private val field2: Int,
        private val minReadaheadMs: Int
    ) {
        companion object {
            @JvmStatic
            @Throws(SabrProtocolException::class)
            internal fun decode(data: ByteArray): Item {
                var field1 = 0
                var field2 = 0
                var minReadaheadMs = 0
                for (field in SabrProto.readFields(data)) {
                    if (field.getWireType() != SabrProto.WIRE_VARINT) continue
                    when (field.getNumber()) {
                        1 -> field1 = field.getVarint().toInt()
                        2 -> field2 = field.getVarint().toInt()
                        3 -> minReadaheadMs = field.getVarint().toInt()
                    }
                }
                return Item(field1, field2, minReadaheadMs)
            }
        }

        fun getField1(): Int = field1
        fun getField2(): Int = field2
        fun getMinReadaheadMs(): Int = minReadaheadMs

        fun summarize(): String = "field1=$field1/field2=$field2/minReadaheadMs=$minReadaheadMs"
    }
}
