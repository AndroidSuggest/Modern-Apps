package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections

class SabrSegmentIndex(
    entries: List<Entry>
) {
    private val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))

    fun getEntry(sequenceNumber: Int): Entry? {
        if (sequenceNumber <= 0 || sequenceNumber > entries.size) return null
        return entries[sequenceNumber - 1]
    }

    fun size(): Int = entries.size

    class Entry(
        val sequenceNumber: Int,
        val startMs: Long,
        private val durationMs: Long
    ) {
        fun getDurationMs(): Long = durationMs
        fun getEndMs(): Long = startMs + durationMs
    }
}
