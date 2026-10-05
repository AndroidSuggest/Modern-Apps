package com.vayunmathur.musicbrainz.platform

import com.vayunmathur.musicbrainz.data.library.LibrarySnapshot
import com.vayunmathur.musicbrainz.network.api.CoverArt
import com.vayunmathur.musicbrainz.network.api.MbArtist
import com.vayunmathur.musicbrainz.network.api.MbRecording
import com.vayunmathur.musicbrainz.network.api.MbRelease
import com.vayunmathur.musicbrainz.network.api.MbReleaseGroup
import com.vayunmathur.musicbrainz.network.api.display

private const val YEAR_PREFIX_LENGTH = 4
private const val MAX_ERROR_LENGTH = 200

internal fun MbArtist.toRow() = ArtistRow(
    id = id,
    name = name,
    subtitle = listOfNotNull(
        disambiguation?.takeIf { it.isNotBlank() },
        type,
        country,
    ).joinToString(" \u00B7 ").ifEmpty { null },
)

/**
 * The release's own date, or the release-group's first-release date when the pressing
 * carries none. Many individual releases have a blank date even though the group has a
 * year on file, and `release(id)` already includes the group, so this fills the gap.
 */
internal fun MbRelease.effectiveDate(): String? =
    date?.takeIf { it.isNotBlank() } ?: releaseGroup?.firstReleaseDate?.takeIf { it.isNotBlank() }

internal fun MbRelease.toUiState() = ReleaseUiState(
    loading = false,
    id = id,
    title = title,
    artist = artistCredit.display().orEmpty(),
    subtitle = listOfNotNull(
        effectiveDate()?.takeIf { it.isNotBlank() },
        status,
        media.firstOrNull()?.format,
        media.sumOf { it.trackCount }.takeIf { it > 0 }?.let { "$it tracks" },
    ).joinToString(" \u00B7 ").ifEmpty { null },
    coverUrl = CoverArt.release(id),
    fallbackCoverUrl = releaseGroup?.id?.let { CoverArt.releaseGroup(it) },
    tracks = media.flatMapIndexed { mediumIndex, medium ->
        medium.tracks.mapIndexed { trackIndex, track ->
            TrackRow(
                // Positional, so it is unique whatever the catalogue sends: an id it
                // repeated - or omitted for every track - would crash the list.
                rowKey = "$mediumIndex/$trackIndex",
                mediumIndex = mediumIndex,
                releaseTrackId = track.id.ifBlank { null },
                recordingId = track.recording?.id?.ifBlank { null },
                position = track.position,
                title = track.title.ifBlank { track.recording?.title.orEmpty() },
                // Track credits beat release credits: on a compilation the release is
                // credited to "Various Artists", which is nobody's actual track artist.
                artist = track.artistCredit.display()
                    ?: track.recording?.artistCredit.display()
                    ?: artistCredit.display().orEmpty(),
                durationMs = track.length ?: track.recording?.length,
                discNumber = medium.position,
                isrcs = track.recording?.isrcs.orEmpty(),
            )
        }
    },
)

internal fun MbReleaseGroup.toRow(includeArtist: Boolean = true) = ReleaseGroupRow(
    id = id,
    title = title,
    artist = if (includeArtist) artistCredit.display().orEmpty() else "",
    subtitle = listOfNotNull(
        firstReleaseDate?.take(YEAR_PREFIX_LENGTH)?.takeIf { it.isNotBlank() },
        primaryType,
        secondaryTypes.firstOrNull(),
    ).joinToString(" \u00B7 ").ifEmpty { null },
    coverUrl = CoverArt.releaseGroup(id),
)

internal fun MbRecording.toRow(): RecordingRow {
    val firstRelease = releases.firstOrNull()
    return RecordingRow(
        id = id,
        title = title,
        artist = artistCredit.display().orEmpty(),
        album = firstRelease?.title,
        releaseId = firstRelease?.id,
        releaseGroupId = firstRelease?.releaseGroup?.id,
        durationMs = length,
    )
}

internal fun LibrarySnapshot.matches(row: RecordingRow) = hasTrack(
    recordingId = row.id,
    releaseTrackId = null,
    artist = row.artist,
    album = row.album,
    title = row.title,
)

internal fun Exception.readableMessage(): String =
    message?.takeIf { it.isNotBlank() }?.take(MAX_ERROR_LENGTH) ?: "Something went wrong"
