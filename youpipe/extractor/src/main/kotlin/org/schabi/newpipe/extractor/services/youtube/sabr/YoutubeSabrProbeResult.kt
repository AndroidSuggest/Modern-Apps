package org.schabi.newpipe.extractor.services.youtube.sabr

class YoutubeSabrProbeResult internal constructor(
    @JvmField val info: YoutubeSabrInfo,
    @JvmField val decodedResponse: SabrDecodedResponse,
    @JvmField val segments: List<SabrMediaSegment>,
    @JvmField val segmentCount: Int,
    @JvmField val responseCode: Int,
    @JvmField val contentType: String,
    @JvmField val responseBytes: Long,
    @JvmField val mediaPayloadBytes: Long,
    @JvmField val mediaPartPayloadBytes: Long,
    @JvmField val controlPayloadBytes: Long,
    @JvmField val totalPayloadBytes: Long,
    @JvmField val maxPartBytes: Long,
    @JvmField val maxMediaPartPayloadBytes: Long,
    @JvmField val maxSegmentBytes: Long,
    @JvmField val requestElapsedMs: Long,
    @JvmField val firstSegmentElapsedMs: Long
) {
    fun getInfo(): YoutubeSabrInfo = info
    fun getDecodedResponse(): SabrDecodedResponse = decodedResponse
    fun getSegments(): List<SabrMediaSegment> = segments
    fun getSegmentCount(): Int = segmentCount
    fun getResponseCode(): Int = responseCode
    fun getContentType(): String = contentType
    fun getResponseBytes(): Long = responseBytes
    fun getMediaPayloadBytes(): Long = mediaPayloadBytes
    fun getMediaPartPayloadBytes(): Long = mediaPartPayloadBytes
    fun getControlPayloadBytes(): Long = controlPayloadBytes
    fun getTotalPayloadBytes(): Long = totalPayloadBytes
    fun getMaxPartBytes(): Long = maxPartBytes
    fun getMaxMediaPartPayloadBytes(): Long = maxMediaPartPayloadBytes
    fun getMaxSegmentBytes(): Long = maxSegmentBytes
    fun getRequestElapsedMs(): Long = requestElapsedMs
    fun getFirstSegmentElapsedMs(): Long = firstSegmentElapsedMs
}
