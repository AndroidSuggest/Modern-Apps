package com.vayunmathur.library.media

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Ogg page construction: lacing tables, the Ogg CRC-32, and splitting a packet across pages.
 *
 * Shared by [OggOpusTagger], which rewrites the pages of a file the platform muxer produced,
 * and by [OggStreamWriter], which builds a file from encoder packets with no muxer involved.
 */
internal object OggPages {

    const val HEADER_SIZE = 27
    const val MAX_SEGMENTS = 255

    const val FLAG_CONTINUED = 0x01
    const val FLAG_BEGIN_OF_STREAM = 0x02
    const val FLAG_END_OF_STREAM = 0x04

    private const val CHECKSUM_OFFSET = 22
    private const val CHECKSUM_END = 25
    private const val CHECKSUM_SIZE_BYTES = 4
    private const val BYTE_OFFSET_1 = 1
    private const val BYTE_OFFSET_2 = 2
    private const val BYTE_OFFSET_3 = 3
    private const val LONG_SIZE_BYTES = 8
    private const val BITS_PER_BYTE = 8
    private const val BYTE_SHIFT_1 = 8
    private const val BYTE_SHIFT_2 = 16
    private const val BYTE_SHIFT_3 = 24
    private const val BYTE_MASK = 0xff
    private const val CRC_TOP_SHIFT = 8
    private const val CRC_TABLE_BITS = 8
    private const val CRC_TABLE_TOP = 24

    /**
     * The lacing values for one packet: 255 for every full segment, then a final value under
     * 255 that marks the packet's end. A packet whose length is a multiple of 255 therefore
     * ends with a zero-length segment, which is what tells a reader the packet is complete.
     */
    fun lacingFor(packetSize: Int): List<Int> {
        val lacing = ArrayList<Int>(packetSize / MAX_SEGMENTS + 1)
        var remaining = packetSize
        while (remaining >= MAX_SEGMENTS) {
            lacing.add(MAX_SEGMENTS)
            remaining -= MAX_SEGMENTS
        }
        lacing.add(remaining)
        return lacing
    }

    fun build(
        headerType: Int,
        granulePosition: Long,
        serialNumber: Int,
        sequence: Int,
        segments: List<Int>,
        payload: ByteArray,
        payloadOffset: Int,
        payloadSize: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(HEADER_SIZE + segments.size + payloadSize)
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // stream structure version
        out.write(headerType)
        out.write(longLe(granulePosition))
        out.write(VorbisComments.intLe(serialNumber))
        out.write(VorbisComments.intLe(sequence))
        out.write(ByteArray(CHECKSUM_SIZE_BYTES)) // CRC placeholder, filled in below
        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(payload, payloadOffset, payloadSize)
        val bytes = out.toByteArray()
        setChecksum(bytes)
        return bytes
    }

    /**
     * Splits a single packet into as many pages as its length needs.
     *
     * Only a header packet with embedded cover art gets anywhere near this; audio packets
     * always fit on one page.
     */
    fun forPacket(
        packet: ByteArray,
        serialNumber: Int,
        startSequence: Int,
        granulePosition: Long,
        firstHeaderType: Int,
    ): List<ByteArray> {
        val lacing = lacingFor(packet.size)
        val pages = ArrayList<ByteArray>()
        var lacingIndex = 0
        var payloadOffset = 0
        var sequence = startSequence
        while (lacingIndex < lacing.size) {
            val segmentCount = minOf(MAX_SEGMENTS, lacing.size - lacingIndex)
            val segments = lacing.subList(lacingIndex, lacingIndex + segmentCount)
            val payloadSize = segments.sum()
            pages.add(
                build(
                    headerType = if (lacingIndex == 0) firstHeaderType else FLAG_CONTINUED,
                    granulePosition = granulePosition,
                    serialNumber = serialNumber,
                    sequence = sequence,
                    segments = segments,
                    payload = packet,
                    payloadOffset = payloadOffset,
                    payloadSize = payloadSize,
                ),
            )
            lacingIndex += segmentCount
            payloadOffset += payloadSize
            sequence++
        }
        return pages
    }

    /** Zeroes the checksum field, computes the Ogg CRC over the whole page, and writes it back. */
    fun setChecksum(page: ByteArray) {
        for (i in CHECKSUM_OFFSET..CHECKSUM_END) page[i] = 0
        writeIntLe(page, CHECKSUM_OFFSET, crc(page))
    }

    fun writeIntLe(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + BYTE_OFFSET_1] = (value ushr BYTE_SHIFT_1).toByte()
        buffer[offset + BYTE_OFFSET_2] = (value ushr BYTE_SHIFT_2).toByte()
        buffer[offset + BYTE_OFFSET_3] = (value ushr BYTE_SHIFT_3).toByte()
    }

    fun writeLongLe(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until LONG_SIZE_BYTES) buffer[offset + i] = (value ushr (BITS_PER_BYTE * i)).toByte()
    }

    fun longLe(value: Long): ByteArray = ByteArray(LONG_SIZE_BYTES).also { writeLongLe(it, 0, value) }

    // ------------------------------------------------------------------
    // Ogg CRC-32 (poly 0x04C11DB7, no reflection, no initial or final xor)
    // ------------------------------------------------------------------

    private val crcTable = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var crc = i shl CRC_TABLE_TOP
            repeat(CRC_TABLE_BITS) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04c11db7
                } else {
                    crc shl 1
                }
            }
            table[i] = crc
        }
    }

    fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val index = ((crc ushr CRC_TABLE_TOP) xor (b.toInt() and BYTE_MASK)) and BYTE_MASK
            crc = (crc shl CRC_TOP_SHIFT) xor crcTable[index]
        }
        return crc
    }
}

