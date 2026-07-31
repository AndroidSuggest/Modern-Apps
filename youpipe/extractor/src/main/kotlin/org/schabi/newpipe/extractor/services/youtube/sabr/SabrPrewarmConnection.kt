package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections

class SabrPrewarmConnection private constructor(
    connections: List<String>,
    extraFields: List<String>
) {
    private val connections: List<String> = Collections.unmodifiableList(ArrayList(connections))
    private val extraFields: List<String> = Collections.unmodifiableList(ArrayList(extraFields))

    companion object {
        private const val MAX_SUMMARY_ITEMS = 4
        private const val MAX_NESTING_DEPTH = 2

        @Throws(SabrProtocolException::class)
        @JvmStatic
        internal fun decode(data: ByteArray): SabrPrewarmConnection {
            val connections = ArrayList<String>()
            val extraFields = ArrayList<String>()
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    connections.add(describeNestedMessage(field.getBytes(), 0))
                } else {
                    extraFields.add(describeField(field, 0))
                }
            }
            return SabrPrewarmConnection(connections, extraFields)
        }

        private fun summarizeList(values: List<String>): String {
            val builder = StringBuilder()
            builder.append(values.size).append('[')
            val sampleSize = Math.min(MAX_SUMMARY_ITEMS, values.size)
            for (i in 0 until sampleSize) {
                if (i > 0) {
                    builder.append(',')
                }
                builder.append(values[i])
            }
            if (values.size > sampleSize) {
                builder.append(",...")
            }
            return builder.append(']').toString()
        }

        private fun describeNestedMessage(data: ByteArray, depth: Int): String {
            if (depth >= MAX_NESTING_DEPTH) {
                return describeOpaqueBytes(data)
            }
            try {
                val fields = ArrayList<String>()
                for (field in SabrProto.readFields(data)) {
                    fields.add(describeField(field, depth + 1))
                }
                return '{' + join(fields) + '}'
            } catch (e: SabrProtocolException) {
                return describeOpaqueBytes(data)
            }
        }

        private fun describeOpaqueBytes(data: ByteArray): String {
            return "bytes(" + data.size + (if (isPrintableAscii(data)) ",ascii" else "") + ')'
        }

        private fun isPrintableAscii(data: ByteArray): Boolean {
            if (data.isEmpty()) {
                return false
            }
            for (value in data) {
                val unsigned = value.toInt() and 0xff
                if (unsigned < 0x20 || unsigned > 0x7e) {
                    return false
                }
            }
            return true
        }

        @Throws(SabrProtocolException::class)
        private fun describeField(field: SabrProto.Field, depth: Int): String {
            if (field.wireType == SabrProto.WIRE_VARINT) {
                return "${field.number}=${field.varint}"
            }
            if (field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                val bytes = field.getBytes()
                val nested = describeNestedMessage(bytes, depth)
                return "${field.number}=$nested"
            }
            return "${field.number}=bytes(${field.getBytes().size})"
        }

        private fun join(values: List<String>): String {
            val builder = StringBuilder()
            for (value in values) {
                if (builder.isNotEmpty()) {
                    builder.append('/')
                }
                builder.append(value)
            }
            return builder.toString()
        }
    }

    fun getConnections(): List<String> = connections

    fun getExtraFields(): List<String> = extraFields

    fun summarize(): String {
        return "connections=" + summarizeList(connections) +
            (if (extraFields.isEmpty()) "" else ", extra=" + summarizeList(extraFields))
    }
}
