package org.schabi.newpipe.extractor.services.youtube

import javax.annotation.Nonnull

/**
 * A session-bound proof-of-origin token and the visitor data it is bound to.
 */
class YoutubeSessionPoToken(
    @field:Nonnull
    val visitorData: String,
    @field:Nonnull
    private val poToken: String
) {
    init {
        requireNotNull(visitorData)
        requireNotNull(poToken)
    }

    @Nonnull
    fun getPoToken(): String = poToken
}