/**
 * Writes an Ogg stream from packets, in one pass, with no platform muxer.
 *
 * [MediaMuxer][android.media.MediaMuxer]'s `MUXER_OUTPUT_OGG` can only be handed a track
 * format, and the platform Opus encoder describes itself through Android's own
 * `AOPUSHDR` marker-chunk codec config rather than the split `csd-0`/`csd-1`/`csd-2` an
 * extractor produces - so whether the muxer would accept it is not something the app can
 * rely on, and it would also need a temp file, since the muxer cannot write to memory. All
 * of the Ogg framing the app needs already existed in [OggOpusTagger] for retagging, so
 * this writes the container directly instead: no temp file, no framework dependency, and
 * the byte-level behaviour is unit-testable.
 *
 * Header packets each get their own page, as the Opus mapping requires. Audio packets are
 * grouped so a page holds up to about a second of audio, which is the usual trade between
 * page overhead and how much a reader loses to a corrupt page.
 *
 * **Pages go straight to [sink] as they are completed, and no byte is ever revisited.** Each
 * page is built whole - lacing table, granule position, CRC - and only then written, and
 * nothing discovered later reaches back into one already gone: [finish]'s final granule
 * position lands on the *pending* page, and the header packets are fixed in size when written.
 * So the same code serves a file being accumulated in memory and a stream being read while it
 * is still being encoded, which is what lets a transcode start playing before it has finished.
 */
internal class OggStreamWriter(
    private val serialNumber: Int,
    /**
     * Where the pages go. Pass a `ByteArrayOutputStream` to accumulate the whole stream, or a
     * file for a reader to follow along behind.
     */
    private val sink: OutputStream,
) {

    private var sequence = 0
    private var headerWritten = false

    private val pendingPackets = ArrayList<ByteArray>()
    private var pendingSegments = 0
    private var pendingGranule = 0L

    /** Writes a header packet on a page of its own, with a granule position of zero. */
    fun writeHeaderPacket(packet: ByteArray) {
        val pages = OggPages.forPacket(
            packet = packet,
            serialNumber = serialNumber,
            startSequence = sequence,
            granulePosition = 0L,
            firstHeaderType = if (headerWritten) 0 else OggPages.FLAG_BEGIN_OF_STREAM,
        )
        pages.forEach { writePage(it) }
        sequence += pages.size
        headerWritten = true
    }

    /**
     * Queues an audio packet. [granulePosition] is the total number of samples a decoder gets
     * out of the stream through the end of this packet, which is what Ogg records for the last
     * packet finishing on a page.
     */
    fun writeAudioPacket(packet: ByteArray, granulePosition: Long) {
        val segments = OggPages.lacingFor(packet.size).size
        if (pendingPackets.isNotEmpty() &&
            (pendingSegments + segments > OggPages.MAX_SEGMENTS ||
                pendingPackets.size >= MAX_PACKETS_PER_PAGE)
        ) {
            flushPage(endOfStream = false, granulePosition = pendingGranule)
        }
        pendingPackets.add(packet)
        pendingSegments += segments
        pendingGranule = granulePosition
    }

    /**
     * Writes the last page, flagged end of stream.
     *
     * [finalGranulePosition] overrides the last page's granule position so it reports the
     * stream's true length: the encoder pads its final packet out to a whole frame, and a
     * decoder trims that padding by comparing what it decoded against this number. It only
     * ever affects the page still pending, which is why nothing already written has to be
     * revisited and why the stream can be read as it is produced.
     */
    fun finish(finalGranulePosition: Long) {
        flushPage(endOfStream = true, granulePosition = finalGranulePosition)
    }

    private fun flushPage(endOfStream: Boolean, granulePosition: Long) {
        if (pendingPackets.isEmpty() && !endOfStream) return
        val segments = ArrayList<Int>(pendingSegments)
        var payloadSize = 0
        for (packet in pendingPackets) {
            segments.addAll(OggPages.lacingFor(packet.size))
            payloadSize += packet.size
        }
        val payload = ByteArray(payloadSize)
        var at = 0
        for (packet in pendingPackets) {
            packet.copyInto(payload, at)
            at += packet.size
        }
        writePage(
            OggPages.build(
                headerType = if (endOfStream) OggPages.FLAG_END_OF_STREAM else 0,
                granulePosition = granulePosition,
                serialNumber = serialNumber,
                sequence = sequence,
                segments = segments,
                payload = payload,
                payloadOffset = 0,
                payloadSize = payloadSize,
            ),
        )
        sequence++
        pendingPackets.clear()
        pendingSegments = 0
    }

    /**
     * Flushed per page rather than left to the sink's own buffering.
     *
     * A reader following a live transcode can only see bytes that have actually been written,
     * so a page held back in a buffer is a page the player does not have - and with a page
     * carrying about a second of audio, buffering a few of them is the difference between
     * playback starting and the TV waiting.
     */
    private fun writePage(page: ByteArray) {
        sink.write(page)
        sink.flush()
    }

    private companion object {
        /** Fifty 20 ms Opus packets, so a page carries about one second. */
        const val MAX_PACKETS_PER_PAGE = 50
    }
}
