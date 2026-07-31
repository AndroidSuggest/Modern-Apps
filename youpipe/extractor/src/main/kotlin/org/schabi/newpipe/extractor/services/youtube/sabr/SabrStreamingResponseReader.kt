package org.schabi.newpipe.extractor.services.youtube.sabr

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Streaming counterpart of [SabrResponseDecoder.decode]: parse the UMP envelope from a
 * stream one part at a time, assembling MEDIA segments on the fly (via
 * [SabrMediaSegmentCollector.Incremental]) so the big MEDIA payloads are never all held at
 * once.
 */
object SabrStreamingResponseReader {

    private const val MAX_CONTROL_PARTS = 512
    private const val MAX_CONTROL_PAYLOAD_BYTES = 512 * 1024L

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun read(`in`: InputStream): Result {
        return read(`in`, null as SegmentConsumer?)
    }

    fun interface SegmentConsumer {
        @Throws(SabrProtocolException::class)
        fun accept(segment: SabrMediaSegment)
    }

    fun interface StoppableSegmentConsumer {
        @Throws(SabrProtocolException::class)
        fun accept(segment: SabrMediaSegment): Boolean
    }

    /**
     * Streams completed segments directly to [segmentConsumer]. When a consumer is supplied,
     * completed segments are not retained by the result.
     */
    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun read(`in`: InputStream, segmentConsumer: SegmentConsumer?): Result {
        return readUntil(`in`, if (segmentConsumer == null) null else StoppableSegmentConsumer { segment ->
            segmentConsumer.accept(segment)
            true
        })
    }

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readUntil(`in`: InputStream, segmentConsumer: StoppableSegmentConsumer?): Result {
        return readUntil(`in`, segmentConsumer, null as File?)
    }

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readUntil(
        `in`: InputStream,
        segmentConsumer: StoppableSegmentConsumer?,
        spoolDirectory: File?
    ): Result {
        return readUntil(`in`, segmentConsumer, null as SegmentConsumer?, spoolDirectory)
    }

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readUntil(
        `in`: InputStream,
        segmentConsumer: StoppableSegmentConsumer?,
        segmentStartConsumer: SegmentConsumer?,
        spoolDirectory: File?
    ): Result {
        return readUntil(
            `in`, segmentConsumer, segmentStartConsumer, spoolDirectory,
            SabrMediaProtocol.builtin()
        )
    }

