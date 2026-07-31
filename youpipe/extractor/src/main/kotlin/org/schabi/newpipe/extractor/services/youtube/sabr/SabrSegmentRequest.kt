package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrSegmentRequest private constructor(
    val format: YoutubeSabrFormat,
    private val initializationSegment: Boolean,
    private val sequenceNumber: Int
) {
    companion object {
        @JvmStatic
        fun initialization(format: YoutubeSabrFormat): SabrSegmentRequest =
            SabrSegmentRequest(format, true, -1)

        @JvmStatic
        fun media(format: YoutubeSabrFormat, sequenceNumber: Int): SabrSegmentRequest {
            if (sequenceNumber <= 0) throw IllegalArgumentException("SABR media sequence number must be positive")
            return SabrSegmentRequest(format, false, sequenceNumber)
        }
    }

    internal fun matches(header: SabrMediaHeader): Boolean {
        if (header.itag != format.itag) return false
        if (initializationSegment) return header.isInitSegment()
        return !header.isInitSegment() && header.sequenceNumber == sequenceNumber
    }

    fun isInitializationSegment(): Boolean = initializationSegment
    fun getSequenceNumber(): Int = sequenceNumber
}
