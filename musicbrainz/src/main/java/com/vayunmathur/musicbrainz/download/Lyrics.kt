package com.vayunmathur.musicbrainz.download

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable

@Serializable
private data class LrcLibResponse(
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
)

/**
 * Fetches lyrics from LRCLIB.
 *
 * Synced lyrics are preferred so players that support them can follow along, with plain
 * text as the fallback. Failures are silent - lyrics are a nicety, and a missing set is
 * not a reason to fail a download.
 */
object Lyrics {

    private const val BASE = "https://lrclib.net/api/get"
    private val headers = mapOf(
        "User-Agent" to "ModernAppsMusicBrainz/1.0 ( https://ma.vayunmathur.com/apps/musicbrainz )",
    )

    suspend fun fetch(artist: String, title: String, album: String?, durationMs: Int?): String? {
        val url = buildString {
            append(BASE)
            append("?artist_name=").append(encode(artist))
            append("&track_name=").append(encode(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(encode(album))
            if (durationMs != null && durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
        return try {
            val response = NetworkClient.getJson<LrcLibResponse>(url, headers = headers)
            response.syncedLyrics?.takeIf { it.isNotBlank() }
                ?: response.plainLyrics?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