    @JvmStatic
    @Throws(SabrProtocolException::class, IOException::class)
    fun readUntil(
        `in`: InputStream,
        segmentConsumer: StoppableSegmentConsumer?,
        segmentStartConsumer: SegmentConsumer?,
        spoolDirectory: File?,
        mediaProtocol: SabrMediaProtocol
    ): Result {
        val controlParts = ArrayList<UmpPart>()
        val partSummaries = ArrayList<String>()
        val segments = ArrayList<SabrMediaSegment>()
        val segmentCount = intArrayOf(0)
        val mediaPayloadBytes = longArrayOf(0)
        val mediaPartPayloadBytes = longArrayOf(0)
        val controlPayloadBytes = longArrayOf(0)
        val totalPayloadBytes = longArrayOf(0)
        val maxPartBytes = longArrayOf(0)
        val maxMediaPartPayloadBytes = longArrayOf(0)
        val maxSegmentBytes = longArrayOf(0)
        val mediaBytesByHeaderId = HashMap<Int, Long>()
        val collector = SabrMediaSegmentCollector.Incremental(spoolDirectory, mediaProtocol)
        try {
            UmpReader.readPayloadsUntil(`in`) { type, size, payloadStream ->
                SabrDecodedResponse.addPartSummary(partSummaries, type, size)
                maxPartBytes[0] = maxOf(maxPartBytes[0], size.toLong())
                totalPayloadBytes[0] += size.toLong()
                if (type != mediaProtocol.getMediaPartType() &&
                    (controlParts.size >= MAX_CONTROL_PARTS ||
                        controlPayloadBytes[0] + size > MAX_CONTROL_PAYLOAD_BYTES)
                ) {
                    throw SabrProtocolException("SABR control response exceeded Host limit")
                }
                when (type) {
                    mediaProtocol.getHeaderPartType() -> {
                        val payload = readPayloadBytes(payloadStream, size)
                        controlPayloadBytes[0] += payload.size.toLong()
                        controlParts.add(UmpPart(type, payload.size, payload))
                        try {
                            val started = collector.onMediaHeader(payload)
                            if (started != null) {
                                segmentStartConsumer?.accept(started)
                            }
                        } catch (ignored: SabrProtocolException) {
                            if (!isMalformedMediaHeader(payload, mediaProtocol)) {
                                throw ignored
                            }
                        }
                    }
                    mediaProtocol.getMediaPartType() -> {
                        mediaPartPayloadBytes[0] += size.toLong()
                        if (size > 0) {
                            val headerId = payloadStream.read()
                            if (headerId < 0) {
                                throw SabrRecoverableException(
                                    "Unexpected EOF while reading SABR media header id"
                                )
                            }
                            val mediaBytes = size - 1
                            collector.onMedia(headerId, payloadStream, mediaBytes)
                            maxMediaPartPayloadBytes[0] =
                                maxOf(maxMediaPartPayloadBytes[0], mediaBytes.toLong())
                            mediaPayloadBytes[0] += mediaBytes.toLong()
                            mediaBytesByHeaderId[headerId] =
                                (mediaBytesByHeaderId[headerId] ?: 0L) + mediaBytes.toLong()
                        }
                    }
                    mediaProtocol.getEndPartType() -> {
                        val payload = readPayloadBytes(payloadStream, size)
                        controlPayloadBytes[0] += payload.size.toLong()
                        val segment = collector.onMediaEnd(payload)
                        controlParts.add(UmpPart(type, payload.size, payload))
                        if (segment != null) {
                            segmentCount[0]++
                            maxSegmentBytes[0] = maxOf(maxSegmentBytes[0], segment.length.toLong())
                            if (segmentConsumer == null) {
                                segments.add(segment)
                            } else {
                                return@readPayloadsUntil segmentConsumer.accept(segment)
                            }
                        }
                    }
                    else -> {
                        val payload = readPayloadBytes(payloadStream, size)
                        controlPayloadBytes[0] += payload.size.toLong()
                        controlParts.add(UmpPart(type, payload.size, payload))
                    }
                }
                true
            }
        } finally {
            collector.abort()
        }
        val decoded = SabrResponseDecoder.decodeParts(controlParts, mediaProtocol)
        decoded.setPartSummaries(partSummaries)
        for ((key, value) in mediaBytesByHeaderId) {
            decoded.addMediaBytes(key, value)
        }
        return Result(
            decoded, segments, segmentCount[0], mediaPayloadBytes[0],
            mediaPartPayloadBytes[0], controlPayloadBytes[0], totalPayloadBytes[0],
            maxPartBytes[0], maxMediaPartPayloadBytes[0], maxSegmentBytes[0]
        )
    }

    @Throws(IOException::class)
    private fun readPayloadBytes(input: InputStream, size: Int): ByteArray {
        val output = ByteArrayOutputStream(size)
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) {
                throw IOException("Unexpected EOF while reading UMP part data")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun isMalformedMediaHeader(payload: ByteArray, mediaProtocol: SabrMediaProtocol): Boolean {
        return try {
            mediaProtocol.decodeHeader(payload)
            false
        } catch (e: SabrProtocolException) {
            true
        }
    }

    /** The decoded control response plus the segments assembled while streaming. */
    class Result(
        val decodedResponse: SabrDecodedResponse,
        val segments: List<SabrMediaSegment>,
        val segmentCount: Int,
        val mediaPayloadBytes: Long,
        val mediaPartPayloadBytes: Long,
        val controlPayloadBytes: Long,
        val totalPayloadBytes: Long,
        val maxPartBytes: Long,
        val maxMediaPartPayloadBytes: Long,
        private val maxSegmentBytes: Long
    ) {
        fun getMaxSegmentBytes(): Long = maxSegmentBytes
    }
}
