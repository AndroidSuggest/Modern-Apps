package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.utils.Utils.encodeUrlUtf8
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty

class YoutubeSearchQueryHandlerFactory private constructor() : SearchQueryHandlerFactory() {

    companion object {
        private val INSTANCE = YoutubeSearchQueryHandlerFactory()

        const val ALL: String = "all"
        const val VIDEOS: String = "videos"
        const val CHANNELS: String = "channels"
        const val PLAYLISTS: String = "playlists"

        const val MUSIC_SONGS: String = "music_songs"
        const val MUSIC_VIDEOS: String = "music_videos"
        const val MUSIC_ALBUMS: String = "music_albums"
        const val MUSIC_PLAYLISTS: String = "music_playlists"
        const val MUSIC_ARTISTS: String = "music_artists"

        private const val SEARCH_URL = "https://www.youtube.com/results?search_query="
        private const val MUSIC_SEARCH_URL = "https://music.youtube.com/search?q="

        @JvmStatic
        fun getInstance(): YoutubeSearchQueryHandlerFactory = INSTANCE

        @JvmStatic
        fun getSearchParameter(contentFilter: String?): String {
            if (isNullOrEmpty(contentFilter)) {
                return "8AEB"
            }
            return when (contentFilter) {
                VIDEOS -> "EgIQAfABAQ%3D%3D"
                CHANNELS -> "EgIQAvABAQ%3D%3D"
                PLAYLISTS -> "EgIQA_ABAQ%3D%3D"
                MUSIC_SONGS, MUSIC_VIDEOS, MUSIC_ALBUMS, MUSIC_PLAYLISTS, MUSIC_ARTISTS -> ""
                else -> "8AEB"
            }
        }
    }

    @Throws(ParsingException::class)
    override fun getUrl(searchString: String, contentFilters: List<String>, sortFilter: String): String {
        val contentFilter = if (contentFilters.isNotEmpty()) contentFilters[0] else ""
        return when (contentFilter) {
            VIDEOS -> SEARCH_URL + encodeUrlUtf8(searchString) + "&sp=EgIQAfABAQ%253D%253D"
            CHANNELS -> SEARCH_URL + encodeUrlUtf8(searchString) + "&sp=EgIQAvABAQ%253D%253D"
            PLAYLISTS -> SEARCH_URL + encodeUrlUtf8(searchString) + "&sp=EgIQA_ABAQ%253D%253D"
            MUSIC_SONGS, MUSIC_VIDEOS, MUSIC_ALBUMS, MUSIC_PLAYLISTS, MUSIC_ARTISTS ->
                MUSIC_SEARCH_URL + encodeUrlUtf8(searchString)
            else -> SEARCH_URL + encodeUrlUtf8(searchString) + "&sp=8AEB"
        }
    }

    override fun getAvailableContentFilter(): Array<String> {
        return arrayOf(
            ALL,
            VIDEOS,
            CHANNELS,
            PLAYLISTS,
            MUSIC_SONGS,
            MUSIC_VIDEOS,
            MUSIC_ALBUMS,
            MUSIC_PLAYLISTS
            // MUSIC_ARTISTS
        )
    }
}
