package com.vayunmathur.musicbrainz.data.library

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal object TagMp4 {

    /**
     * Pulls `moov` out of the file and parses its `udta.meta.ilst` in memory.
     *
     * `moov` is walked box by box from the channel rather than read wholesale because it
     * sits after the audio data in files that were never prepared for streaming, and in
     * an album-length track that means skipping tens of megabytes to reach it.
     */
    fun readMp4(channel: FileChannel): AudioTags {
        val moov = findMoov(channel) ?: return AudioTags()
        return parseIlst(findIlst(moov) ?: return AudioTags())
    }

    private fun findMoov(channel: FileChannel): ByteArray? {
        val size = channel.size()
        var position = 0L
        while (position < size) {
            val header = readBoxHeader(channel, position, size) ?: break
            if (header.type == "moov") return readBoxPayload(channel, header)
            position = header.end
        }
        return null
    }

    private fun readBoxPayload(channel: FileChannel, header: BoxHeader): ByteArray? {
        val payloadSize = header.end - header.contentStart
        if (payloadSize <= 0 || payloadSize > MAX_MOOV_BYTES) return null
        val buffer = ByteBuffer.allocate(payloadSize.toInt())
        channel.position(header.contentStart)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) return null
        }
        return buffer.array()
    }

    private class BoxHeader(val type: String, val contentStart: Long, val end: Long)

    private fun readBoxHeader(channel: FileChannel, position: Long, fileSize: Long): BoxHeader? {
        if (position + MP4_BOX_HEADER > fileSize) return null
        val head = ByteBuffer.allocate(MP4_BOX_HEADER)
        channel.position(position)
        while (head.hasRemaining()) {
            if (channel.read(head) < 0) return null
        }
        head.flip()
        val declared = head.int.toLong() and UINT_MASK
        val type = String(ByteArray(MAGIC_TEXT_LENGTH) { head.get() }, Charsets.ISO_8859_1)
        val end = boxEnd(channel, position, fileSize, declared) ?: return null
        return BoxHeader(type, position + MP4_BOX_HEADER, end)
    }

    private fun boxEnd(channel: FileChannel, position: Long, fileSize: Long, declared: Long): Long? {
        if (declared == MP4_LARGE_MARKER) return readLargeBoxEnd(channel, position, fileSize)
        if (declared == MP4_SELF_MARKER) return fileSize
        if (declared < MP4_MIN_BOX) return null
        return (position + declared).coerceAtMost(fileSize)
    }

    private fun readLargeBoxEnd(channel: FileChannel, position: Long, fileSize: Long): Long? {
        val ext = ByteBuffer.allocate(MP4_BOX_HEADER)
        channel.position(position + MP4_BOX_HEADER)
        while (ext.hasRemaining()) {
            if (channel.read(ext) < 0) return null
        }
        ext.flip()
        val large = ext.long
        if (large < MP4_LARGE_HEADER) return null
        return (position + large).coerceAtMost(fileSize)
    }

    /**
     * Locates the `ilst` payload inside a `moov` blob.
     *
     * `meta` is a full box, so its four version/flags bytes have to be stepped over before
     * its children start - treating it like a plain container is the usual reason a tag
     * parser sees nothing here.
     */
    private fun findIlst(moov: ByteArray): ByteArray? =
        descendBoxes(moov, 0, moov.size, listOf("udta", "meta", "ilst"))

    private fun descendBoxes(buf: ByteArray, start: Int, end: Int, path: List<String>): ByteArray? {
        var offset = start
        while (offset + MP4_BOX_HEADER <= end) {
            val size = readInt(buf, offset)
            val type = String(buf, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
            val boxEnd = if (size == 0) end else offset + size
            if (size < MP4_BOX_HEADER || boxEnd > end) return null
            matchBoxLevel(buf, offset, boxEnd, type, path)?.let { return it }
            offset = boxEnd
        }
        return null
    }

    private fun matchBoxLevel(
        buf: ByteArray,
        offset: Int,
        boxEnd: Int,
        type: String,
        path: List<String>,
    ): ByteArray? {
        if (type != path.first()) return null
        val contentStart = if (type == "meta") {
            offset + META_FULL_BOX_SKIP
        } else {
            offset + MP4_BOX_HEADER
        }
        if (path.size == 1) {
            return buf.copyOfRange(contentStart.coerceAtMost(boxEnd), boxEnd)
        }
        return descendBoxes(buf, contentStart, boxEnd, path.drop(1))
    }

    private fun parseIlst(ilst: ByteArray): AudioTags {
        val values = HashMap<String, String>()
        var offset = 0
        while (offset + MP4_BOX_HEADER <= ilst.size) {
            val size = readInt(ilst, offset)
            if (size < MP4_BOX_HEADER || offset + size > ilst.size) break
            val type = String(ilst, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
            val entryEnd = offset + size
            collectIlstEntry(ilst, offset, entryEnd, type, values)
            offset = entryEnd
        }
        return AudioTags(
            title = values["\u00A9nam"],
            artist = values["\u00A9ART"],
            album = values["\u00A9alb"],
            albumArtist = values["aART"],
            recordingId = values["MusicBrainz Track Id"],
            releaseId = values["MusicBrainz Album Id"],
            releaseTrackId = values["MusicBrainz Release Track Id"],
        )
    }

    private fun collectIlstEntry(
        ilst: ByteArray,
        offset: Int,
        entryEnd: Int,
        type: String,
        values: HashMap<String, String>,
    ) {
        if (type == "----") {
            parseFreeform(ilst, offset + MP4_BOX_HEADER, entryEnd)?.let { (key, value) ->
                values[key] = value
            }
        } else {
            dataPayload(ilst, offset + MP4_BOX_HEADER, entryEnd)?.let { values[type] = it }
        }
    }

    /** Reads the `mean`/`name`/`data` triplet an iTunes-style custom tag is made of. */
    private fun parseFreeform(buf: ByteArray, start: Int, end: Int): Pair<String, String>? {
        var offset = start
        var name: String? = null
        var value: String? = null
        while (offset + MP4_BOX_HEADER <= end) {
            val size = readInt(buf, offset)
            if (size < MP4_BOX_HEADER || offset + size > end) break
            val type = String(buf, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
            if (type == "name") name = freeformName(buf, offset, size)
            if (type == "data") value = freeformValue(buf, offset, size)
            offset += size
        }
        val key = name ?: return null
        val v = value ?: return null
        return key to v
    }

    private fun freeformName(buf: ByteArray, offset: Int, size: Int): String? {
        if (offset + NAME_BOX_HEADER_SKIP > offset + size) return null
        return String(buf, offset + NAME_BOX_HEADER_SKIP, size - NAME_BOX_HEADER_SKIP, Charsets.UTF_8)
    }

    private fun freeformValue(buf: ByteArray, offset: Int, size: Int): String? {
        if (offset + DATA_BOX_HEADER_SKIP > offset + size) return null
        return String(
            buf,
            offset + DATA_BOX_HEADER_SKIP,
            size - DATA_BOX_HEADER_SKIP,
            Charsets.UTF_8,
        )
    }

    private fun dataPayload(buf: ByteArray, start: Int, end: Int): String? {
        var offset = start
        while (offset + MP4_BOX_HEADER <= end) {
            val size = readInt(buf, offset)
            if (size < MP4_BOX_HEADER || offset + size > end) return null
            if (String(buf, offset + INT_BYTES, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1) == "data") {
                return dataText(buf, offset, size)
            }
            offset += size
        }
        return null
    }

    private fun dataText(buf: ByteArray, offset: Int, size: Int): String? {
        val payloadStart = offset + DATA_BOX_HEADER_SKIP
        val payloadEnd = offset + size
        if (payloadStart >= payloadEnd) return null
        return String(buf, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)
            .trim()
            .ifEmpty { null }
    }


}

