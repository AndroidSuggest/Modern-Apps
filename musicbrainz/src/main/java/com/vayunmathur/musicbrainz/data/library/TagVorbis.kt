package com.vayunmathur.musicbrainz.data.library

import java.io.InputStream

internal object TagVorbis {

    fun readFlac(input: InputStream): AudioTags {
        val magic = input.readNBytesCompat(MAGIC_TEXT_LENGTH)
        if (magic.size < MAGIC_TEXT_LENGTH || String(magic, Charsets.ISO_8859_1) != "fLaC") {
            return AudioTags()
        }
        while (true) {
            val block = nextFlacBlock(input) ?: return AudioTags()
            if (block.isComment) return parseVorbisComments(block.payload, 0)
            if (block.isLast) return AudioTags()
        }
    }

    private class FlacBlock(val isLast: Boolean, val isComment: Boolean, val payload: ByteArray)

    private fun nextFlacBlock(input: InputStream): FlacBlock? {
        val header = input.readNBytesCompat(FLAC_BLOCK_HEADER)
        if (header.size < FLAC_BLOCK_HEADER) return null
        val flags = header[0].toInt() and BYTE_MASK
        val isLast = flags and FLAG_LAST_MASK != 0
        val blockType = flags and BLOCK_TYPE_MASK
        val length = ((header[1].toInt() and BYTE_MASK) shl SHIFT_SHORT_HIGH) or
            ((header[SHORT_MID_HIGH_INDEX].toInt() and BYTE_MASK) shl SHIFT_BYTE_MID) or
            (header[SHORT_LOW_INDEX].toInt() and BYTE_MASK)
        if (length < 0 || length > MAX_ID3_BYTES) return null
        val block = input.readNBytesCompat(length)
        if (block.size < length) return null
        return FlacBlock(isLast, blockType == FLAC_COMMENT_BLOCK, block)
    }

    /**
     * Ogg keeps its comment header in the second packet, so the pages are reassembled
     * until that packet is complete. Only the head of the file is scanned - if the
     * comments are not there the file is not one we can read anyway.
     */
    fun readOgg(input: InputStream): AudioTags {
        val data = input.readNBytesCompat(OGG_SCAN_BYTES)
        var offset = 0
        var packetIndex = 0
        val packet = java.io.ByteArrayOutputStream()
        while (offset + OGG_PAGE_HEADER_MIN <= data.size) {
            val next = consumeOggPage(data, offset, packet) ?: break
            offset = next.first
            val completed = next.second
            if (completed != null) {
                if (packetIndex == COMMENT_PACKET_INDEX) return parseOggCommentPacket(completed)
                packetIndex++
            }
        }
        return AudioTags()
    }

    private fun consumeOggPage(
        data: ByteArray,
        offset: Int,
        packet: java.io.ByteArrayOutputStream,
    ): Pair<Int, ByteArray?>? {
        if (String(data, offset, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1) != "OggS") return null
        val segmentCount = data[offset + OGG_SEGMENT_COUNT_OFFSET].toInt() and BYTE_MASK
        val tableStart = offset + OGG_PAGE_HEADER_MIN
        if (tableStart + segmentCount > data.size) return null
        val payloadSize = oggPayloadSize(data, tableStart, segmentCount)
        val packetEnds = oggPacketEnds(data, tableStart, segmentCount)
        val payloadStart = tableStart + segmentCount
        if (payloadStart + payloadSize > data.size) return null
        packet.write(data, payloadStart, payloadSize)
        val completed = if (packetEnds) {
            val bytes = packet.toByteArray()
            packet.reset()
            bytes
        } else {
            null
        }
        return (payloadStart + payloadSize) to completed
    }

    private fun oggPayloadSize(data: ByteArray, tableStart: Int, segmentCount: Int): Int {
        var payloadSize = 0
        for (i in 0 until segmentCount) {
            payloadSize += data[tableStart + i].toInt() and BYTE_MASK
        }
        return payloadSize
    }

    private fun oggPacketEnds(data: ByteArray, tableStart: Int, segmentCount: Int): Boolean {
        for (i in 0 until segmentCount) {
            val segment = data[tableStart + i].toInt() and BYTE_MASK
            if (segment < OGG_SEGMENT_TERMINATOR) return true
        }
        return false
    }

    private fun parseOggCommentPacket(packet: ByteArray): AudioTags = when {
        packet.size > OPUS_TAGS_LENGTH &&
            String(packet, 0, OPUS_TAGS_LENGTH, Charsets.ISO_8859_1) == "OpusTags" ->
            parseVorbisComments(packet, OPUS_TAGS_SKIP)
        packet.size > VORBIS_MAGIC_LENGTH &&
            String(
                packet,
                VORBIS_MAGIC_OFFSET,
                VORBIS_MAGIC_TEXT_LENGTH,
                Charsets.ISO_8859_1,
            ) == "vorbis" ->
            parseVorbisComments(packet, VORBIS_MAGIC_LENGTH)
        else -> AudioTags()
    }

    private fun parseVorbisComments(buf: ByteArray, start: Int): AudioTags {
        val values = readVorbisValues(buf, start) ?: return AudioTags()
        return AudioTags(
            title = values["TITLE"],
            artist = values["ARTIST"],
            album = values["ALBUM"],
            albumArtist = values["ALBUMARTIST"],
            recordingId = values["MUSICBRAINZ_TRACKID"],
            releaseId = values["MUSICBRAINZ_ALBUMID"],
            releaseTrackId = values["MUSICBRAINZ_RELEASETRACKID"],
        )
    }

    private fun readVorbisValues(buf: ByteArray, start: Int): Map<String, String>? {
        var offset = start
        if (offset + INT_BYTES > buf.size) return null
        val vendorLength = readIntLe(buf, offset)
        offset += INT_BYTES + vendorLength
        if (offset + INT_BYTES > buf.size || vendorLength < 0) return null
        val count = readIntLe(buf, offset)
        offset += INT_BYTES
        if (count < 0 || count > VORBIS_COMMENT_LIMIT) return null
        val values = HashMap<String, String>()
        repeat(count) {
            val entry = readVorbisEntry(buf, offset) ?: return null
            offset = entry.second
            collectVorbisEntry(entry.first, values)
        }
        return values
    }

    private fun readVorbisEntry(buf: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset + INT_BYTES > buf.size) return null
        val length = readIntLe(buf, offset)
        val start = offset + INT_BYTES
        if (length < 0 || start + length > buf.size) return null
        val entry = String(buf, start, length, Charsets.UTF_8)
        return entry to (start + length)
    }

    private fun collectVorbisEntry(entry: String, values: HashMap<String, String>) {
        val separator = entry.indexOf('=')
        if (separator <= 0) return
        values[entry.substring(0, separator).uppercase()] = entry.substring(separator + 1)
    }

}

