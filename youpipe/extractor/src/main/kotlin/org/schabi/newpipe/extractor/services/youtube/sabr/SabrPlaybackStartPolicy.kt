package org.schabi.newpipe.extractor.services.youtube.sabr


class SabrPlaybackStartPolicy private constructor(
    startMinReadaheadPolicies: List<ReadaheadPolicy>,
    resumeMinReadaheadPolicies: List<ReadaheadPolicy>,
    extraVarints: Map<Int, Long>
) {
    private val startMinReadaheadPolicies: List<ReadaheadPolicy> =
        startMinReadaheadPolicies.toList()
    private val resumeMinReadaheadPolicies: List<ReadaheadPolicy> =
        resumeMinReadaheadPolicies.toList()
    private val extraVarints: Map<Int, Long> =
        extraVarints.toMap()

    companion object {
        @Throws(SabrProtocolException::class)
        @JvmStatic
        internal fun decode(data: ByteArray): SabrPlaybackStartPolicy {
            val startMinReadaheadPolicies = ArrayList<ReadaheadPolicy>()
            val resumeMinReadaheadPolicies = ArrayList<ReadaheadPolicy>()
            val extraVarints: MutableMap<Int, Long> = LinkedHashMap()

            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    startMinReadaheadPolicies.add(decodeReadaheadPolicy(field.getBytes()))
                } else if (field.number == 2 && field.wireType == SabrProto.WIRE_LENGTH_DELIMITED) {
                    resumeMinReadaheadPolicies.add(decodeReadaheadPolicy(field.getBytes()))
                } else if (field.wireType == SabrProto.WIRE_VARINT) {
                    extraVarints[field.number] = field.varint
                }
            }

            return SabrPlaybackStartPolicy(
                startMinReadaheadPolicies,
                resumeMinReadaheadPolicies, extraVarints
            )
        }

        @Throws(SabrProtocolException::class)
        private fun decodeReadaheadPolicy(data: ByteArray): ReadaheadPolicy {
            var minBandwidthBytesPerSecond = -1
            var minReadaheadMs = -1
            for (field in SabrProto.readFields(data)) {
                if (field.number == 1 && field.wireType == SabrProto.WIRE_VARINT) {
                    minBandwidthBytesPerSecond = field.varint.toInt()
                } else if (field.number == 2 && field.wireType == SabrProto.WIRE_VARINT) {
                    minReadaheadMs = field.varint.toInt()
                }
            }
            return ReadaheadPolicy(minBandwidthBytesPerSecond, minReadaheadMs)
        }

        private fun summarizePolicies(policies: List<ReadaheadPolicy>): String {
            if (policies.isEmpty()) {
                return "[]"
            }
            val builder = StringBuilder()
            builder.append(policies.size).append('[')
            val sampleSize = Math.min(6, policies.size)
            for (i in 0 until sampleSize) {
                if (i > 0) {
                    builder.append(',')
                }
                builder.append(policies[i].summarize())
            }
            if (policies.size > sampleSize) {
                builder.append(",...")
            }
            builder.append(']')
            return builder.toString()
        }
    }

    fun summarize(): String {
        return "start=${summarizePolicies(startMinReadaheadPolicies)}" +
            ", resume=${summarizePolicies(resumeMinReadaheadPolicies)}" +
            ", extraVarints=$extraVarints"
    }

    class ReadaheadPolicy(
        val minBandwidthBytesPerSecond: Int,
        private val minReadaheadMs: Int
    ) {

        fun getMinReadaheadMs(): Int = minReadaheadMs

        internal fun summarize(): String = "${minReadaheadMs}ms/${minBandwidthBytesPerSecond}Bps"
    }
}
