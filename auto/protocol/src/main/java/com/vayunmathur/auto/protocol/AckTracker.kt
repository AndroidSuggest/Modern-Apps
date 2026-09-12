package com.vayunmathur.auto.protocol

/**
 * Unwraps the 0x8004 `MediaAck` frame counter.
 *
 * The wire `ack` field is a frame counter **mod 256**: it wraps back to 0 every
 * 256 frames, so a raw value below the previous one is a wrap, not a
 * regression. This folds each arrival into a monotonically non-decreasing
 * sequence the phone UI can track; stale arrivals (reordered or duplicated
 * acks) hold the line instead of moving it backwards.
 *
 * Pure state machine, no I/O: the Android side feeds it parsed ack values and
 * publishes the unwrapped sequence.
 */
class AckTracker {
    /** Last unwrapped counter, or null before the first ack carrying the field. */
    var lastUnwrapped: Long? = null
        private set

    /**
     * Folds [raw] (the unsigned-extended `ack` field) into the sequence.
     *
     * @return the new sequence head, or the previous head when [raw] is stale.
     */
    fun onAck(raw: Long): Long {
        val value = raw and (MOD - 1)
        val previous = lastUnwrapped
        val candidate = when {
            previous == null -> value
            value >= previous and (MOD - 1) -> previous - (previous and (MOD - 1)) + value
            else -> previous - (previous and (MOD - 1)) + value + MOD
        }
        if (previous == null || candidate > previous) {
            lastUnwrapped = candidate
        }
        return checkNotNull(lastUnwrapped)
    }

    fun reset() {
        lastUnwrapped = null
    }

    companion object {
        /** Modulus of the 0x8004 `ack` frame counter. */
        const val MOD = 256L
    }
}
