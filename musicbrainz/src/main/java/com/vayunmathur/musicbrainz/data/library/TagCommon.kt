package com.vayunmathur.musicbrainz.data.library

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val MAX_MOOV_BYTES = 16 * 1024 * 1024
internal const val MAX_ID3_BYTES = 4 * 1024 * 1024

// Large enough to hold an Opus comment header that embeds cover art: the app writes the
// front cover into the `OpusTags` packet, and the identifying tags parsed here sit in
// that same packet, so the whole of it has to be scanned to read them back.
internal const val OGG_SCAN_BYTES = 1024 * 1024

internal const val BYTE_MASK = 0xff
internal const val SYNCHSAFE_MASK = 0x7f
internal const val FLAG_LAST_MASK = 0x80
internal const val BLOCK_TYPE_MASK = 0x7f
internal const val SHIFT_SHORT_HIGH = 16
internal const val SHIFT_BYTE_MID = 8
internal const val SHIFT_BYTE_HIGH = 24
internal const val SHIFT_SYNCH_3 = 21
internal const val SHIFT_SYNCH_2 = 14
internal const val SHIFT_SYNCH_1 = 7
internal const val INT_BYTES = 4
internal const val MAGIC_TEXT_LENGTH = 4
internal const val MP4_BOX_HEADER = 8
internal const val MP4_LARGE_HEADER = 16
internal const val MP4_LARGE_MARKER = 1L
internal const val MP4_SELF_MARKER = 0L
internal const val MP4_MIN_BOX = 8L
internal const val UINT_MASK = 0xffffffffL
internal const val META_FULL_BOX_SKIP = 12
internal const val OGG_PAGE_HEADER_MIN = 27
internal const val OGG_SEGMENT_COUNT_OFFSET = 26
internal const val OGG_SEGMENT_TERMINATOR = 255
internal const val ID3_HEADER_LENGTH = 10
internal const val ID3_TAG_OFFSET = 6
internal const val ID3_MAGIC_LENGTH = 3
internal const val ID3_FRAME_HEADER = 10
internal const val ID3_MIN_MAJOR = 3
internal const val ID3_V24_MAJOR = 4
internal const val FLAC_BLOCK_HEADER = 4
internal const val FLAC_COMMENT_BLOCK = 4
internal const val VORBIS_COMMENT_LIMIT = 10_000
internal const val OPUS_TAGS_LENGTH = 8
internal const val OPUS_TAGS_SKIP = 8
internal const val VORBIS_MAGIC_LENGTH = 7
internal const val VORBIS_MAGIC_OFFSET = 1
internal const val VORBIS_MAGIC_TEXT_LENGTH = 6
internal const val DATA_BOX_HEADER_SKIP = 16
internal const val NAME_BOX_HEADER_SKIP = 12
internal const val COMMENT_PACKET_INDEX = 1
internal const val ID3_TEXT_MIN = 2
internal const val SHORT_HIGH_INDEX = 1
internal const val SHORT_MID_HIGH_INDEX = 2
internal const val SHORT_LOW_INDEX = 3
internal const val ID3_MAJOR_INDEX = 3

internal const val FRAME_TITLE = "TIT2"
internal const val FRAME_ARTIST = "TPE1"
internal const val FRAME_ALBUM = "TALB"
internal const val FRAME_ALBUM_ARTIST = "TPE2"
internal const val FRAME_USER_TEXT = "TXXX"
internal const val FRAME_UNIQUE_ID = "UFID"
internal const val MB_OWNER_MARKER = "musicbrainz.org"

internal val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "m4b", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
)

internal val TEXT_FRAME_IDS = setOf(FRAME_TITLE, FRAME_ARTIST, FRAME_ALBUM, FRAME_ALBUM_ARTIST)

internal fun readInt(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and BYTE_MASK) shl SHIFT_BYTE_HIGH) or
        ((buf[offset + SHORT_HIGH_INDEX].toInt() and BYTE_MASK) shl SHIFT_SHORT_HIGH) or
        ((buf[offset + SHORT_MID_HIGH_INDEX].toInt() and BYTE_MASK) shl SHIFT_BYTE_MID) or
        (buf[offset + SHORT_LOW_INDEX].toInt() and BYTE_MASK)

internal fun readIntLe(buf: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(buf, offset, INT_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

/** ID3 sizes drop the high bit of each byte so they can never look like a frame sync. */
internal fun synchsafe(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and SYNCHSAFE_MASK) shl SHIFT_SYNCH_3) or
        ((buf[offset + SHORT_HIGH_INDEX].toInt() and SYNCHSAFE_MASK) shl SHIFT_SYNCH_2) or
        ((buf[offset + SHORT_MID_HIGH_INDEX].toInt() and SYNCHSAFE_MASK) shl SHIFT_SYNCH_1) or
        (buf[offset + SHORT_LOW_INDEX].toInt() and SYNCHSAFE_MASK)

internal fun ByteArray.indexOfZero(from: Int, to: Int): Int {
    for (i in from until minOf(to, size)) if (this[i].toInt() == 0) return i
    return -1
}

internal fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(out, read, count - read)
        if (n < 0) break
        read += n
    }
    return if (read == count) out else out.copyOf(read)
}
