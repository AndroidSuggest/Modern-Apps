package com.vayunmathur.musicbrainz.data.library

import java.io.InputStream

internal object TagId3 {

    fun readId3(input: InputStream): AudioTags {
        val body = readId3Body(input) ?: return AudioTags()
        return collectId3Frames(body)
    }

    private fun collectId3Frames(body: Id3Body): AudioTags {
        val values = HashMap<String, String>()
        val frames = collectFrames(body)
        val recordingId = frames.ufid
        for (frame in frames.text) consumeFrame(body.bytes, frame, values)
        return AudioTags(
            title = values[FRAME_TITLE],
            artist = values[FRAME_ARTIST],
            album = values[FRAME_ALBUM],
            albumArtist = values[FRAME_ALBUM_ARTIST],
            recordingId = recordingId ?: values["MusicBrainz Track Id"],
            releaseId = values["MusicBrainz Album Id"],
            releaseTrackId = values["MusicBrainz Release Track Id"],
        )
    }

    private class CollectedFrames(val text: List<FrameRange>, val ufid: String?)

    private fun collectFrames(body: Id3Body): CollectedFrames {
        val text = ArrayList<FrameRange>()
        var recordingId: String? = null
        var offset = 0
        val bytes = body.bytes
        while (offset + ID3_FRAME_HEADER <= bytes.size) {
            val frame = readFrameHeader(bytes, offset, body.major)
            if (frame == null || frame.isPadding) break
            if (frame.id == FRAME_UNIQUE_ID) {
                recordingId = readUfid(bytes, frame) ?: recordingId
            } else {
                text.add(frame)
            }
            offset = frame.frameEnd
        }
        return CollectedFrames(text, recordingId)
    }

    private class Id3Body(val bytes: ByteArray, val major: Int)

    private class FrameRange(
        val id: String,
        val frameStart: Int,
        val frameSize: Int,
        val frameEnd: Int,
        val isPadding: Boolean,
    )

    private fun readId3Body(input: InputStream): Id3Body? {
        val header = input.readNBytesCompat(ID3_HEADER_LENGTH)
        if (header.size < ID3_HEADER_LENGTH) return null
        if (String(header, 0, ID3_MAGIC_LENGTH, Charsets.ISO_8859_1) != "ID3") return null
        val major = header[ID3_MAJOR_INDEX].toInt() and BYTE_MASK
        if (major < ID3_MIN_MAJOR) return null
        val tagSize = synchsafe(header, ID3_TAG_OFFSET)
        if (tagSize <= 0 || tagSize > MAX_ID3_BYTES) return null
        return Id3Body(input.readNBytesCompat(tagSize), major)
    }

    private fun readFrameHeader(body: ByteArray, offset: Int, major: Int): FrameRange? {
        val id = String(body, offset, MAGIC_TEXT_LENGTH, Charsets.ISO_8859_1)
        if (id[0] == '\u0000') return FrameRange(id, 0, 0, 0, true)
        // v2.4 made frame sizes synchsafe; v2.3 left them as plain big-endian ints.
        val frameSize = if (major >= ID3_V24_MAJOR) {
            synchsafe(body, offset + INT_BYTES)
        } else {
            readInt(body, offset + INT_BYTES)
        }
        if (frameSize <= 0 || offset + ID3_FRAME_HEADER + frameSize > body.size) return null
        val frameStart = offset + ID3_FRAME_HEADER
        return FrameRange(id, frameStart, frameSize, frameStart + frameSize, false)
    }

    private fun consumeFrame(
        body: ByteArray,
        frame: FrameRange,
        values: HashMap<String, String>,
    ) {
        if (frame.id in TEXT_FRAME_IDS) {
            decodeTextFrame(body, frame.frameStart, frame.frameSize)?.let {
                values[frame.id] = it
            }
            return
        }
        if (frame.id == FRAME_USER_TEXT) {
            decodeUserTextFrame(body, frame.frameStart, frame.frameSize)
                ?.let { (key, value) -> values[key] = value }
        }
    }

    private fun readUfid(body: ByteArray, frame: FrameRange): String? {
        if (frame.id != FRAME_UNIQUE_ID) return null
        val ownerEnd = body.indexOfZero(frame.frameStart, frame.frameStart + frame.frameSize)
        if (ownerEnd <= 0) return null
        val owner = String(body, frame.frameStart, ownerEnd - frame.frameStart, Charsets.ISO_8859_1)
        if (!owner.contains(MB_OWNER_MARKER)) return null
        return String(
            body,
            ownerEnd + 1,
            frame.frameStart + frame.frameSize - ownerEnd - 1,
            Charsets.ISO_8859_1,
        ).trim().ifEmpty { null }
    }

    private fun decodeTextFrame(buf: ByteArray, start: Int, size: Int): String? {
        if (size < ID3_TEXT_MIN) return null
        return decodeString(buf, start + 1, start + size, buf[start].toInt() and BYTE_MASK)
            .trimEnd('\u0000')
            .trim()
            .ifEmpty { null }
    }

    /** `TXXX` is a description and a value, both in the frame's declared encoding. */
    private fun decodeUserTextFrame(buf: ByteArray, start: Int, size: Int): Pair<String, String>? {
        if (size < ID3_TEXT_MIN) return null
        val encoding = buf[start].toInt() and BYTE_MASK
        val end = start + size
        val separator = findTextSeparator(buf, start + 1, end, encoding) ?: return null
        val description = decodeString(buf, start + 1, separator, encoding).trim()
        val valueStart = separator + separatorWidth(encoding)
        val value = decodeString(buf, valueStart, end, encoding).trimEnd('\u0000').trim()
        if (description.isEmpty() || value.isEmpty()) return null
        return description to value
    }

    private fun separatorWidth(encoding: Int): Int = if (isWideEncoding(encoding)) 2 else 1

    private fun isWideEncoding(encoding: Int): Boolean = encoding == 1 || encoding == 2

    private fun findTextSeparator(buf: ByteArray, from: Int, end: Int, encoding: Int): Int? {
        var scan = from
        while (scan < end) {
            if (isWideEncoding(encoding)) {
                if (isWideZero(buf, scan, end)) return scan
                scan += 2
            } else {
                if (buf[scan].toInt() == 0) return scan
                scan++
            }
        }
        return null
    }

    private fun isWideZero(buf: ByteArray, scan: Int, end: Int): Boolean =
        scan + 1 < end && buf[scan].toInt() == 0 && buf[scan + 1].toInt() == 0

    private fun decodeString(buf: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(buf, start, end - start, charset)
    }

}

