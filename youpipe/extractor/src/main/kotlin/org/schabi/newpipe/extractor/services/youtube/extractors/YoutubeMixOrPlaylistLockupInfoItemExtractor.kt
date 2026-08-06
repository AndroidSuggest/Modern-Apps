package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractPlaylistTypeFromPlaylistId
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.hasArtistOrVerifiedIconBadgeAttachment
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

open class YoutubeMixOrPlaylistLockupInfoItemExtractor(
    private val lockupViewModel: JsonObject
) : PlaylistInfoItemExtractor {

    private val thumbnailViewModel: JsonObject = lockupViewModel.getObject("contentImage").orEmptyObject()
        .getObject("collectionThumbnailViewModel").orEmptyObject()
        .getObject("primaryThumbnail").orEmptyObject()
        .getObject("thumbnailViewModel").orEmptyObject()

    private val lockupMetadataViewModel: JsonObject = lockupViewModel.getObject("metadata").orEmptyObject()
        .getObject("lockupMetadataViewModel").orEmptyObject()

    /*
    The metadata rows are structured in the following way:
    1st part: uploader info, playlist type, playlist updated date
    2nd part: space row
    3rd element: first video
    4th (not always returned for playlists with less than 2 items?): second video
    5th element (always returned, but at a different index for playlists with less than 2
    items?): Show full playlist

    The first metadata row has the following structure:
    1st array element: uploader info
    2nd element: playlist type (course, playlist, podcast)
    3rd element (not always returned): playlist updated date
     */
    private val firstMetadataRow: JsonObject = lockupMetadataViewModel.getObject("metadata").orEmptyObject()
        .getObject("contentMetadataViewModel").orEmptyObject()
        .getArray("metadataRows").orEmptyArray()
        .getObject(0).orEmptyObject()

    private var playlistType: PlaylistInfo.PlaylistType

    init {
        val type = try {
            extractPlaylistTypeFromPlaylistId(getPlaylistId())
        } catch (e: ParsingException) {
            // If we cannot extract the playlist type, fall back to the normal one
            PlaylistInfo.PlaylistType.NORMAL
        }
        playlistType = type
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? {
        return firstMetadataRow.getArray("metadataParts").orEmptyArray()
            .getObject(0).orEmptyObject()
            .getObject("text").orEmptyObject()
            .getString("content")
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        if (playlistType != PlaylistInfo.PlaylistType.NORMAL) {
            return null
        }

        return getUrlFromNavigationEndpoint(
            firstMetadataRow.getArray("metadataParts").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject("text").orEmptyObject()
                .getArray("commandRuns").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject("onTap").orEmptyObject()
                .getObject("innertubeCommand")
        )
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean {
        if (playlistType != PlaylistInfo.PlaylistType.NORMAL) {
            return false
        }

        return hasArtistOrVerifiedIconBadgeAttachment(
            firstMetadataRow.getArray("metadataParts").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject("text").orEmptyObject()
                .getArray("attachmentRuns").orEmptyArray()
        )
    }

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        if (playlistType != PlaylistInfo.PlaylistType.NORMAL) {
            return ListExtractor.ITEM_COUNT_INFINITE
        }

        try {
            return Utils.removeNonDigitCharacters(
                thumbnailViewModel.getArray("overlays").orEmptyArray()
                    .filterIsInstance<JsonObject>()
                    .filter { it.containsKey("thumbnailOverlayBadgeViewModel") }
                    .firstOrNull()
                    ?.getObject("thumbnailOverlayBadgeViewModel")
                    ?.getArray("thumbnailBadges")
                    ?.filterIsInstance<JsonObject>()
                    ?.filter { it.containsKey("thumbnailBadgeViewModel") }
                    ?.firstOrNull()
                    ?.getObject("thumbnailBadgeViewModel")
                    ?.getString("text")
                    ?: throw ParsingException("Could not get thumbnailBadgeViewModel")
            ).toLong()
        } catch (e: Exception) {
            throw ParsingException("Could not get playlist stream count", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        return lockupMetadataViewModel.getObject("title").orEmptyObject()
            .getString("content")
            ?: throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        // If the playlist item is a mix, we cannot return just its playlist ID as mix playlists
        // are not viewable in playlist pages
        if (playlistType == PlaylistInfo.PlaylistType.NORMAL) {
            try {
                return YoutubePlaylistLinkHandlerFactory.getInstance().getUrl(getPlaylistId())
            } catch (ignored: Exception) {
            }
        }

        return getUrlFromNavigationEndpoint(
            lockupViewModel.getObject("rendererContext").orEmptyObject()
                .getObject("commandContext").orEmptyObject()
                .getObject("onTap").orEmptyObject()
                .getObject("innertubeCommand").orEmptyObject()
        ) ?: throw ParsingException("Could not get url")
    }

    override fun getThumbnails(): List<Image> {
        return getImagesFromThumbnailsArray(
            thumbnailViewModel.getObject("image").orEmptyObject()
                .getArray("sources").orEmptyArray()
        )
    }

    override fun getPlaylistType(): PlaylistInfo.PlaylistType = playlistType

    @Throws(ParsingException::class)
    private fun getPlaylistId(): String {
        var id = lockupViewModel.getString("contentId")
        if (Utils.isNullOrEmpty(id)) {
            id = lockupViewModel.getObject("rendererContext")
                ?.getObject("commandContext")
                ?.getObject("watchEndpoint")
                ?.getString("playlistId")
        }

        if (Utils.isNullOrEmpty(id)) {
            throw ParsingException("Could not get playlist ID")
        }

        return id
    }
}
