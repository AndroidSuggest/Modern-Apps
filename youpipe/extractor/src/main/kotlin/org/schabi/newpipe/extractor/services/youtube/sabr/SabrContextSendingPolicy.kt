package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections

class SabrContextSendingPolicy private constructor() {
    private val startPolicy: MutableList<Int> = ArrayList()
    private val stopPolicy: MutableList<Int> = ArrayList()
    private val discardPolicy: MutableList<Int> = ArrayList()

    private fun readPolicyValues(field: SabrProto.Field, output: MutableList<Int>) {
        if (field.getWireType() == SabrProto.WIRE_VARINT) {
            output.add(field.getVarint().toInt())
        } else if (field.getWireType() == SabrProto.WIRE_LENGTH_DELIMITED) {
            for (value in SabrProto.readPackedVarints(field.getBytes())) {
                output.add(value.toInt())
            }
        }
    }

    fun getStartPolicy(): List<Int> = Collections.unmodifiableList(startPolicy)

    fun getStopPolicy(): List<Int> = Collections.unmodifiableList(stopPolicy)

    fun getDiscardPolicy(): List<Int> = Collections.unmodifiableList(discardPolicy)

    fun summarize(): String = "start=$startPolicy, stop=$stopPolicy, discard=$discardPolicy"

    companion object {
        @JvmStatic
        fun normalized(start: List<Int>, stop: List<Int>, discard: List<Int>): SabrContextSendingPolicy {
            if (start.size > 128 || stop.size > 128 || discard.size > 128
                || containsNegative(start) || containsNegative(stop) || containsNegative(discard)
            ) {
                throw IllegalArgumentException("Invalid normalized SABR context policy")
            }
            val policy = SabrContextSendingPolicy()
            policy.startPolicy.addAll(start)
            policy.stopPolicy.addAll(stop)
            policy.discardPolicy.addAll(discard)
            return policy
        }

        private fun containsNegative(values: List<Int>): Boolean {
            for (value in values) {
                if (value < 0) return true
            }
            return false
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decode(data: ByteArray): SabrContextSendingPolicy {
            val policy = SabrContextSendingPolicy()
            for (field in SabrProto.readFields(data)) {
                when (field.getNumber()) {
                    1 -> policy.readPolicyValues(field, policy.startPolicy)
                    2 -> policy.readPolicyValues(field, policy.stopPolicy)
                    3 -> policy.readPolicyValues(field, policy.discardPolicy)
                }
            }
            return policy
        }
    }
}
