package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getImagesFromThumbnailsArray
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getUrlFromNavigationEndpoint
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.Companion.MUSIC_ALBUMS
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

class YoutubeMusicAlbumOrPlaylistInfoItemExtractor(
    private val albumOrPlaylistInfoItem: JsonObject,
    descriptionElements: JsonArray,
    searchType: String
) : PlaylistInfoItemExtractor {

    private val descriptionElementUploader: JsonObject = descriptionElements.getObject(
        // For albums: "Album/Single/EP", " • ", uploader, " • ", year -> uploader is at 2
        // For playlists: uploader, " • ", view count -> uploader is at 0
        if (MUSIC_ALBUMS == searchType) 2 else 0
    ).orEmptyObject()

    override fun getThumbnails(): List<Image> {
        try {
            return getImagesFromThumbnailsArray(
                albumOrPlaylistInfoItem.getObject("thumbnail").orEmptyObject()
                    .getObject("musicThumbnailRenderer").orEmptyObject()
                    .getObject("thumbnail").orEmptyObject()
                    .getArray("thumbnails").orEmptyArray()
            )
        } catch (e: Exception) {
            throw ParsingException("Could not get thumbnails", e)
        }
    }

    @Throws(ParsingException::class)
    override fun getName(): String {
        val name = getTextFromObject(
            albumOrPlaylistInfoItem.getArray("flexColumns").orEmptyArray()
                .getObject(0).orEmptyObject()
                .getObject("musicResponsiveListItemFlexColumnRenderer").orEmptyObject()
                .getObject("text")
        )

        if (!isNullOrEmpty(name)) {
            return name
        }

        throw ParsingException("Could not get name")
    }

    @Throws(ParsingException::class)
    override fun getUrl(): String {
        var playlistId = albumOrPlaylistInfoItem.getObject("menu").orEmptyObject()
            .getObject("menuRenderer").orEmptyObject()
            .getArray("items").orEmptyArray()
            .getObject(4).orEmptyObject()
            .getObject("toggleMenuServiceItemRenderer").orEmptyObject()
            .getObject("toggledServiceEndpoint").orEmptyObject()
            .getObject("likeEndpoint").orEmptyObject()
            .getObject("target").orEmptyObject()
            .getString("playlistId")

        if (isNullOrEmpty(playlistId)) {
            playlistId = albumOrPlaylistInfoItem.getObject("overlay").orEmptyObject()
                .getObject("musicItemThumbnailOverlayRenderer").orEmptyObject()
                .getObject("content").orEmptyObject()
                .getObject("musicPlayButtonRenderer").orEmptyObject()
                .getObject("playNavigationEndpoint").orEmptyObject()
                .getObject("watchPlaylistEndpoint").orEmptyObject()
                .getString("playlistId")
        }

        if (!isNullOrEmpty(playlistId)) {
            return "https://music.youtube.com/playlist?list=$playlistId"
        }

        throw ParsingException("Could not get URL")
    }

    @Throws(ParsingException::class)
    override fun getUploaderName(): String? {
        val name = descriptionElementUploader.getString("text")

        if (!isNullOrEmpty(name)) {
            return name
        }

        throw ParsingException("Could not get uploader name")
    }

    @Throws(ParsingException::class)
    override fun getUploaderUrl(): String? {
        // first try obtaining the uploader from the menu (will not work for MUSIC_PLAYLISTS though)
        val items = albumOrPlaylistInfoItem.getObject("menu").orEmptyObject()
            .getObject("menuRenderer").orEmptyObject()
            .getArray("items").orEmptyArray()
        for (item in items) {
            val menuNavigationItemRenderer =
                (item as JsonObject).getObject("menuNavigationItemRenderer")
            if (menuNavigationItemRenderer?.getObject("icon")
                    ?.getString("iconType", "") == "ARTIST"
            ) {
                return getUrlFromNavigationEndpoint(
                    menuNavigationItemRenderer.getObject("navigationEndpoint")
                )
            }
        }

        // then try obtaining it from the uploader description element
        if (!descriptionElementUploader.containsKey("navigationEndpoint")) {
            return null
        }
        return getUrlFromNavigationEndpoint(
            descriptionElementUploader.getObject("navigationEndpoint")
        )
    }

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean = false

    @Throws(ParsingException::class)
    override fun getStreamCount(): Long {
        // YouTube Music album and playlist info items don't expose the stream count anywhere...
        return ListExtractor.ITEM_COUNT_UNKNOWN
    }
}
