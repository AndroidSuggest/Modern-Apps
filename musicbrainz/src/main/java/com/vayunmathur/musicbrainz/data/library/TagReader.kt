package com.vayunmathur.musicbrainz.data.library

import android.content.Context
import android.net.Uri
import java.io.FileInputStream

/** The tags the library index cares about. Every field is absent on files that lack it. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val recordingId: String? = null,
    val releaseId: String? = null,
    val releaseTrackId: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null &&
            recordingId == null && releaseId == null
}

/**
 * Reads identifying tags out of audio files, so the app can tell what the user already
 * owns without keeping its own download ledger.
 *
 * There is no tag library in the repo and adding one would pull in a large third-party
 * dependency for what amounts to four container formats, so the parsers live alongside:
 * MP4 in [TagMp4], ID3 in [TagId3], Vorbis/FLAC/Ogg in [TagVorbis], shared bits in
 * [TagCommon]. Each one reads only the metadata region and stops - a library scan touches
 * every file, and reading whole albums of audio to find a title would make it unusable.
 *
 * MusicBrainz IDs are the reliable signal but only Picard-tagged files carry them, so
 * plain title/artist/album are read too and the matching falls back to those.
 */
object TagReader {

    fun isAudioFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    /** Returns whatever could be read; a malformed or unsupported file yields empty tags. */
    fun read(context: Context, uri: Uri, fileName: String): AudioTags = try {
        readUnsafe(context, uri, fileName)
    } catch (_: IllegalArgumentException) {
        AudioTags()
    } catch (_: IllegalStateException) {
        AudioTags()
    } catch (_: SecurityException) {
        AudioTags()
    }

    internal fun readId3(input: java.io.InputStream): AudioTags = TagId3.readId3(input)

    internal fun readMp4(channel: java.nio.channels.FileChannel): AudioTags = TagMp4.readMp4(channel)

    internal fun readFlac(input: java.io.InputStream): AudioTags = TagVorbis.readFlac(input)

    internal fun readOgg(input: java.io.InputStream): AudioTags = TagVorbis.readOgg(input)

    private fun readUnsafe(context: Context, uri: Uri, fileName: String): AudioTags {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                return when (fileName.substringAfterLast('.', "").lowercase()) {
                    "mp3", "aac" -> readId3(input)
                    "m4a", "m4b", "mp4" -> readMp4(input.channel)
                    "flac" -> readFlac(input)
                    "ogg", "oga", "opus" -> readOgg(input)
                    else -> AudioTags()
                }
            }
        }
        return AudioTags()
    }
}
